#include "../cinterop/wasmtime_pulley.h"

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

struct wasm_config_t;
struct wasm_engine_t;
struct wasm_functype_t;
struct wasm_trap_t;
struct wasm_valtype_t;
struct wasmtime_caller_t;
struct wasmtime_context_t;
struct wasmtime_error_t;
struct wasmtime_module_t;
struct wasmtime_store_t;
struct wasi_config_t;
struct wasmtime_component_t;
struct wasmtime_component_instance_t;
struct wasmtime_component_linker_t;

struct WasmByteVec {
    std::size_t size;
    char *data;
};

struct WasmValTypeVec {
    std::size_t size;
    wasm_valtype_t **data;
};

struct WasmtimeFunc {
    std::uint64_t storeId;
    void *privateData;
};

struct WasmtimeMemory {
    std::uint64_t storeId;
    std::uint32_t private1;
    std::uint32_t padding1;
    std::uint32_t private2;
    std::uint32_t padding2;
};

struct WasmtimeInstance {
    std::uint64_t storeId;
    std::size_t privateData;
};

union WasmtimeExternPayload {
    WasmtimeFunc func;
    WasmtimeMemory memory;
    std::uint8_t storage[24];
};

struct WasmtimeExtern {
    std::uint8_t kind;
    std::uint8_t padding[7];
    WasmtimeExternPayload of;
};

union alignas(8) WasmtimeValRaw {
    std::int32_t i32;
    std::int64_t i64;
    float f32;
    double f64;
    void *ref;
    std::uint8_t bytes[16];
};

static_assert(sizeof(WasmtimeFunc) == 16, "unexpected wasmtime_func_t size");
static_assert(sizeof(WasmtimeMemory) == 24, "unexpected wasmtime_memory_t size");
static_assert(sizeof(WasmtimeInstance) == 16, "unexpected wasmtime_instance_t size");
static_assert(sizeof(WasmtimeExtern) == 32, "unexpected wasmtime_extern_t size");
static_assert(sizeof(WasmtimeValRaw) == 16, "unexpected wasmtime_val_raw_t size");

constexpr std::uint8_t WASMTIME_EXTERN_FUNC = 0;
constexpr std::uint8_t WASMTIME_EXTERN_MEMORY = 3;
constexpr int VALTYPE_I32 = 0x7F;
constexpr int VALTYPE_I64 = 0x7E;
constexpr int VALTYPE_F32 = 0x7D;
constexpr int VALTYPE_F64 = 0x7C;

using HostCallback = wasm_trap_t *(*)(void *, wasmtime_caller_t *, WasmtimeValRaw *, std::size_t);

extern "C" {
wasm_config_t *wasm_config_new(void);
void wasm_config_delete(wasm_config_t *);
wasm_engine_t *wasm_engine_new_with_config(wasm_config_t *);
void wasm_engine_delete(wasm_engine_t *);
wasmtime_error_t *wasmtime_config_target_set(wasm_config_t *, const char *);
void wasmtime_config_wasm_gc_set(wasm_config_t *, bool);
void wasmtime_config_wasm_function_references_set(wasm_config_t *, bool);
void wasmtime_config_wasm_reference_types_set(wasm_config_t *, bool);
void wasmtime_config_wasm_exceptions_set(wasm_config_t *, bool);
void wasmtime_config_wasm_bulk_memory_set(wasm_config_t *, bool);
void wasmtime_config_wasm_multi_memory_set(wasm_config_t *, bool);
void wasmtime_config_max_wasm_stack_set(wasm_config_t *, std::size_t);
void wasmtime_config_memory_may_move_set(wasm_config_t *, bool);
void wasmtime_config_concurrency_support_set(wasm_config_t *, bool);
wasmtime_error_t *wasmtime_module_new(wasm_engine_t *, const std::uint8_t *, std::size_t, wasmtime_module_t **);
wasmtime_error_t *wasmtime_module_deserialize(wasm_engine_t *, const std::uint8_t *, std::size_t, wasmtime_module_t **);
void wasmtime_module_delete(wasmtime_module_t *);
wasmtime_store_t *wasmtime_store_new(wasm_engine_t *, void *, void (*)(void *));
wasmtime_context_t *wasmtime_store_context(wasmtime_store_t *);
void wasmtime_store_limiter(wasmtime_store_t *, std::int64_t, std::int64_t, std::int64_t, std::int64_t, std::int64_t);
void wasmtime_store_delete(wasmtime_store_t *);
wasmtime_error_t *wasmtime_instance_new(
    wasmtime_context_t *,
    const wasmtime_module_t *,
    const WasmtimeExtern *,
    std::size_t,
    WasmtimeInstance *,
    wasm_trap_t **
);
bool wasmtime_instance_export_get(
    wasmtime_context_t *,
    const WasmtimeInstance *,
    const char *,
    std::size_t,
    WasmtimeExtern *
);
wasmtime_error_t *wasmtime_func_call_unchecked(
    wasmtime_context_t *,
    const WasmtimeFunc *,
    WasmtimeValRaw *,
    std::size_t,
    wasm_trap_t **
);
void wasmtime_func_new_unchecked(
    wasmtime_context_t *,
    const wasm_functype_t *,
    HostCallback,
    void *,
    void (*)(void *),
    WasmtimeFunc *
);
wasm_valtype_t *wasm_valtype_new(std::uint8_t);
void wasm_valtype_vec_new_empty(WasmValTypeVec *);
void wasm_valtype_vec_new(WasmValTypeVec *, std::size_t, wasm_valtype_t *const[]);
wasm_functype_t *wasm_functype_new(WasmValTypeVec *, WasmValTypeVec *);
void wasm_functype_delete(wasm_functype_t *);
void wasmtime_error_message(wasmtime_error_t *, WasmByteVec *);
void wasmtime_error_delete(wasmtime_error_t *);
void wasm_trap_message(wasm_trap_t *, WasmByteVec *);
void wasm_trap_delete(wasm_trap_t *);
wasm_trap_t *wasmtime_trap_new(const char *, std::size_t);
void wasm_byte_vec_delete(WasmByteVec *);
std::uint8_t *wasmtime_memory_data(wasmtime_context_t *, const WasmtimeMemory *);
std::size_t wasmtime_memory_data_size(const wasmtime_context_t *, const WasmtimeMemory *);
wasmtime_error_t *wasmtime_memory_grow(wasmtime_context_t *, const WasmtimeMemory *, std::uint64_t, std::uint64_t *);

wasi_config_t *wasi_config_new(void) __attribute__((weak_import));
void wasi_config_delete(wasi_config_t *) __attribute__((weak_import));
bool wasi_config_preopen_dir(wasi_config_t *, const char *, const char *, std::size_t, std::size_t)
    __attribute__((weak_import));
wasmtime_error_t *wasmtime_context_set_wasi(wasmtime_context_t *, wasi_config_t *) __attribute__((weak_import));
wasmtime_error_t *wasmtime_context_set_wasi_http(wasmtime_context_t *) __attribute__((weak_import));
wasmtime_error_t *wasmtime_component_new(
    wasm_engine_t *,
    const std::uint8_t *,
    std::size_t,
    wasmtime_component_t **
) __attribute__((weak_import));
wasmtime_component_linker_t *wasmtime_component_linker_new(wasm_engine_t *) __attribute__((weak_import));
wasmtime_error_t *wasmtime_component_linker_add_wasip2(wasmtime_component_linker_t *) __attribute__((weak_import));
wasmtime_error_t *wasmtime_component_linker_add_wasi_http(wasmtime_component_linker_t *)
    __attribute__((weak_import));
wasmtime_error_t *wasmtime_component_linker_instantiate(
    const wasmtime_component_linker_t *,
    wasmtime_context_t *,
    const wasmtime_component_t *,
    wasmtime_component_instance_t *
) __attribute__((weak_import));
}

struct CallbackEnv {
    krwa_pulley_host_callback_t callback;
    std::int64_t callbackId;
    std::vector<int> paramOpcodes;
    std::vector<int> returnOpcodes;
};

struct NativeExecution {
    ~NativeExecution() {
        callbackEnvs.clear();
        if (store != nullptr) {
            wasmtime_store_delete(store);
        }
        if (module != nullptr) {
            wasmtime_module_delete(module);
        }
        if (engine != nullptr) {
            wasm_engine_delete(engine);
        }
    }

    wasm_engine_t *engine = nullptr;
    wasmtime_module_t *module = nullptr;
    wasmtime_store_t *store = nullptr;
    wasmtime_context_t *context = nullptr;
    WasmtimeInstance instance{};
    std::vector<std::unique_ptr<CallbackEnv>> callbackEnvs;
    std::recursive_mutex mutex;
};

struct NativeFunction {
    NativeExecution *execution;
    WasmtimeFunc func;
};

struct NativeMemoryHandle {
    NativeExecution *execution;
    WasmtimeMemory memory;
};

thread_local std::string gLastError;

const char *setError(std::string message) {
    gLastError = std::move(message);
    return gLastError.c_str();
}

NativeExecution *executionFrom(std::int64_t handle) {
    return reinterpret_cast<NativeExecution *>(handle);
}

NativeFunction *functionFrom(std::int64_t handle) {
    return reinterpret_cast<NativeFunction *>(handle);
}

NativeMemoryHandle *memoryFrom(std::int64_t handle) {
    return reinterpret_cast<NativeMemoryHandle *>(handle);
}

std::string readMessage(void *handle, bool trap) {
    WasmByteVec message{};
    if (trap) {
        wasm_trap_message(reinterpret_cast<wasm_trap_t *>(handle), &message);
    } else {
        wasmtime_error_message(reinterpret_cast<wasmtime_error_t *>(handle), &message);
    }
    std::string result(message.data, message.size);
    wasm_byte_vec_delete(&message);
    return result;
}

std::string consumeError(wasmtime_error_t *error) {
    if (error == nullptr) {
        return {};
    }
    std::string result = readMessage(error, false);
    wasmtime_error_delete(error);
    return result;
}

std::string consumeTrap(wasm_trap_t *trap) {
    if (trap == nullptr) {
        return {};
    }
    std::string result = readMessage(trap, true);
    wasm_trap_delete(trap);
    return result;
}

wasm_trap_t *trapFromMessage(const std::string &message) {
    return wasmtime_trap_new(message.c_str(), message.size());
}

std::string configurePulley(wasm_config_t *config, std::int64_t maxWasmStackBytes) {
    wasmtime_error_t *targetError = wasmtime_config_target_set(config, "pulley64");
    if (targetError != nullptr) {
        return "wasmtime_config_target_set(pulley64) failed: " + consumeError(targetError);
    }
    if (maxWasmStackBytes <= 0) {
        return "Wasmtime max Wasm stack bytes must be positive";
    }
    wasmtime_config_max_wasm_stack_set(config, static_cast<std::size_t>(maxWasmStackBytes));
    wasmtime_config_wasm_gc_set(config, true);
    wasmtime_config_wasm_function_references_set(config, true);
    wasmtime_config_wasm_reference_types_set(config, true);
    wasmtime_config_wasm_exceptions_set(config, true);
    wasmtime_config_wasm_bulk_memory_set(config, true);
    wasmtime_config_wasm_multi_memory_set(config, true);
    wasmtime_config_memory_may_move_set(config, true);
    wasmtime_config_concurrency_support_set(config, false);
    return {};
}

std::vector<int> opcodeSlice(const std::int32_t *opcodes, std::int32_t start, std::int32_t end) {
    if (opcodes == nullptr || start < 0 || end < start) {
        return {};
    }
    return std::vector<int>(opcodes + start, opcodes + end);
}

std::uint8_t wasmKindForOpcode(int opcode) {
    switch (opcode) {
        case VALTYPE_I32:
            return 0;
        case VALTYPE_I64:
            return 1;
        case VALTYPE_F32:
            return 2;
        case VALTYPE_F64:
            return 3;
        default:
            return 255;
    }
}

wasm_functype_t *createFunctionType(
    const std::vector<int> &params,
    const std::vector<int> &returns,
    std::string *error
) {
    auto makeVec = [&](const std::vector<int> &opcodes, WasmValTypeVec *out) -> bool {
        if (opcodes.empty()) {
            wasm_valtype_vec_new_empty(out);
            return true;
        }
        std::vector<wasm_valtype_t *> values;
        values.reserve(opcodes.size());
        for (int opcode : opcodes) {
            std::uint8_t kind = wasmKindForOpcode(opcode);
            if (kind == 255) {
                *error = "Wasmtime Pulley bridge supports numeric boundary values only";
                return false;
            }
            values.push_back(wasm_valtype_new(kind));
        }
        wasm_valtype_vec_new(out, values.size(), values.data());
        return true;
    };

    WasmValTypeVec paramVec{};
    WasmValTypeVec returnVec{};
    if (!makeVec(params, &paramVec) || !makeVec(returns, &returnVec)) {
        return nullptr;
    }
    return wasm_functype_new(&paramVec, &returnVec);
}

void writeRawValues(WasmtimeValRaw *raw, const std::vector<int> &opcodes, const std::int64_t *values) {
    for (std::size_t i = 0; i < opcodes.size(); i++) {
        switch (opcodes[i]) {
            case VALTYPE_I32:
            case VALTYPE_F32:
                raw[i].i32 = static_cast<std::int32_t>(values[i]);
                break;
            case VALTYPE_I64:
            case VALTYPE_F64:
                raw[i].i64 = static_cast<std::int64_t>(values[i]);
                break;
            default:
                break;
        }
    }
}

void readRawValues(const WasmtimeValRaw *raw, const std::vector<int> &opcodes, std::int64_t *values) {
    for (std::size_t i = 0; i < opcodes.size(); i++) {
        switch (opcodes[i]) {
            case VALTYPE_I32:
            case VALTYPE_F32:
                values[i] = raw[i].i32;
                break;
            case VALTYPE_I64:
            case VALTYPE_F64:
                values[i] = raw[i].i64;
                break;
            default:
                values[i] = 0;
                break;
        }
    }
}

wasm_trap_t *hostFunctionCallback(
    void *rawEnv,
    wasmtime_caller_t *,
    WasmtimeValRaw *argsAndResults,
    std::size_t numArgsAndResults
) {
    auto *callback = reinterpret_cast<CallbackEnv *>(rawEnv);
    if (callback == nullptr) {
        return nullptr;
    }
    if (numArgsAndResults < std::max(callback->paramOpcodes.size(), callback->returnOpcodes.size())) {
        return trapFromMessage("host callback raw value buffer is too small");
    }

    std::vector<std::int64_t> args(callback->paramOpcodes.size());
    std::vector<std::int64_t> results(callback->returnOpcodes.size());
    readRawValues(argsAndResults, callback->paramOpcodes, args.data());
    int status =
        callback->callback(
            callback->callbackId,
            args.empty() ? nullptr : args.data(),
            args.size(),
            results.empty() ? nullptr : results.data(),
            results.size()
        );
    if (status != 0) {
        return trapFromMessage("host callback failed");
    }
    writeRawValues(argsAndResults, callback->returnOpcodes, results.data());
    return nullptr;
}

std::uint8_t *checkedMemorySlice(
    NativeExecution *execution,
    NativeMemoryHandle *memory,
    std::int32_t addr,
    std::size_t size
) {
    if (execution == nullptr || memory == nullptr) {
        setError("invalid Wasmtime memory handle");
        return nullptr;
    }
    if (addr < 0) {
        setError("out of bounds memory access");
        return nullptr;
    }
    std::uint8_t *data = wasmtime_memory_data(execution->context, &memory->memory);
    std::size_t byteSize = wasmtime_memory_data_size(execution->context, &memory->memory);
    std::uint64_t end = static_cast<std::uint64_t>(addr) + static_cast<std::uint64_t>(size);
    if (end > byteSize) {
        setError("out of bounds memory access");
        return nullptr;
    }
    return data + addr;
}

template <typename T>
int readPrimitive(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, T *result) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint8_t *source = checkedMemorySlice(execution, memory, addr, sizeof(T));
    if (source == nullptr) {
        return -1;
    }
    std::memcpy(result, source, sizeof(T));
    return 0;
}

template <typename T>
int writePrimitive(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, T value) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint8_t *target = checkedMemorySlice(execution, memory, addr, sizeof(T));
    if (target == nullptr) {
        return -1;
    }
    std::memcpy(target, &value, sizeof(T));
    return 0;
}

} // namespace

extern "C" const char *krwa_pulley_last_error(void) {
    return gLastError.empty() ? nullptr : gLastError.c_str();
}

extern "C" const char *krwa_pulley_unavailable_reason(void) {
    gLastError.clear();
    wasm_config_t *config = wasm_config_new();
    if (config == nullptr) {
        return setError("wasm_config_new returned null");
    }
    std::unique_ptr<wasm_config_t, decltype(&wasm_config_delete)> configGuard(config, wasm_config_delete);
    std::string configError = configurePulley(config, 512L * 1024L);
    if (!configError.empty()) {
        return setError(configError);
    }
    wasm_engine_t *engine = wasm_engine_new_with_config(configGuard.release());
    if (engine == nullptr) {
        return setError("wasm_engine_new_with_config returned null");
    }
    wasm_engine_delete(engine);
    return nullptr;
}

extern "C" const char *krwa_wasmtime_component_wasi_unavailable_reason(void) {
    gLastError.clear();
    struct RequiredSymbol {
        const char *name;
        const void *address;
    };
    const RequiredSymbol requiredSymbols[] = {
        {"wasi_config_new", reinterpret_cast<const void *>(wasi_config_new)},
        {"wasi_config_delete", reinterpret_cast<const void *>(wasi_config_delete)},
        {"wasi_config_preopen_dir", reinterpret_cast<const void *>(wasi_config_preopen_dir)},
        {"wasmtime_context_set_wasi", reinterpret_cast<const void *>(wasmtime_context_set_wasi)},
        {"wasmtime_context_set_wasi_http", reinterpret_cast<const void *>(wasmtime_context_set_wasi_http)},
        {"wasmtime_component_new", reinterpret_cast<const void *>(wasmtime_component_new)},
        {"wasmtime_component_linker_new", reinterpret_cast<const void *>(wasmtime_component_linker_new)},
        {"wasmtime_component_linker_add_wasip2", reinterpret_cast<const void *>(wasmtime_component_linker_add_wasip2)},
        {"wasmtime_component_linker_add_wasi_http", reinterpret_cast<const void *>(wasmtime_component_linker_add_wasi_http)},
        {"wasmtime_component_linker_instantiate", reinterpret_cast<const void *>(wasmtime_component_linker_instantiate)},
    };
    for (const RequiredSymbol &symbol : requiredSymbols) {
        if (symbol.address == nullptr) {
            return setError(
                std::string("Wasmtime C API component/WASIp2 primitives are not linked: missing symbol ") +
                    symbol.name
            );
        }
    }
    return nullptr;
}

extern "C" std::int64_t krwa_pulley_create(
    const std::uint8_t *moduleBytes,
    std::size_t moduleSize,
    std::int32_t precompiledModule,
    std::int64_t maxMemoryBytes,
    std::int64_t maxWasmStackBytes,
    std::int64_t maxTableElements,
    std::int64_t maxInstances,
    std::int64_t maxTables,
    std::int64_t maxMemories,
    const std::int64_t *callbackIds,
    std::size_t importCount,
    const std::int32_t *paramOffsets,
    const std::int32_t *paramOpcodes,
    const std::int32_t *returnOffsets,
    const std::int32_t *returnOpcodes,
    krwa_pulley_host_callback_t hostCallback
) {
    gLastError.clear();
    if (moduleBytes == nullptr || hostCallback == nullptr) {
        setError("missing Wasmtime Pulley create input");
        return 0;
    }
    if (maxMemoryBytes <= 0) {
        setError("Wasmtime max memory bytes must be positive");
        return 0;
    }
    if (maxWasmStackBytes <= 0) {
        setError("Wasmtime max Wasm stack bytes must be positive");
        return 0;
    }
    if (maxTableElements < -1 || maxInstances < -1 || maxTables < -1 || maxMemories < -1) {
        setError("Wasmtime resource limits must be -1 for unlimited or non-negative");
        return 0;
    }

    std::unique_ptr<NativeExecution> execution = std::make_unique<NativeExecution>();

    wasm_config_t *config = wasm_config_new();
    if (config == nullptr) {
        setError("wasm_config_new returned null");
        return 0;
    }
    std::unique_ptr<wasm_config_t, decltype(&wasm_config_delete)> configGuard(config, wasm_config_delete);
    std::string configError = configurePulley(config, maxWasmStackBytes);
    if (!configError.empty()) {
        setError(configError);
        return 0;
    }
    execution->engine = wasm_engine_new_with_config(configGuard.release());
    if (execution->engine == nullptr) {
        setError("wasm_engine_new_with_config returned null");
        return 0;
    }

    wasmtime_error_t *moduleError =
        precompiledModule != 0
            ? wasmtime_module_deserialize(execution->engine, moduleBytes, moduleSize, &execution->module)
            : wasmtime_module_new(execution->engine, moduleBytes, moduleSize, &execution->module);
    if (moduleError != nullptr) {
        setError(
            std::string(precompiledModule != 0 ? "deserialize precompiled Pulley module: " : "compile module for Pulley: ") +
            consumeError(moduleError)
        );
        return 0;
    }

    execution->store = wasmtime_store_new(execution->engine, nullptr, nullptr);
    if (execution->store == nullptr) {
        setError("wasmtime_store_new returned null");
        return 0;
    }
    wasmtime_store_limiter(
        execution->store,
        maxMemoryBytes,
        maxTableElements,
        maxInstances,
        maxTables,
        maxMemories);
    execution->context = wasmtime_store_context(execution->store);

    std::vector<WasmtimeExtern> imports(importCount);
    for (std::size_t i = 0; i < importCount; i++) {
        if (paramOffsets == nullptr || returnOffsets == nullptr || callbackIds == nullptr) {
            setError("missing import callback metadata");
            return 0;
        }
        std::vector<int> params = opcodeSlice(paramOpcodes, paramOffsets[i], paramOffsets[i + 1]);
        std::vector<int> returns = opcodeSlice(returnOpcodes, returnOffsets[i], returnOffsets[i + 1]);

        std::string typeError;
        wasm_functype_t *type = createFunctionType(params, returns, &typeError);
        if (type == nullptr) {
            setError(typeError.empty() ? "failed to create Wasmtime function type" : typeError);
            return 0;
        }

        auto callback = std::make_unique<CallbackEnv>();
        callback->callback = hostCallback;
        callback->callbackId = callbackIds[i];
        callback->paramOpcodes = std::move(params);
        callback->returnOpcodes = std::move(returns);

        WasmtimeFunc func{};
        wasmtime_func_new_unchecked(execution->context, type, hostFunctionCallback, callback.get(), nullptr, &func);
        wasm_functype_delete(type);
        execution->callbackEnvs.push_back(std::move(callback));

        imports[i].kind = WASMTIME_EXTERN_FUNC;
        imports[i].of.func = func;
    }

    wasm_trap_t *trap = nullptr;
    wasmtime_error_t *instanceError =
        wasmtime_instance_new(
            execution->context,
            execution->module,
            imports.empty() ? nullptr : imports.data(),
            imports.size(),
            &execution->instance,
            &trap
        );
    if (instanceError != nullptr) {
        setError("instantiate Pulley module: " + consumeError(instanceError));
        return 0;
    }
    if (trap != nullptr) {
        setError("instantiate Pulley module: " + consumeTrap(trap));
        return 0;
    }

    return reinterpret_cast<std::int64_t>(execution.release());
}

extern "C" void krwa_pulley_destroy(std::int64_t nativeHandle) {
    delete executionFrom(nativeHandle);
}

extern "C" std::int64_t krwa_pulley_bind_function(std::int64_t nativeHandle, const char *name, std::size_t nameSize) {
    gLastError.clear();
    auto *execution = executionFrom(nativeHandle);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    WasmtimeExtern item{};
    bool found = wasmtime_instance_export_get(execution->context, &execution->instance, name, nameSize, &item);
    if (!found || item.kind != WASMTIME_EXTERN_FUNC) {
        setError("Unknown function export");
        return 0;
    }
    auto *function = new NativeFunction{execution, item.of.func};
    return reinterpret_cast<std::int64_t>(function);
}

extern "C" std::int64_t krwa_pulley_bind_memory(std::int64_t nativeHandle, const char *name, std::size_t nameSize) {
    gLastError.clear();
    auto *execution = executionFrom(nativeHandle);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    WasmtimeExtern item{};
    bool found = wasmtime_instance_export_get(execution->context, &execution->instance, name, nameSize, &item);
    if (!found || item.kind != WASMTIME_EXTERN_MEMORY) {
        return 0;
    }
    auto *memory = new NativeMemoryHandle{execution, item.of.memory};
    return reinterpret_cast<std::int64_t>(memory);
}

extern "C" std::int32_t krwa_pulley_call(
    std::int64_t nativeHandle,
    std::int64_t nativeFunction,
    const std::int32_t *paramOpcodes,
    std::size_t paramCount,
    const std::int32_t *returnOpcodes,
    std::size_t returnCount,
    const std::int64_t *args,
    std::size_t argCount,
    std::int64_t *results,
    std::size_t resultCount
) {
    gLastError.clear();
    auto *execution = executionFrom(nativeHandle);
    auto *function = functionFrom(nativeFunction);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    if (argCount != paramCount || resultCount != returnCount) {
        setError("wrong number of arguments");
        return -1;
    }

    std::vector<int> params(paramOpcodes, paramOpcodes + paramCount);
    std::vector<int> returns(returnOpcodes, returnOpcodes + returnCount);
    std::size_t slotCount = std::max<std::size_t>(1, std::max(params.size(), returns.size()));
    std::vector<WasmtimeValRaw> raw(slotCount);
    if (paramCount > 0) {
        writeRawValues(raw.data(), params, args);
    }

    wasm_trap_t *trap = nullptr;
    wasmtime_error_t *callError =
        wasmtime_func_call_unchecked(execution->context, &function->func, raw.data(), slotCount, &trap);
    if (callError != nullptr) {
        setError("call Pulley export: " + consumeError(callError));
        return -1;
    }
    if (trap != nullptr) {
        setError("call Pulley export: " + consumeTrap(trap));
        return -1;
    }
    if (returnCount > 0) {
        readRawValues(raw.data(), returns, results);
    }
    return 0;
}

extern "C" std::uint64_t krwa_pulley_memory_byte_size(std::int64_t nativeHandle, std::int64_t nativeMemory) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    return static_cast<std::uint64_t>(wasmtime_memory_data_size(execution->context, &memory->memory));
}

extern "C" std::int32_t krwa_pulley_memory_grow(
    std::int64_t nativeHandle,
    std::int64_t nativeMemory,
    std::int32_t deltaPages,
    std::int32_t *previousPages
) {
    gLastError.clear();
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint64_t previous = 0;
    wasmtime_error_t *error = wasmtime_memory_grow(execution->context, &memory->memory, deltaPages, &previous);
    if (error != nullptr) {
        wasmtime_error_delete(error);
        return -1;
    }
    if (previousPages != nullptr) {
        *previousPages = static_cast<std::int32_t>(previous);
    }
    return 0;
}

extern "C" std::int32_t krwa_pulley_memory_read(
    std::int64_t nativeHandle,
    std::int64_t nativeMemory,
    std::int32_t addr,
    std::uint8_t *target,
    std::size_t size
) {
    gLastError.clear();
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint8_t *source = checkedMemorySlice(execution, memory, addr, size);
    if (source == nullptr) {
        return -1;
    }
    std::memcpy(target, source, size);
    return 0;
}

extern "C" std::int32_t krwa_pulley_memory_write(
    std::int64_t nativeHandle,
    std::int64_t nativeMemory,
    std::int32_t addr,
    const std::uint8_t *source,
    std::size_t size
) {
    gLastError.clear();
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint8_t *target = checkedMemorySlice(execution, memory, addr, size);
    if (target == nullptr) {
        return -1;
    }
    std::memcpy(target, source, size);
    return 0;
}

extern "C" std::int32_t krwa_pulley_memory_fill(
    std::int64_t nativeHandle,
    std::int64_t nativeMemory,
    std::uint8_t value,
    std::int32_t fromIndex,
    std::int32_t toIndex
) {
    gLastError.clear();
    if (toIndex < fromIndex) {
        setError("out of bounds memory access");
        return -1;
    }
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint8_t *target = checkedMemorySlice(execution, memory, fromIndex, static_cast<std::size_t>(toIndex - fromIndex));
    if (target == nullptr) {
        return -1;
    }
    std::memset(target, value, static_cast<std::size_t>(toIndex - fromIndex));
    return 0;
}

extern "C" std::int32_t krwa_pulley_memory_read_u8(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, std::uint8_t *result) {
    return readPrimitive(nativeHandle, nativeMemory, addr, result);
}

extern "C" std::int32_t krwa_pulley_memory_write_u8(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, std::uint8_t value) {
    return writePrimitive(nativeHandle, nativeMemory, addr, value);
}

extern "C" std::int32_t krwa_pulley_memory_read_i16(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, std::int16_t *result) {
    return readPrimitive(nativeHandle, nativeMemory, addr, result);
}

extern "C" std::int32_t krwa_pulley_memory_write_i16(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, std::int16_t value) {
    return writePrimitive(nativeHandle, nativeMemory, addr, value);
}

extern "C" std::int32_t krwa_pulley_memory_read_i32(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, std::int32_t *result) {
    return readPrimitive(nativeHandle, nativeMemory, addr, result);
}

extern "C" std::int32_t krwa_pulley_memory_write_i32(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, std::int32_t value) {
    return writePrimitive(nativeHandle, nativeMemory, addr, value);
}

extern "C" std::int32_t krwa_pulley_memory_read_i64(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, std::int64_t *result) {
    return readPrimitive(nativeHandle, nativeMemory, addr, result);
}

extern "C" std::int32_t krwa_pulley_memory_write_i64(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, std::int64_t value) {
    return writePrimitive(nativeHandle, nativeMemory, addr, value);
}

extern "C" std::int32_t krwa_pulley_memory_read_f32(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, float *result) {
    return readPrimitive(nativeHandle, nativeMemory, addr, result);
}

extern "C" std::int32_t krwa_pulley_memory_write_f32(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, float value) {
    return writePrimitive(nativeHandle, nativeMemory, addr, value);
}

extern "C" std::int32_t krwa_pulley_memory_read_f64(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, double *result) {
    return readPrimitive(nativeHandle, nativeMemory, addr, result);
}

extern "C" std::int32_t krwa_pulley_memory_write_f64(std::int64_t nativeHandle, std::int64_t nativeMemory, std::int32_t addr, double value) {
    return writePrimitive(nativeHandle, nativeMemory, addr, value);
}
