use bytes::Bytes;
use http::HeaderName;
use http::uri::Scheme;
use http_body_util::BodyExt;
use std::cell::RefCell;
use std::collections::HashSet;
use std::ffi::{CStr, CString, c_char, c_int};
use std::fs;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use wasmtime::component::types::ComponentItem;
use wasmtime::component::{Component, ComponentExportIndex, Linker, ResourceTable};
use wasmtime::{Config, Engine, Store, StoreLimits, StoreLimitsBuilder};
use wasmtime_wasi::p3::bindings::Command as P3Command;
use wasmtime_wasi::{
    DirPerms, FilePerms, I32Exit, TrappableError, WasiCtx, WasiCtxBuilder, WasiCtxView, WasiView,
    p2::pipe::{MemoryInputPipe, MemoryOutputPipe},
};
use wasmtime_wasi_http::p3::bindings::http::types::ErrorCode;
use wasmtime_wasi_http::p3::{RequestOptions, WasiHttpCtxView, WasiHttpHooks, WasiHttpView};
use wasmtime_wasi_http::{DEFAULT_FORBIDDEN_HEADERS, WasiHttpCtx};

const DEFAULT_MAX_MEMORY_BYTES: u64 = 256 * 1024 * 1024;
const DEFAULT_MAX_WASM_STACK_BYTES: u64 = 512 * 1024;
const UNLIMITED_RESOURCE_LIMIT: i64 = -1;
const DISABLED_EXECUTION_TIMEOUT_EPOCH_DEADLINE: u64 = u64::MAX / 2;

thread_local! {
    static LAST_ERROR: RefCell<Option<CString>> = const { RefCell::new(None) };
    static LAST_RESULT: RefCell<Option<CString>> = const { RefCell::new(None) };
}

struct KrwaP3State {
    ctx: WasiCtx,
    table: ResourceTable,
    http: WasiHttpCtx,
    http_hooks: PolicyHttpHooks,
    limits: StoreLimits,
}

struct P3Preopen {
    host_root: PathBuf,
    guest_root: String,
    writable: bool,
}

struct P3CommandStdio {
    stdin: Bytes,
    stdout: MemoryOutputPipe,
    stderr: MemoryOutputPipe,
}

#[derive(Clone, Default)]
struct HttpPolicy {
    allowed_hosts: Vec<String>,
    blocked_hosts: Vec<String>,
    allow_private_network: bool,
}

#[derive(Clone, Copy)]
struct P3Limits {
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
}

impl Default for P3Limits {
    fn default() -> Self {
        Self {
            max_memory_bytes: DEFAULT_MAX_MEMORY_BYTES,
            max_wasm_stack_bytes: DEFAULT_MAX_WASM_STACK_BYTES,
            max_table_elements: UNLIMITED_RESOURCE_LIMIT,
            max_instances: UNLIMITED_RESOURCE_LIMIT,
            max_tables: UNLIMITED_RESOURCE_LIMIT,
            max_memories: UNLIMITED_RESOURCE_LIMIT,
        }
    }
}

impl P3Limits {
    fn validate(self) -> Result<(), String> {
        validate_max_memory_bytes(self.max_memory_bytes)?;
        validate_max_wasm_stack_bytes(self.max_wasm_stack_bytes)?;
        validate_optional_resource_limit("max table elements", self.max_table_elements)?;
        validate_optional_resource_limit("max instances", self.max_instances)?;
        validate_optional_resource_limit("max tables", self.max_tables)?;
        validate_optional_resource_limit("max memories", self.max_memories)?;
        Ok(())
    }
}

struct PolicyHttpHooks {
    policy: HttpPolicy,
}

struct ExecutionControlState {
    state: Mutex<ExecutionControl>,
    cvar: Condvar,
}

#[derive(Default)]
struct ExecutionControl {
    completed: bool,
    cancelled: bool,
}

struct ExecutionCancellationHandle {
    state: Arc<ExecutionControlState>,
}

impl ExecutionControlState {
    fn new() -> Self {
        Self {
            state: Mutex::new(ExecutionControl::default()),
            cvar: Condvar::new(),
        }
    }

    fn cancel(&self) {
        if let Ok(mut state) = self.state.lock() {
            state.cancelled = true;
            self.cvar.notify_all();
        }
    }

    fn is_cancelled(&self) -> bool {
        self.state
            .lock()
            .map(|state| state.cancelled)
            .unwrap_or(true)
    }
}

impl WasiHttpHooks for PolicyHttpHooks {
    fn is_forbidden_header(&mut self, name: &HeaderName) -> bool {
        let normalized = name.as_str().to_ascii_lowercase();
        DEFAULT_FORBIDDEN_HEADERS.contains(name)
            || normalized == "host"
            || normalized == "content-length"
            || normalized.starts_with("proxy-")
    }

    fn send_request(
        &mut self,
        request: http::Request<http_body_util::combinators::UnsyncBoxBody<Bytes, ErrorCode>>,
        options: Option<RequestOptions>,
        fut: Box<dyn Future<Output = Result<(), ErrorCode>> + Send>,
    ) -> Box<
        dyn Future<
                Output = Result<
                    (
                        http::Response<
                            http_body_util::combinators::UnsyncBoxBody<Bytes, ErrorCode>,
                        >,
                        Box<dyn Future<Output = Result<(), ErrorCode>> + Send>,
                    ),
                    TrappableError<ErrorCode>,
                >,
            > + Send,
    > {
        let validation = self.policy.validate_request(&request);
        Box::new(async move {
            validation.map_err(TrappableError::from)?;
            drop(fut);
            let (response, io) = wasmtime_wasi_http::p3::default_send_request(request, options)
                .await
                .map_err(TrappableError::from)?;
            Ok((
                response.map(BodyExt::boxed_unsync),
                Box::new(io) as Box<dyn Future<Output = Result<(), ErrorCode>> + Send>,
            ))
        })
    }
}

impl HttpPolicy {
    fn validate_request<B>(&self, request: &http::Request<B>) -> Result<(), ErrorCode> {
        let method = request.method().as_str().to_ascii_uppercase();
        if !ALLOWED_HTTP_METHODS.contains(&method.as_str()) {
            return Err(ErrorCode::HttpRequestDenied);
        }

        let uri = request.uri();
        let scheme = uri.scheme().unwrap_or(&Scheme::HTTPS);
        if scheme != &Scheme::HTTP && scheme != &Scheme::HTTPS {
            return Err(ErrorCode::HttpRequestDenied);
        }
        let host = uri
            .host()
            .map(normalized_host)
            .filter(|host| !host.is_empty())
            .ok_or(ErrorCode::HttpRequestDenied)?;

        if !self.allow_private_network && is_private_network_host(&host) {
            return Err(ErrorCode::HttpRequestDenied);
        }
        if host_matches_any(&host, &self.blocked_hosts) {
            return Err(ErrorCode::HttpRequestDenied);
        }
        if !host_matches_any(&host, &self.allowed_hosts) {
            return Err(ErrorCode::HttpRequestDenied);
        }
        for name in request.headers().keys() {
            let normalized = name.as_str().to_ascii_lowercase();
            if normalized == "host"
                || normalized == "content-length"
                || normalized.starts_with("proxy-")
            {
                return Err(ErrorCode::HttpRequestDenied);
            }
        }
        Ok(())
    }
}

impl WasiView for KrwaP3State {
    fn ctx(&mut self) -> WasiCtxView<'_> {
        WasiCtxView {
            ctx: &mut self.ctx,
            table: &mut self.table,
        }
    }
}

impl WasiHttpView for KrwaP3State {
    fn http(&mut self) -> WasiHttpCtxView<'_> {
        WasiHttpCtxView {
            ctx: &mut self.http,
            table: &mut self.table,
            hooks: &mut self.http_hooks,
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_execution_cancellation_create()
-> *mut ExecutionCancellationHandle {
    Box::into_raw(Box::new(ExecutionCancellationHandle {
        state: Arc::new(ExecutionControlState::new()),
    }))
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_execution_cancellation_cancel(
    handle: *mut ExecutionCancellationHandle,
) {
    if handle.is_null() {
        return;
    }
    unsafe {
        (*handle).state.cancel();
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_execution_cancellation_is_cancelled(
    handle: *const ExecutionCancellationHandle,
) -> u8 {
    if handle.is_null() {
        return 0;
    }
    unsafe { u8::from((*handle).state.is_cancelled()) }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_execution_cancellation_free(
    handle: *mut ExecutionCancellationHandle,
) {
    if handle.is_null() {
        return;
    }
    unsafe {
        drop(Box::from_raw(handle));
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_bridge_unavailable_reason(
    host_root: *const c_char,
    guest_root: *const c_char,
) -> *const c_char {
    match check_bridge(host_root, guest_root) {
        Ok(()) => ptr::null(),
        Err(error) => set_last_error(error),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_precompiled_component_instantiate_unavailable_reason(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
) -> *const c_char {
    match check_component_instantiation(
        component_bytes,
        component_len,
        host_roots,
        guest_roots,
        writable_preopens,
        preopen_count,
        arguments,
        argument_count,
        environment_keys,
        environment_values,
        environment_count,
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
        p3_limits_from_c(
            max_memory_bytes,
            max_wasm_stack_bytes,
            max_table_elements,
            max_instances,
            max_tables,
            max_memories,
        ),
    ) {
        Ok(()) => ptr::null(),
        Err(error) => set_last_error(error),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_precompiled_component_call0_unavailable_reason(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    export_name: *const c_char,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
) -> *const c_char {
    match check_component_call0(
        component_bytes,
        component_len,
        host_roots,
        guest_roots,
        writable_preopens,
        preopen_count,
        arguments,
        argument_count,
        environment_keys,
        environment_values,
        environment_count,
        export_name,
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
        p3_limits_from_c(
            max_memory_bytes,
            max_wasm_stack_bytes,
            max_table_elements,
            max_instances,
            max_tables,
            max_memories,
        ),
    ) {
        Ok(()) => ptr::null(),
        Err(error) => set_last_error(error),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_precompiled_component_call_s32_unavailable_reason(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    export_name: *const c_char,
    argument: c_int,
    expected_result: c_int,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
) -> *const c_char {
    match check_component_call_s32(
        component_bytes,
        component_len,
        host_roots,
        guest_roots,
        writable_preopens,
        preopen_count,
        arguments,
        argument_count,
        environment_keys,
        environment_values,
        environment_count,
        export_name,
        argument,
        expected_result,
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
        p3_limits_from_c(
            max_memory_bytes,
            max_wasm_stack_bytes,
            max_table_elements,
            max_instances,
            max_tables,
            max_memories,
        ),
    ) {
        Ok(()) => ptr::null(),
        Err(error) => set_last_error(error),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_precompiled_component_call_string_unavailable_reason(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    export_name: *const c_char,
    argument: *const c_char,
    expected_result: *const c_char,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
) -> *const c_char {
    match check_component_call_string(
        component_bytes,
        component_len,
        host_roots,
        guest_roots,
        writable_preopens,
        preopen_count,
        arguments,
        argument_count,
        environment_keys,
        environment_values,
        environment_count,
        export_name,
        argument,
        expected_result,
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
        p3_limits_from_c(
            max_memory_bytes,
            max_wasm_stack_bytes,
            max_table_elements,
            max_instances,
            max_tables,
            max_memories,
        ),
    ) {
        Ok(()) => ptr::null(),
        Err(error) => set_last_error(error),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_precompiled_component_call_string(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    export_name: *const c_char,
    argument: *const c_char,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
    execution_timeout_millis: u64,
    execution_cancellation: *const ExecutionCancellationHandle,
    result_out: *mut *const c_char,
) -> *const c_char {
    match call_component_string(
        component_bytes,
        component_len,
        host_roots,
        guest_roots,
        writable_preopens,
        preopen_count,
        arguments,
        argument_count,
        environment_keys,
        environment_values,
        environment_count,
        export_name,
        argument,
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
        p3_limits_from_c(
            max_memory_bytes,
            max_wasm_stack_bytes,
            max_table_elements,
            max_instances,
            max_tables,
            max_memories,
        ),
        execution_timeout_millis,
        execution_cancellation,
    ) {
        Ok(result) => {
            if result_out.is_null() {
                set_last_error("result_out is null".to_string())
            } else {
                unsafe {
                    *result_out = set_last_result(result);
                }
                ptr::null()
            }
        }
        Err(error) => set_last_error(error),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_precompiled_command_run_unavailable_reason(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
    execution_timeout_millis: u64,
) -> *const c_char {
    match check_command_run(
        component_bytes,
        component_len,
        host_roots,
        guest_roots,
        writable_preopens,
        preopen_count,
        arguments,
        argument_count,
        environment_keys,
        environment_values,
        environment_count,
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
        p3_limits_from_c(
            max_memory_bytes,
            max_wasm_stack_bytes,
            max_table_elements,
            max_instances,
            max_tables,
            max_memories,
        ),
        execution_timeout_millis,
    ) {
        Ok(()) => ptr::null(),
        Err(error) => set_last_error(error),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn krwa_wasmtime_p3_precompiled_command_run_string(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    stdin_bytes: *const u8,
    stdin_len: usize,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
    max_output_bytes: u64,
    execution_timeout_millis: u64,
    execution_cancellation: *const ExecutionCancellationHandle,
    result_out: *mut *const c_char,
) -> *const c_char {
    match run_command_string(
        component_bytes,
        component_len,
        host_roots,
        guest_roots,
        writable_preopens,
        preopen_count,
        arguments,
        argument_count,
        environment_keys,
        environment_values,
        environment_count,
        stdin_bytes,
        stdin_len,
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
        p3_limits_from_c(
            max_memory_bytes,
            max_wasm_stack_bytes,
            max_table_elements,
            max_instances,
            max_tables,
            max_memories,
        ),
        max_output_bytes,
        execution_timeout_millis,
        execution_cancellation,
    ) {
        Ok(result) => {
            if result_out.is_null() {
                set_last_error("result_out is null".to_string())
            } else {
                unsafe {
                    *result_out = set_last_result(result);
                }
                ptr::null()
            }
        }
        Err(error) => set_last_error(error),
    }
}

fn check_bridge(host_root: *const c_char, guest_root: *const c_char) -> Result<(), String> {
    let preopens = single_preopen_from_c(host_root, guest_root, true)?;
    let limits = P3Limits::default();
    let engine = p3_engine(limits)?;
    let mut linker = Linker::<KrwaP3State>::new(&engine);
    add_p3_linker_imports(&mut linker)?;
    let mut store = Store::new(
        &engine,
        p3_state(&preopens, &[], &[], HttpPolicy::default(), limits)?,
    );
    store.limiter(|state| &mut state.limits);
    Ok(())
}

fn p3_limits_from_c(
    max_memory_bytes: u64,
    max_wasm_stack_bytes: u64,
    max_table_elements: i64,
    max_instances: i64,
    max_tables: i64,
    max_memories: i64,
) -> P3Limits {
    P3Limits {
        max_memory_bytes,
        max_wasm_stack_bytes,
        max_table_elements,
        max_instances,
        max_tables,
        max_memories,
    }
}

fn check_component_instantiation(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    limits: P3Limits,
) -> Result<(), String> {
    let preopens = preopens_from_c(host_roots, guest_roots, writable_preopens, preopen_count)?;
    let arguments = string_list_from_c(arguments, argument_count, "arguments")?;
    let environment = environment_from_c(environment_keys, environment_values, environment_count)?;
    let http_policy = http_policy_from_c(
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
    )?;
    limits.validate()?;
    if component_bytes.is_null() {
        return Err("component_bytes is null".to_string());
    }
    let bytes = unsafe { std::slice::from_raw_parts(component_bytes, component_len) };
    let engine = p3_engine(limits)?;
    let mut linker = Linker::<KrwaP3State>::new(&engine);
    add_p3_linker_imports(&mut linker)?;
    let component = unsafe { Component::deserialize(&engine, bytes) }
        .map_err(|error| format!("failed to deserialize Wasmtime component: {error}"))?;
    let mut store = Store::new(
        &engine,
        p3_state(&preopens, &arguments, &environment, http_policy, limits)?,
    );
    store.limiter(|state| &mut state.limits);
    let _ = arm_execution_deadline(&engine, &mut store, None, None);
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| {
            format!("failed to create Wasmtime Preview3 component runtime: {error}")
        })?;
    runtime.block_on(async {
        linker
            .instantiate_async(&mut store, &component)
            .await
            .map(|_| ())
            .map_err(|error| format!("failed to instantiate Wasmtime Preview3 component: {error}"))
    })
}

fn check_command_run(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    limits: P3Limits,
    execution_timeout_millis: u64,
) -> Result<(), String> {
    let preopens = preopens_from_c(host_roots, guest_roots, writable_preopens, preopen_count)?;
    let arguments = string_list_from_c(arguments, argument_count, "arguments")?;
    let environment = environment_from_c(environment_keys, environment_values, environment_count)?;
    let http_policy = http_policy_from_c(
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
    )?;
    limits.validate()?;
    let execution_timeout = validate_execution_timeout_millis(execution_timeout_millis);
    if component_bytes.is_null() {
        return Err("component_bytes is null".to_string());
    }
    let bytes = unsafe { std::slice::from_raw_parts(component_bytes, component_len) };
    let engine = p3_engine(limits)?;
    let mut linker = Linker::<KrwaP3State>::new(&engine);
    add_p3_linker_imports(&mut linker)?;
    let component = unsafe { Component::deserialize(&engine, bytes) }
        .map_err(|error| format!("failed to deserialize Wasmtime component: {error}"))?;
    let mut store = Store::new(
        &engine,
        p3_state(&preopens, &arguments, &environment, http_policy, limits)?,
    );
    store.limiter(|state| &mut state.limits);
    let watchdog = arm_execution_deadline(&engine, &mut store, execution_timeout, None);
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("failed to create Wasmtime Preview3 command runtime: {error}"))?;
    let result = runtime.block_on(with_execution_timeout(
        execution_timeout,
        run_wasi_command(&mut store, &component, &linker),
    ));
    finalize_execution_result(result, watchdog, execution_timeout)
}

fn run_command_string(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    stdin_bytes: *const u8,
    stdin_len: usize,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    limits: P3Limits,
    max_output_bytes: u64,
    execution_timeout_millis: u64,
    execution_cancellation: *const ExecutionCancellationHandle,
) -> Result<String, String> {
    let preopens = preopens_from_c(host_roots, guest_roots, writable_preopens, preopen_count)?;
    let arguments = string_list_from_c(arguments, argument_count, "arguments")?;
    let environment = environment_from_c(environment_keys, environment_values, environment_count)?;
    let stdin = bytes_from_c(stdin_bytes, stdin_len, "stdin_bytes")?;
    let http_policy = http_policy_from_c(
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
    )?;
    limits.validate()?;
    let execution_timeout = validate_execution_timeout_millis(execution_timeout_millis);
    let execution_cancellation = execution_cancellation_from_c(execution_cancellation);
    let stdio = P3CommandStdio::new(stdin, max_output_bytes)?;
    if component_bytes.is_null() {
        return Err("component_bytes is null".to_string());
    }
    let bytes = unsafe { std::slice::from_raw_parts(component_bytes, component_len) };
    let engine = p3_engine(limits)?;
    let mut linker = Linker::<KrwaP3State>::new(&engine);
    add_p3_linker_imports(&mut linker)?;
    let component = unsafe { Component::deserialize(&engine, bytes) }
        .map_err(|error| format!("failed to deserialize Wasmtime component: {error}"))?;
    let mut store = Store::new(
        &engine,
        p3_state_with_stdio(
            &preopens,
            &arguments,
            &environment,
            http_policy,
            limits,
            Some(&stdio),
        )?,
    );
    store.limiter(|state| &mut state.limits);
    let watchdog = arm_execution_deadline(
        &engine,
        &mut store,
        execution_timeout,
        execution_cancellation,
    );
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| format!("failed to create Wasmtime Preview3 command runtime: {error}"))?;
    let result = runtime.block_on(with_execution_timeout(
        execution_timeout,
        run_wasi_command(&mut store, &component, &linker),
    ));
    match finalize_execution_result(result, watchdog, execution_timeout) {
        Ok(()) => stdio.stdout_string(),
        Err(error) => Err(stdio.command_error(error)),
    }
}

async fn run_wasi_command(
    store: &mut Store<KrwaP3State>,
    component: &Component,
    linker: &Linker<KrwaP3State>,
) -> Result<(), String> {
    let instance = linker
        .instantiate_async(&mut *store, component)
        .await
        .map_err(|error| format!("failed to instantiate Wasmtime command: {error}"))?;
    match P3Command::new(&mut *store, &instance) {
        Ok(command) => {
            let result = match wasi_command_call_result(
                store
                    .run_concurrent(async |store| command.wasi_cli_run().call_run(store).await)
                    .await,
                "failed to run Wasmtime Preview3 command",
            )? {
                Some(result) => result,
                None => return Ok(()),
            };
            let result = match wasi_command_call_result(result, "Wasmtime command trapped")? {
                Some(result) => result,
                None => return Ok(()),
            };
            result.map_err(|()| "Wasmtime command returned failure".to_string())
        }
        Err(_) => {
            let result = match wasi_command_call_result(
                wasmtime_wasi::p2::bindings::Command::new(&mut *store, &instance)
                    .map_err(|error| {
                        format!("failed to bind Wasmtime WASI command export: {error}")
                    })?
                    .wasi_cli_run()
                    .call_run(&mut *store)
                    .await,
                "failed to run Wasmtime Preview2 command",
            )? {
                Some(result) => result,
                None => return Ok(()),
            };
            result.map_err(|()| "Wasmtime command returned failure".to_string())
        }
    }
}

fn wasi_command_call_result<T>(
    result: Result<T, wasmtime::Error>,
    label: &str,
) -> Result<Option<T>, String> {
    match result {
        Ok(value) => Ok(Some(value)),
        Err(error) => {
            if let Some(exit) = error.downcast_ref::<I32Exit>() {
                if exit.0 == 0 {
                    return Ok(None);
                }
                return Err(format!("Wasmtime command exited with status {}", exit.0));
            }
            Err(format!("{label}: {error}"))
        }
    }
}

fn check_component_call0(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    export_name: *const c_char,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    limits: P3Limits,
) -> Result<(), String> {
    let preopens = preopens_from_c(host_roots, guest_roots, writable_preopens, preopen_count)?;
    let arguments = string_list_from_c(arguments, argument_count, "arguments")?;
    let environment = environment_from_c(environment_keys, environment_values, environment_count)?;
    let http_policy = http_policy_from_c(
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
    )?;
    let export_name = string_from_c(export_name, "export_name")?;
    if export_name.trim().is_empty() {
        return Err("Wasmtime Preview3 component export name must not be blank".to_string());
    }
    limits.validate()?;
    if component_bytes.is_null() {
        return Err("component_bytes is null".to_string());
    }
    let bytes = unsafe { std::slice::from_raw_parts(component_bytes, component_len) };
    let engine = p3_engine(limits)?;
    let mut linker = Linker::<KrwaP3State>::new(&engine);
    add_p3_linker_imports(&mut linker)?;
    let component = unsafe { Component::deserialize(&engine, bytes) }
        .map_err(|error| format!("failed to deserialize Wasmtime component: {error}"))?;
    let mut store = Store::new(
        &engine,
        p3_state(&preopens, &arguments, &environment, http_policy, limits)?,
    );
    store.limiter(|state| &mut state.limits);
    let _ = arm_execution_deadline(&engine, &mut store, None, None);
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| {
            format!("failed to create Wasmtime Preview3 component runtime: {error}")
        })?;
    runtime.block_on(async {
        let instance = linker
            .instantiate_async(&mut store, &component)
            .await
            .map_err(|error| format!("failed to instantiate Wasmtime Preview3 component: {error}"))?;
        let export_index = component_export_index(&component, &engine, &instance, &mut store, export_name)?;
        let function = instance
            .get_typed_func::<(), ()>(&mut store, &export_index)
            .map_err(|error| {
                format!("Wasmtime Preview3 component export is not a function with type () -> (): {export_name}: {error}")
            })?;
        store
            .run_concurrent(async move |store| {
                function
                    .call_concurrent(store, ())
                    .await
                    .map_err(|error| {
                        format!(
                            "failed to call Wasmtime Preview3 component export {export_name}: {error:#}"
                        )
                    })
            })
            .await
            .map_err(|error| {
                format!("failed to drive Wasmtime Preview3 component export {export_name}: {error:#}")
            })?
    })
}

fn check_component_call_s32(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    export_name: *const c_char,
    argument: c_int,
    expected_result: c_int,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    limits: P3Limits,
) -> Result<(), String> {
    let preopens = preopens_from_c(host_roots, guest_roots, writable_preopens, preopen_count)?;
    let arguments = string_list_from_c(arguments, argument_count, "arguments")?;
    let environment = environment_from_c(environment_keys, environment_values, environment_count)?;
    let http_policy = http_policy_from_c(
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
    )?;
    let export_name = string_from_c(export_name, "export_name")?;
    if export_name.trim().is_empty() {
        return Err("Wasmtime Preview3 component export name must not be blank".to_string());
    }
    limits.validate()?;
    if component_bytes.is_null() {
        return Err("component_bytes is null".to_string());
    }
    let bytes = unsafe { std::slice::from_raw_parts(component_bytes, component_len) };
    let engine = p3_engine(limits)?;
    let mut linker = Linker::<KrwaP3State>::new(&engine);
    add_p3_linker_imports(&mut linker)?;
    let component = unsafe { Component::deserialize(&engine, bytes) }
        .map_err(|error| format!("failed to deserialize Wasmtime component: {error}"))?;
    let mut store = Store::new(
        &engine,
        p3_state(&preopens, &arguments, &environment, http_policy, limits)?,
    );
    store.limiter(|state| &mut state.limits);
    let _ = arm_execution_deadline(&engine, &mut store, None, None);
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| {
            format!("failed to create Wasmtime Preview3 component runtime: {error}")
        })?;
    let results = runtime.block_on(async {
        let instance = linker
            .instantiate_async(&mut store, &component)
            .await
            .map_err(|error| format!("failed to instantiate Wasmtime Preview3 component: {error}"))?;
        let export_index = component_export_index(&component, &engine, &instance, &mut store, export_name)?;
        let function = instance
            .get_typed_func::<(i32,), (i32,)>(&mut store, &export_index)
            .map_err(|error| {
                format!("Wasmtime Preview3 component export is not a function with type (s32) -> s32: {export_name}: {error}")
            })?;
        store
            .run_concurrent(async move |store| {
                function
                    .call_concurrent(store, (argument,))
                    .await
                    .map_err(|error| {
                        format!(
                            "failed to call Wasmtime Preview3 component export {export_name}: {error:#}"
                        )
                    })
            })
            .await
            .map_err(|error| {
                format!("failed to drive Wasmtime Preview3 component export {export_name}: {error:#}")
            })?
    })?;
    match results {
        (value,) if value == expected_result => Ok(()),
        value => Err(format!(
            "Wasmtime Preview3 component export {export_name} returned {value:?}; expected s32 {expected_result}"
        )),
    }
}

fn check_component_call_string(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    export_name: *const c_char,
    argument: *const c_char,
    expected_result: *const c_char,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    limits: P3Limits,
) -> Result<(), String> {
    let expected_result = string_from_c(expected_result, "expected_result")?.to_string();
    let value = call_component_string(
        component_bytes,
        component_len,
        host_roots,
        guest_roots,
        writable_preopens,
        preopen_count,
        arguments,
        argument_count,
        environment_keys,
        environment_values,
        environment_count,
        export_name,
        argument,
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
        limits,
        0,
        ptr::null(),
    )?;
    if value == expected_result {
        Ok(())
    } else {
        let export_name = string_from_c(export_name, "export_name")?;
        Err(format!(
            "Wasmtime Preview3 component export {export_name} returned {value:?}; expected string {expected_result:?}"
        ))
    }
}

fn call_component_string(
    component_bytes: *const u8,
    component_len: usize,
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
    arguments: *const *const c_char,
    argument_count: usize,
    environment_keys: *const *const c_char,
    environment_values: *const *const c_char,
    environment_count: usize,
    export_name: *const c_char,
    argument: *const c_char,
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
    limits: P3Limits,
    execution_timeout_millis: u64,
    execution_cancellation: *const ExecutionCancellationHandle,
) -> Result<String, String> {
    let preopens = preopens_from_c(host_roots, guest_roots, writable_preopens, preopen_count)?;
    let arguments = string_list_from_c(arguments, argument_count, "arguments")?;
    let environment = environment_from_c(environment_keys, environment_values, environment_count)?;
    let http_policy = http_policy_from_c(
        allowed_hosts,
        allowed_host_count,
        blocked_hosts,
        blocked_host_count,
        allow_private_network,
    )?;
    let export_name = string_from_c(export_name, "export_name")?;
    if export_name.trim().is_empty() {
        return Err("Wasmtime Preview3 component export name must not be blank".to_string());
    }
    let argument = string_from_c(argument, "argument")?.to_string();
    limits.validate()?;
    let execution_timeout = validate_execution_timeout_millis(execution_timeout_millis);
    let execution_cancellation = execution_cancellation_from_c(execution_cancellation);
    if component_bytes.is_null() {
        return Err("component_bytes is null".to_string());
    }
    let bytes = unsafe { std::slice::from_raw_parts(component_bytes, component_len) };
    let engine = p3_engine(limits)?;
    let mut linker = Linker::<KrwaP3State>::new(&engine);
    add_p3_linker_imports(&mut linker)?;
    let component = unsafe { Component::deserialize(&engine, bytes) }
        .map_err(|error| format!("failed to deserialize Wasmtime component: {error}"))?;
    let mut store = Store::new(
        &engine,
        p3_state(&preopens, &arguments, &environment, http_policy, limits)?,
    );
    store.limiter(|state| &mut state.limits);
    let watchdog = arm_execution_deadline(
        &engine,
        &mut store,
        execution_timeout,
        execution_cancellation,
    );
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|error| {
            format!("failed to create Wasmtime Preview3 component runtime: {error}")
        })?;
    let results = runtime.block_on(with_execution_timeout(execution_timeout, async {
        let instance = linker
            .instantiate_async(&mut store, &component)
            .await
            .map_err(|error| format!("failed to instantiate Wasmtime Preview3 component: {error}"))?;
        let export_index = component_export_index(&component, &engine, &instance, &mut store, export_name)?;
        let function = instance
            .get_typed_func::<(String,), (String,)>(&mut store, &export_index)
            .map_err(|error| {
                format!("Wasmtime Preview3 component export is not a function with type (string) -> string: {export_name}: {error}")
            })?;
        store
            .run_concurrent(async move |store| {
                function
                    .call_concurrent(store, (argument,))
                    .await
                    .map_err(|error| {
                        format!(
                            "failed to call Wasmtime Preview3 component export {export_name}: {error:#}"
                        )
                    })
            })
            .await
            .map_err(|error| {
                format!("failed to drive Wasmtime Preview3 component export {export_name}: {error:#}")
            })?
    }));
    let results = finalize_execution_result(results, watchdog, execution_timeout)?;
    match results {
        (value,) => Ok(value),
    }
}

fn component_export_index(
    component: &Component,
    engine: &Engine,
    instance: &wasmtime::component::Instance,
    store: &mut Store<KrwaP3State>,
    export_name: &str,
) -> Result<ComponentExportIndex, String> {
    let parts = export_name.split('.').collect::<Vec<_>>();
    if parts.iter().any(|part| part.is_empty()) {
        return Err(format!(
            "Wasmtime Preview3 component export path contains an empty segment: {export_name}"
        ));
    }

    if let Some(index) = component_export_index_with_root(instance, store, parts[0], &parts[1..]) {
        return Ok(index);
    }

    if parts.len() > 1 {
        let qualified_roots = qualified_interface_export_names(component, engine, parts[0]);
        if qualified_roots.len() > 1 {
            return Err(format!(
                "Wasmtime Preview3 component export path is ambiguous for local interface {}: {}",
                parts[0],
                qualified_roots.join(", ")
            ));
        }
        if let Some(qualified_root) = qualified_roots.first() {
            if let Some(index) =
                component_export_index_with_root(instance, store, qualified_root, &parts[1..])
            {
                return Ok(index);
            }
        }
    }

    Err(format!(
        "Wasmtime Preview3 component export was not found: {export_name}"
    ))
}

fn component_export_index_with_root(
    instance: &wasmtime::component::Instance,
    store: &mut Store<KrwaP3State>,
    root_name: &str,
    nested_names: &[&str],
) -> Option<ComponentExportIndex> {
    let mut parent = instance.get_export_index(&mut *store, None, root_name)?;
    for name in nested_names {
        parent = instance.get_export_index(&mut *store, Some(&parent), *name)?;
    }
    Some(parent)
}

fn qualified_interface_export_names(
    component: &Component,
    engine: &Engine,
    local_name: &str,
) -> Vec<String> {
    component
        .component_type()
        .exports(engine)
        .filter_map(|(name, item)| match item {
            item if matches!(item.ty, ComponentItem::ComponentInstance(_))
                && interface_export_matches(name, local_name) =>
            {
                Some(name.to_string())
            }
            _ => None,
        })
        .collect()
}

fn interface_export_matches(export_name: &str, local_name: &str) -> bool {
    export_name.rsplit_once('/').is_some_and(|(_, interface)| {
        interface == local_name || interface.starts_with(&format!("{local_name}@"))
    })
}

fn add_p3_linker_imports(linker: &mut Linker<KrwaP3State>) -> Result<(), String> {
    // The Wasmtime P3 command binding can run components that still import WASI
    // 0.2.x through the preview1 adapter. Link both namespaces, matching
    // Wasmtime's own P3 command runner tests.
    wasmtime_wasi::p2::add_to_linker_async(linker)
        .map_err(|error| format!("failed to link Wasmtime WASI Preview2 imports: {error}"))?;
    wasmtime_wasi::p3::add_to_linker(linker)
        .map_err(|error| format!("failed to link Wasmtime WASI Preview3 imports: {error}"))?;
    wasmtime_wasi_http::p3::add_to_linker(linker)
        .map_err(|error| format!("failed to link Wasmtime WASI Preview3 HTTP imports: {error}"))?;
    Ok(())
}

struct ExecutionDeadlineWatchdog {
    control: Arc<ExecutionControlState>,
    timed_out: Arc<AtomicBool>,
    cancelled: Arc<AtomicBool>,
    handle: Option<thread::JoinHandle<()>>,
}

impl ExecutionDeadlineWatchdog {
    fn timed_out(&self) -> bool {
        self.timed_out.load(Ordering::SeqCst)
    }

    fn cancelled(&self) -> bool {
        self.cancelled.load(Ordering::SeqCst)
    }
}

impl Drop for ExecutionDeadlineWatchdog {
    fn drop(&mut self) {
        if let Ok(mut state) = self.control.state.lock() {
            state.completed = true;
            self.control.cvar.notify_all();
        }
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn execution_cancellation_from_c(
    cancellation: *const ExecutionCancellationHandle,
) -> Option<Arc<ExecutionControlState>> {
    if cancellation.is_null() {
        None
    } else {
        Some(unsafe { Arc::clone(&(*cancellation).state) })
    }
}

fn arm_execution_deadline(
    engine: &Engine,
    store: &mut Store<KrwaP3State>,
    timeout: Option<Duration>,
    cancellation: Option<Arc<ExecutionControlState>>,
) -> Option<ExecutionDeadlineWatchdog> {
    store.epoch_deadline_trap();
    store.set_epoch_deadline(if timeout.is_some() || cancellation.is_some() {
        1
    } else {
        DISABLED_EXECUTION_TIMEOUT_EPOCH_DEADLINE
    });

    if timeout.is_none() && cancellation.is_none() {
        return None;
    }

    let control = cancellation.unwrap_or_else(|| Arc::new(ExecutionControlState::new()));
    let timed_out = Arc::new(AtomicBool::new(false));
    let cancelled = Arc::new(AtomicBool::new(false));
    let thread_control = Arc::clone(&control);
    let thread_timed_out = Arc::clone(&timed_out);
    let thread_cancelled = Arc::clone(&cancelled);
    let engine = engine.clone();
    let handle = thread::spawn(move || {
        let deadline = timeout.map(|duration| Instant::now() + duration);
        let Ok(mut state) = thread_control.state.lock() else {
            return;
        };
        loop {
            if state.completed {
                return;
            }
            if state.cancelled {
                thread_cancelled.store(true, Ordering::SeqCst);
                engine.increment_epoch();
                return;
            }
            match deadline {
                Some(deadline) => {
                    let now = Instant::now();
                    if now >= deadline {
                        thread_timed_out.store(true, Ordering::SeqCst);
                        engine.increment_epoch();
                        return;
                    }
                    let Ok((new_state, wait_result)) =
                        thread_control.cvar.wait_timeout(state, deadline - now)
                    else {
                        return;
                    };
                    state = new_state;
                    if wait_result.timed_out() && !state.completed && !state.cancelled {
                        thread_timed_out.store(true, Ordering::SeqCst);
                        engine.increment_epoch();
                        return;
                    }
                }
                None => {
                    let Ok(new_state) = thread_control.cvar.wait(state) else {
                        return;
                    };
                    state = new_state;
                }
            }
        }
    });

    Some(ExecutionDeadlineWatchdog {
        control,
        timed_out,
        cancelled,
        handle: Some(handle),
    })
}

async fn with_execution_timeout<T, F>(timeout: Option<Duration>, future: F) -> Result<T, String>
where
    F: Future<Output = Result<T, String>>,
{
    match timeout {
        Some(timeout) => tokio::time::timeout(timeout, future)
            .await
            .map_err(|_| execution_timeout_message(timeout))?,
        None => future.await,
    }
}

fn finalize_execution_result<T>(
    result: Result<T, String>,
    watchdog: Option<ExecutionDeadlineWatchdog>,
    timeout: Option<Duration>,
) -> Result<T, String> {
    let timed_out = watchdog
        .as_ref()
        .is_some_and(ExecutionDeadlineWatchdog::timed_out);
    let cancelled = watchdog
        .as_ref()
        .is_some_and(ExecutionDeadlineWatchdog::cancelled);
    drop(watchdog);
    match result {
        Err(_) if cancelled => Err(execution_cancelled_message()),
        Err(_) if timed_out => Err(execution_timeout_message(
            timeout.expect("timed out watchdog requires timeout"),
        )),
        other => other,
    }
}

fn execution_cancelled_message() -> String {
    "Wasmtime Preview3 execution was cancelled".to_string()
}

fn execution_timeout_message(timeout: Duration) -> String {
    format!(
        "Wasmtime Preview3 execution timed out after {} ms",
        timeout.as_millis()
    )
}

fn p3_engine(limits: P3Limits) -> Result<Engine, String> {
    let max_wasm_stack_size = validate_max_wasm_stack_bytes(limits.max_wasm_stack_bytes)?;
    let mut config = Config::new();
    config
        .target("pulley64")
        .map_err(|error| format!("failed to configure Wasmtime Pulley target: {error}"))?;
    config.max_wasm_stack(max_wasm_stack_size);
    config.wasm_component_model(true);
    config.wasm_component_model_async(true);
    config.wasm_component_model_more_async_builtins(true);
    config.wasm_component_model_async_stackful(true);
    config.wasm_component_model_threading(true);
    config.wasm_component_model_error_context(true);
    config.wasm_gc(true);
    config.wasm_function_references(true);
    config.wasm_reference_types(true);
    config.wasm_exceptions(true);
    config.wasm_bulk_memory(true);
    config.wasm_multi_memory(true);
    config.async_support(true);
    config.epoch_interruption(true);
    Engine::new(&config)
        .map_err(|error| format!("failed to create Wasmtime Preview3 engine: {error}"))
}

fn p3_state(
    preopens: &[P3Preopen],
    arguments: &[String],
    environment: &[(String, String)],
    http_policy: HttpPolicy,
    limits: P3Limits,
) -> Result<KrwaP3State, String> {
    p3_state_with_stdio(preopens, arguments, environment, http_policy, limits, None)
}

fn p3_state_with_stdio(
    preopens: &[P3Preopen],
    arguments: &[String],
    environment: &[(String, String)],
    http_policy: HttpPolicy,
    limits: P3Limits,
    stdio: Option<&P3CommandStdio>,
) -> Result<KrwaP3State, String> {
    let store_limits = wasmtime_store_limits(limits)?;
    if preopens.is_empty() {
        return Err("Wasmtime Preview3 preopen list must not be empty".to_string());
    }
    let mut guest_roots = HashSet::new();
    let mut builder = WasiCtxBuilder::new();
    builder.args(arguments);
    builder.envs(environment);
    if let Some(stdio) = stdio {
        builder.stdin(MemoryInputPipe::new(stdio.stdin.clone()));
        builder.stdout(stdio.stdout.clone());
        builder.stderr(stdio.stderr.clone());
    }
    for preopen in preopens {
        let host_root = validated_host_preopen_root(&preopen.host_root)?;
        validate_guest_preopen_root(&preopen.guest_root)?;
        let normalized_guest_root = normalized_guest_root(&preopen.guest_root);
        if !guest_roots.insert(normalized_guest_root.clone()) {
            return Err(format!(
                "Wasmtime Preview3 guest preopen root must be unique: {}",
                normalized_guest_root
            ));
        }
        let dir_perms = if preopen.writable {
            DirPerms::READ | DirPerms::MUTATE
        } else {
            DirPerms::READ
        };
        let file_perms = if preopen.writable {
            FilePerms::READ | FilePerms::WRITE
        } else {
            FilePerms::READ
        };
        builder
            .preopened_dir(&host_root, &preopen.guest_root, dir_perms, file_perms)
            .map_err(|error| format!("failed to preopen Wasmtime Preview3 directory: {error}"))?;
    }
    Ok(KrwaP3State {
        ctx: builder.build(),
        table: ResourceTable::new(),
        http: WasiHttpCtx::new(),
        http_hooks: PolicyHttpHooks {
            policy: http_policy,
        },
        limits: store_limits,
    })
}

impl P3CommandStdio {
    fn new(stdin: Vec<u8>, max_output_bytes: u64) -> Result<Self, String> {
        let max_output_bytes = max_output_bytes
            .try_into()
            .map_err(|_| "Wasmtime Preview3 max output bytes exceeds host usize".to_string())?;
        if max_output_bytes == 0 {
            return Err("Wasmtime Preview3 max output bytes must be positive".to_string());
        }
        Ok(Self {
            stdin: Bytes::from(stdin),
            stdout: MemoryOutputPipe::new(max_output_bytes),
            stderr: MemoryOutputPipe::new(max_output_bytes),
        })
    }

    fn stdout_string(&self) -> Result<String, String> {
        String::from_utf8(self.stdout.contents().to_vec())
            .map_err(|error| format!("Wasmtime Preview3 command stdout was not UTF-8: {error}"))
    }

    fn command_error(&self, error: String) -> String {
        let stderr = String::from_utf8_lossy(&self.stderr.contents())
            .trim()
            .to_string();
        if stderr.is_empty() {
            error
        } else {
            format!("{error}: stderr={stderr}")
        }
    }
}

fn single_preopen_from_c(
    host_root: *const c_char,
    guest_root: *const c_char,
    writable: bool,
) -> Result<Vec<P3Preopen>, String> {
    Ok(vec![P3Preopen {
        host_root: path_from_c(host_root, "host_root")?.to_path_buf(),
        guest_root: string_from_c(guest_root, "guest_root")?.to_string(),
        writable,
    }])
}

fn preopens_from_c(
    host_roots: *const *const c_char,
    guest_roots: *const *const c_char,
    writable_preopens: *const u8,
    preopen_count: usize,
) -> Result<Vec<P3Preopen>, String> {
    if preopen_count == 0 {
        return Err("Wasmtime Preview3 preopen list must not be empty".to_string());
    }
    if host_roots.is_null() {
        return Err("host_roots is null".to_string());
    }
    if guest_roots.is_null() {
        return Err("guest_roots is null".to_string());
    }
    if writable_preopens.is_null() {
        return Err("writable_preopens is null".to_string());
    }
    let mut preopens = Vec::with_capacity(preopen_count);
    for index in 0..preopen_count {
        let host_root = unsafe { *host_roots.add(index) };
        let guest_root = unsafe { *guest_roots.add(index) };
        let writable = unsafe { *writable_preopens.add(index) != 0 };
        preopens.push(P3Preopen {
            host_root: path_from_c(host_root, &format!("host_roots[{index}]"))?.to_path_buf(),
            guest_root: string_from_c(guest_root, &format!("guest_roots[{index}]"))?.to_string(),
            writable,
        });
    }
    Ok(preopens)
}

fn string_list_from_c(
    values: *const *const c_char,
    count: usize,
    label: &str,
) -> Result<Vec<String>, String> {
    if count == 0 {
        return Ok(Vec::new());
    }
    if values.is_null() {
        return Err(format!("{label} is null"));
    }
    let mut result = Vec::with_capacity(count);
    for index in 0..count {
        let value = unsafe { *values.add(index) };
        result.push(string_from_c(value, &format!("{label}[{index}]"))?.to_string());
    }
    Ok(result)
}

fn bytes_from_c(values: *const u8, count: usize, label: &str) -> Result<Vec<u8>, String> {
    if count == 0 {
        return Ok(Vec::new());
    }
    if values.is_null() {
        return Err(format!("{label} is null"));
    }
    Ok(unsafe { std::slice::from_raw_parts(values, count) }.to_vec())
}

fn environment_from_c(
    keys: *const *const c_char,
    values: *const *const c_char,
    count: usize,
) -> Result<Vec<(String, String)>, String> {
    let keys = string_list_from_c(keys, count, "environment_keys")?;
    let values = string_list_from_c(values, count, "environment_values")?;
    let mut result = Vec::with_capacity(count);
    for (index, (key, value)) in keys.into_iter().zip(values.into_iter()).enumerate() {
        if key.trim().is_empty() {
            return Err(format!("environment_keys[{index}] is blank"));
        }
        result.push((key, value));
    }
    Ok(result)
}

fn http_policy_from_c(
    allowed_hosts: *const *const c_char,
    allowed_host_count: usize,
    blocked_hosts: *const *const c_char,
    blocked_host_count: usize,
    allow_private_network: u8,
) -> Result<HttpPolicy, String> {
    Ok(HttpPolicy {
        allowed_hosts: host_patterns_from_c(allowed_hosts, allowed_host_count, "allowed_hosts")?,
        blocked_hosts: host_patterns_from_c(blocked_hosts, blocked_host_count, "blocked_hosts")?,
        allow_private_network: allow_private_network != 0,
    })
}

fn host_patterns_from_c(
    values: *const *const c_char,
    count: usize,
    label: &str,
) -> Result<Vec<String>, String> {
    let values = string_list_from_c(values, count, label)?;
    let mut result = Vec::with_capacity(values.len());
    for (index, value) in values.into_iter().enumerate() {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            return Err(format!("{label}[{index}] is blank"));
        }
        if trimmed != value {
            return Err(format!(
                "{label}[{index}] must not contain surrounding whitespace"
            ));
        }
        if value.contains("://") || value.contains('/') || value.contains('\\') {
            return Err(format!("{label}[{index}] must be a host pattern"));
        }
        result.push(value.to_ascii_lowercase());
    }
    Ok(result)
}

fn host_matches_any(host: &str, patterns: &[String]) -> bool {
    patterns.iter().any(|pattern| {
        let pattern = pattern.as_str();
        if pattern == "*" {
            return true;
        }
        let pattern = pattern.strip_prefix("*.").unwrap_or(pattern);
        host == pattern || host.ends_with(&format!(".{pattern}"))
    })
}

fn normalized_host(host: &str) -> String {
    host.trim_matches(|char| char == '[' || char == ']')
        .trim_end_matches('.')
        .to_ascii_lowercase()
}

fn is_private_network_host(host: &str) -> bool {
    is_local_network_host(host)
        || is_private_ipv6_host(host)
        || ipv4_octets(host).is_some_and(is_private_ipv4_host)
}

fn is_local_network_host(host: &str) -> bool {
    host == "localhost" || host.ends_with(".localhost") || host.ends_with(".local")
}

fn is_private_ipv6_host(host: &str) -> bool {
    host == "::1" || host.starts_with("fc") || host.starts_with("fd") || host.starts_with("fe80:")
}

fn ipv4_octets(host: &str) -> Option<[u8; 4]> {
    let mut result = [0; 4];
    let mut parts = host.split('.');
    for item in &mut result {
        *item = parts.next()?.parse::<u8>().ok()?;
    }
    if parts.next().is_some() {
        return None;
    }
    Some(result)
}

fn is_private_ipv4_host(octets: [u8; 4]) -> bool {
    match octets[0] {
        0 | 10 | 127 => true,
        100 => (64..=127).contains(&octets[1]),
        169 => octets[1] == 254,
        172 => (16..=31).contains(&octets[1]),
        192 => octets[1] == 168,
        _ => false,
    }
}

fn validate_max_memory_bytes(max_memory_bytes: u64) -> Result<usize, String> {
    if max_memory_bytes == 0 {
        return Err("Wasmtime Preview3 max memory bytes must be positive".to_string());
    }
    usize::try_from(max_memory_bytes)
        .map_err(|_| "Wasmtime Preview3 max memory bytes exceeds host usize".to_string())
}

fn validate_max_wasm_stack_bytes(max_wasm_stack_bytes: u64) -> Result<usize, String> {
    if max_wasm_stack_bytes == 0 {
        return Err("Wasmtime Preview3 max Wasm stack bytes must be positive".to_string());
    }
    usize::try_from(max_wasm_stack_bytes)
        .map_err(|_| "Wasmtime Preview3 max Wasm stack bytes exceeds host usize".to_string())
}

fn validate_optional_resource_limit(label: &str, value: i64) -> Result<Option<usize>, String> {
    if value == UNLIMITED_RESOURCE_LIMIT {
        return Ok(None);
    }
    if value < UNLIMITED_RESOURCE_LIMIT {
        return Err(format!(
            "Wasmtime Preview3 {label} must be {UNLIMITED_RESOURCE_LIMIT} for unlimited or non-negative"
        ));
    }
    usize::try_from(value)
        .map(Some)
        .map_err(|_| format!("Wasmtime Preview3 {label} exceeds host usize"))
}

fn wasmtime_store_limits(limits: P3Limits) -> Result<StoreLimits, String> {
    let mut builder =
        StoreLimitsBuilder::new().memory_size(validate_max_memory_bytes(limits.max_memory_bytes)?);
    if let Some(limit) =
        validate_optional_resource_limit("max table elements", limits.max_table_elements)?
    {
        builder = builder.table_elements(limit);
    }
    if let Some(limit) = validate_optional_resource_limit("max instances", limits.max_instances)? {
        builder = builder.instances(limit);
    }
    if let Some(limit) = validate_optional_resource_limit("max tables", limits.max_tables)? {
        builder = builder.tables(limit);
    }
    if let Some(limit) = validate_optional_resource_limit("max memories", limits.max_memories)? {
        builder = builder.memories(limit);
    }
    Ok(builder.build())
}

fn validate_execution_timeout_millis(timeout_millis: u64) -> Option<Duration> {
    if timeout_millis == 0 {
        return None;
    }
    Some(Duration::from_millis(timeout_millis))
}

const ALLOWED_HTTP_METHODS: &[&str] = &["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"];

fn validated_host_preopen_root(host_root: &Path) -> Result<PathBuf, String> {
    let raw_host_root = host_root.to_string_lossy();
    if raw_host_root.trim() != raw_host_root {
        return Err(format!(
            "Wasmtime Preview3 host preopen root must not contain surrounding whitespace: {}",
            host_root.display()
        ));
    }
    if !host_root.is_absolute() {
        return Err(format!(
            "Wasmtime Preview3 host preopen root must be absolute: {}",
            host_root.display()
        ));
    }
    if contains_current_or_parent_segment(&raw_host_root) {
        return Err(format!(
            "Wasmtime Preview3 host preopen root must not contain current or parent segments: {}",
            host_root.display()
        ));
    }
    let metadata = fs::symlink_metadata(host_root).map_err(|error| {
        format!(
            "failed to stat Wasmtime Preview3 host preopen root {}: {error}",
            host_root.display()
        )
    })?;
    if metadata.file_type().is_symlink() {
        return Err(format!(
            "Wasmtime Preview3 host preopen root must not be a symbolic link: {}",
            host_root.display()
        ));
    }

    let canonical = host_root.canonicalize().map_err(|error| {
        format!(
            "failed to canonicalize Wasmtime Preview3 host preopen root {}: {error}",
            host_root.display()
        )
    })?;
    if !canonical.is_dir() {
        return Err(format!(
            "Wasmtime Preview3 host preopen root must be a directory: {}",
            canonical.display()
        ));
    }
    if canonical.parent().is_none() {
        return Err(
            "Wasmtime Preview3 host preopen root must not be the filesystem root".to_string(),
        );
    }
    Ok(canonical)
}

fn validate_guest_preopen_root(guest_root: &str) -> Result<(), String> {
    let trimmed_guest_root = guest_root.trim();
    if trimmed_guest_root.is_empty() {
        return Err("Wasmtime Preview3 guest preopen root is blank".to_string());
    }
    if trimmed_guest_root != guest_root {
        return Err(format!(
            "Wasmtime Preview3 guest preopen root must not contain surrounding whitespace: {guest_root}"
        ));
    }
    if guest_root.contains('\\') {
        return Err(format!(
            "Wasmtime Preview3 guest preopen root must use forward slashes: {guest_root}"
        ));
    }
    let guest_path = Path::new(guest_root);
    if !guest_path.is_absolute() {
        return Err(format!(
            "Wasmtime Preview3 guest preopen root must be absolute: {guest_root}"
        ));
    }
    if contains_current_or_parent_segment(guest_root) {
        return Err(format!(
            "Wasmtime Preview3 guest preopen root must not contain current or parent segments: {guest_root}"
        ));
    }
    Ok(())
}

fn contains_current_or_parent_segment(path: &str) -> bool {
    path.split(|character| character == '/' || character == '\\')
        .any(|segment| segment == "." || segment == "..")
}

fn normalized_guest_root(guest_root: &str) -> String {
    let normalized = guest_root.trim_end_matches('/');
    if normalized.is_empty() {
        "/".to_string()
    } else {
        normalized.to_string()
    }
}

fn path_from_c<'a>(value: *const c_char, label: &str) -> Result<&'a Path, String> {
    let value = string_from_c(value, label)?;
    Ok(Path::new(value))
}

fn string_from_c<'a>(value: *const c_char, label: &str) -> Result<&'a str, String> {
    if value.is_null() {
        return Err(format!("{label} is null"));
    }
    unsafe { CStr::from_ptr(value) }
        .to_str()
        .map_err(|error| format!("{label} is not valid UTF-8: {error}"))
}

fn set_last_error(error: String) -> *const c_char {
    let sanitized = error.replace('\0', "\\0");
    LAST_ERROR.with(|last_error| {
        *last_error.borrow_mut() =
            Some(CString::new(sanitized).expect("sanitized error contains no NUL"));
        last_error
            .borrow()
            .as_ref()
            .map_or(ptr::null(), |error| error.as_ptr())
    })
}

fn set_last_result(result: String) -> *const c_char {
    let sanitized = result.replace('\0', "\\0");
    LAST_RESULT.with(|last_result| {
        *last_result.borrow_mut() =
            Some(CString::new(sanitized).expect("sanitized result contains no NUL"));
        last_result
            .borrow()
            .as_ref()
            .map_or(ptr::null(), |result| result.as_ptr())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn p3_bridge_links_wasi_and_preopens_sandbox_root() {
        let sandbox = temp_sandbox("p3-bridge");
        let host_root = CString::new(sandbox.to_string_lossy().as_bytes()).unwrap();
        let guest_root = CString::new("/").unwrap();

        assert!(
            krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr())
                .is_null()
        );

        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_bridge_reports_invalid_preopen_arguments() {
        let guest_root = CString::new("/").unwrap();
        let error = krwa_wasmtime_p3_bridge_unavailable_reason(ptr::null(), guest_root.as_ptr());

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(message.contains("host_root is null"), "{message}");
    }

    #[test]
    fn p3_bridge_rejects_host_filesystem_root_preopen() {
        let host_root = CString::new("/").unwrap();
        let guest_root = CString::new("/").unwrap();

        let error =
            krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr());

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(
            message.contains("must not be the filesystem root"),
            "{message}"
        );
    }

    #[test]
    fn p3_bridge_rejects_relative_host_preopen_root() {
        let host_root = CString::new("relative-sandbox").unwrap();
        let guest_root = CString::new("/").unwrap();

        let error =
            krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr());

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(
            message.contains("host preopen root must be absolute"),
            "{message}"
        );
    }

    #[test]
    fn p3_bridge_rejects_current_or_parent_host_preopen_segments() {
        let sandbox = temp_sandbox("p3-bridge-host-segments");
        let host_with_current = sandbox.join(".");
        let host_root = CString::new(host_with_current.to_string_lossy().as_bytes()).unwrap();
        let guest_root = CString::new("/").unwrap();

        let error =
            krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr());

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(
            message.contains("host preopen root must not contain current or parent segments"),
            "{message}"
        );

        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_bridge_rejects_file_host_preopen_root() {
        let sandbox = temp_sandbox("p3-bridge-file-host");
        let file = sandbox.join("not-a-directory");
        fs::write(&file, b"test").unwrap();
        let host_root = CString::new(file.to_string_lossy().as_bytes()).unwrap();
        let guest_root = CString::new("/").unwrap();

        let error =
            krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr());

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(
            message.contains("host preopen root must be a directory"),
            "{message}"
        );

        fs::remove_dir_all(sandbox).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn p3_bridge_rejects_host_preopen_symlink_root() {
        use std::os::unix::fs::symlink;

        let sandbox = temp_sandbox("p3-bridge-host-symlink");
        let root_link = sandbox.join("root-link");
        symlink("/", &root_link).unwrap();
        let host_root = CString::new(root_link.to_string_lossy().as_bytes()).unwrap();
        let guest_root = CString::new("/").unwrap();

        let error =
            krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr());

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(
            message.contains("host preopen root must not be a symbolic link"),
            "{message}"
        );

        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_bridge_rejects_relative_guest_preopen_root() {
        let sandbox = temp_sandbox("p3-bridge-relative-guest");
        let host_root = CString::new(sandbox.to_string_lossy().as_bytes()).unwrap();
        let guest_root = CString::new("cache").unwrap();

        let error =
            krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr());

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(
            message.contains("guest preopen root must be absolute"),
            "{message}"
        );

        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_bridge_rejects_parent_segments_in_guest_preopen_root() {
        let sandbox = temp_sandbox("p3-bridge-parent-guest");
        let host_root = CString::new(sandbox.to_string_lossy().as_bytes()).unwrap();
        let guest_root = CString::new("/../cache").unwrap();

        let error =
            krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr());

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(
            message.contains("guest preopen root must not contain current or parent segments"),
            "{message}"
        );

        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_bridge_rejects_current_segments_and_backslashes_in_guest_preopen_root() {
        let sandbox = temp_sandbox("p3-bridge-current-guest");
        let host_root = CString::new(sandbox.to_string_lossy().as_bytes()).unwrap();

        for guest_root in ["/./cache", "\\suvio\\cache"] {
            let guest_root = CString::new(guest_root).unwrap();
            let error =
                krwa_wasmtime_p3_bridge_unavailable_reason(host_root.as_ptr(), guest_root.as_ptr());

            assert!(!error.is_null());
            let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
            assert!(message.contains("guest preopen root"), "{message}");
        }

        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_bridge_accepts_multiple_explicit_preopens() {
        let sandbox = temp_sandbox("p3-bridge-multiple-preopens");
        let cache = sandbox.join("cache");
        let data = sandbox.join("data");
        fs::create_dir_all(&cache).unwrap();
        fs::create_dir_all(&data).unwrap();

        let state = p3_state(
            &[
                P3Preopen {
                    host_root: cache,
                    guest_root: "/suvio/cache".to_string(),
                    writable: true,
                },
                P3Preopen {
                    host_root: data,
                    guest_root: "/suvio/data".to_string(),
                    writable: false,
                },
            ],
            &[],
            &[],
            HttpPolicy::default(),
            P3Limits::default(),
        );

        assert!(state.is_ok());
        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_bridge_rejects_duplicate_guest_preopen_roots() {
        let sandbox = temp_sandbox("p3-bridge-duplicate-preopens");
        let cache_a = sandbox.join("cache-a");
        let cache_b = sandbox.join("cache-b");
        fs::create_dir_all(&cache_a).unwrap();
        fs::create_dir_all(&cache_b).unwrap();

        let error = match p3_state(
            &[
                P3Preopen {
                    host_root: cache_a,
                    guest_root: "/suvio/cache".to_string(),
                    writable: true,
                },
                P3Preopen {
                    host_root: cache_b,
                    guest_root: "/suvio/cache/".to_string(),
                    writable: true,
                },
            ],
            &[],
            &[],
            HttpPolicy::default(),
            P3Limits::default(),
        ) {
            Ok(_) => panic!("duplicate guest preopen root should be rejected"),
            Err(error) => error,
        };

        assert!(
            error.contains("guest preopen root must be unique"),
            "{error}"
        );
        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_bridge_rejects_zero_component_memory_limit() {
        let sandbox = temp_sandbox("p3-bridge-zero-memory");
        let host_root = CString::new(sandbox.to_string_lossy().as_bytes()).unwrap();
        let guest_root = CString::new("/").unwrap();
        let bytes = [0u8; 1];
        let host_roots = [host_root.as_ptr()];
        let guest_roots = [guest_root.as_ptr()];
        let writable = [1u8];

        let error = krwa_wasmtime_p3_precompiled_component_instantiate_unavailable_reason(
            bytes.as_ptr(),
            bytes.len(),
            host_roots.as_ptr(),
            guest_roots.as_ptr(),
            writable.as_ptr(),
            host_roots.len(),
            ptr::null(),
            0,
            ptr::null(),
            ptr::null(),
            0,
            ptr::null(),
            0,
            ptr::null(),
            0,
            0,
            0,
            DEFAULT_MAX_WASM_STACK_BYTES,
            UNLIMITED_RESOURCE_LIMIT,
            UNLIMITED_RESOURCE_LIMIT,
            UNLIMITED_RESOURCE_LIMIT,
            UNLIMITED_RESOURCE_LIMIT,
        );

        assert!(!error.is_null());
        let message = unsafe { CStr::from_ptr(error) }.to_str().unwrap();
        assert!(
            message.contains("max memory bytes must be positive"),
            "{message}"
        );

        fs::remove_dir_all(sandbox).unwrap();
    }

    #[test]
    fn p3_http_policy_denies_network_by_default() {
        let policy = HttpPolicy::default();
        let request = http_request("https://api.example.test/catalog");

        assert!(matches!(
            policy.validate_request(&request),
            Err(ErrorCode::HttpRequestDenied)
        ));
    }

    #[test]
    fn p3_http_policy_allows_declared_host_and_subdomains() {
        let policy = HttpPolicy {
            allowed_hosts: vec!["example.test".to_string()],
            ..HttpPolicy::default()
        };

        assert!(
            policy
                .validate_request(&http_request("https://example.test/catalog"))
                .is_ok()
        );
        assert!(
            policy
                .validate_request(&http_request("https://api.example.test/catalog"))
                .is_ok()
        );
    }

    #[test]
    fn p3_http_policy_denies_blocked_host_before_allowed_wildcard() {
        let policy = HttpPolicy {
            allowed_hosts: vec!["*".to_string()],
            blocked_hosts: vec!["blocked.example.test".to_string()],
            allow_private_network: true,
        };

        assert!(matches!(
            policy.validate_request(&http_request("https://blocked.example.test/catalog")),
            Err(ErrorCode::HttpRequestDenied)
        ));
    }

    #[test]
    fn p3_http_policy_denies_private_network_without_permission() {
        let policy = HttpPolicy {
            allowed_hosts: vec!["*".to_string()],
            ..HttpPolicy::default()
        };

        assert!(matches!(
            policy.validate_request(&http_request("http://127.0.0.1/catalog")),
            Err(ErrorCode::HttpRequestDenied)
        ));
    }

    fn http_request(uri: &str) -> http::Request<()> {
        http::Request::builder()
            .method("GET")
            .uri(uri)
            .body(())
            .unwrap()
    }

    fn temp_sandbox(prefix: &str) -> std::path::PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!("{prefix}-{}-{nanos}", std::process::id()));
        fs::create_dir_all(&path).unwrap();
        path
    }
}
