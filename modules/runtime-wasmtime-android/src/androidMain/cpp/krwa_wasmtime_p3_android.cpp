#include <dlfcn.h>
#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace {

using ExecutionCancellationCreate = void *(*)();
using ExecutionCancellationCancel = void (*)(void *);
using ExecutionCancellationIsCancelled = std::uint8_t (*)(const void *);
using ExecutionCancellationFree = void (*)(void *);

using ComponentInstantiateUnavailableReason = const char * (*)(
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    const char * const *,
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    std::size_t,
    const char * const *,
    const char * const *,
    std::size_t,
    const char * const *,
    std::size_t,
    const char * const *,
    std::size_t,
    std::uint8_t,
    std::uint64_t,
    std::uint64_t,
    std::int64_t,
    std::int64_t,
    std::int64_t,
    std::int64_t);

using ComponentCallString = const char * (*)(
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    const char * const *,
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    std::size_t,
    const char * const *,
    const char * const *,
    std::size_t,
    const char *,
    const char *,
    const char * const *,
    std::size_t,
    const char * const *,
    std::size_t,
    std::uint8_t,
    std::uint64_t,
    std::uint64_t,
    std::int64_t,
    std::int64_t,
    std::int64_t,
    std::int64_t,
    std::uint64_t,
    const void *,
    const char **);

using CommandRunUnavailableReason = const char * (*)(
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    const char * const *,
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    std::size_t,
    const char * const *,
    const char * const *,
    std::size_t,
    const char * const *,
    std::size_t,
    const char * const *,
    std::size_t,
    std::uint8_t,
    std::uint64_t,
    std::uint64_t,
    std::int64_t,
    std::int64_t,
    std::int64_t,
    std::int64_t,
    std::uint64_t);

using CommandRunString = const char * (*)(
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    const char * const *,
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    std::size_t,
    const char * const *,
    const char * const *,
    std::size_t,
    const std::uint8_t *,
    std::size_t,
    const char * const *,
    std::size_t,
    const char * const *,
    std::size_t,
    std::uint8_t,
    std::uint64_t,
    std::uint64_t,
    std::int64_t,
    std::int64_t,
    std::int64_t,
    std::int64_t,
    std::uint64_t,
    std::uint64_t,
    const void *,
    const char **);

struct P3BridgeApi {
    void *library = nullptr;
    ExecutionCancellationCreate executionCancellationCreate = nullptr;
    ExecutionCancellationCancel executionCancellationCancel = nullptr;
    ExecutionCancellationIsCancelled executionCancellationIsCancelled = nullptr;
    ExecutionCancellationFree executionCancellationFree = nullptr;
    ComponentInstantiateUnavailableReason instantiateUnavailableReason = nullptr;
    ComponentCallString callString = nullptr;
    CommandRunUnavailableReason commandRunUnavailableReason = nullptr;
    CommandRunString commandRunString = nullptr;
};

template <typename T>
T symbol(void *library, const char *name, std::string *error) {
    dlerror();
    void *address = dlsym(library, name);
    const char *symbolError = dlerror();
    if (symbolError != nullptr || address == nullptr) {
        *error = std::string("missing Wasmtime Preview3 bridge symbol ") + name;
        if (symbolError != nullptr) {
            *error += ": ";
            *error += symbolError;
        }
        return nullptr;
    }
    return reinterpret_cast<T>(address);
}

P3BridgeApi *loadApi(std::string *error) {
    static P3BridgeApi api;
    static bool attempted = false;
    static std::string loadError;

    if (attempted) {
        if (!loadError.empty()) {
            *error = loadError;
            return nullptr;
        }
        return &api;
    }
    attempted = true;

    api.library = dlopen("libkrwa_wasmtime_p3_bridge.so", RTLD_NOW | RTLD_LOCAL);
    if (api.library == nullptr) {
        const char *dlopenError = dlerror();
        loadError = "failed to load libkrwa_wasmtime_p3_bridge.so";
        if (dlopenError != nullptr) {
            loadError += ": ";
            loadError += dlopenError;
        }
        *error = loadError;
        return nullptr;
    }

    api.executionCancellationCreate = symbol<ExecutionCancellationCreate>(
        api.library,
        "krwa_wasmtime_p3_execution_cancellation_create",
        &loadError);
    api.executionCancellationCancel = symbol<ExecutionCancellationCancel>(
        api.library,
        "krwa_wasmtime_p3_execution_cancellation_cancel",
        &loadError);
    api.executionCancellationIsCancelled = symbol<ExecutionCancellationIsCancelled>(
        api.library,
        "krwa_wasmtime_p3_execution_cancellation_is_cancelled",
        &loadError);
    api.executionCancellationFree = symbol<ExecutionCancellationFree>(
        api.library,
        "krwa_wasmtime_p3_execution_cancellation_free",
        &loadError);
    api.instantiateUnavailableReason = symbol<ComponentInstantiateUnavailableReason>(
        api.library,
        "krwa_wasmtime_p3_precompiled_component_instantiate_unavailable_reason",
        &loadError);
    api.callString = symbol<ComponentCallString>(
        api.library,
        "krwa_wasmtime_p3_precompiled_component_call_string",
        &loadError);
    api.commandRunUnavailableReason = symbol<CommandRunUnavailableReason>(
        api.library,
        "krwa_wasmtime_p3_precompiled_command_run_unavailable_reason",
        &loadError);
    api.commandRunString = symbol<CommandRunString>(
        api.library,
        "krwa_wasmtime_p3_precompiled_command_run_string",
        &loadError);
    if (!loadError.empty()) {
        *error = loadError;
        return nullptr;
    }
    return &api;
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

jstring nullableString(JNIEnv *env, const char *value) {
    if (value == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(value);
}

std::string stringFrom(JNIEnv *env, jstring value, const char *label) {
    if (value == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", std::string(label) + " must not be null");
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

struct StringArray {
    std::vector<std::string> strings;
    std::vector<const char *> pointers;

    const char * const *data() const {
        return pointers.empty() ? nullptr : pointers.data();
    }

    std::size_t size() const {
        return pointers.size();
    }
};

StringArray stringArrayFrom(JNIEnv *env, jobjectArray values, const char *label) {
    StringArray result;
    if (values == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", std::string(label) + " must not be null");
        return result;
    }
    const jsize count = env->GetArrayLength(values);
    result.strings.reserve(static_cast<std::size_t>(count));
    result.pointers.reserve(static_cast<std::size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        if (value == nullptr) {
            throwJava(
                env,
                "java/lang/IllegalArgumentException",
                std::string(label) + "[" + std::to_string(index) + "] must not be null");
            return result;
        }
        result.strings.push_back(stringFrom(env, value, label));
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            return result;
        }
    }
    for (const std::string &value : result.strings) {
        result.pointers.push_back(value.c_str());
    }
    return result;
}

std::string stringFromNullable(JNIEnv *env, jstring value) {
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

std::vector<std::uint8_t> bytesFrom(JNIEnv *env, jbyteArray values, const char *label) {
    std::vector<std::uint8_t> result;
    if (values == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", std::string(label) + " must not be null");
        return result;
    }
    const jsize count = env->GetArrayLength(values);
    result.resize(static_cast<std::size_t>(count));
    if (count > 0) {
        env->GetByteArrayRegion(
            values,
            0,
            count,
            reinterpret_cast<jbyte *>(result.data()));
    }
    return result;
}

std::vector<std::uint8_t> writablePreopensFrom(JNIEnv *env, jbooleanArray values, const char *label) {
    std::vector<std::uint8_t> result;
    if (values == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", std::string(label) + " must not be null");
        return result;
    }
    const jsize count = env->GetArrayLength(values);
    std::vector<jboolean> booleans(static_cast<std::size_t>(count));
    if (count > 0) {
        env->GetBooleanArrayRegion(values, 0, count, booleans.data());
    }
    result.reserve(static_cast<std::size_t>(count));
    for (jboolean value : booleans) {
        result.push_back(value == JNI_TRUE ? 1 : 0);
    }
    return result;
}

struct P3CallArgs {
    std::vector<std::uint8_t> componentBytes;
    StringArray hostRoots;
    StringArray guestRoots;
    std::vector<std::uint8_t> writablePreopens;
    StringArray arguments;
    StringArray environmentKeys;
    StringArray environmentValues;
    StringArray allowedHosts;
    StringArray blockedHosts;
};

P3CallArgs p3CallArgsFrom(
    JNIEnv *env,
    jbyteArray componentBytes,
    jobjectArray hostRoots,
    jobjectArray guestRoots,
    jbooleanArray writablePreopens,
    jobjectArray arguments,
    jobjectArray environmentKeys,
    jobjectArray environmentValues,
    jobjectArray allowedHosts,
    jobjectArray blockedHosts) {
    return P3CallArgs{
        bytesFrom(env, componentBytes, "componentBytes"),
        stringArrayFrom(env, hostRoots, "hostRoots"),
        stringArrayFrom(env, guestRoots, "guestRoots"),
        writablePreopensFrom(env, writablePreopens, "writablePreopens"),
        stringArrayFrom(env, arguments, "arguments"),
        stringArrayFrom(env, environmentKeys, "environmentKeys"),
        stringArrayFrom(env, environmentValues, "environmentValues"),
        stringArrayFrom(env, allowedHosts, "allowedHosts"),
        stringArrayFrom(env, blockedHosts, "blockedHosts"),
    };
}

bool validateP3CallArgs(JNIEnv *env, const P3CallArgs &args) {
    if (env->ExceptionCheck()) {
        return false;
    }
    if (args.hostRoots.size() != args.guestRoots.size() ||
        args.hostRoots.size() != args.writablePreopens.size()) {
        throwJava(env, "java/lang/IllegalArgumentException", "Preview3 preopen arrays have different sizes");
        return false;
    }
    if (args.environmentKeys.size() != args.environmentValues.size()) {
        throwJava(env, "java/lang/IllegalArgumentException", "Preview3 environment arrays have different sizes");
        return false;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePreview3Native_nativeComponentUnavailableReason(
    JNIEnv *env,
    jclass,
    jbyteArray componentBytes,
    jobjectArray hostRoots,
    jobjectArray guestRoots,
    jbooleanArray writablePreopens,
    jobjectArray arguments,
    jobjectArray environmentKeys,
    jobjectArray environmentValues,
    jobjectArray allowedHosts,
    jobjectArray blockedHosts,
    jboolean allowPrivateNetwork,
    jlong maxMemoryBytes,
    jlong maxWasmStackBytes,
    jlong maxTableElements,
    jlong maxInstances,
    jlong maxTables,
    jlong maxMemories) {
    std::string loadError;
    P3BridgeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        return env->NewStringUTF(loadError.c_str());
    }
    P3CallArgs args = p3CallArgsFrom(
        env,
        componentBytes,
        hostRoots,
        guestRoots,
        writablePreopens,
        arguments,
        environmentKeys,
        environmentValues,
        allowedHosts,
        blockedHosts);
    if (!validateP3CallArgs(env, args)) {
        return nullptr;
    }
    const char *error = api->instantiateUnavailableReason(
        args.componentBytes.data(),
        args.componentBytes.size(),
        args.hostRoots.data(),
        args.guestRoots.data(),
        args.writablePreopens.empty() ? nullptr : args.writablePreopens.data(),
        args.hostRoots.size(),
        args.arguments.data(),
        args.arguments.size(),
        args.environmentKeys.data(),
        args.environmentValues.data(),
        args.environmentKeys.size(),
        args.allowedHosts.data(),
        args.allowedHosts.size(),
        args.blockedHosts.data(),
        args.blockedHosts.size(),
        allowPrivateNetwork == JNI_TRUE ? 1 : 0,
        static_cast<std::uint64_t>(maxMemoryBytes),
        static_cast<std::uint64_t>(maxWasmStackBytes),
        static_cast<std::int64_t>(maxTableElements),
        static_cast<std::int64_t>(maxInstances),
        static_cast<std::int64_t>(maxTables),
        static_cast<std::int64_t>(maxMemories));
    return nullableString(env, error);
}

extern "C" JNIEXPORT jlong JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePreview3Native_nativeExecutionCancellationCreate(
    JNIEnv *env,
    jobject) {
    std::string loadError;
    P3BridgeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        throwEngine(env, loadError);
        return 0;
    }
    void *handle = api->executionCancellationCreate();
    if (handle == nullptr) {
        throwEngine(env, "Wasmtime Preview3 cancellation handle allocation failed");
        return 0;
    }
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePreview3Native_nativeExecutionCancellationCancel(
    JNIEnv *env,
    jobject,
    jlong handle) {
    if (handle == 0) {
        return;
    }
    std::string loadError;
    P3BridgeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        throwEngine(env, loadError);
        return;
    }
    api->executionCancellationCancel(reinterpret_cast<void *>(handle));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePreview3Native_nativeExecutionCancellationIsCancelled(
    JNIEnv *env,
    jobject,
    jlong handle) {
    if (handle == 0) {
        return JNI_FALSE;
    }
    std::string loadError;
    P3BridgeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        throwEngine(env, loadError);
        return JNI_FALSE;
    }
    return api->executionCancellationIsCancelled(reinterpret_cast<const void *>(handle)) == 0
        ? JNI_FALSE
        : JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePreview3Native_nativeExecutionCancellationFree(
    JNIEnv *env,
    jobject,
    jlong handle) {
    if (handle == 0) {
        return;
    }
    std::string loadError;
    P3BridgeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        throwEngine(env, loadError);
        return;
    }
    api->executionCancellationFree(reinterpret_cast<void *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePreview3Native_nativeComponentCallString(
    JNIEnv *env,
    jclass,
    jbyteArray componentBytes,
    jobjectArray hostRoots,
    jobjectArray guestRoots,
    jbooleanArray writablePreopens,
    jobjectArray arguments,
    jobjectArray environmentKeys,
    jobjectArray environmentValues,
    jobjectArray allowedHosts,
    jobjectArray blockedHosts,
    jboolean allowPrivateNetwork,
    jstring exportName,
    jstring argument,
    jlong maxMemoryBytes,
    jlong maxWasmStackBytes,
    jlong maxTableElements,
    jlong maxInstances,
    jlong maxTables,
    jlong maxMemories,
    jlong executionTimeoutMillis,
    jlong executionCancellationHandle) {
    std::string loadError;
    P3BridgeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        throwEngine(env, loadError);
        return nullptr;
    }
    P3CallArgs args = p3CallArgsFrom(
        env,
        componentBytes,
        hostRoots,
        guestRoots,
        writablePreopens,
        arguments,
        environmentKeys,
        environmentValues,
        allowedHosts,
        blockedHosts);
    if (!validateP3CallArgs(env, args)) {
        return nullptr;
    }
    const std::string exportNameValue = stringFrom(env, exportName, "exportName");
    const std::string argumentValue = stringFrom(env, argument, "argument");
    if (env->ExceptionCheck()) {
        return nullptr;
    }
    const char *result = nullptr;
    const char *error = api->callString(
        args.componentBytes.data(),
        args.componentBytes.size(),
        args.hostRoots.data(),
        args.guestRoots.data(),
        args.writablePreopens.empty() ? nullptr : args.writablePreopens.data(),
        args.hostRoots.size(),
        args.arguments.data(),
        args.arguments.size(),
        args.environmentKeys.data(),
        args.environmentValues.data(),
        args.environmentKeys.size(),
        exportNameValue.c_str(),
        argumentValue.c_str(),
        args.allowedHosts.data(),
        args.allowedHosts.size(),
        args.blockedHosts.data(),
        args.blockedHosts.size(),
        allowPrivateNetwork == JNI_TRUE ? 1 : 0,
        static_cast<std::uint64_t>(maxMemoryBytes),
        static_cast<std::uint64_t>(maxWasmStackBytes),
        static_cast<std::int64_t>(maxTableElements),
        static_cast<std::int64_t>(maxInstances),
        static_cast<std::int64_t>(maxTables),
        static_cast<std::int64_t>(maxMemories),
        static_cast<std::uint64_t>(executionTimeoutMillis),
        reinterpret_cast<const void *>(executionCancellationHandle),
        &result);
    if (error != nullptr) {
        throwEngine(env, error);
        return nullptr;
    }
    if (result == nullptr) {
        throwEngine(env, "Wasmtime Preview3 component call returned a null result");
        return nullptr;
    }
    return env->NewStringUTF(result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePreview3Native_nativeCommandRunUnavailableReason(
    JNIEnv *env,
    jclass,
    jbyteArray componentBytes,
    jobjectArray hostRoots,
    jobjectArray guestRoots,
    jbooleanArray writablePreopens,
    jobjectArray arguments,
    jobjectArray environmentKeys,
    jobjectArray environmentValues,
    jobjectArray allowedHosts,
    jobjectArray blockedHosts,
    jboolean allowPrivateNetwork,
    jlong maxMemoryBytes,
    jlong maxWasmStackBytes,
    jlong maxTableElements,
    jlong maxInstances,
    jlong maxTables,
    jlong maxMemories,
    jlong executionTimeoutMillis) {
    std::string loadError;
    P3BridgeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        return env->NewStringUTF(loadError.c_str());
    }
    P3CallArgs args = p3CallArgsFrom(
        env,
        componentBytes,
        hostRoots,
        guestRoots,
        writablePreopens,
        arguments,
        environmentKeys,
        environmentValues,
        allowedHosts,
        blockedHosts);
    if (!validateP3CallArgs(env, args)) {
        return nullptr;
    }
    const char *error = api->commandRunUnavailableReason(
        args.componentBytes.data(),
        args.componentBytes.size(),
        args.hostRoots.data(),
        args.guestRoots.data(),
        args.writablePreopens.empty() ? nullptr : args.writablePreopens.data(),
        args.hostRoots.size(),
        args.arguments.data(),
        args.arguments.size(),
        args.environmentKeys.data(),
        args.environmentValues.data(),
        args.environmentKeys.size(),
        args.allowedHosts.data(),
        args.allowedHosts.size(),
        args.blockedHosts.data(),
        args.blockedHosts.size(),
        allowPrivateNetwork == JNI_TRUE ? 1 : 0,
        static_cast<std::uint64_t>(maxMemoryBytes),
        static_cast<std::uint64_t>(maxWasmStackBytes),
        static_cast<std::int64_t>(maxTableElements),
        static_cast<std::int64_t>(maxInstances),
        static_cast<std::int64_t>(maxTables),
        static_cast<std::int64_t>(maxMemories),
        static_cast<std::uint64_t>(executionTimeoutMillis));
    return nullableString(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_uk_shusek_krwa_runtime_wasmtime_android_AndroidWasmtimePreview3Native_nativeCommandRunString(
    JNIEnv *env,
    jclass,
    jbyteArray componentBytes,
    jobjectArray hostRoots,
    jobjectArray guestRoots,
    jbooleanArray writablePreopens,
    jobjectArray arguments,
    jobjectArray environmentKeys,
    jobjectArray environmentValues,
    jbyteArray stdinBytes,
    jobjectArray allowedHosts,
    jobjectArray blockedHosts,
    jboolean allowPrivateNetwork,
    jlong maxMemoryBytes,
    jlong maxWasmStackBytes,
    jlong maxTableElements,
    jlong maxInstances,
    jlong maxTables,
    jlong maxMemories,
    jlong maxOutputBytes,
    jlong executionTimeoutMillis,
    jlong executionCancellationHandle) {
    std::string loadError;
    P3BridgeApi *api = loadApi(&loadError);
    if (api == nullptr) {
        throwEngine(env, loadError);
        return nullptr;
    }
    P3CallArgs args = p3CallArgsFrom(
        env,
        componentBytes,
        hostRoots,
        guestRoots,
        writablePreopens,
        arguments,
        environmentKeys,
        environmentValues,
        allowedHosts,
        blockedHosts);
    std::vector<std::uint8_t> stdin = bytesFrom(env, stdinBytes, "stdinBytes");
    if (!validateP3CallArgs(env, args) || env->ExceptionCheck()) {
        return nullptr;
    }
    const char *result = nullptr;
    const char *error = api->commandRunString(
        args.componentBytes.data(),
        args.componentBytes.size(),
        args.hostRoots.data(),
        args.guestRoots.data(),
        args.writablePreopens.empty() ? nullptr : args.writablePreopens.data(),
        args.hostRoots.size(),
        args.arguments.data(),
        args.arguments.size(),
        args.environmentKeys.data(),
        args.environmentValues.data(),
        args.environmentKeys.size(),
        stdin.empty() ? nullptr : stdin.data(),
        stdin.size(),
        args.allowedHosts.data(),
        args.allowedHosts.size(),
        args.blockedHosts.data(),
        args.blockedHosts.size(),
        allowPrivateNetwork == JNI_TRUE ? 1 : 0,
        static_cast<std::uint64_t>(maxMemoryBytes),
        static_cast<std::uint64_t>(maxWasmStackBytes),
        static_cast<std::int64_t>(maxTableElements),
        static_cast<std::int64_t>(maxInstances),
        static_cast<std::int64_t>(maxTables),
        static_cast<std::int64_t>(maxMemories),
        static_cast<std::uint64_t>(maxOutputBytes),
        static_cast<std::uint64_t>(executionTimeoutMillis),
        reinterpret_cast<const void *>(executionCancellationHandle),
        &result);
    if (error != nullptr) {
        throwEngine(env, error);
        return nullptr;
    }
    if (result == nullptr) {
        throwEngine(env, "Wasmtime Preview3 command returned a null result");
        return nullptr;
    }
    return env->NewStringUTF(result);
}
