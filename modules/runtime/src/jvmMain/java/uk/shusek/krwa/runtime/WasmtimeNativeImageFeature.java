package uk.shusek.krwa.runtime;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeForeignAccess;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Native Image registrations required by the JVM Wasmtime backend.
 *
 * <p>The descriptors use the same {@link ValueLayout} constants as the runtime call sites so
 * their ABI remains identical on every supported Native Image platform.</p>
 */
public final class WasmtimeNativeImageFeature implements Feature {
    private static final MemoryLayout ADDRESS = ValueLayout.ADDRESS;
    private static final MemoryLayout BOOL = ValueLayout.JAVA_BYTE;
    private static final MemoryLayout INT = ValueLayout.JAVA_INT;
    private static final MemoryLayout LONG = ValueLayout.JAVA_LONG;

    @Override
    public void duringSetup(DuringSetupAccess access) {
        registerReflectiveEntrypoints();
        downcallDescriptors().forEach(RuntimeForeignAccess::registerForDowncall);
        registerHostFunctionUpcall();
    }

    private static void registerReflectiveEntrypoints() {
        RuntimeReflection.register(WasmtimePulleyExecution.class);
        Method[] entrypoints = Arrays.stream(WasmtimePulleyExecution.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .toArray(Method[]::new);
        RuntimeReflection.register(entrypoints);
    }

    private static void registerHostFunctionUpcall() {
        FunctionDescriptor descriptor = FunctionDescriptor.of(
                ADDRESS,
                ADDRESS,
                ADDRESS,
                ADDRESS,
                LONG
        );
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    WasmtimePulleyExecution.class,
                    MethodHandles.lookup()
            );
            MethodHandle target = lookup.findStatic(
                    WasmtimePulleyExecution.class,
                    "invokeHostFunction",
                    descriptor.toMethodType()
            );
            RuntimeForeignAccess.registerForDirectUpcall(target, descriptor);
        } catch (NoSuchMethodException | IllegalAccessException exception) {
            throw new IllegalStateException("Cannot register the Wasmtime host callback", exception);
        }
    }

    private static List<FunctionDescriptor> downcallDescriptors() {
        return List.of(
                // Wasmtime C API used by WasmtimeApi.
                FunctionDescriptor.of(ADDRESS),
                FunctionDescriptor.of(ADDRESS, ADDRESS),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS),
                FunctionDescriptor.ofVoid(ADDRESS, BOOL),
                FunctionDescriptor.ofVoid(ADDRESS, LONG),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS),
                FunctionDescriptor.ofVoid(ADDRESS),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                FunctionDescriptor.ofVoid(ADDRESS, LONG, LONG, LONG, LONG, LONG),
                FunctionDescriptor.of(ADDRESS, ADDRESS, LONG),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS, ADDRESS),
                FunctionDescriptor.of(BOOL, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                FunctionDescriptor.of(ADDRESS, INT),
                FunctionDescriptor.ofVoid(ADDRESS, LONG, ADDRESS),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS),
                FunctionDescriptor.of(LONG, ADDRESS, ADDRESS),
                // KRWA Wasmtime Preview3 bridge.
                FunctionDescriptor.of(LONG),
                FunctionDescriptor.ofVoid(LONG),
                FunctionDescriptor.of(BOOL, LONG),
                FunctionDescriptor.of(
                        ADDRESS,
                        ADDRESS, LONG, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS, LONG,
                        ADDRESS, ADDRESS, LONG, ADDRESS, LONG, ADDRESS, LONG, BOOL,
                        LONG, LONG, LONG, LONG, LONG, LONG, LONG
                ),
                FunctionDescriptor.of(
                        ADDRESS,
                        ADDRESS, LONG, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS, LONG,
                        ADDRESS, ADDRESS, LONG, ADDRESS, ADDRESS, LONG, ADDRESS, LONG,
                        BOOL, LONG, LONG, LONG, LONG, LONG, LONG, LONG
                ),
                FunctionDescriptor.of(
                        ADDRESS,
                        ADDRESS, LONG, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS, LONG,
                        ADDRESS, ADDRESS, LONG, ADDRESS, INT, INT, ADDRESS, LONG,
                        ADDRESS, LONG, BOOL, LONG, LONG, LONG, LONG, LONG, LONG, LONG
                ),
                FunctionDescriptor.of(
                        ADDRESS,
                        ADDRESS, LONG, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS, LONG,
                        ADDRESS, ADDRESS, LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, LONG,
                        ADDRESS, LONG, BOOL, LONG, LONG, LONG, LONG, LONG, LONG, LONG
                ),
                FunctionDescriptor.of(
                        ADDRESS,
                        ADDRESS, LONG, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS, LONG,
                        ADDRESS, ADDRESS, LONG, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS,
                        LONG, BOOL, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG,
                        LONG, ADDRESS
                ),
                FunctionDescriptor.of(
                        ADDRESS,
                        ADDRESS, LONG, ADDRESS, ADDRESS, ADDRESS, LONG, ADDRESS, LONG,
                        ADDRESS, ADDRESS, LONG, ADDRESS, LONG, ADDRESS, LONG, BOOL,
                        LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG
                )
        );
    }
}
