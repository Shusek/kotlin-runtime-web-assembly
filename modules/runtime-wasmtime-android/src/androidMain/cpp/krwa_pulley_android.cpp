#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
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

struct WasmtimeApi {
    void *library = nullptr;
    wasm_config_t *(*wasmConfigNew)() = nullptr;
    wasm_engine_t *(*wasmEngineNewWithConfig)(wasm_config_t *) = nullptr;
    void (*wasmEngineDelete)(wasm_engine_t *) = nullptr;
    wasmtime_error_t *(*configTargetSet)(wasm_config_t *, const char *) = nullptr;
    void (*configWasmGcSet)(wasm_config_t *, bool) = nullptr;
    void (*configWasmFunctionReferencesSet)(wasm_config_t *, bool) = nullptr;
    void (*configWasmReferenceTypesSet)(wasm_config_t *, bool) = nullptr;
    void (*configWasmExceptionsSet)(wasm_config_t *, bool) = nullptr;
    void (*configWasmBulkMemorySet)(wasm_config_t *, bool) = nullptr;
    void (*configWasmMultiMemorySet)(wasm_config_t *, bool) = nullptr;
    void (*configMaxWasmStackSet)(wasm_config_t *, std::size_t) = nullptr;
    void (*configMemoryMayMoveSet)(wasm_config_t *, bool) = nullptr;
    void (*configConcurrencySupportSet)(wasm_config_t *, bool) = nullptr;
    void (*configConsumeFuelSet)(wasm_config_t *, bool) = nullptr;
    wasmtime_error_t *(*moduleNew)(wasm_engine_t *, const std::uint8_t *, std::size_t, wasmtime_module_t **) = nullptr;
    wasmtime_error_t *(*moduleDeserialize)(wasm_engine_t *, const std::uint8_t *, std::size_t, wasmtime_module_t **) = nullptr;
    wasmtime_error_t *(*moduleSerialize)(wasmtime_module_t *, WasmByteVec *) = nullptr;
    void (*moduleDelete)(wasmtime_module_t *) = nullptr;
    wasmtime_store_t *(*storeNew)(wasm_engine_t *, void *, void (*)(void *)) = nullptr;
    wasmtime_context_t *(*storeContext)(wasmtime_store_t *) = nullptr;
    void (*storeLimiter)(wasmtime_store_t *, std::int64_t, std::int64_t, std::int64_t, std::int64_t, std::int64_t) = nullptr;
    wasmtime_error_t *(*contextSetFuel)(wasmtime_context_t *, std::uint64_t) = nullptr;
    void (*storeDelete)(wasmtime_store_t *) = nullptr;
    wasmtime_error_t *(*instanceNew)(wasmtime_context_t *, const wasmtime_module_t *, const WasmtimeExtern *, std::size_t, WasmtimeInstance *, wasm_trap_t **) = nullptr;
    bool (*instanceExportGet)(wasmtime_context_t *, const WasmtimeInstance *, const char *, std::size_t, WasmtimeExtern *) = nullptr;
    wasmtime_error_t *(*funcCallUnchecked)(wasmtime_context_t *, const WasmtimeFunc *, WasmtimeValRaw *, std::size_t, wasm_trap_t **) = nullptr;
    void (*funcNewUnchecked)(wasmtime_context_t *, const wasm_functype_t *, HostCallback, void *, void (*)(void *), WasmtimeFunc *) = nullptr;
    wasm_valtype_t *(*valTypeNew)(std::uint8_t) = nullptr;
    void (*valTypeVecNewEmpty)(WasmValTypeVec *) = nullptr;
    void (*valTypeVecNew)(WasmValTypeVec *, std::size_t, wasm_valtype_t *const[]) = nullptr;
    wasm_functype_t *(*funcTypeNew)(WasmValTypeVec *, WasmValTypeVec *) = nullptr;
    void (*funcTypeDelete)(wasm_functype_t *) = nullptr;
    void (*errorMessage)(wasmtime_error_t *, WasmByteVec *) = nullptr;
    void (*errorDelete)(wasmtime_error_t *) = nullptr;
    void (*trapMessage)(wasm_trap_t *, WasmByteVec *) = nullptr;
    void (*trapDelete)(wasm_trap_t *) = nullptr;
    wasm_trap_t *(*trapNew)(const char *, std::size_t) = nullptr;
    void (*byteVecDelete)(WasmByteVec *) = nullptr;
    std::uint8_t *(*memoryData)(wasmtime_context_t *, const WasmtimeMemory *) = nullptr;
    std::size_t (*memoryDataSize)(const wasmtime_context_t *, const WasmtimeMemory *) = nullptr;
    wasmtime_error_t *(*memoryGrow)(wasmtime_context_t *, const WasmtimeMemory *, std::uint64_t, std::uint64_t *) = nullptr;
};

struct CallbackEnv {
    WasmtimeApi *api;
    jlong callbackId;
    std::vector<int> paramOpcodes;
    std::vector<int> returnOpcodes;
};

struct NativeExecution {
    explicit NativeExecution(WasmtimeApi *api) : api(api) {}

    ~NativeExecution() {
        callbackEnvs.clear();
        if (store != nullptr) {
            api->storeDelete(store);
        }
        if (module != nullptr) {
            api->moduleDelete(module);
        }
        if (engine != nullptr) {
            api->wasmEngineDelete(engine);
        }
    }

    WasmtimeApi *api;
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

JavaVM *gVm = nullptr;
jclass gNativeClass = nullptr;
jmethodID gInvokeHostFunction = nullptr;
std::mutex gNativeClassMutex;

template <typename T>
T symbol(void *library, const char *name, std::string *error) {
    dlerror();
    void *address = dlsym(library, name);
    const char *symbolError = dlerror();
    if (symbolError != nullptr || address == nullptr) {
        *error = std::string("missing Wasmtime symbol ") + name;
        if (symbolError != nullptr) {
            *error += ": ";
            *error += symbolError;
        }
        return nullptr;
    }
    return reinterpret_cast<T>(address);
}

template <typename T>
bool loadSymbol(void *library, const char *name, T *target, std::string *error) {
    *target = symbol<T>(library, name, error);
    return error->empty();
}

WasmtimeApi *loadApi(std::string *error) {
    static std::mutex mutex;
    static WasmtimeApi api;
    static bool attempted = false;
    static std::string loadError;

    std::lock_guard<std::mutex> lock(mutex);
    if (attempted) {
        if (!loadError.empty()) {
            *error = loadError;
            return nullptr;
        }
        return &api;
    }
    attempted = true;

    api.library = dlopen("libwasmtime.so", RTLD_NOW | RTLD_LOCAL);
    if (api.library == nullptr) {
        const char *dlopenError = dlerror();
        loadError = std::string("failed to load libwasmtime.so");
        if (dlopenError != nullptr) {
            loadError += ": ";
            loadError += dlopenError;
        }
        *error = loadError;
        return nullptr;
    }

    auto require = [&](const char *name, auto *target) {
        return loadSymbol(api.library, name, target, &loadError);
    };

    if (!require("wasm_config_new", &api.wasmConfigNew) ||
        !require("wasm_engine_new_with_config", &api.wasmEngineNewWithConfig) ||
        !require("wasm_engine_delete", &api.wasmEngineDelete) ||
        !require("wasmtime_config_target_set", &api.configTargetSet) ||
        !require("wasmtime_config_wasm_gc_set", &api.configWasmGcSet) ||
        !require("wasmtime_config_wasm_function_references_set", &api.configWasmFunctionReferencesSet) ||
        !require("wasmtime_config_wasm_reference_types_set", &api.configWasmReferenceTypesSet) ||
        !require("wasmtime_config_wasm_exceptions_set", &api.configWasmExceptionsSet) ||
        !require("wasmtime_config_wasm_bulk_memory_set", &api.configWasmBulkMemorySet) ||
        !require("wasmtime_config_wasm_multi_memory_set", &api.configWasmMultiMemorySet) ||
        !require("wasmtime_config_max_wasm_stack_set", &api.configMaxWasmStackSet) ||
        !require("wasmtime_config_memory_may_move_set", &api.configMemoryMayMoveSet) ||
        !require("wasmtime_config_concurrency_support_set", &api.configConcurrencySupportSet) ||
        !require("wasmtime_config_consume_fuel_set", &api.configConsumeFuelSet) ||
        !require("wasmtime_module_new", &api.moduleNew) ||
        !require("wasmtime_module_deserialize", &api.moduleDeserialize) ||
        !require("wasmtime_module_serialize", &api.moduleSerialize) ||
        !require("wasmtime_module_delete", &api.moduleDelete) ||
        !require("wasmtime_store_new", &api.storeNew) ||
        !require("wasmtime_store_context", &api.storeContext) ||
        !require("wasmtime_store_limiter", &api.storeLimiter) ||
        !require("wasmtime_context_set_fuel", &api.contextSetFuel) ||
        !require("wasmtime_store_delete", &api.storeDelete) ||
        !require("wasmtime_instance_new", &api.instanceNew) ||
        !require("wasmtime_instance_export_get", &api.instanceExportGet) ||
        !require("wasmtime_func_call_unchecked", &api.funcCallUnchecked) ||
        !require("wasmtime_func_new_unchecked", &api.funcNewUnchecked) ||
        !require("wasm_valtype_new", &api.valTypeNew) ||
        !require("wasm_valtype_vec_new_empty", &api.valTypeVecNewEmpty) ||
        !require("wasm_valtype_vec_new", &api.valTypeVecNew) ||
        !require("wasm_functype_new", &api.funcTypeNew) ||
        !require("wasm_functype_delete", &api.funcTypeDelete) ||
        !require("wasmtime_error_message", &api.errorMessage) ||
        !require("wasmtime_error_delete", &api.errorDelete) ||
        !require("wasm_trap_message", &api.trapMessage) ||
        !require("wasm_trap_delete", &api.trapDelete) ||
        !require("wasmtime_trap_new", &api.trapNew) ||
        !require("wasm_byte_vec_delete", &api.byteVecDelete) ||
        !require("wasmtime_memory_data", &api.memoryData) ||
        !require("wasmtime_memory_data_size", &api.memoryDataSize) ||
        !require("wasmtime_memory_grow", &api.memoryGrow)) {
        *error = loadError;
        return nullptr;
    }

    return &api;
}

std::string readMessage(WasmtimeApi *api, void *handle, bool trap) {
    WasmByteVec message{};
    if (trap) {
        api->trapMessage(reinterpret_cast<wasm_trap_t *>(handle), &message);
    } else {
        api->errorMessage(reinterpret_cast<wasmtime_error_t *>(handle), &message);
    }
    std::string result(message.data, message.size);
    api->byteVecDelete(&message);
    return result;
}

std::string consumeError(WasmtimeApi *api, wasmtime_error_t *error) {
    if (error == nullptr) {
        return {};
    }
    std::string result = readMessage(api, error, false);
    api->errorDelete(error);
    return result;
}

std::string consumeTrap(WasmtimeApi *api, wasm_trap_t *trap) {
    if (trap == nullptr) {
        return {};
    }
    std::string result = readMessage(api, trap, true);
    api->trapDelete(trap);
    return result;
}

jstring nullableString(JNIEnv *env, const std::string &value) {
    if (value.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(value.c_str());
}

std::string stringFrom(JNIEnv *env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void throwJava(JNIEnv *env, const char *className, const std::string &message) {
    jclass cls = env->FindClass(className);
    if (cls == nullptr) {
        env->ExceptionClear();
        cls = env->FindClass("java/lang/RuntimeException");
    }
    env->ThrowNew(cls, message.c_str());
}

void throwEngine(JNIEnv *env, const std::string &message) {
    throwJava(env, "uk/shusek/krwa/wasm/WasmEngineException", message);
}

void throwRuntime(JNIEnv *env, const std::string &message) {
    throwJava(env, "uk/shusek/krwa/runtime/WasmRuntimeException", message);
}

NativeExecution *executionFrom(jlong handle) {
    return reinterpret_cast<NativeExecution *>(handle);
}

NativeFunction *functionFrom(jlong handle) {
    return reinterpret_cast<NativeFunction *>(handle);
}

NativeMemoryHandle *memoryFrom(jlong handle) {
    return reinterpret_cast<NativeMemoryHandle *>(handle);
}

std::string configureWasmtime(
    WasmtimeApi *api,
    wasm_config_t *config,
    const std::string &target,
    bool memoryMayMove,
    std::int64_t maxWasmStackBytes,
    std::int64_t maxFuel
) {
    if (!target.empty() && target != "native" && target != "cranelift") {
        wasmtime_error_t *targetError = api->configTargetSet(config, target.c_str());
        if (targetError != nullptr) {
            return "wasmtime_config_target_set(" + target + ") failed: " + consumeError(api, targetError);
        }
    }
    if (maxWasmStackBytes <= 0) {
        return "Wasmtime max Wasm stack bytes must be positive";
    }
    api->configMaxWasmStackSet(config, static_cast<std::size_t>(maxWasmStackBytes));
    api->configWasmGcSet(config, true);
    api->configWasmFunctionReferencesSet(config, true);
    api->configWasmReferenceTypesSet(config, true);
    api->configWasmExceptionsSet(config, true);
    api->configWasmBulkMemorySet(config, true);
    api->configWasmMultiMemorySet(config, true);
    api->configMemoryMayMoveSet(config, memoryMayMove);
    api->configConcurrencySupportSet(config, false);
    api->configConsumeFuelSet(config, maxFuel != -1);
    return {};
}

std::string checkWasmtimeTarget(const std::string &target) {
    std::string error;
    WasmtimeApi *api = loadApi(&error);
    if (api == nullptr) {
        return error;
    }

    wasm_config_t *config = api->wasmConfigNew();
    if (config == nullptr) {
        return "wasm_config_new returned null";
    }
    std::string configError = configureWasmtime(api, config, target, false, 512L * 1024L, -1);
    if (!configError.empty()) {
        return configError;
    }
    wasm_engine_t *engine = api->wasmEngineNewWithConfig(config);
    if (engine == nullptr) {
        return "wasm_engine_new_with_config returned null";
    }
    api->wasmEngineDelete(engine);
    return {};
}

std::string checkWasmtimeModuleCompiler(const std::string &target, std::int64_t maxWasmStackBytes) {
    std::string error;
    WasmtimeApi *api = loadApi(&error);
    if (api == nullptr) {
        return error;
    }

    wasm_config_t *config = api->wasmConfigNew();
    if (config == nullptr) {
        return "wasm_config_new returned null";
    }
    std::string configError = configureWasmtime(api, config, target, true, maxWasmStackBytes, -1);
    if (!configError.empty()) {
        return configError;
    }
    wasm_engine_t *engine = api->wasmEngineNewWithConfig(config);
    if (engine == nullptr) {
        return "wasm_engine_new_with_config returned null";
    }
    api->wasmEngineDelete(engine);
    return {};
}

std::string checkWasmtimeComponentWasi() {
    std::string error;
    WasmtimeApi *api = loadApi(&error);
    if (api == nullptr) {
        return error;
    }

    const char *requiredSymbols[] = {
        "wasi_config_new",
        "wasi_config_delete",
        "wasi_config_preopen_dir",
        "wasmtime_context_set_wasi",
        "wasmtime_context_set_wasi_http",
        "wasmtime_component_new",
        "wasmtime_component_linker_new",
        "wasmtime_component_linker_add_wasip2",
        "wasmtime_component_linker_add_wasi_http",
        "wasmtime_component_linker_instantiate",
    };
    for (const char *name : requiredSymbols) {
        dlerror();
        void *address = dlsym(api->library, name);
        const char *symbolError = dlerror();
        if (symbolError != nullptr || address == nullptr) {
            std::string result = "Wasmtime C API component/WASIp2 primitives are not linked: missing symbol ";
            result += name;
            if (symbolError != nullptr) {
                result += ": ";
                result += symbolError;
            }
            return result;
        }
    }
    return {};
}

std::vector<int> intArray(JNIEnv *env, jintArray array) {
    if (array == nullptr) {
        return {};
    }
    jsize length = env->GetArrayLength(array);
    std::vector<int> result(static_cast<std::size_t>(length));
    env->GetIntArrayRegion(array, 0, length, reinterpret_cast<jint *>(result.data()));
    return result;
}

std::vector<int> intArrayAt(JNIEnv *env, jobjectArray arrays, jsize index) {
    auto item = reinterpret_cast<jintArray>(env->GetObjectArrayElement(arrays, index));
    if (item == nullptr) {
        throwEngine(env, "missing import type opcode array");
        return {};
    }
    std::vector<int> result = intArray(env, item);
    env->DeleteLocalRef(item);
    return result;
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
    WasmtimeApi *api,
    const std::vector<int> &params,
    const std::vector<int> &returns,
    std::string *error
) {
    auto makeVec = [&](const std::vector<int> &opcodes, WasmValTypeVec *out) -> bool {
        if (opcodes.empty()) {
            api->valTypeVecNewEmpty(out);
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
            values.push_back(api->valTypeNew(kind));
        }
        api->valTypeVecNew(out, values.size(), values.data());
        return true;
    };

    WasmValTypeVec paramVec{};
    WasmValTypeVec returnVec{};
    if (!makeVec(params, &paramVec) || !makeVec(returns, &returnVec)) {
        return nullptr;
    }
    return api->funcTypeNew(&paramVec, &returnVec);
}

void writeRawValues(WasmtimeValRaw *raw, const std::vector<int> &opcodes, const jlong *values) {
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

void readRawValues(const WasmtimeValRaw *raw, const std::vector<int> &opcodes, jlong *values) {
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

JNIEnv *currentEnv(bool *detach) {
    *detach = false;
    if (gVm == nullptr) {
        return nullptr;
    }
    JNIEnv *env = nullptr;
    jint status = gVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) {
        return env;
    }
    if (status == JNI_EDETACHED && gVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        *detach = true;
        return env;
    }
    return nullptr;
}

bool ensureNativeClass(JNIEnv *env) {
    std::lock_guard<std::mutex> lock(gNativeClassMutex);
    if (gNativeClass != nullptr && gInvokeHostFunction != nullptr) {
        return true;
    }
    jclass localClass =
        env->FindClass("uk/shusek/krwa/runtime/wasmtime/android/AndroidWasmtimePulleyNative");
    if (localClass == nullptr) {
        env->ExceptionClear();
        return false;
    }
    gNativeClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    gInvokeHostFunction =
        env->GetStaticMethodID(gNativeClass, "invokeHostFunction", "(J[J)[J");
    if (gInvokeHostFunction == nullptr) {
        env->ExceptionClear();
        return false;
    }
    return true;
}

std::string throwableToString(JNIEnv *env, jthrowable throwable) {
    jclass objectClass = env->FindClass("java/lang/Object");
    if (objectClass == nullptr) {
        env->ExceptionClear();
        return "host callback failed";
    }
    jmethodID toString = env->GetMethodID(objectClass, "toString", "()Ljava/lang/String;");
    if (toString == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(objectClass);
        return "host callback failed";
    }
    auto message = reinterpret_cast<jstring>(env->CallObjectMethod(throwable, toString));
    env->DeleteLocalRef(objectClass);
    if (message == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return "host callback failed";
    }
    const char *chars = env->GetStringUTFChars(message, nullptr);
    std::string result = chars == nullptr ? "host callback failed" : chars;
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(message, chars);
    }
    env->DeleteLocalRef(message);
    return result;
}

wasm_trap_t *trapFromMessage(WasmtimeApi *api, const std::string &message) {
    return api->trapNew(message.c_str(), message.size());
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
        return trapFromMessage(callback->api, "host callback raw value buffer is too small");
    }

    bool detach = false;
    JNIEnv *env = currentEnv(&detach);
    if (env == nullptr || !ensureNativeClass(env)) {
        if (detach) {
            gVm->DetachCurrentThread();
        }
        return trapFromMessage(callback->api, "failed to attach JNI host callback");
    }

    jlongArray args = env->NewLongArray(static_cast<jsize>(callback->paramOpcodes.size()));
    if (args == nullptr) {
        if (detach) {
            gVm->DetachCurrentThread();
        }
        return trapFromMessage(callback->api, "failed to allocate host callback args");
    }
    std::vector<jlong> argValues(callback->paramOpcodes.size());
    readRawValues(argsAndResults, callback->paramOpcodes, argValues.data());
    env->SetLongArrayRegion(args, 0, static_cast<jsize>(argValues.size()), argValues.data());

    auto results = reinterpret_cast<jlongArray>(
        env->CallStaticObjectMethod(gNativeClass, gInvokeHostFunction, callback->callbackId, args)
    );
    env->DeleteLocalRef(args);
    if (env->ExceptionCheck()) {
        jthrowable throwable = env->ExceptionOccurred();
        env->ExceptionClear();
        std::string message = throwable == nullptr ? "host callback failed" : throwableToString(env, throwable);
        if (throwable != nullptr) {
            env->DeleteLocalRef(throwable);
        }
        if (detach) {
            gVm->DetachCurrentThread();
        }
        return trapFromMessage(callback->api, message);
    }

    jsize resultSize = results == nullptr ? 0 : env->GetArrayLength(results);
    if (resultSize != static_cast<jsize>(callback->returnOpcodes.size())) {
        if (results != nullptr) {
            env->DeleteLocalRef(results);
        }
        if (detach) {
            gVm->DetachCurrentThread();
        }
        return trapFromMessage(callback->api, "host callback returned wrong number of results");
    }
    std::vector<jlong> resultValues(callback->returnOpcodes.size());
    if (results != nullptr && resultSize > 0) {
        env->GetLongArrayRegion(results, 0, resultSize, resultValues.data());
    }
    if (results != nullptr) {
        env->DeleteLocalRef(results);
    }
    writeRawValues(argsAndResults, callback->returnOpcodes, resultValues.data());

    if (detach) {
        gVm->DetachCurrentThread();
    }
    return nullptr;
}

std::uint8_t *checkedMemorySlice(
    JNIEnv *env,
    NativeExecution *execution,
    NativeMemoryHandle *memory,
    jint addr,
    jint size
) {
    if (execution == nullptr || memory == nullptr) {
        throwEngine(env, "invalid Wasmtime memory handle");
        return nullptr;
    }
    if (addr < 0 || size < 0) {
        throwRuntime(env, "out of bounds memory access");
        return nullptr;
    }
    std::uint8_t *data = execution->api->memoryData(execution->context, &memory->memory);
    std::size_t byteSize = execution->api->memoryDataSize(execution->context, &memory->memory);
    std::uint64_t end = static_cast<std::uint64_t>(addr) + static_cast<std::uint64_t>(size);
    if (end > byteSize) {
        throwRuntime(env, "out of bounds memory access");
        return nullptr;
    }
    return data + addr;
}

template <typename T>
T readPrimitive(JNIEnv *env, jlong nativeHandle, jlong nativeMemory, jint addr) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint8_t *source = checkedMemorySlice(env, execution, memory, addr, sizeof(T));
    if (source == nullptr) {
        return T{};
    }
    T result{};
    std::memcpy(&result, source, sizeof(T));
    return result;
}

template <typename T>
void writePrimitive(JNIEnv *env, jlong nativeHandle, jlong nativeMemory, jint addr, T value) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint8_t *target = checkedMemorySlice(env, execution, memory, addr, sizeof(T));
    if (target == nullptr) {
        return;
    }
    std::memcpy(target, &value, sizeof(T));
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_nativeUnavailableReason(
    JNIEnv *env,
    jclass,
    jstring target
) {
    return nullableString(env, checkWasmtimeTarget(stringFrom(env, target)));
}

extern "C" JNIEXPORT jstring JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_nativeComponentWasiUnavailableReason(
    JNIEnv *env,
    jclass
) {
    return nullableString(env, checkWasmtimeComponentWasi());
}

extern "C" JNIEXPORT jstring JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimeModuleCompilerNative_nativeCompilerUnavailableReason(
    JNIEnv *env,
    jclass,
    jstring target,
    jlong maxWasmStackBytes
) {
    return nullableString(env, checkWasmtimeModuleCompiler(stringFrom(env, target), maxWasmStackBytes));
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimeModuleCompilerNative_nativeCompileModuleToCwasm(
    JNIEnv *env,
    jclass,
    jbyteArray moduleBytes,
    jstring target,
    jlong maxWasmStackBytes
) {
    if (moduleBytes == nullptr) {
        throwEngine(env, "module bytes must not be null");
        return nullptr;
    }

    std::string targetValue = stringFrom(env, target);
    std::string loadError;
    WasmtimeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        throwEngine(env, loadError);
        return nullptr;
    }

    wasm_config_t *config = api->wasmConfigNew();
    if (config == nullptr) {
        throwEngine(env, "wasm_config_new returned null");
        return nullptr;
    }
    std::string configError = configureWasmtime(api, config, targetValue, true, maxWasmStackBytes, -1);
    if (!configError.empty()) {
        throwEngine(env, configError);
        return nullptr;
    }
    wasm_engine_t *engine = api->wasmEngineNewWithConfig(config);
    if (engine == nullptr) {
        throwEngine(env, "wasm_engine_new_with_config returned null");
        return nullptr;
    }

    wasmtime_module_t *module = nullptr;
    jsize moduleSize = env->GetArrayLength(moduleBytes);
    jbyte *moduleData = env->GetByteArrayElements(moduleBytes, nullptr);
    if (moduleData == nullptr) {
        api->wasmEngineDelete(engine);
        return nullptr;
    }
    wasmtime_error_t *moduleError =
        api->moduleNew(
            engine,
            reinterpret_cast<const std::uint8_t *>(moduleData),
            static_cast<std::size_t>(moduleSize),
            &module
        );
    env->ReleaseByteArrayElements(moduleBytes, moduleData, JNI_ABORT);
    if (moduleError != nullptr) {
        api->wasmEngineDelete(engine);
        throwEngine(env, "compile module for target " + targetValue + ": " + consumeError(api, moduleError));
        return nullptr;
    }

    WasmByteVec serialized{};
    wasmtime_error_t *serializeError = api->moduleSerialize(module, &serialized);
    api->moduleDelete(module);
    api->wasmEngineDelete(engine);
    if (serializeError != nullptr) {
        throwEngine(env, "serialize module for target " + targetValue + ": " + consumeError(api, serializeError));
        return nullptr;
    }
    if (serialized.size > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        api->byteVecDelete(&serialized);
        throwEngine(env, "serialized module is too large for a JVM byte array");
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(serialized.size));
    if (result != nullptr && serialized.size > 0) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(serialized.size),
            reinterpret_cast<const jbyte *>(serialized.data)
        );
    }
    api->byteVecDelete(&serialized);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_create(
    JNIEnv *env,
    jclass,
    jbyteArray moduleBytes,
    jboolean precompiledModule,
    jstring target,
    jlong maxMemoryBytes,
    jlong maxWasmStackBytes,
    jlong maxTableElements,
    jlong maxInstances,
    jlong maxTables,
    jlong maxMemories,
    jlong maxFuel,
    jlongArray callbackIds,
    jobjectArray paramOpcodes,
    jobjectArray returnOpcodes
) {
    std::string targetValue = stringFrom(env, target);
    std::string loadError;
    WasmtimeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        throwEngine(env, loadError);
        return 0;
    }
    if (maxMemoryBytes <= 0) {
        throwEngine(env, "Wasmtime max memory bytes must be positive");
        return 0;
    }
    if (maxWasmStackBytes <= 0) {
        throwEngine(env, "Wasmtime max Wasm stack bytes must be positive");
        return 0;
    }
    if (maxTableElements < -1 || maxInstances < -1 || maxTables < -1 || maxMemories < -1 ||
        maxFuel < -1) {
        throwEngine(env, "Wasmtime resource limits must be -1 for unlimited or non-negative");
        return 0;
    }

    jsize importCount = env->GetArrayLength(callbackIds);
    if (env->GetArrayLength(paramOpcodes) != importCount ||
        env->GetArrayLength(returnOpcodes) != importCount) {
        throwEngine(env, "import callback and type arrays have different sizes");
        return 0;
    }

    std::unique_ptr<NativeExecution> execution = std::make_unique<NativeExecution>(api);

    wasm_config_t *config = api->wasmConfigNew();
    if (config == nullptr) {
        throwEngine(env, "wasm_config_new returned null");
        return 0;
    }
    std::string configError =
        configureWasmtime(api, config, targetValue, precompiledModule, maxWasmStackBytes, maxFuel);
    if (!configError.empty()) {
        throwEngine(env, configError);
        return 0;
    }
    execution->engine = api->wasmEngineNewWithConfig(config);
    if (execution->engine == nullptr) {
        throwEngine(env, "wasm_engine_new_with_config returned null");
        return 0;
    }

    jsize moduleSize = env->GetArrayLength(moduleBytes);
    jbyte *moduleData = env->GetByteArrayElements(moduleBytes, nullptr);
    wasmtime_error_t *moduleError =
        precompiledModule
            ? api->moduleDeserialize(
                execution->engine,
                reinterpret_cast<const std::uint8_t *>(moduleData),
                static_cast<std::size_t>(moduleSize),
                &execution->module
            )
            : api->moduleNew(
                execution->engine,
                reinterpret_cast<const std::uint8_t *>(moduleData),
                static_cast<std::size_t>(moduleSize),
                &execution->module
            );
    env->ReleaseByteArrayElements(moduleBytes, moduleData, JNI_ABORT);
    if (moduleError != nullptr) {
        const char *operation = precompiledModule ? "deserialize" : "compile";
        throwEngine(env, std::string(operation) + " module for target " + targetValue + ": " +
            consumeError(api, moduleError));
        return 0;
    }

    execution->store = api->storeNew(execution->engine, nullptr, nullptr);
    if (execution->store == nullptr) {
        throwEngine(env, "wasmtime_store_new returned null");
        return 0;
    }
    api->storeLimiter(
        execution->store,
        maxMemoryBytes,
        maxTableElements,
        maxInstances,
        maxTables,
        maxMemories);
    execution->context = api->storeContext(execution->store);
    if (maxFuel != -1) {
        wasmtime_error_t *fuelError =
            api->contextSetFuel(execution->context, static_cast<std::uint64_t>(maxFuel));
        if (fuelError != nullptr) {
            throwEngine(env, "set Wasmtime fuel: " + consumeError(api, fuelError));
            return 0;
        }
    }

    std::vector<WasmtimeExtern> imports(static_cast<std::size_t>(importCount));
    std::vector<jlong> callbackIdValues(static_cast<std::size_t>(importCount));
    env->GetLongArrayRegion(callbackIds, 0, importCount, callbackIdValues.data());

    for (jsize i = 0; i < importCount; i++) {
        std::vector<int> params = intArrayAt(env, paramOpcodes, i);
        if (env->ExceptionCheck()) {
            return 0;
        }
        std::vector<int> returns = intArrayAt(env, returnOpcodes, i);
        if (env->ExceptionCheck()) {
            return 0;
        }
        std::string typeError;
        wasm_functype_t *type = createFunctionType(api, params, returns, &typeError);
        if (type == nullptr) {
            throwEngine(env, typeError.empty() ? "failed to create Wasmtime function type" : typeError);
            return 0;
        }

        auto callback = std::make_unique<CallbackEnv>();
        callback->api = api;
        callback->callbackId = callbackIdValues[static_cast<std::size_t>(i)];
        callback->paramOpcodes = std::move(params);
        callback->returnOpcodes = std::move(returns);

        WasmtimeFunc func{};
        api->funcNewUnchecked(
            execution->context,
            type,
            hostFunctionCallback,
            callback.get(),
            nullptr,
            &func
        );
        api->funcTypeDelete(type);
        execution->callbackEnvs.push_back(std::move(callback));

        imports[static_cast<std::size_t>(i)].kind = WASMTIME_EXTERN_FUNC;
        imports[static_cast<std::size_t>(i)].of.func = func;
    }

    wasm_trap_t *trap = nullptr;
    wasmtime_error_t *instanceError =
        api->instanceNew(
            execution->context,
            execution->module,
            imports.empty() ? nullptr : imports.data(),
            imports.size(),
            &execution->instance,
            &trap
        );
    if (instanceError != nullptr) {
        throwEngine(env, "instantiate Pulley module: " + consumeError(api, instanceError));
        return 0;
    }
    if (trap != nullptr) {
        throwEngine(env, "instantiate Pulley module: " + consumeTrap(api, trap));
        return 0;
    }

    return reinterpret_cast<jlong>(execution.release());
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_destroy(
    JNIEnv *,
    jclass,
    jlong nativeHandle
) {
    delete executionFrom(nativeHandle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_bindFunction(
    JNIEnv *env,
    jclass,
    jlong nativeHandle,
    jstring name
) {
    auto *execution = executionFrom(nativeHandle);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    const char *chars = env->GetStringUTFChars(name, nullptr);
    if (chars == nullptr) {
        return 0;
    }
    std::size_t length = static_cast<std::size_t>(env->GetStringUTFLength(name));
    WasmtimeExtern item{};
    bool found =
        execution->api->instanceExportGet(
            execution->context,
            &execution->instance,
            chars,
            length,
            &item
        );
    env->ReleaseStringUTFChars(name, chars);
    if (!found || item.kind != WASMTIME_EXTERN_FUNC) {
        throwEngine(env, "Unknown function export");
        return 0;
    }
    auto *function = new NativeFunction{execution, item.of.func};
    return reinterpret_cast<jlong>(function);
}

extern "C" JNIEXPORT jlong JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_bindMemory(
    JNIEnv *env,
    jclass,
    jlong nativeHandle,
    jstring name
) {
    auto *execution = executionFrom(nativeHandle);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    const char *chars = env->GetStringUTFChars(name, nullptr);
    if (chars == nullptr) {
        return 0;
    }
    std::size_t length = static_cast<std::size_t>(env->GetStringUTFLength(name));
    WasmtimeExtern item{};
    bool found =
        execution->api->instanceExportGet(
            execution->context,
            &execution->instance,
            chars,
            length,
            &item
        );
    env->ReleaseStringUTFChars(name, chars);
    if (!found || item.kind != WASMTIME_EXTERN_MEMORY) {
        return 0;
    }
    auto *memory = new NativeMemoryHandle{execution, item.of.memory};
    return reinterpret_cast<jlong>(memory);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_call(
    JNIEnv *env,
    jclass,
    jlong nativeHandle,
    jlong nativeFunction,
    jintArray paramOpcodes,
    jintArray returnOpcodes,
    jlongArray args
) {
    auto *execution = executionFrom(nativeHandle);
    auto *function = functionFrom(nativeFunction);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::vector<int> params = intArray(env, paramOpcodes);
    std::vector<int> returns = intArray(env, returnOpcodes);
    jsize argCount = env->GetArrayLength(args);
    if (argCount != static_cast<jsize>(params.size())) {
        throwEngine(env, "wrong number of arguments");
        return nullptr;
    }

    std::size_t slotCount = std::max<std::size_t>(1, std::max(params.size(), returns.size()));
    std::vector<WasmtimeValRaw> raw(slotCount);
    std::vector<jlong> argValues(params.size());
    if (!argValues.empty()) {
        env->GetLongArrayRegion(args, 0, argCount, argValues.data());
        writeRawValues(raw.data(), params, argValues.data());
    }

    wasm_trap_t *trap = nullptr;
    wasmtime_error_t *callError =
        execution->api->funcCallUnchecked(
            execution->context,
            &function->func,
            raw.data(),
            slotCount,
            &trap
        );
    if (callError != nullptr) {
        throwEngine(env, "call Pulley export: " + consumeError(execution->api, callError));
        return nullptr;
    }
    if (trap != nullptr) {
        throwEngine(env, "call Pulley export: " + consumeTrap(execution->api, trap));
        return nullptr;
    }

    jlongArray result = env->NewLongArray(static_cast<jsize>(returns.size()));
    if (result == nullptr) {
        return nullptr;
    }
    std::vector<jlong> resultValues(returns.size());
    readRawValues(raw.data(), returns, resultValues.data());
    if (!resultValues.empty()) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(resultValues.size()), resultValues.data());
    }
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryByteSize(
    JNIEnv *,
    jclass,
    jlong nativeHandle,
    jlong nativeMemory
) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    return static_cast<jlong>(execution->api->memoryDataSize(execution->context, &memory->memory));
}

extern "C" JNIEXPORT jint JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryGrow(
    JNIEnv *,
    jclass,
    jlong nativeHandle,
    jlong nativeMemory,
    jint deltaPages
) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint64_t previous = 0;
    wasmtime_error_t *error =
        execution->api->memoryGrow(execution->context, &memory->memory, deltaPages, &previous);
    if (error != nullptr) {
        execution->api->errorDelete(error);
        return -1;
    }
    return static_cast<jint>(previous);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryRead(
    JNIEnv *env,
    jclass,
    jlong nativeHandle,
    jlong nativeMemory,
    jint addr,
    jint size
) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    std::uint8_t *source = checkedMemorySlice(env, execution, memory, addr, size);
    if (source == nullptr) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(size);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, size, reinterpret_cast<jbyte *>(source));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryReadInto(
    JNIEnv *env,
    jclass,
    jlong nativeHandle,
    jlong nativeMemory,
    jint addr,
    jbyteArray target,
    jint offset,
    jint size
) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    if (offset < 0 || size < 0 || offset + size > env->GetArrayLength(target)) {
        throwRuntime(env, "out of bounds memory target access");
        return;
    }
    std::uint8_t *source = checkedMemorySlice(env, execution, memory, addr, size);
    if (source == nullptr) {
        return;
    }
    env->SetByteArrayRegion(target, offset, size, reinterpret_cast<jbyte *>(source));
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryWrite(
    JNIEnv *env,
    jclass,
    jlong nativeHandle,
    jlong nativeMemory,
    jint addr,
    jbyteArray data,
    jint offset,
    jint size
) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    if (offset < 0 || size < 0 || offset + size > env->GetArrayLength(data)) {
        throwRuntime(env, "out of bounds memory source access");
        return;
    }
    std::uint8_t *target = checkedMemorySlice(env, execution, memory, addr, size);
    if (target == nullptr) {
        return;
    }
    env->GetByteArrayRegion(data, offset, size, reinterpret_cast<jbyte *>(target));
}

extern "C" JNIEXPORT jbyte JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryReadByte(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr
) {
    return readPrimitive<jbyte>(env, nativeHandle, nativeMemory, addr);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryWriteByte(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr, jbyte value
) {
    writePrimitive(env, nativeHandle, nativeMemory, addr, value);
}

extern "C" JNIEXPORT jshort JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryReadI16(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr
) {
    return readPrimitive<jshort>(env, nativeHandle, nativeMemory, addr);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryWriteI16(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr, jshort value
) {
    writePrimitive(env, nativeHandle, nativeMemory, addr, value);
}

extern "C" JNIEXPORT jint JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryReadI32(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr
) {
    return readPrimitive<jint>(env, nativeHandle, nativeMemory, addr);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryWriteI32(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr, jint value
) {
    writePrimitive(env, nativeHandle, nativeMemory, addr, value);
}

extern "C" JNIEXPORT jlong JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryReadI64(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr
) {
    return readPrimitive<jlong>(env, nativeHandle, nativeMemory, addr);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryWriteI64(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr, jlong value
) {
    writePrimitive(env, nativeHandle, nativeMemory, addr, value);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryReadF32(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr
) {
    return readPrimitive<jfloat>(env, nativeHandle, nativeMemory, addr);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryWriteF32(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr, jfloat value
) {
    writePrimitive(env, nativeHandle, nativeMemory, addr, value);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryReadF64(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr
) {
    return readPrimitive<jdouble>(env, nativeHandle, nativeMemory, addr);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryWriteF64(
    JNIEnv *env, jclass, jlong nativeHandle, jlong nativeMemory, jint addr, jdouble value
) {
    writePrimitive(env, nativeHandle, nativeMemory, addr, value);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePulleyNative_memoryFill(
    JNIEnv *env,
    jclass,
    jlong nativeHandle,
    jlong nativeMemory,
    jbyte value,
    jint fromIndex,
    jint toIndex
) {
    auto *execution = executionFrom(nativeHandle);
    auto *memory = memoryFrom(nativeMemory);
    std::lock_guard<std::recursive_mutex> lock(execution->mutex);
    if (toIndex < fromIndex) {
        throwRuntime(env, "out of bounds memory access");
        return;
    }
    jint size = toIndex - fromIndex;
    std::uint8_t *target = checkedMemorySlice(env, execution, memory, fromIndex, size);
    if (target == nullptr) {
        return;
    }
    std::memset(target, static_cast<unsigned char>(value), static_cast<std::size_t>(size));
}
