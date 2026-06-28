#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef int32_t (*krwa_pulley_host_callback_t)(
    int64_t callback_id,
    const int64_t *args,
    size_t arg_count,
    int64_t *results,
    size_t result_count
);

const char *krwa_pulley_last_error(void);
const char *krwa_pulley_unavailable_reason(void);
const char *krwa_wasmtime_component_wasi_unavailable_reason(void);

void *krwa_wasmtime_p3_execution_cancellation_create(void);
void krwa_wasmtime_p3_execution_cancellation_cancel(void *handle);
uint8_t krwa_wasmtime_p3_execution_cancellation_is_cancelled(const void *handle);
void krwa_wasmtime_p3_execution_cancellation_free(void *handle);

const char *krwa_wasmtime_p3_precompiled_component_instantiate_unavailable_reason(
    const uint8_t *component_bytes,
    size_t component_len,
    const char * const *host_roots,
    const char * const *guest_roots,
    const uint8_t *writable_preopens,
    size_t preopen_count,
    const char * const *arguments,
    size_t argument_count,
    const char * const *environment_keys,
    const char * const *environment_values,
    size_t environment_count,
    const char * const *allowed_hosts,
    size_t allowed_host_count,
    const char * const *blocked_hosts,
    size_t blocked_host_count,
    uint8_t allow_private_network,
    uint64_t max_memory_bytes,
    uint64_t max_wasm_stack_bytes,
    int64_t max_table_elements,
    int64_t max_instances,
    int64_t max_tables,
    int64_t max_memories
);

const char *krwa_wasmtime_p3_precompiled_component_call0_unavailable_reason(
    const uint8_t *component_bytes,
    size_t component_len,
    const char * const *host_roots,
    const char * const *guest_roots,
    const uint8_t *writable_preopens,
    size_t preopen_count,
    const char * const *arguments,
    size_t argument_count,
    const char * const *environment_keys,
    const char * const *environment_values,
    size_t environment_count,
    const char *export_name,
    const char * const *allowed_hosts,
    size_t allowed_host_count,
    const char * const *blocked_hosts,
    size_t blocked_host_count,
    uint8_t allow_private_network,
    uint64_t max_memory_bytes,
    uint64_t max_wasm_stack_bytes,
    int64_t max_table_elements,
    int64_t max_instances,
    int64_t max_tables,
    int64_t max_memories
);

const char *krwa_wasmtime_p3_precompiled_component_call_s32_unavailable_reason(
    const uint8_t *component_bytes,
    size_t component_len,
    const char * const *host_roots,
    const char * const *guest_roots,
    const uint8_t *writable_preopens,
    size_t preopen_count,
    const char * const *arguments,
    size_t argument_count,
    const char * const *environment_keys,
    const char * const *environment_values,
    size_t environment_count,
    const char *export_name,
    int32_t argument,
    int32_t expected_result,
    const char * const *allowed_hosts,
    size_t allowed_host_count,
    const char * const *blocked_hosts,
    size_t blocked_host_count,
    uint8_t allow_private_network,
    uint64_t max_memory_bytes,
    uint64_t max_wasm_stack_bytes,
    int64_t max_table_elements,
    int64_t max_instances,
    int64_t max_tables,
    int64_t max_memories
);

const char *krwa_wasmtime_p3_precompiled_component_call_string_unavailable_reason(
    const uint8_t *component_bytes,
    size_t component_len,
    const char * const *host_roots,
    const char * const *guest_roots,
    const uint8_t *writable_preopens,
    size_t preopen_count,
    const char * const *arguments,
    size_t argument_count,
    const char * const *environment_keys,
    const char * const *environment_values,
    size_t environment_count,
    const char *export_name,
    const char *argument,
    const char *expected_result,
    const char * const *allowed_hosts,
    size_t allowed_host_count,
    const char * const *blocked_hosts,
    size_t blocked_host_count,
    uint8_t allow_private_network,
    uint64_t max_memory_bytes,
    uint64_t max_wasm_stack_bytes,
    int64_t max_table_elements,
    int64_t max_instances,
    int64_t max_tables,
    int64_t max_memories
);

const char *krwa_wasmtime_p3_precompiled_component_call_string(
    const uint8_t *component_bytes,
    size_t component_len,
    const char * const *host_roots,
    const char * const *guest_roots,
    const uint8_t *writable_preopens,
    size_t preopen_count,
    const char * const *arguments,
    size_t argument_count,
    const char * const *environment_keys,
    const char * const *environment_values,
    size_t environment_count,
    const char *export_name,
    const char *argument,
    const char * const *allowed_hosts,
    size_t allowed_host_count,
    const char * const *blocked_hosts,
    size_t blocked_host_count,
    uint8_t allow_private_network,
    uint64_t max_memory_bytes,
    uint64_t max_wasm_stack_bytes,
    int64_t max_table_elements,
    int64_t max_instances,
    int64_t max_tables,
    int64_t max_memories,
    uint64_t execution_timeout_millis,
    const void *execution_cancellation,
    uintptr_t *result_out
);

const char *krwa_wasmtime_p3_precompiled_command_run_unavailable_reason(
    const uint8_t *component_bytes,
    size_t component_len,
    const char * const *host_roots,
    const char * const *guest_roots,
    const uint8_t *writable_preopens,
    size_t preopen_count,
    const char * const *arguments,
    size_t argument_count,
    const char * const *environment_keys,
    const char * const *environment_values,
    size_t environment_count,
    const char * const *allowed_hosts,
    size_t allowed_host_count,
    const char * const *blocked_hosts,
    size_t blocked_host_count,
    uint8_t allow_private_network,
    uint64_t max_memory_bytes,
    uint64_t max_wasm_stack_bytes,
    int64_t max_table_elements,
    int64_t max_instances,
    int64_t max_tables,
    int64_t max_memories,
    uint64_t execution_timeout_millis
);

int64_t krwa_pulley_create(
    const uint8_t *module_bytes,
    size_t module_size,
    int32_t precompiled_module,
    int64_t max_memory_bytes,
    int64_t max_wasm_stack_bytes,
    int64_t max_table_elements,
    int64_t max_instances,
    int64_t max_tables,
    int64_t max_memories,
    const int64_t *callback_ids,
    size_t import_count,
    const int32_t *param_offsets,
    const int32_t *param_opcodes,
    const int32_t *return_offsets,
    const int32_t *return_opcodes,
    krwa_pulley_host_callback_t host_callback
);
void krwa_pulley_destroy(int64_t native_handle);

int64_t krwa_pulley_bind_function(int64_t native_handle, const char *name, size_t name_size);
int64_t krwa_pulley_bind_memory(int64_t native_handle, const char *name, size_t name_size);

int32_t krwa_pulley_call(
    int64_t native_handle,
    int64_t native_function,
    const int32_t *param_opcodes,
    size_t param_count,
    const int32_t *return_opcodes,
    size_t return_count,
    const int64_t *args,
    size_t arg_count,
    int64_t *results,
    size_t result_count
);

uint64_t krwa_pulley_memory_byte_size(int64_t native_handle, int64_t native_memory);
int32_t krwa_pulley_memory_grow(
    int64_t native_handle,
    int64_t native_memory,
    int32_t delta_pages,
    int32_t *previous_pages
);
int32_t krwa_pulley_memory_read(
    int64_t native_handle,
    int64_t native_memory,
    int32_t addr,
    uint8_t *target,
    size_t size
);
int32_t krwa_pulley_memory_write(
    int64_t native_handle,
    int64_t native_memory,
    int32_t addr,
    const uint8_t *source,
    size_t size
);
int32_t krwa_pulley_memory_fill(
    int64_t native_handle,
    int64_t native_memory,
    uint8_t value,
    int32_t from_index,
    int32_t to_index
);
int32_t krwa_pulley_memory_read_u8(int64_t native_handle, int64_t native_memory, int32_t addr, uint8_t *result);
int32_t krwa_pulley_memory_write_u8(int64_t native_handle, int64_t native_memory, int32_t addr, uint8_t value);
int32_t krwa_pulley_memory_read_i16(int64_t native_handle, int64_t native_memory, int32_t addr, int16_t *result);
int32_t krwa_pulley_memory_write_i16(int64_t native_handle, int64_t native_memory, int32_t addr, int16_t value);
int32_t krwa_pulley_memory_read_i32(int64_t native_handle, int64_t native_memory, int32_t addr, int32_t *result);
int32_t krwa_pulley_memory_write_i32(int64_t native_handle, int64_t native_memory, int32_t addr, int32_t value);
int32_t krwa_pulley_memory_read_i64(int64_t native_handle, int64_t native_memory, int32_t addr, int64_t *result);
int32_t krwa_pulley_memory_write_i64(int64_t native_handle, int64_t native_memory, int32_t addr, int64_t value);
int32_t krwa_pulley_memory_read_f32(int64_t native_handle, int64_t native_memory, int32_t addr, float *result);
int32_t krwa_pulley_memory_write_f32(int64_t native_handle, int64_t native_memory, int32_t addr, float value);
int32_t krwa_pulley_memory_read_f64(int64_t native_handle, int64_t native_memory, int32_t addr, double *result);
int32_t krwa_pulley_memory_write_f64(int64_t native_handle, int64_t native_memory, int32_t addr, double value);

#ifdef __cplusplus
}
#endif
