package uk.shusek.krwa.runtime;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import uk.shusek.krwa.wasm.InvalidException;
import uk.shusek.krwa.wasm.UninstantiableException;
import uk.shusek.krwa.wasm.UnlinkableException;
import uk.shusek.krwa.wasm.WasmEngineException;
import uk.shusek.krwa.wasm.WasmModule;
import uk.shusek.krwa.wasm.types.DataSegment;
import uk.shusek.krwa.wasm.types.Export;
import uk.shusek.krwa.wasm.types.ExternalType;
import uk.shusek.krwa.wasm.types.FunctionImport;
import uk.shusek.krwa.wasm.types.FunctionType;
import uk.shusek.krwa.wasm.types.Import;
import uk.shusek.krwa.wasm.types.ValType;

public final class WasmtimePulleyExecution implements PlatformInstanceExecution {
    private static final ValueLayout.OfByte C_BOOL = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat C_FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfDouble C_DOUBLE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfByte C_BYTE = ValueLayout.JAVA_BYTE;
    private static final ValueLayout.OfShort C_SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final long WASMTIME_VAL_SIZE = 32;
    private static final long WASMTIME_VAL_RAW_SIZE = 16;
    private static final long WASMTIME_EXTERN_SIZE = 32;
    private static final long WASMTIME_FUNC_SIZE = 16;
    private static final long WASMTIME_MEMORY_SIZE = 24;
    private static final int WASMTIME_EXTERN_FUNC = 0;
    private static final int WASMTIME_EXTERN_MEMORY = 3;
    private static final int WASM_I32 = 0;
    private static final int WASM_I64 = 1;
    private static final int WASM_F32 = 2;
    private static final int WASM_F64 = 3;
    private static final int WASM_MAGIC_AND_VERSION_SIZE = 8;
    private static final int WASM_EXPORT_SECTION_ID = 7;
    private static final String SYNTHETIC_MEMORY_EXPORT_PREFIX = "__krwa_memory_";
    private static final String DEFAULT_WASMTIME_TARGET = "pulley64";
    private static final long DEFAULT_MAX_MEMORY_BYTES = 256L * 1024L * 1024L;
    private static final long DEFAULT_MAX_WASM_STACK_BYTES = 512L * 1024L;
    private static final long DEFAULT_ASYNC_STACK_HEADROOM_BYTES = 512L * 1024L;
    private static final long UNLIMITED_RESOURCE_LIMIT = -1L;
    private static final long DEFAULT_MAX_INSTANCES = 1L;
    private static final long DEFAULT_MAX_TABLES = 128L;
    private static final long DEFAULT_MAX_MEMORIES = 16L;
    private static final String[] COMPONENT_WASI_SYMBOLS = {
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

    private static final AtomicLong NEXT_HOST_CALLBACK_ID = new AtomicLong();
    private static final Map<Long, HostCallback> HOST_CALLBACKS = new ConcurrentHashMap<>();
    private static final Map<Long, Throwable> HOST_CALLBACK_FAILURES = new ConcurrentHashMap<>();
    private static volatile MethodHandle trapNewHandle;

    private final Arena arena;
    private final WasmtimeApi api;
    private final MemorySegment engine;
    private final MemorySegment module;
    private final MemorySegment store;
    private final MemorySegment context;
    private final MemorySegment instance;
    private final Map<String, FunctionExport> functionsByName;
    private final List<Long> callbackIds;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Map<String, Memory> memoriesByName = new HashMap<>();
    private final List<Memory> memoriesByIndex = new ArrayList<>();

    private WasmtimePulleyExecution(
            Arena arena,
            WasmtimeApi api,
            MemorySegment engine,
            MemorySegment module,
            MemorySegment store,
            MemorySegment context,
            MemorySegment instance,
            Map<String, FunctionExport> functionsByName,
            List<Long> callbackIds
    ) {
        this.arena = arena;
        this.api = api;
        this.engine = engine;
        this.module = module;
        this.store = store;
        this.context = context;
        this.instance = instance;
        this.functionsByName = functionsByName;
        this.callbackIds = callbackIds;
    }

    public static String unavailableReason() {
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeApi.load(arena);
            return null;
        } catch (IllegalCallerException e) {
            return "Wasmtime Pulley execution needs JVM native access enabled " +
                    "(for example --enable-native-access=ALL-UNNAMED)";
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return "Wasmtime Pulley execution is not linked on this JVM runtime: " + message;
        }
    }

    public static String unavailableReason(String targetName) {
        try (Arena arena = Arena.ofConfined()) {
            return createConfig(arena, normalizedTarget(targetName)).error;
        } catch (IllegalCallerException e) {
            return "Wasmtime Pulley execution needs JVM native access enabled " +
                    "(for example --enable-native-access=ALL-UNNAMED)";
        } catch (WasmEngineException e) {
            return e.getMessage();
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return "Wasmtime Pulley execution is not linked on this JVM runtime: " + message;
        }
    }

    public static String componentWasiUnavailableReason() {
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(WasmtimeApi.findLibrary(), arena);
            for (String symbol : COMPONENT_WASI_SYMBOLS) {
                if (lookup.find(symbol).isEmpty()) {
                    return "Wasmtime C API component/WASIp2 primitives are not linked: missing symbol " + symbol;
                }
            }
            return null;
        } catch (IllegalCallerException e) {
            return "Wasmtime C API component/WASIp2 primitive checks need JVM native access enabled " +
                    "(for example --enable-native-access=ALL-UNNAMED)";
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return "Wasmtime C API component/WASIp2 primitives are not linked on this JVM runtime: " + message;
        }
    }

    public static String preview3ComponentUnavailableReason(
            byte[] precompiledComponentBytes,
            String[] hostPreopenRoots,
            String[] guestPreopenRoots,
            boolean[] writablePreopens,
            String[] arguments,
            String[] environmentKeys,
            String[] environmentValues,
            String[] allowedHosts,
            String[] blockedHosts,
            boolean allowPrivateNetwork,
            String targetName,
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel
    ) {
        String normalizedTarget = normalizedTarget(targetName);
        if (!DEFAULT_WASMTIME_TARGET.equals(normalizedTarget)) {
            return "Wasmtime Preview3 component bridge supports only " + DEFAULT_WASMTIME_TARGET +
                    "; requested " + normalizedTarget;
        }
        String limitError = preview3LimitError(
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel
        );
        if (limitError != null) return limitError;
        if (
                hostPreopenRoots.length == 0 ||
                hostPreopenRoots.length != guestPreopenRoots.length ||
                        hostPreopenRoots.length != writablePreopens.length
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-empty preopen arrays";
        }
        if (
                arguments == null ||
                        environmentKeys == null ||
                        environmentValues == null ||
                        environmentKeys.length != environmentValues.length ||
                        allowedHosts == null ||
                        blockedHosts == null
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-null argument, environment, and network policy arrays";
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            MemorySegment componentBytes = arena.allocate(precompiledComponentBytes.length);
            componentBytes.copyFrom(MemorySegment.ofArray(precompiledComponentBytes));
            MemorySegment hostRoots = allocateCStringArray(arena, hostPreopenRoots);
            MemorySegment guestRoots = allocateCStringArray(arena, guestPreopenRoots);
            MemorySegment argumentValues = allocateCStringArray(arena, arguments);
            MemorySegment envKeys = allocateCStringArray(arena, environmentKeys);
            MemorySegment envValues = allocateCStringArray(arena, environmentValues);
            MemorySegment allowedHostValues = allocateCStringArray(arena, allowedHosts);
            MemorySegment blockedHostValues = allocateCStringArray(arena, blockedHosts);
            MemorySegment writable = arena.allocate(writablePreopens.length);
            for (int i = 0; i < writablePreopens.length; i++) {
                writable.set(C_BOOL, i, (byte) (writablePreopens[i] ? 1 : 0));
            }
            MemorySegment error = (MemorySegment) api.precompiledComponentInstantiateUnavailableReason.invokeExact(
                    componentBytes,
                    (long) precompiledComponentBytes.length,
                    hostRoots,
                    guestRoots,
                    writable,
                    (long) hostPreopenRoots.length,
                    argumentValues,
                    (long) arguments.length,
                    envKeys,
                    envValues,
                    (long) environmentKeys.length,
                    allowedHostValues,
                    (long) allowedHosts.length,
                    blockedHostValues,
                    (long) blockedHosts.length,
                    (byte) (allowPrivateNetwork ? 1 : 0),
                    maxMemoryBytes,
                    maxWasmStackBytes,
                    maxTableElements,
                    maxInstances,
                    maxTables,
                    maxMemories,
                    maxFuel
            );
            if (error.equals(MemorySegment.NULL)) {
                return null;
            }
            return error.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (IllegalCallerException e) {
            return "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                    "(for example --enable-native-access=ALL-UNNAMED)";
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return "Wasmtime Preview3 component bridge is not linked on this JVM runtime: " + message;
        }
    }

    public static String preview3ComponentCall0UnavailableReason(
            byte[] precompiledComponentBytes,
            String[] hostPreopenRoots,
            String[] guestPreopenRoots,
            boolean[] writablePreopens,
            String[] arguments,
            String[] environmentKeys,
            String[] environmentValues,
            String[] allowedHosts,
            String[] blockedHosts,
            boolean allowPrivateNetwork,
            String targetName,
            String exportName,
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel
    ) {
        String normalizedTarget = normalizedTarget(targetName);
        if (!DEFAULT_WASMTIME_TARGET.equals(normalizedTarget)) {
            return "Wasmtime Preview3 component bridge supports only " + DEFAULT_WASMTIME_TARGET +
                    "; requested " + normalizedTarget;
        }
        if (exportName == null || exportName.isBlank()) {
            return "Wasmtime Preview3 component export name must not be blank";
        }
        String limitError = preview3LimitError(
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel
        );
        if (limitError != null) return limitError;
        if (
                hostPreopenRoots.length == 0 ||
                hostPreopenRoots.length != guestPreopenRoots.length ||
                        hostPreopenRoots.length != writablePreopens.length
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-empty preopen arrays";
        }
        if (
                arguments == null ||
                        environmentKeys == null ||
                        environmentValues == null ||
                        environmentKeys.length != environmentValues.length ||
                        allowedHosts == null ||
                        blockedHosts == null
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-null argument, environment, and network policy arrays";
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            MemorySegment componentBytes = arena.allocate(precompiledComponentBytes.length);
            componentBytes.copyFrom(MemorySegment.ofArray(precompiledComponentBytes));
            MemorySegment hostRoots = allocateCStringArray(arena, hostPreopenRoots);
            MemorySegment guestRoots = allocateCStringArray(arena, guestPreopenRoots);
            MemorySegment argumentValues = allocateCStringArray(arena, arguments);
            MemorySegment envKeys = allocateCStringArray(arena, environmentKeys);
            MemorySegment envValues = allocateCStringArray(arena, environmentValues);
            MemorySegment allowedHostValues = allocateCStringArray(arena, allowedHosts);
            MemorySegment blockedHostValues = allocateCStringArray(arena, blockedHosts);
            MemorySegment writable = arena.allocate(writablePreopens.length);
            for (int i = 0; i < writablePreopens.length; i++) {
                writable.set(C_BOOL, i, (byte) (writablePreopens[i] ? 1 : 0));
            }
            MemorySegment error = (MemorySegment) api.precompiledComponentCall0UnavailableReason.invokeExact(
                    componentBytes,
                    (long) precompiledComponentBytes.length,
                    hostRoots,
                    guestRoots,
                    writable,
                    (long) hostPreopenRoots.length,
                    argumentValues,
                    (long) arguments.length,
                    envKeys,
                    envValues,
                    (long) environmentKeys.length,
                    arena.allocateFrom(exportName),
                    allowedHostValues,
                    (long) allowedHosts.length,
                    blockedHostValues,
                    (long) blockedHosts.length,
                    (byte) (allowPrivateNetwork ? 1 : 0),
                    maxMemoryBytes,
                    maxWasmStackBytes,
                    maxTableElements,
                    maxInstances,
                    maxTables,
                    maxMemories,
                    maxFuel
            );
            if (error.equals(MemorySegment.NULL)) {
                return null;
            }
            return error.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (IllegalCallerException e) {
            return "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                    "(for example --enable-native-access=ALL-UNNAMED)";
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return "Wasmtime Preview3 component bridge is not linked on this JVM runtime: " + message;
        }
    }

    public static String preview3ComponentCallS32UnavailableReason(
            byte[] precompiledComponentBytes,
            String[] hostPreopenRoots,
            String[] guestPreopenRoots,
            boolean[] writablePreopens,
            String[] arguments,
            String[] environmentKeys,
            String[] environmentValues,
            String[] allowedHosts,
            String[] blockedHosts,
            boolean allowPrivateNetwork,
            String targetName,
            String exportName,
            int argument,
            int expectedResult,
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel
    ) {
        String normalizedTarget = normalizedTarget(targetName);
        if (!DEFAULT_WASMTIME_TARGET.equals(normalizedTarget)) {
            return "Wasmtime Preview3 component bridge supports only " + DEFAULT_WASMTIME_TARGET +
                    "; requested " + normalizedTarget;
        }
        if (exportName == null || exportName.isBlank()) {
            return "Wasmtime Preview3 component export name must not be blank";
        }
        String limitError = preview3LimitError(
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel
        );
        if (limitError != null) return limitError;
        if (
                hostPreopenRoots.length == 0 ||
                hostPreopenRoots.length != guestPreopenRoots.length ||
                        hostPreopenRoots.length != writablePreopens.length
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-empty preopen arrays";
        }
        if (
                arguments == null ||
                        environmentKeys == null ||
                        environmentValues == null ||
                        environmentKeys.length != environmentValues.length ||
                        allowedHosts == null ||
                        blockedHosts == null
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-null argument, environment, and network policy arrays";
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            MemorySegment componentBytes = arena.allocate(precompiledComponentBytes.length);
            componentBytes.copyFrom(MemorySegment.ofArray(precompiledComponentBytes));
            MemorySegment hostRoots = allocateCStringArray(arena, hostPreopenRoots);
            MemorySegment guestRoots = allocateCStringArray(arena, guestPreopenRoots);
            MemorySegment argumentValues = allocateCStringArray(arena, arguments);
            MemorySegment envKeys = allocateCStringArray(arena, environmentKeys);
            MemorySegment envValues = allocateCStringArray(arena, environmentValues);
            MemorySegment allowedHostValues = allocateCStringArray(arena, allowedHosts);
            MemorySegment blockedHostValues = allocateCStringArray(arena, blockedHosts);
            MemorySegment writable = arena.allocate(writablePreopens.length);
            for (int i = 0; i < writablePreopens.length; i++) {
                writable.set(C_BOOL, i, (byte) (writablePreopens[i] ? 1 : 0));
            }
            MemorySegment error = (MemorySegment) api.precompiledComponentCallS32UnavailableReason.invokeExact(
                    componentBytes,
                    (long) precompiledComponentBytes.length,
                    hostRoots,
                    guestRoots,
                    writable,
                    (long) hostPreopenRoots.length,
                    argumentValues,
                    (long) arguments.length,
                    envKeys,
                    envValues,
                    (long) environmentKeys.length,
                    arena.allocateFrom(exportName),
                    argument,
                    expectedResult,
                    allowedHostValues,
                    (long) allowedHosts.length,
                    blockedHostValues,
                    (long) blockedHosts.length,
                    (byte) (allowPrivateNetwork ? 1 : 0),
                    maxMemoryBytes,
                    maxWasmStackBytes,
                    maxTableElements,
                    maxInstances,
                    maxTables,
                    maxMemories,
                    maxFuel
            );
            if (error.equals(MemorySegment.NULL)) {
                return null;
            }
            return error.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (IllegalCallerException e) {
            return "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                    "(for example --enable-native-access=ALL-UNNAMED)";
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return "Wasmtime Preview3 component bridge is not linked on this JVM runtime: " + message;
        }
    }

    public static String preview3ComponentCallStringUnavailableReason(
            byte[] precompiledComponentBytes,
            String[] hostPreopenRoots,
            String[] guestPreopenRoots,
            boolean[] writablePreopens,
            String[] arguments,
            String[] environmentKeys,
            String[] environmentValues,
            String[] allowedHosts,
            String[] blockedHosts,
            boolean allowPrivateNetwork,
            String targetName,
            String exportName,
            String argument,
            String expectedResult,
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel
    ) {
        String normalizedTarget = normalizedTarget(targetName);
        if (!DEFAULT_WASMTIME_TARGET.equals(normalizedTarget)) {
            return "Wasmtime Preview3 component bridge supports only " + DEFAULT_WASMTIME_TARGET +
                    "; requested " + normalizedTarget;
        }
        if (exportName == null || exportName.isBlank()) {
            return "Wasmtime Preview3 component export name must not be blank";
        }
        if (argument == null || expectedResult == null) {
            return "Wasmtime Preview3 component string probe requires non-null argument and expected result";
        }
        String limitError = preview3LimitError(
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel
        );
        if (limitError != null) return limitError;
        if (
                hostPreopenRoots.length == 0 ||
                hostPreopenRoots.length != guestPreopenRoots.length ||
                        hostPreopenRoots.length != writablePreopens.length
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-empty preopen arrays";
        }
        if (
                arguments == null ||
                        environmentKeys == null ||
                        environmentValues == null ||
                        environmentKeys.length != environmentValues.length ||
                        allowedHosts == null ||
                        blockedHosts == null
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-null argument, environment, and network policy arrays";
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            MemorySegment componentBytes = arena.allocate(precompiledComponentBytes.length);
            componentBytes.copyFrom(MemorySegment.ofArray(precompiledComponentBytes));
            MemorySegment hostRoots = allocateCStringArray(arena, hostPreopenRoots);
            MemorySegment guestRoots = allocateCStringArray(arena, guestPreopenRoots);
            MemorySegment argumentValues = allocateCStringArray(arena, arguments);
            MemorySegment envKeys = allocateCStringArray(arena, environmentKeys);
            MemorySegment envValues = allocateCStringArray(arena, environmentValues);
            MemorySegment allowedHostValues = allocateCStringArray(arena, allowedHosts);
            MemorySegment blockedHostValues = allocateCStringArray(arena, blockedHosts);
            MemorySegment writable = arena.allocate(writablePreopens.length);
            for (int i = 0; i < writablePreopens.length; i++) {
                writable.set(C_BOOL, i, (byte) (writablePreopens[i] ? 1 : 0));
            }
            MemorySegment error = (MemorySegment) api.precompiledComponentCallStringUnavailableReason.invokeExact(
                    componentBytes,
                    (long) precompiledComponentBytes.length,
                    hostRoots,
                    guestRoots,
                    writable,
                    (long) hostPreopenRoots.length,
                    argumentValues,
                    (long) arguments.length,
                    envKeys,
                    envValues,
                    (long) environmentKeys.length,
                    arena.allocateFrom(exportName),
                    arena.allocateFrom(argument),
                    arena.allocateFrom(expectedResult),
                    allowedHostValues,
                    (long) allowedHosts.length,
                    blockedHostValues,
                    (long) blockedHosts.length,
                    (byte) (allowPrivateNetwork ? 1 : 0),
                    maxMemoryBytes,
                    maxWasmStackBytes,
                    maxTableElements,
                    maxInstances,
                    maxTables,
                    maxMemories,
                    maxFuel
            );
            if (error.equals(MemorySegment.NULL)) {
                return null;
            }
            return error.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (IllegalCallerException e) {
            return "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                    "(for example --enable-native-access=ALL-UNNAMED)";
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return "Wasmtime Preview3 component bridge is not linked on this JVM runtime: " + message;
        }
    }

    public static long preview3ExecutionCancellationCreate() {
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            long handle = (long) api.executionCancellationCreate.invokeExact();
            if (handle == 0L) {
                throw new WasmEngineException("Wasmtime Preview3 cancellation handle allocation failed");
            }
            return handle;
        } catch (IllegalCallerException e) {
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                            "(for example --enable-native-access=ALL-UNNAMED)",
                    e
            );
        } catch (WasmEngineException e) {
            throw e;
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge is not linked on this JVM runtime: " + message,
                    e
            );
        }
    }

    public static void preview3ExecutionCancellationCancel(long handle) {
        if (handle == 0L) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            api.executionCancellationCancel.invokeExact(handle);
        } catch (IllegalCallerException e) {
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                            "(for example --enable-native-access=ALL-UNNAMED)",
                    e
            );
        } catch (Throwable e) {
            throw bridgeUnavailableException(e);
        }
    }

    public static boolean preview3ExecutionCancellationIsCancelled(long handle) {
        if (handle == 0L) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            byte cancelled = (byte) api.executionCancellationIsCancelled.invokeExact(handle);
            return cancelled != 0;
        } catch (IllegalCallerException e) {
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                            "(for example --enable-native-access=ALL-UNNAMED)",
                    e
            );
        } catch (Throwable e) {
            throw bridgeUnavailableException(e);
        }
    }

    public static void preview3ExecutionCancellationFree(long handle) {
        if (handle == 0L) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            api.executionCancellationFree.invokeExact(handle);
        } catch (IllegalCallerException e) {
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                            "(for example --enable-native-access=ALL-UNNAMED)",
                    e
            );
        } catch (Throwable e) {
            throw bridgeUnavailableException(e);
        }
    }

    public static String preview3ComponentCallString(
            byte[] precompiledComponentBytes,
            String[] hostPreopenRoots,
            String[] guestPreopenRoots,
            boolean[] writablePreopens,
            String[] arguments,
            String[] environmentKeys,
            String[] environmentValues,
            String[] allowedHosts,
            String[] blockedHosts,
            boolean allowPrivateNetwork,
            String targetName,
            String exportName,
            String argument,
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel,
            long executionTimeoutMillis
    ) {
        return preview3ComponentCallString(
                precompiledComponentBytes,
                hostPreopenRoots,
                guestPreopenRoots,
                writablePreopens,
                arguments,
                environmentKeys,
                environmentValues,
                allowedHosts,
                blockedHosts,
                allowPrivateNetwork,
                targetName,
                exportName,
                argument,
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel,
                executionTimeoutMillis,
                0L
        );
    }

    public static String preview3ComponentCallString(
            byte[] precompiledComponentBytes,
            String[] hostPreopenRoots,
            String[] guestPreopenRoots,
            boolean[] writablePreopens,
            String[] arguments,
            String[] environmentKeys,
            String[] environmentValues,
            String[] allowedHosts,
            String[] blockedHosts,
            boolean allowPrivateNetwork,
            String targetName,
            String exportName,
            String argument,
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel,
            long executionTimeoutMillis,
            long executionCancellationHandle
    ) {
        String normalizedTarget = normalizedTarget(targetName);
        if (!DEFAULT_WASMTIME_TARGET.equals(normalizedTarget)) {
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge supports only " + DEFAULT_WASMTIME_TARGET +
                            "; requested " + normalizedTarget
            );
        }
        if (exportName == null || exportName.isBlank()) {
            throw new WasmEngineException("Wasmtime Preview3 component export name must not be blank");
        }
        if (argument == null) {
            throw new WasmEngineException("Wasmtime Preview3 component string call requires non-null argument");
        }
        throwIfPreview3LimitError(
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel
        );
        if (executionTimeoutMillis < 0) {
            throw new WasmEngineException("Wasmtime Preview3 execution timeout millis must not be negative");
        }
        if (
                hostPreopenRoots.length == 0 ||
                hostPreopenRoots.length != guestPreopenRoots.length ||
                        hostPreopenRoots.length != writablePreopens.length
        ) {
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge requires matching non-empty preopen arrays"
            );
        }
        if (
                arguments == null ||
                        environmentKeys == null ||
                        environmentValues == null ||
                        environmentKeys.length != environmentValues.length ||
                        allowedHosts == null ||
                        blockedHosts == null
        ) {
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge requires matching non-null argument, environment, and network policy arrays"
            );
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            MemorySegment componentBytes = arena.allocate(precompiledComponentBytes.length);
            componentBytes.copyFrom(MemorySegment.ofArray(precompiledComponentBytes));
            MemorySegment hostRoots = allocateCStringArray(arena, hostPreopenRoots);
            MemorySegment guestRoots = allocateCStringArray(arena, guestPreopenRoots);
            MemorySegment argumentValues = allocateCStringArray(arena, arguments);
            MemorySegment envKeys = allocateCStringArray(arena, environmentKeys);
            MemorySegment envValues = allocateCStringArray(arena, environmentValues);
            MemorySegment allowedHostValues = allocateCStringArray(arena, allowedHosts);
            MemorySegment blockedHostValues = allocateCStringArray(arena, blockedHosts);
            MemorySegment writable = arena.allocate(writablePreopens.length);
            for (int i = 0; i < writablePreopens.length; i++) {
                writable.set(C_BOOL, i, (byte) (writablePreopens[i] ? 1 : 0));
            }
            MemorySegment resultOut = arena.allocate(ValueLayout.ADDRESS);
            resultOut.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            MemorySegment error = (MemorySegment) api.precompiledComponentCallString.invokeExact(
                    componentBytes,
                    (long) precompiledComponentBytes.length,
                    hostRoots,
                    guestRoots,
                    writable,
                    (long) hostPreopenRoots.length,
                    argumentValues,
                    (long) arguments.length,
                    envKeys,
                    envValues,
                    (long) environmentKeys.length,
                    arena.allocateFrom(exportName),
                    arena.allocateFrom(argument),
                    allowedHostValues,
                    (long) allowedHosts.length,
                    blockedHostValues,
                    (long) blockedHosts.length,
                    (byte) (allowPrivateNetwork ? 1 : 0),
                    maxMemoryBytes,
                    maxWasmStackBytes,
                    maxTableElements,
                    maxInstances,
                    maxTables,
                    maxMemories,
                    maxFuel,
                    executionTimeoutMillis,
                    executionCancellationHandle,
                    resultOut
            );
            if (!error.equals(MemorySegment.NULL)) {
                throw new WasmEngineException(error.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8));
            }
            MemorySegment result = resultOut.get(ValueLayout.ADDRESS, 0);
            if (result.equals(MemorySegment.NULL)) {
                throw new WasmEngineException("Wasmtime Preview3 component bridge returned null result");
            }
            return result.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (IllegalCallerException e) {
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                            "(for example --enable-native-access=ALL-UNNAMED)",
                    e
            );
        } catch (WasmEngineException e) {
            throw e;
        } catch (Throwable e) {
            throw bridgeUnavailableException(e);
        }
    }

    public static String preview3CommandRunUnavailableReason(
            byte[] precompiledComponentBytes,
            String[] hostPreopenRoots,
            String[] guestPreopenRoots,
            boolean[] writablePreopens,
            String[] arguments,
            String[] environmentKeys,
            String[] environmentValues,
            String[] allowedHosts,
            String[] blockedHosts,
            boolean allowPrivateNetwork,
            String targetName,
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel,
            long executionTimeoutMillis
    ) {
        String normalizedTarget = normalizedTarget(targetName);
        if (!DEFAULT_WASMTIME_TARGET.equals(normalizedTarget)) {
            return "Wasmtime Preview3 component bridge supports only " + DEFAULT_WASMTIME_TARGET +
                    "; requested " + normalizedTarget;
        }
        String limitError = preview3LimitError(
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel
        );
        if (limitError != null) return limitError;
        if (executionTimeoutMillis < 0) {
            return "Wasmtime Preview3 execution timeout millis must not be negative";
        }
        if (
                hostPreopenRoots.length == 0 ||
                hostPreopenRoots.length != guestPreopenRoots.length ||
                        hostPreopenRoots.length != writablePreopens.length
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-empty preopen arrays";
        }
        if (
                arguments == null ||
                        environmentKeys == null ||
                        environmentValues == null ||
                        environmentKeys.length != environmentValues.length ||
                        allowedHosts == null ||
                        blockedHosts == null
        ) {
            return "Wasmtime Preview3 component bridge requires matching non-null argument, environment, and network policy arrays";
        }
        try (Arena arena = Arena.ofConfined()) {
            WasmtimeP3BridgeApi api = WasmtimeP3BridgeApi.shared();
            MemorySegment componentBytes = arena.allocate(precompiledComponentBytes.length);
            componentBytes.copyFrom(MemorySegment.ofArray(precompiledComponentBytes));
            MemorySegment hostRoots = allocateCStringArray(arena, hostPreopenRoots);
            MemorySegment guestRoots = allocateCStringArray(arena, guestPreopenRoots);
            MemorySegment argumentValues = allocateCStringArray(arena, arguments);
            MemorySegment envKeys = allocateCStringArray(arena, environmentKeys);
            MemorySegment envValues = allocateCStringArray(arena, environmentValues);
            MemorySegment allowedHostValues = allocateCStringArray(arena, allowedHosts);
            MemorySegment blockedHostValues = allocateCStringArray(arena, blockedHosts);
            MemorySegment writable = arena.allocate(writablePreopens.length);
            for (int i = 0; i < writablePreopens.length; i++) {
                writable.set(C_BOOL, i, (byte) (writablePreopens[i] ? 1 : 0));
            }
            MemorySegment error = (MemorySegment) api.precompiledCommandRunUnavailableReason.invokeExact(
                    componentBytes,
                    (long) precompiledComponentBytes.length,
                    hostRoots,
                    guestRoots,
                    writable,
                    (long) hostPreopenRoots.length,
                    argumentValues,
                    (long) arguments.length,
                    envKeys,
                    envValues,
                    (long) environmentKeys.length,
                    allowedHostValues,
                    (long) allowedHosts.length,
                    blockedHostValues,
                    (long) blockedHosts.length,
                    (byte) (allowPrivateNetwork ? 1 : 0),
                    maxMemoryBytes,
                    maxWasmStackBytes,
                    maxTableElements,
                    maxInstances,
                    maxTables,
                    maxMemories,
                    maxFuel,
                    executionTimeoutMillis
            );
            if (error.equals(MemorySegment.NULL)) {
                return null;
            }
            return error.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
        } catch (IllegalCallerException e) {
            return "Wasmtime Preview3 component bridge needs JVM native access enabled " +
                    "(for example --enable-native-access=ALL-UNNAMED)";
        } catch (Throwable e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return "Wasmtime Preview3 component bridge is not linked on this JVM runtime: " + message;
        }
    }

    private static MemorySegment allocateCStringArray(Arena arena, String[] values) {
        MemorySegment array = arena.allocate(ValueLayout.ADDRESS.byteSize() * values.length);
        for (int i = 0; i < values.length; i++) {
            array.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(values[i]));
        }
        return array;
    }

    public static PlatformInstanceExecution create(WasmModule module, ImportValues imports, Instance hostInstance) {
        return create(module, imports, hostInstance, DEFAULT_WASMTIME_TARGET, null);
    }

    public static PlatformInstanceExecution create(
            WasmModule module,
            ImportValues imports,
            Instance hostInstance,
            String targetName,
            byte[] precompiledModuleBytes
    ) {
        return create(
                module,
                imports,
                hostInstance,
                targetName,
                precompiledModuleBytes,
                DEFAULT_MAX_MEMORY_BYTES,
                DEFAULT_MAX_WASM_STACK_BYTES,
                UNLIMITED_RESOURCE_LIMIT,
                DEFAULT_MAX_INSTANCES,
                DEFAULT_MAX_TABLES,
                DEFAULT_MAX_MEMORIES,
                UNLIMITED_RESOURCE_LIMIT
        );
    }

    public static PlatformInstanceExecution create(
            WasmModule module,
            ImportValues imports,
            Instance hostInstance,
            String targetName,
            byte[] precompiledModuleBytes,
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel
    ) {
        if (maxMemoryBytes <= 0) {
            throw new WasmEngineException("Wasmtime max memory bytes must be positive");
        }
        if (maxWasmStackBytes <= 0) {
            throw new WasmEngineException("Wasmtime max Wasm stack bytes must be positive");
        }
        validateResourceLimit("max table elements", maxTableElements);
        validateResourceLimit("max instances", maxInstances);
        validateResourceLimit("max tables", maxTables);
        validateResourceLimit("max memories", maxMemories);
        validateResourceLimit("max fuel", maxFuel);
        String target = normalizedTarget(targetName);
        byte[] bytes = module.originalBytes();
        if (bytes == null) {
            throw new WasmEngineException("Wasmtime Pulley execution needs original module bytes");
        }

        Arena arena = Arena.ofShared();
        try {
            WasmtimeApi api = WasmtimeApi.load(arena);
            trapNewHandle = api.trapNew;

            ConfigResult config = createConfig(api, arena, target, maxWasmStackBytes, maxFuel);
            if (config.error != null) {
                throw new WasmEngineException(config.error);
            }

            MemorySegment engine = (MemorySegment) api.wasmEngineNewWithConfig.invokeExact(config.config);
            requireNotNull(engine, "wasm_engine_new_with_config");

            PulleyModuleBytes pulleyModuleBytes = moduleBytesWithSyntheticMemoryExports(bytes, module);
            byte[] moduleBytes =
                    precompiledModuleBytes != null && pulleyModuleBytes.syntheticMemoryExports.isEmpty()
                            ? precompiledModuleBytes
                            : pulleyModuleBytes.bytes;
            boolean precompiledModule = moduleBytes == precompiledModuleBytes;
            MemorySegment wasmBytes = arena.allocate(moduleBytes.length);
            wasmBytes.copyFrom(MemorySegment.ofArray(moduleBytes));
            MemorySegment wasmtimeModule =
                    createWasmtimeModule(api, arena, engine, wasmBytes, moduleBytes.length, precompiledModule, target);

            MemorySegment store = (MemorySegment) api.storeNew.invokeExact(engine, MemorySegment.NULL, MemorySegment.NULL);
            requireNotNull(store, "wasmtime_store_new");
            api.storeLimiter.invokeExact(
                    store,
                    maxMemoryBytes,
                    maxTableElements,
                    maxInstances,
                    maxTables,
                    maxMemories
            );
            MemorySegment context = (MemorySegment) api.storeContext.invokeExact(store);
            configureFuel(api, context, maxFuel);

            List<Long> callbackIds = new ArrayList<>();
            MemorySegment importExterns = buildImports(api, arena, context, module, imports, hostInstance, callbackIds);
            MemorySegment instance = arena.allocate(16);
            MemorySegment trapOut = arena.allocate(ValueLayout.ADDRESS);
            long importCount = module.importSection().importCount();
            requireNoErrorOrInstantiationTrap(
                    api,
                    (MemorySegment) api.instanceNew.invokeExact(context, wasmtimeModule, importExterns, importCount, instance, trapOut),
                    trapOut,
                    "instantiate Pulley module"
            );

            WasmtimePulleyExecution execution = new WasmtimePulleyExecution(
                    arena,
                    api,
                    engine,
                    wasmtimeModule,
                    store,
                    context,
                    instance,
                    exportedFunctions(module),
                    callbackIds
            );
            execution.bindExportedFunctions();
            execution.bindExportedMemories(module, pulleyModuleBytes.syntheticMemoryExports);
            return execution;
        } catch (WasmEngineException e) {
            arena.close();
            throw e;
        } catch (IllegalCallerException e) {
            arena.close();
            throw new WasmEngineException(
                    "Wasmtime Pulley execution needs JVM native access enabled " +
                            "(for example --enable-native-access=ALL-UNNAMED)",
                    e
            );
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            arena.close();
            throw new WasmEngineException("Wasmtime Pulley execution is not linked on this JVM runtime", e);
        } catch (Throwable e) {
            arena.close();
            throw new WasmEngineException("Wasmtime Pulley execution failed", e);
        }
    }

    private static String normalizedTarget(String targetName) {
        if (targetName == null || targetName.isBlank()) {
            return DEFAULT_WASMTIME_TARGET;
        }
        return targetName.trim();
    }

    private static WasmEngineException bridgeUnavailableException(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        return new WasmEngineException(
                "Wasmtime Preview3 component bridge is not linked on this JVM runtime: " + message,
                e
        );
    }

    private static String preview3LimitError(
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel
    ) {
        if (maxMemoryBytes <= 0) {
            return "Wasmtime Preview3 max memory bytes must be positive";
        }
        if (maxWasmStackBytes <= 0) {
            return "Wasmtime Preview3 max Wasm stack bytes must be positive";
        }
        if (maxTableElements < UNLIMITED_RESOURCE_LIMIT ||
                maxInstances < UNLIMITED_RESOURCE_LIMIT ||
                maxTables < UNLIMITED_RESOURCE_LIMIT ||
                maxMemories < UNLIMITED_RESOURCE_LIMIT ||
                maxFuel < UNLIMITED_RESOURCE_LIMIT) {
            return "Wasmtime Preview3 resource limits must be " + UNLIMITED_RESOURCE_LIMIT +
                    " for unlimited or non-negative";
        }
        return null;
    }

    private static void throwIfPreview3LimitError(
            long maxMemoryBytes,
            long maxWasmStackBytes,
            long maxTableElements,
            long maxInstances,
            long maxTables,
            long maxMemories,
            long maxFuel
    ) {
        String error = preview3LimitError(
                maxMemoryBytes,
                maxWasmStackBytes,
                maxTableElements,
                maxInstances,
                maxTables,
                maxMemories,
                maxFuel
        );
        if (error != null) {
            throw new WasmEngineException(error);
        }
    }

    private static ConfigResult createConfig(Arena arena, String targetName) throws Throwable {
        WasmtimeApi api = WasmtimeApi.load(arena);
        return createConfig(api, arena, targetName, DEFAULT_MAX_WASM_STACK_BYTES, UNLIMITED_RESOURCE_LIMIT);
    }

    private static ConfigResult createConfig(
            WasmtimeApi api,
            Arena arena,
            String targetName,
            long maxWasmStackBytes,
            long maxFuel
    ) throws Throwable {
        MemorySegment config = (MemorySegment) api.wasmConfigNew.invokeExact();
        requireNotNull(config, "wasm_config_new");
        String error = configureTarget(api, arena, config, targetName);
        api.configAsyncStackSizeSet.invokeExact(config, asyncStackSizeBytes(maxWasmStackBytes));
        api.configMaxWasmStackSet.invokeExact(config, maxWasmStackBytes);
        api.configWasmGcSet.invokeExact(config, (byte) 1);
        api.configWasmFunctionReferencesSet.invokeExact(config, (byte) 1);
        api.configWasmReferenceTypesSet.invokeExact(config, (byte) 1);
        api.configWasmExceptionsSet.invokeExact(config, (byte) 1);
        api.configWasmBulkMemorySet.invokeExact(config, (byte) 1);
        api.configWasmMultiMemorySet.invokeExact(config, (byte) 1);
        api.configMemoryMayMoveSet.invokeExact(config, (byte) 1);
        api.configConcurrencySupportSet.invokeExact(config, (byte) 0);
        api.configConsumeFuelSet.invokeExact(config, (byte) (maxFuel == UNLIMITED_RESOURCE_LIMIT ? 0 : 1));
        return new ConfigResult(config, error);
    }

    private static void configureFuel(
            WasmtimeApi api,
            MemorySegment context,
            long maxFuel
    ) throws Throwable {
        if (maxFuel == UNLIMITED_RESOURCE_LIMIT) {
            return;
        }
        requireNoError(
                api,
                (MemorySegment) api.contextSetFuel.invokeExact(context, maxFuel),
                "set Wasmtime fuel"
        );
    }

    private static void validateResourceLimit(String name, long value) {
        if (value < UNLIMITED_RESOURCE_LIMIT) {
            throw new WasmEngineException(
                    "Wasmtime " + name + " must be " + UNLIMITED_RESOURCE_LIMIT +
                            " for unlimited or non-negative"
            );
        }
    }

    private static String configureTarget(
            WasmtimeApi api,
            Arena arena,
            MemorySegment config,
            String targetName
    ) throws Throwable {
        if (targetName.isEmpty() || targetName.equals("native") || targetName.equals("cranelift")) {
            return null;
        }
        MemorySegment target = arena.allocateFrom(targetName);
        MemorySegment error = (MemorySegment) api.configTargetSet.invokeExact(config, target);
        if (error == MemorySegment.NULL) {
            return null;
        }
        return "Wasmtime Pulley execution is not linked on this JVM runtime: " +
                "wasmtime_config_target_set(" + targetName + ") failed";
    }

    private static MemorySegment createWasmtimeModule(
            WasmtimeApi api,
            Arena arena,
            MemorySegment engine,
            MemorySegment moduleBytes,
            int moduleSize,
            boolean precompiledModule,
            String targetName
    ) throws Throwable {
        MemorySegment moduleOut = arena.allocate(ValueLayout.ADDRESS);
        MemorySegment error;
        if (precompiledModule) {
            error = (MemorySegment) api.moduleDeserialize.invokeExact(engine, moduleBytes, (long) moduleSize, moduleOut);
        } else {
            error = (MemorySegment) api.moduleNew.invokeExact(engine, moduleBytes, (long) moduleSize, moduleOut);
        }
        String operation = precompiledModule ? "deserialize precompiled module" : "compile module";
        requireNoError(api, error, operation + " for target " + targetName);
        return moduleOut.get(ValueLayout.ADDRESS, 0);
    }

    private static final class ConfigResult {
        final MemorySegment config;
        final String error;

        ConfigResult(MemorySegment config, String error) {
            this.config = config;
            this.error = error;
        }
    }

    @Override
    public ExecutionBackend getBackend() {
        return ExecutionBackend.PULLEY;
    }

    @Override
    public ExportFunction export(String name) {
        FunctionExport export = functionsByName.get(name);
        if (export == null) {
            throw new InvalidException("Unknown export with name " + name);
        }
        return args -> call(export, args);
    }

    @Override
    public FunctionType exportType(String name) {
        FunctionExport export = functionsByName.get(name);
        if (export == null) {
            throw new InvalidException("Unknown export with name " + name);
        }
        return export.type;
    }

    @Override
    public Memory memory(String name) {
        Memory memory = memoriesByName.get(name);
        if (memory == null) {
            throw new InvalidException("Unknown export with name " + name);
        }
        return memory;
    }

    @Override
    public Memory memory(int index) {
        if (index < 0 || index >= memoriesByIndex.size()) {
            return null;
        }
        return memoriesByIndex.get(index);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (long callbackId : callbackIds) {
            HOST_CALLBACKS.remove(callbackId);
            HOST_CALLBACK_FAILURES.remove(callbackId);
        }
        try {
            api.storeDelete.invokeExact(store);
            api.moduleDelete.invokeExact(module);
            api.engineDelete.invokeExact(engine);
        } catch (Throwable error) {
            throw new WasmEngineException("failed to close Wasmtime Pulley execution", error);
        } finally {
            arena.close();
        }
    }

    private long[] call(FunctionExport export, long[] args) {
        FunctionType type = export.type;
        if (args.length != type.params().size()) {
            throw new WasmEngineException(
                    "wrong number of arguments: expected " + type.params().size() + ", got " + args.length
            );
        }
        try (Arena callArena = Arena.ofConfined()) {
            int valueSlotCount = Math.max(type.params().size(), type.returns().size());
            int rawSlotCount = Math.max(1, valueSlotCount);
            MemorySegment raw = callArena.allocate(rawSlotCount * WASMTIME_VAL_RAW_SIZE);
            writeRawValues(raw, type.params(), args);
            MemorySegment trapOut = callArena.allocate(ValueLayout.ADDRESS);
            clearHostCallbackFailures();
            try {
                requireNoErrorOrTrap(
                        api,
                        (MemorySegment) api.funcCallUnchecked.invokeExact(context, export.func, raw, (long) rawSlotCount, trapOut),
                        trapOut,
                        "call Pulley export " + export.name
                );
            } catch (TrapException e) {
                Throwable hostFailure = takeHostCallbackFailure();
                if (hostFailure != null) {
                    throw hostFailure(hostFailure);
                }
                throw e;
            } catch (WasmEngineException e) {
                Throwable hostFailure = takeHostCallbackFailure();
                if (hostFailure != null) {
                    throw hostFailure(hostFailure);
                }
                throw e;
            } finally {
                clearHostCallbackFailures();
            }
            return readRawValues(raw, type.returns());
        } catch (WasmEngineException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable e) {
            throw new WasmEngineException("Wasmtime Pulley export call failed", e);
        }
    }

    private void bindExportedMemories(WasmModule module, Map<Integer, String> syntheticMemoryExports) throws Throwable {
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            Export export = module.exportSection().getExport(i);
            if (export.exportType() != ExternalType.MEMORY) {
                continue;
            }
            bindMemoryExport(module, export.name(), export.index(), true);
        }
        for (Map.Entry<Integer, String> export : syntheticMemoryExports.entrySet()) {
            if (export.getKey() >= 0 && export.getKey() < memoriesByIndex.size() && memoriesByIndex.get(export.getKey()) != null) {
                continue;
            }
            bindMemoryExport(module, export.getValue(), export.getKey(), false);
        }
    }

    private void bindMemoryExport(
            WasmModule module,
            String name,
            int index,
            boolean exposeName
    ) throws Throwable {
        MemorySegment item = arena.allocate(WASMTIME_EXTERN_SIZE);
        MemorySegment nameSegment = arena.allocateFrom(name);
        long nameByteLength = name.getBytes(StandardCharsets.UTF_8).length;
        byte found = (byte) api.instanceExportGet.invokeExact(
                context,
                instance,
                nameSegment,
                nameByteLength,
                item
        );
        if (found == 0 || item.get(C_BYTE, 0) != WASMTIME_EXTERN_MEMORY) {
            return;
        }
        MemorySegment memoryRef = arena.allocate(WASMTIME_MEMORY_SIZE);
        memoryRef.copyFrom(item.asSlice(8, WASMTIME_MEMORY_SIZE));
        Memory memory = new WasmtimeMemory(api, context, memoryRef, exportedMemoryInitialPages(module, index));
        if (exposeName) {
            memoriesByName.put(name, memory);
        }
        while (memoriesByIndex.size() <= index) {
            memoriesByIndex.add(null);
        }
        memoriesByIndex.set(index, memory);
    }

    private static int exportedMemoryInitialPages(WasmModule module, int index) {
        int importedMemoryCount = module.importSection().count(ExternalType.MEMORY);
        int definedIndex = index - importedMemoryCount;
        if (definedIndex < 0 || module.memorySection() == null || definedIndex >= module.memorySection().memoryCount()) {
            return 0;
        }
        return module.memorySection().getMemory(definedIndex).limits().initialPages();
    }

    private static PulleyModuleBytes moduleBytesWithSyntheticMemoryExports(byte[] bytes, WasmModule module) {
        Map<Integer, String> missingExports = missingDefinedMemoryExports(module);
        if (missingExports.isEmpty()) {
            return new PulleyModuleBytes(bytes, missingExports);
        }
        return new PulleyModuleBytes(insertSyntheticMemoryExports(bytes, missingExports), missingExports);
    }

    private static Map<Integer, String> missingDefinedMemoryExports(WasmModule module) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (module.memorySection() == null || module.memorySection().memoryCount() == 0) {
            return result;
        }
        int importedMemoryCount = module.importSection().count(ExternalType.MEMORY);
        Map<Integer, Boolean> exportedMemoryIndexes = new HashMap<>();
        List<String> exportNames = new ArrayList<>();
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            Export export = module.exportSection().getExport(i);
            exportNames.add(export.name());
            if (export.exportType() == ExternalType.MEMORY) {
                exportedMemoryIndexes.put(export.index(), true);
            }
        }
        for (int definedIndex = 0; definedIndex < module.memorySection().memoryCount(); definedIndex++) {
            int memoryIndex = importedMemoryCount + definedIndex;
            if (exportedMemoryIndexes.containsKey(memoryIndex)) {
                continue;
            }
            result.put(memoryIndex, uniqueSyntheticExportName(exportNames, memoryIndex));
            exportNames.add(result.get(memoryIndex));
        }
        return result;
    }

    private static String uniqueSyntheticExportName(List<String> exportNames, int memoryIndex) {
        String base = SYNTHETIC_MEMORY_EXPORT_PREFIX + memoryIndex;
        String name = base;
        int suffix = 1;
        while (exportNames.contains(name)) {
            name = base + "_" + suffix++;
        }
        return name;
    }

    private static byte[] insertSyntheticMemoryExports(byte[] bytes, Map<Integer, String> missingExports) {
        if (bytes.length < WASM_MAGIC_AND_VERSION_SIZE) {
            return bytes;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + missingExports.size() * 24);
        out.write(bytes, 0, WASM_MAGIC_AND_VERSION_SIZE);
        int position = WASM_MAGIC_AND_VERSION_SIZE;
        boolean wroteExportSection = false;
        while (position < bytes.length) {
            int sectionStart = position;
            int sectionId = bytes[position++] & 0xff;
            Leb sectionSize = readUnsignedLeb128(bytes, position);
            int bodyStart = sectionSize.nextPosition;
            int bodyEnd = bodyStart + sectionSize.value;
            if (bodyEnd > bytes.length) {
                return bytes;
            }
            if (!wroteExportSection && sectionId != 0 && sectionId > WASM_EXPORT_SECTION_ID) {
                writeExportSection(out, null, 0, 0, missingExports);
                wroteExportSection = true;
            }
            if (sectionId == WASM_EXPORT_SECTION_ID) {
                writeExportSection(out, bytes, bodyStart, bodyEnd, missingExports);
                wroteExportSection = true;
            } else {
                out.write(bytes, sectionStart, bodyEnd - sectionStart);
            }
            position = bodyEnd;
        }
        if (!wroteExportSection) {
            writeExportSection(out, null, 0, 0, missingExports);
        }
        return out.toByteArray();
    }

    private static void writeExportSection(
            ByteArrayOutputStream out,
            byte[] originalBytes,
            int bodyStart,
            int bodyEnd,
            Map<Integer, String> memoryExports
    ) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int originalExportCount = 0;
        int originalExportsStart = bodyEnd;
        if (originalBytes != null) {
            Leb count = readUnsignedLeb128(originalBytes, bodyStart);
            originalExportCount = count.value;
            originalExportsStart = count.nextPosition;
        }
        writeUnsignedLeb128(body, originalExportCount + memoryExports.size());
        if (originalBytes != null) {
            body.write(originalBytes, originalExportsStart, bodyEnd - originalExportsStart);
        }
        for (Map.Entry<Integer, String> memoryExport : memoryExports.entrySet()) {
            writeName(body, memoryExport.getValue());
            writeUnsignedLeb128(body, ExternalType.MEMORY.id());
            writeUnsignedLeb128(body, memoryExport.getKey());
        }
        out.write(WASM_EXPORT_SECTION_ID);
        writeUnsignedLeb128(out, body.size());
        out.writeBytes(body.toByteArray());
    }

    private static void writeName(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUnsignedLeb128(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeUnsignedLeb128(ByteArrayOutputStream out, int value) {
        long remaining = Integer.toUnsignedLong(value);
        do {
            int current = (int) (remaining & 0x7f);
            remaining >>>= 7;
            if (remaining != 0) {
                current |= 0x80;
            }
            out.write(current);
        } while (remaining != 0);
    }

    private static Leb readUnsignedLeb128(byte[] bytes, int position) {
        int result = 0;
        int shift = 0;
        int currentPosition = position;
        while (currentPosition < bytes.length) {
            int current = bytes[currentPosition++] & 0xff;
            result |= (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                return new Leb(result, currentPosition);
            }
            shift += 7;
        }
        return new Leb(0, currentPosition);
    }

    private void bindExportedFunctions() throws Throwable {
        for (FunctionExport export : functionsByName.values()) {
            MemorySegment item = arena.allocate(WASMTIME_EXTERN_SIZE);
            MemorySegment name = arena.allocateFrom(export.name);
            long nameByteLength = export.name.getBytes(StandardCharsets.UTF_8).length;
            byte found = (byte) api.instanceExportGet.invokeExact(
                    context,
                    instance,
                    name,
                    nameByteLength,
                    item
            );
            if (found == 0 || item.get(C_BYTE, 0) != WASMTIME_EXTERN_FUNC) {
                throw new InvalidException("Unknown function export with name " + export.name);
            }
            export.func = arena.allocate(WASMTIME_FUNC_SIZE);
            export.func.copyFrom(item.asSlice(8, WASMTIME_FUNC_SIZE));
        }
    }

    private static MemorySegment buildImports(
            WasmtimeApi api,
            Arena arena,
            MemorySegment context,
            WasmModule module,
            ImportValues imports,
            Instance hostInstance,
            List<Long> callbackIds
    ) throws Throwable {
        int count = module.importSection().importCount();
        if (count == 0) {
            return MemorySegment.NULL;
        }

        MemorySegment result = arena.allocate(count * WASMTIME_EXTERN_SIZE);
        MemorySegment callbackStub = api.linker.upcallStub(
                MethodHandles.lookup().findStatic(
                        WasmtimePulleyExecution.class,
                        "invokeHostFunction",
                        MethodType.methodType(
                                MemorySegment.class,
                                MemorySegment.class,
                                MemorySegment.class,
                                MemorySegment.class,
                                long.class
                        )
                ),
                FunctionDescriptor.of(
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS,
                        ValueLayout.JAVA_LONG
                ),
                arena
        );

        int functionImportIndex = 0;
        for (int i = 0; i < count; i++) {
            Import importValue = module.importSection().getImport(i);
            if (importValue.importType() != ExternalType.FUNCTION) {
                throw new UnlinkableException("Wasmtime Pulley currently supports function imports only: " + importValue);
            }
            FunctionImport functionImport = (FunctionImport) importValue;
            FunctionType expectedType = module.typeSection().getType(functionImport.typeIndex());
            ImportFunction hostFunction =
                    functionImportIndex < imports.functionCount()
                            ? imports.function(functionImportIndex++)
                            : null;
            if (hostFunction == null ||
                    !hostFunction.module().equals(importValue.module()) ||
                    !hostFunction.name().equals(importValue.name())) {
                throw new UnlinkableException(
                        "unknown native import, could not find import named " +
                                importValue.module() + "." + importValue.name()
                );
            }
            if (!expectedType.typesMatch(hostFunction.functionType())) {
                throw new UnlinkableException(
                        "incompatible native import type for function " +
                                importValue.module() + "." + importValue.name()
                );
            }
            if (hostFunction.handle() == null) {
                throw new UnlinkableException(
                        "native WebAssembly import " +
                                importValue.module() + "." + importValue.name() + " has no function handle"
                );
            }

            MemorySegment type = createFunctionType(api, arena, expectedType);
            long callbackId = NEXT_HOST_CALLBACK_ID.incrementAndGet();
            HOST_CALLBACKS.put(callbackId, new HostCallback(hostFunction, expectedType, hostInstance));
            callbackIds.add(callbackId);
            MemorySegment env = arena.allocate(C_LONG);
            env.set(C_LONG, 0, callbackId);
            MemorySegment func = arena.allocate(WASMTIME_FUNC_SIZE);
            api.funcNewUnchecked.invokeExact(context, type, callbackStub, env, MemorySegment.NULL, func);

            MemorySegment extern = result.asSlice(i * WASMTIME_EXTERN_SIZE, WASMTIME_EXTERN_SIZE);
            extern.set(C_BYTE, 0, (byte) WASMTIME_EXTERN_FUNC);
            extern.asSlice(8, WASMTIME_FUNC_SIZE).copyFrom(func);
        }
        return result;
    }

    private static MemorySegment invokeHostFunction(
            MemorySegment env,
            MemorySegment caller,
            MemorySegment argsAndResults,
            long numArgsAndResults
    ) {
        long callbackId = -1L;
        try {
            callbackId = env.reinterpret(Long.BYTES).get(C_LONG, 0);
            HostCallback callback = HOST_CALLBACKS.get(callbackId);
            if (callback == null) {
                return trap("unknown host callback " + callbackId);
            }
            MemorySegment raw = argsAndResults.reinterpret(Math.max(1, numArgsAndResults) * WASMTIME_VAL_RAW_SIZE);
            long[] args = readRawValues(raw, callback.type.params());
            long[] results = callback.function.handle().apply(callback.hostInstance, args);
            if (results == null) {
                results = new long[0];
            }
            if (results.length != callback.type.returns().size()) {
                return trap(
                        "host function " + callback.function.module() + "." + callback.function.name() +
                                " returned " + results.length + " values, expected " +
                                callback.type.returns().size()
                );
            }
            writeRawValues(raw, callback.type.returns(), results);
            return MemorySegment.NULL;
        } catch (Throwable e) {
            if (callbackId >= 0) {
                HOST_CALLBACK_FAILURES.put(callbackId, e);
            }
            return trap(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    private void clearHostCallbackFailures() {
        for (long callbackId : callbackIds) {
            HOST_CALLBACK_FAILURES.remove(callbackId);
        }
    }

    private Throwable takeHostCallbackFailure() {
        for (long callbackId : callbackIds) {
            Throwable failure = HOST_CALLBACK_FAILURES.remove(callbackId);
            if (failure != null) {
                return failure;
            }
        }
        return null;
    }

    private static long asyncStackSizeBytes(long maxWasmStackBytes) {
        if (maxWasmStackBytes > Long.MAX_VALUE - DEFAULT_ASYNC_STACK_HEADROOM_BYTES) {
            return Long.MAX_VALUE;
        }
        return maxWasmStackBytes + DEFAULT_ASYNC_STACK_HEADROOM_BYTES;
    }

    private static RuntimeException hostFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new WasmEngineException("Wasmtime host callback failed", failure);
    }

    private static MemorySegment trap(String message) {
        MethodHandle trapNew = trapNewHandle;
        if (trapNew == null) {
            return MemorySegment.NULL;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment text = arena.allocateFrom(message);
            long messageByteLength = message.getBytes(StandardCharsets.UTF_8).length;
            return (MemorySegment) trapNew.invokeExact(text, messageByteLength);
        } catch (Throwable ignored) {
            return MemorySegment.NULL;
        }
    }

    private static Map<String, FunctionExport> exportedFunctions(WasmModule module) {
        Map<String, FunctionExport> result = new HashMap<>();
        int importedFunctionCount = module.importSection().count(ExternalType.FUNCTION);
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            Export export = module.exportSection().getExport(i);
            if (export.exportType() != ExternalType.FUNCTION) {
                continue;
            }
            FunctionType type;
            if (export.index() < importedFunctionCount) {
                FunctionImport importValue = (FunctionImport) module.importSection().getImport(export.index());
                type = module.typeSection().getType(importValue.typeIndex());
            } else {
                type = module.functionSection().getFunctionType(export.index() - importedFunctionCount, module.typeSection());
            }
            result.put(export.name(), new FunctionExport(export.name(), export.index(), type));
        }
        return result;
    }

    private static MemorySegment createFunctionType(WasmtimeApi api, Arena arena, FunctionType type) throws Throwable {
        MemorySegment params = createValTypeVec(api, arena, type.params());
        MemorySegment results = createValTypeVec(api, arena, type.returns());
        return (MemorySegment) api.funcTypeNew.invokeExact(params, results);
    }

    private static MemorySegment createValTypeVec(WasmtimeApi api, Arena arena, List<ValType> types) throws Throwable {
        MemorySegment vec = arena.allocate(16);
        if (types.isEmpty()) {
            api.valTypeVecNewEmpty.invokeExact(vec);
            return vec;
        }
        MemorySegment values = arena.allocate(types.size() * ValueLayout.ADDRESS.byteSize());
        for (int i = 0; i < types.size(); i++) {
            MemorySegment valType = (MemorySegment) api.valTypeNew.invokeExact(wasmValKind(types.get(i)));
            values.set(ValueLayout.ADDRESS, i * ValueLayout.ADDRESS.byteSize(), valType);
        }
        api.valTypeVecNew.invokeExact(vec, (long) types.size(), values);
        return vec;
    }

    private static int wasmValKind(ValType type) {
        return switch (type.opcode()) {
            case ValType.ID.I32 -> WASM_I32;
            case ValType.ID.I64 -> WASM_I64;
            case ValType.ID.F32 -> WASM_F32;
            case ValType.ID.F64 -> WASM_F64;
            default -> throw new WasmEngineException("Wasmtime Pulley bridge supports numeric host boundary values only: " + type);
        };
    }

    private static void writeRawValues(MemorySegment raw, List<ValType> types, long[] values) {
        for (int i = 0; i < types.size(); i++) {
            long offset = i * WASMTIME_VAL_RAW_SIZE;
            ValType type = types.get(i);
            switch (type.opcode()) {
                case ValType.ID.I32, ValType.ID.F32 -> raw.set(C_INT, offset, (int) values[i]);
                case ValType.ID.I64, ValType.ID.F64 -> raw.set(C_LONG, offset, values[i]);
                default -> throw new WasmEngineException("Wasmtime Pulley bridge supports numeric values only: " + type);
            }
        }
    }

    private static long[] readRawValues(MemorySegment raw, List<ValType> types) {
        long[] result = new long[types.size()];
        for (int i = 0; i < types.size(); i++) {
            long offset = i * WASMTIME_VAL_RAW_SIZE;
            ValType type = types.get(i);
            result[i] = switch (type.opcode()) {
                case ValType.ID.I32, ValType.ID.F32 -> raw.get(C_INT, offset);
                case ValType.ID.I64, ValType.ID.F64 -> raw.get(C_LONG, offset);
                default -> throw new WasmEngineException("Wasmtime Pulley bridge supports numeric values only: " + type);
            };
        }
        return result;
    }

    private static void requireNotNull(MemorySegment segment, String label) {
        if (segment.equals(MemorySegment.NULL)) {
            throw new WasmEngineException(label + " returned null");
        }
    }

    private static void requireNoError(WasmtimeApi api, MemorySegment error, String label) throws Throwable {
        if (!error.equals(MemorySegment.NULL)) {
            throw new WasmEngineException(label + ": " + api.consumeErrorMessage(error));
        }
    }

    private static void requireNoErrorOrTrap(
            WasmtimeApi api,
            MemorySegment error,
            MemorySegment trapOut,
            String label
    ) throws Throwable {
        requireNoError(api, error, label);
        MemorySegment trap = trapOut.get(ValueLayout.ADDRESS, 0);
        if (!trap.equals(MemorySegment.NULL)) {
            throw new TrapException(label + ": " + api.consumeTrapMessage(trap));
        }
    }

    private static void requireNoErrorOrInstantiationTrap(
            WasmtimeApi api,
            MemorySegment error,
            MemorySegment trapOut,
            String label
    ) throws Throwable {
        requireNoError(api, error, label);
        MemorySegment trap = trapOut.get(ValueLayout.ADDRESS, 0);
        if (!trap.equals(MemorySegment.NULL)) {
            throw new UninstantiableException(label + ": " + api.consumeTrapMessage(trap));
        }
    }

    private static final class FunctionExport {
        private final String name;
        private final int index;
        private final FunctionType type;
        private MemorySegment func;

        private FunctionExport(String name, int index, FunctionType type) {
            this.name = name;
            this.index = index;
            this.type = type;
        }
    }

    private static final class HostCallback {
        private final ImportFunction function;
        private final FunctionType type;
        private final Instance hostInstance;

        private HostCallback(ImportFunction function, FunctionType type, Instance hostInstance) {
            this.function = function;
            this.type = type;
            this.hostInstance = hostInstance;
        }

        private ImportFunction function() {
            return function;
        }

        private FunctionType type() {
            return type;
        }

        private Instance hostInstance() {
            return hostInstance;
        }
    }

    private static final class PulleyModuleBytes {
        private final byte[] bytes;
        private final Map<Integer, String> syntheticMemoryExports;

        private PulleyModuleBytes(byte[] bytes, Map<Integer, String> syntheticMemoryExports) {
            this.bytes = bytes;
            this.syntheticMemoryExports = syntheticMemoryExports;
        }

        private byte[] bytes() {
            return bytes;
        }

        private Map<Integer, String> syntheticMemoryExports() {
            return syntheticMemoryExports;
        }
    }

    private static final class Leb {
        private final int value;
        private final int nextPosition;

        private Leb(int value, int nextPosition) {
            this.value = value;
            this.nextPosition = nextPosition;
        }

        private int value() {
            return value;
        }

        private int nextPosition() {
            return nextPosition;
        }
    }

    @SuppressWarnings("deprecation")
    private static final class WasmtimeMemory implements Memory {
        private final WasmtimeApi api;
        private final MemorySegment context;
        private final MemorySegment memory;
        private final int initialPages;
        private MemorySegment dataView;
        private long dataByteSize = -1L;

        private WasmtimeMemory(WasmtimeApi api, MemorySegment context, MemorySegment memory, int initialPages) {
            this.api = api;
            this.context = context;
            this.memory = memory;
            this.initialPages = initialPages;
        }

        @Override
        public int pages() {
            return Math.toIntExact(byteSize() / Memory.PAGE_SIZE);
        }

        @Override
        public int grow(int size) {
            try {
                MemorySegment previous = Arena.ofAuto().allocate(C_LONG);
                MemorySegment error = (MemorySegment) api.memoryGrow.invokeExact(context, memory, (long) size, previous);
                if (!error.equals(MemorySegment.NULL)) {
                    return -1;
                }
                refreshMemoryView();
                return Math.toIntExact(previous.get(C_LONG, 0));
            } catch (Throwable e) {
                throw new WasmEngineException("failed to grow Wasmtime memory", e);
            }
        }

        @Override
        public int initialPages() {
            return initialPages;
        }

        @Override
        public int maximumPages() {
            return Memory.RUNTIME_MAX_PAGES;
        }

        @Override
        public boolean shared() {
            return false;
        }

        @Override
        public Object lock(int address) {
            return this;
        }

        @Override
        public int waitOn(int address, int expected, long timeout) {
            throw new WasmEngineException("Attempt to wait on a non-shared memory, not supported.");
        }

        @Override
        public int waitOn(int address, long expected, long timeout) {
            throw new WasmEngineException("Attempt to wait on a non-shared memory, not supported.");
        }

        @Override
        public int notify(int address, int maxThreads) {
            return 0;
        }

        @Override
        public int atomicReadInt(int addr) {
            return readInt(addr);
        }

        @Override
        public long atomicReadLong(int addr) {
            return readLong(addr);
        }

        @Override
        public short atomicReadShort(int addr) {
            return readShort(addr);
        }

        @Override
        public byte atomicReadByte(int addr) {
            return read(addr);
        }

        @Override
        public void atomicWriteInt(int addr, int value) {
            writeI32(addr, value);
        }

        @Override
        public void atomicWriteLong(int addr, long value) {
            writeLong(addr, value);
        }

        @Override
        public void atomicWriteShort(int addr, short value) {
            writeShort(addr, value);
        }

        @Override
        public void atomicWriteByte(int addr, byte value) {
            writeByte(addr, value);
        }

        @Override
        public int atomicAddInt(int addr, int delta) {
            synchronized (this) {
                int previous = readInt(addr);
                writeI32(addr, previous + delta);
                return previous;
            }
        }

        @Override
        public int atomicAndInt(int addr, int mask) {
            synchronized (this) {
                int previous = readInt(addr);
                writeI32(addr, previous & mask);
                return previous;
            }
        }

        @Override
        public int atomicOrInt(int addr, int mask) {
            synchronized (this) {
                int previous = readInt(addr);
                writeI32(addr, previous | mask);
                return previous;
            }
        }

        @Override
        public int atomicXorInt(int addr, int mask) {
            synchronized (this) {
                int previous = readInt(addr);
                writeI32(addr, previous ^ mask);
                return previous;
            }
        }

        @Override
        public int atomicXchgInt(int addr, int value) {
            synchronized (this) {
                int previous = readInt(addr);
                writeI32(addr, value);
                return previous;
            }
        }

        @Override
        public int atomicCmpxchgInt(int addr, int expected, int replacement) {
            synchronized (this) {
                int previous = readInt(addr);
                if (previous == expected) {
                    writeI32(addr, replacement);
                }
                return previous;
            }
        }

        @Override
        public long atomicAddLong(int addr, long delta) {
            synchronized (this) {
                long previous = readLong(addr);
                writeLong(addr, previous + delta);
                return previous;
            }
        }

        @Override
        public long atomicAndLong(int addr, long mask) {
            synchronized (this) {
                long previous = readLong(addr);
                writeLong(addr, previous & mask);
                return previous;
            }
        }

        @Override
        public long atomicOrLong(int addr, long mask) {
            synchronized (this) {
                long previous = readLong(addr);
                writeLong(addr, previous | mask);
                return previous;
            }
        }

        @Override
        public long atomicXorLong(int addr, long mask) {
            synchronized (this) {
                long previous = readLong(addr);
                writeLong(addr, previous ^ mask);
                return previous;
            }
        }

        @Override
        public long atomicXchgLong(int addr, long value) {
            synchronized (this) {
                long previous = readLong(addr);
                writeLong(addr, value);
                return previous;
            }
        }

        @Override
        public long atomicCmpxchgLong(int addr, long expected, long replacement) {
            synchronized (this) {
                long previous = readLong(addr);
                if (previous == expected) {
                    writeLong(addr, replacement);
                }
                return previous;
            }
        }

        @Override
        public short atomicAddShort(int addr, short delta) {
            synchronized (this) {
                short previous = readShort(addr);
                writeShort(addr, (short) (previous + delta));
                return previous;
            }
        }

        @Override
        public short atomicAndShort(int addr, short mask) {
            synchronized (this) {
                short previous = readShort(addr);
                writeShort(addr, (short) (previous & mask));
                return previous;
            }
        }

        @Override
        public short atomicOrShort(int addr, short mask) {
            synchronized (this) {
                short previous = readShort(addr);
                writeShort(addr, (short) (previous | mask));
                return previous;
            }
        }

        @Override
        public short atomicXorShort(int addr, short mask) {
            synchronized (this) {
                short previous = readShort(addr);
                writeShort(addr, (short) (previous ^ mask));
                return previous;
            }
        }

        @Override
        public short atomicXchgShort(int addr, short value) {
            synchronized (this) {
                short previous = readShort(addr);
                writeShort(addr, value);
                return previous;
            }
        }

        @Override
        public short atomicCmpxchgShort(int addr, short expected, short replacement) {
            synchronized (this) {
                short previous = readShort(addr);
                if (previous == expected) {
                    writeShort(addr, replacement);
                }
                return previous;
            }
        }

        @Override
        public byte atomicAddByte(int addr, byte delta) {
            synchronized (this) {
                byte previous = read(addr);
                writeByte(addr, (byte) (previous + delta));
                return previous;
            }
        }

        @Override
        public byte atomicAndByte(int addr, byte mask) {
            synchronized (this) {
                byte previous = read(addr);
                writeByte(addr, (byte) (previous & mask));
                return previous;
            }
        }

        @Override
        public byte atomicOrByte(int addr, byte mask) {
            synchronized (this) {
                byte previous = read(addr);
                writeByte(addr, (byte) (previous | mask));
                return previous;
            }
        }

        @Override
        public byte atomicXorByte(int addr, byte mask) {
            synchronized (this) {
                byte previous = read(addr);
                writeByte(addr, (byte) (previous ^ mask));
                return previous;
            }
        }

        @Override
        public byte atomicXchgByte(int addr, byte value) {
            synchronized (this) {
                byte previous = read(addr);
                writeByte(addr, value);
                return previous;
            }
        }

        @Override
        public byte atomicCmpxchgByte(int addr, byte expected, byte replacement) {
            synchronized (this) {
                byte previous = read(addr);
                if (previous == expected) {
                    writeByte(addr, replacement);
                }
                return previous;
            }
        }

        @Override
        public void initialize(Instance instance, DataSegment[] dataSegments) {
            throw new WasmEngineException("Wasmtime owns native memory initialization");
        }

        @Override
        public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
            throw new WasmEngineException("Wasmtime owns passive data segments");
        }

        @Override
        public void write(int addr, byte[] data, int offset, int size) {
            checkedSlice(addr, size).copyFrom(MemorySegment.ofArray(data).asSlice(offset, size));
        }

        @Override
        public byte read(int addr) {
            return checkedSlice(addr, 1).get(C_BYTE, 0);
        }

        @Override
        public byte[] readBytes(int addr, int len) {
            byte[] result = new byte[len];
            MemorySegment.ofArray(result).copyFrom(checkedSlice(addr, len));
            return result;
        }

        @Override
        public void read(int addr, byte[] target, int offset, int size) {
            MemorySegment.ofArray(target).asSlice(offset, size).copyFrom(checkedSlice(addr, size));
        }

        @Override
        public void writeI32(int addr, int data) {
            checkedSlice(addr, Integer.BYTES).set(C_INT, 0, data);
        }

        @Override
        public int readInt(int addr) {
            return checkedSlice(addr, Integer.BYTES).get(C_INT, 0);
        }

        @Override
        public void writeLong(int addr, long data) {
            checkedSlice(addr, Long.BYTES).set(C_LONG, 0, data);
        }

        @Override
        public long readLong(int addr) {
            return checkedSlice(addr, Long.BYTES).get(C_LONG, 0);
        }

        @Override
        public void writeShort(int addr, short data) {
            checkedSlice(addr, Short.BYTES).set(C_SHORT, 0, data);
        }

        @Override
        public short readShort(int addr) {
            return checkedSlice(addr, Short.BYTES).get(C_SHORT, 0);
        }

        @Override
        public long readU16(int addr) {
            return readShort(addr) & 0xffffL;
        }

        @Override
        public void writeByte(int addr, byte data) {
            checkedSlice(addr, 1).set(C_BYTE, 0, data);
        }

        @Override
        public void writeF32(int addr, float data) {
            checkedSlice(addr, Float.BYTES).set(C_FLOAT, 0, data);
        }

        @Override
        public long readF32(int addr) {
            return Float.floatToRawIntBits(readFloat(addr));
        }

        @Override
        public float readFloat(int addr) {
            return checkedSlice(addr, Float.BYTES).get(C_FLOAT, 0);
        }

        @Override
        public void writeF64(int addr, double data) {
            checkedSlice(addr, Double.BYTES).set(C_DOUBLE, 0, data);
        }

        @Override
        public double readDouble(int addr) {
            return checkedSlice(addr, Double.BYTES).get(C_DOUBLE, 0);
        }

        @Override
        public long readF64(int addr) {
            return Double.doubleToRawLongBits(readDouble(addr));
        }

        @Override
        public void zero() {
            fill((byte) 0, 0, Math.toIntExact(byteSize()));
        }

        @Override
        public void fill(byte value, int fromIndex, int toIndex) {
            checkedSlice(fromIndex, toIndex - fromIndex).fill(value);
        }

        @Override
        public void drop(int segment) {
            throw new WasmEngineException("Wasmtime owns passive data segments");
        }

        private MemorySegment checkedSlice(int addr, int size) {
            if (addr < 0 || size < 0) {
                throw outOfBounds(addr, size);
            }
            MemorySegment data;
            long byteSize;
            try {
                data = dataView();
                byteSize = dataByteSize;
            } catch (Throwable e) {
                throw new WasmEngineException("failed to access Wasmtime memory", e);
            }
            long end = (long) addr + size;
            if (end < addr || end > byteSize) {
                try {
                    refreshMemoryView();
                    data = dataView;
                    byteSize = dataByteSize;
                } catch (Throwable e) {
                    throw new WasmEngineException("failed to access Wasmtime memory", e);
                }
            }
            if (end < addr || end > byteSize) {
                throw outOfBounds(addr, size);
            }
            return data.asSlice(addr, size);
        }

        private long byteSize() {
            try {
                refreshMemoryView();
                return dataByteSize;
            } catch (Throwable e) {
                throw new WasmEngineException("failed to read Wasmtime memory byte size", e);
            }
        }

        private MemorySegment dataView() throws Throwable {
            if (dataView == null) {
                refreshMemoryView();
            }
            return dataView;
        }

        private void refreshMemoryView() throws Throwable {
            try {
                long byteSize = (long) api.memoryDataSize.invokeExact(context, memory);
                MemorySegment data = (MemorySegment) api.memoryData.invokeExact(context, memory);
                dataView = data.reinterpret(byteSize);
                dataByteSize = byteSize;
            } catch (Throwable e) {
                throw new WasmEngineException("failed to refresh Wasmtime memory view", e);
            }
        }

        private RuntimeException outOfBounds(int addr, int size) {
            return new WasmRuntimeException(
                    "out of bounds memory access: attempted to access address: " +
                            addr + " but limit is: " + byteSize() + " and size: " + size
            );
        }
    }

    private static final class WasmtimeP3BridgeApi {
        private static volatile WasmtimeP3BridgeApi sharedApi;
        private static final String P3_BRIDGE_CANCELLATION_CREATE_SYMBOL =
                "krwa_wasmtime_p3_execution_cancellation_create";
        private static final String P3_BRIDGE_CANCELLATION_CANCEL_SYMBOL =
                "krwa_wasmtime_p3_execution_cancellation_cancel";
        private static final String P3_BRIDGE_CANCELLATION_IS_CANCELLED_SYMBOL =
                "krwa_wasmtime_p3_execution_cancellation_is_cancelled";
        private static final String P3_BRIDGE_CANCELLATION_FREE_SYMBOL =
                "krwa_wasmtime_p3_execution_cancellation_free";
        private static final String P3_BRIDGE_INSTANTIATE_SYMBOL =
                "krwa_wasmtime_p3_precompiled_component_instantiate_unavailable_reason";
        private static final String P3_BRIDGE_CALL0_SYMBOL =
                "krwa_wasmtime_p3_precompiled_component_call0_unavailable_reason";
        private static final String P3_BRIDGE_CALL_S32_SYMBOL =
                "krwa_wasmtime_p3_precompiled_component_call_s32_unavailable_reason";
        private static final String P3_BRIDGE_CALL_STRING_SYMBOL =
                "krwa_wasmtime_p3_precompiled_component_call_string_unavailable_reason";
        private static final String P3_BRIDGE_CALL_STRING_RESULT_SYMBOL =
                "krwa_wasmtime_p3_precompiled_component_call_string";
        private static final String P3_BRIDGE_COMMAND_RUN_SYMBOL =
                "krwa_wasmtime_p3_precompiled_command_run_unavailable_reason";

        private final MethodHandle executionCancellationCreate;
        private final MethodHandle executionCancellationCancel;
        private final MethodHandle executionCancellationIsCancelled;
        private final MethodHandle executionCancellationFree;
        private final MethodHandle precompiledComponentInstantiateUnavailableReason;
        private final MethodHandle precompiledComponentCall0UnavailableReason;
        private final MethodHandle precompiledComponentCallS32UnavailableReason;
        private final MethodHandle precompiledComponentCallStringUnavailableReason;
        private final MethodHandle precompiledComponentCallString;
        private final MethodHandle precompiledCommandRunUnavailableReason;

        private static WasmtimeP3BridgeApi shared() {
            WasmtimeP3BridgeApi result = sharedApi;
            if (result != null) {
                return result;
            }
            synchronized (WasmtimeP3BridgeApi.class) {
                result = sharedApi;
                if (result == null) {
                    SymbolLookup lookup =
                            SymbolLookup.libraryLookup(findLibrary(), Arena.global());
                    result = new WasmtimeP3BridgeApi(Linker.nativeLinker(), lookup);
                    sharedApi = result;
                }
                return result;
            }
        }

        private WasmtimeP3BridgeApi(Linker linker, SymbolLookup lookup) {
            executionCancellationCreate = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_CANCELLATION_CREATE_SYMBOL,
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG)
            );
            executionCancellationCancel = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_CANCELLATION_CANCEL_SYMBOL,
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)
            );
            executionCancellationIsCancelled = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_CANCELLATION_IS_CANCELLED_SYMBOL,
                    FunctionDescriptor.of(C_BOOL, ValueLayout.JAVA_LONG)
            );
            executionCancellationFree = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_CANCELLATION_FREE_SYMBOL,
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG)
            );
            precompiledComponentInstantiateUnavailableReason = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_INSTANTIATE_SYMBOL,
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            C_BOOL,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG
                    )
            );
            precompiledComponentCall0UnavailableReason = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_CALL0_SYMBOL,
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            C_BOOL,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG
                    )
            );
            precompiledComponentCallS32UnavailableReason = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_CALL_S32_SYMBOL,
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            C_BOOL,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG
                    )
            );
            precompiledComponentCallStringUnavailableReason = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_CALL_STRING_SYMBOL,
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            C_BOOL,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG
                    )
            );
            precompiledComponentCallString = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_CALL_STRING_RESULT_SYMBOL,
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            C_BOOL,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS
                    )
            );
            precompiledCommandRunUnavailableReason = downcall(
                    linker,
                    lookup,
                    P3_BRIDGE_COMMAND_RUN_SYMBOL,
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG,
                            C_BOOL,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG
                    )
            );
        }

        private static MethodHandle downcall(
                Linker linker,
                SymbolLookup lookup,
                String name,
                FunctionDescriptor descriptor
        ) {
            return linker.downcallHandle(lookup.findOrThrow(name), descriptor);
        }

        private static Path findLibrary() {
            String configured = System.getProperty("krwa.wasmtime.p3.bridge.library");
            if (configured == null || configured.isBlank()) {
                configured = System.getenv("KRWA_WASMTIME_P3_BRIDGE_LIBRARY");
            }
            List<Path> candidates = new ArrayList<>();
            if (configured != null && !configured.isBlank()) {
                candidates.add(Path.of(configured));
            }
            candidates.add(Path.of("modules/runtime/build/wasmtime-p3-bridge/target/release/libkrwa_wasmtime_p3_bridge.dylib"));
            candidates.add(Path.of("modules/runtime/build/wasmtime-p3-bridge/target/release/libkrwa_wasmtime_p3_bridge.so"));
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            String message =
                    candidates.stream()
                            .map(Path::toString)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("<none>");
            throw new WasmEngineException(
                    "Wasmtime Preview3 component bridge is not linked on this JVM runtime; checked " + message
            );
        }
    }

    private static final class WasmtimeApi {
        private final Linker linker;
        private final MethodHandle wasmConfigNew;
        private final MethodHandle wasmEngineNewWithConfig;
        private final MethodHandle configTargetSet;
        private final MethodHandle configWasmGcSet;
        private final MethodHandle configWasmFunctionReferencesSet;
        private final MethodHandle configWasmReferenceTypesSet;
        private final MethodHandle configWasmExceptionsSet;
        private final MethodHandle configWasmBulkMemorySet;
        private final MethodHandle configWasmMultiMemorySet;
        private final MethodHandle configAsyncStackSizeSet;
        private final MethodHandle configMaxWasmStackSet;
        private final MethodHandle configMemoryMayMoveSet;
        private final MethodHandle configConcurrencySupportSet;
        private final MethodHandle configConsumeFuelSet;
        private final MethodHandle moduleNew;
        private final MethodHandle moduleDeserialize;
        private final MethodHandle moduleDelete;
        private final MethodHandle engineDelete;
        private final MethodHandle storeNew;
        private final MethodHandle storeDelete;
        private final MethodHandle storeContext;
        private final MethodHandle storeLimiter;
        private final MethodHandle contextSetFuel;
        private final MethodHandle instanceNew;
        private final MethodHandle instanceExportGet;
        private final MethodHandle funcCallUnchecked;
        private final MethodHandle funcNewUnchecked;
        private final MethodHandle valTypeNew;
        private final MethodHandle valTypeVecNewEmpty;
        private final MethodHandle valTypeVecNew;
        private final MethodHandle funcTypeNew;
        private final MethodHandle errorMessage;
        private final MethodHandle errorDelete;
        private final MethodHandle trapMessage;
        private final MethodHandle trapDelete;
        private final MethodHandle byteVecDelete;
        private final MethodHandle trapNew;
        private final MethodHandle memoryData;
        private final MethodHandle memoryDataSize;
        private final MethodHandle memoryGrow;

        private WasmtimeApi(Linker linker, SymbolLookup lookup) {
            this.linker = linker;
            wasmConfigNew = downcall(linker, lookup, "wasm_config_new", FunctionDescriptor.of(ValueLayout.ADDRESS));
            wasmEngineNewWithConfig = downcall(linker, lookup, "wasm_engine_new_with_config", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            configTargetSet = downcall(linker, lookup, "wasmtime_config_target_set", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            configWasmGcSet = downcall(linker, lookup, "wasmtime_config_wasm_gc_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            configWasmFunctionReferencesSet = downcall(linker, lookup, "wasmtime_config_wasm_function_references_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            configWasmReferenceTypesSet = downcall(linker, lookup, "wasmtime_config_wasm_reference_types_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            configWasmExceptionsSet = downcall(linker, lookup, "wasmtime_config_wasm_exceptions_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            configWasmBulkMemorySet = downcall(linker, lookup, "wasmtime_config_wasm_bulk_memory_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            configWasmMultiMemorySet = downcall(linker, lookup, "wasmtime_config_wasm_multi_memory_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            configAsyncStackSizeSet = downcall(linker, lookup, "wasmtime_config_async_stack_size_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            configMaxWasmStackSet = downcall(linker, lookup, "wasmtime_config_max_wasm_stack_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            configMemoryMayMoveSet = downcall(linker, lookup, "wasmtime_config_memory_may_move_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            configConcurrencySupportSet = downcall(linker, lookup, "wasmtime_config_concurrency_support_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            configConsumeFuelSet = downcall(linker, lookup, "wasmtime_config_consume_fuel_set", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, C_BOOL));
            moduleNew = downcall(linker, lookup, "wasmtime_module_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            moduleDeserialize = downcall(linker, lookup, "wasmtime_module_deserialize", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            moduleDelete = downcall(linker, lookup, "wasmtime_module_delete", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            engineDelete = downcall(linker, lookup, "wasm_engine_delete", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            storeNew = downcall(linker, lookup, "wasmtime_store_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            storeDelete = downcall(linker, lookup, "wasmtime_store_delete", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            storeContext = downcall(linker, lookup, "wasmtime_store_context", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            storeLimiter = downcall(linker, lookup, "wasmtime_store_limiter", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            contextSetFuel = downcall(linker, lookup, "wasmtime_context_set_fuel", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            instanceNew = downcall(linker, lookup, "wasmtime_instance_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            instanceExportGet = downcall(linker, lookup, "wasmtime_instance_export_get", FunctionDescriptor.of(C_BOOL, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            funcCallUnchecked = downcall(linker, lookup, "wasmtime_func_call_unchecked", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            funcNewUnchecked = downcall(linker, lookup, "wasmtime_func_new_unchecked", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            valTypeNew = downcall(linker, lookup, "wasm_valtype_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            valTypeVecNewEmpty = downcall(linker, lookup, "wasm_valtype_vec_new_empty", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            valTypeVecNew = downcall(linker, lookup, "wasm_valtype_vec_new", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            funcTypeNew = downcall(linker, lookup, "wasm_functype_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            errorMessage = downcall(linker, lookup, "wasmtime_error_message", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            errorDelete = downcall(linker, lookup, "wasmtime_error_delete", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            trapMessage = downcall(linker, lookup, "wasm_trap_message", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            trapDelete = downcall(linker, lookup, "wasm_trap_delete", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            byteVecDelete = downcall(linker, lookup, "wasm_byte_vec_delete", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            trapNew = downcall(linker, lookup, "wasmtime_trap_new", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
            memoryData = downcall(linker, lookup, "wasmtime_memory_data", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            memoryDataSize = downcall(linker, lookup, "wasmtime_memory_data_size", FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            memoryGrow = downcall(linker, lookup, "wasmtime_memory_grow", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        }

        private static WasmtimeApi load(Arena arena) {
            Path library = findLibrary();
            SymbolLookup lookup = SymbolLookup.libraryLookup(library, arena);
            return new WasmtimeApi(Linker.nativeLinker(), lookup);
        }

        private static Path findLibrary() {
            String configured = System.getProperty("krwa.wasmtime.library");
            if (configured == null || configured.isBlank()) {
                configured = System.getenv("KRWA_WASMTIME_LIBRARY");
            }
            List<Path> candidates = new ArrayList<>();
            if (configured != null && !configured.isBlank()) {
                candidates.add(Path.of(configured));
            }
            candidates.add(Path.of("/opt/homebrew/opt/wasmtime/lib/libwasmtime.dylib"));
            candidates.add(Path.of("/usr/local/opt/wasmtime/lib/libwasmtime.dylib"));
            candidates.add(Path.of("/opt/homebrew/Cellar/wasmtime/47.0.2/lib/libwasmtime.dylib"));
            candidates.add(Path.of("/usr/local/lib/libwasmtime.dylib"));
            candidates.add(Path.of("/usr/lib/libwasmtime.so"));
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            String message =
                    candidates.stream()
                            .map(Path::toString)
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("<none>");
            throw new WasmEngineException(
                    "Wasmtime Pulley execution is not linked on this JVM runtime; checked " + message
            );
        }

        private String consumeErrorMessage(MemorySegment error) throws Throwable {
            try {
                return readMessage(error, errorMessage);
            } finally {
                errorDelete.invokeExact(error);
            }
        }

        private String consumeTrapMessage(MemorySegment trap) throws Throwable {
            try {
                return readMessage(trap, trapMessage);
            } finally {
                trapDelete.invokeExact(trap);
            }
        }

        private String readMessage(MemorySegment handle, MethodHandle writer) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment vec = arena.allocate(16);
                writer.invokeExact(handle, vec);
                long size = vec.get(ValueLayout.JAVA_LONG, 0);
                MemorySegment data = vec.get(ValueLayout.ADDRESS, 8).reinterpret(size);
                byte[] bytes = new byte[Math.toIntExact(size)];
                MemorySegment.ofArray(bytes).copyFrom(data);
                byteVecDelete.invokeExact(vec);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        }

        private static MethodHandle downcall(
                Linker linker,
                SymbolLookup lookup,
                String name,
                FunctionDescriptor descriptor
        ) {
            return linker.downcallHandle(lookup.findOrThrow(name), descriptor);
        }
    }
}
