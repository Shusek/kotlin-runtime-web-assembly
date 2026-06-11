package uk.shusek.krwa.testing

import java.util.function.Function
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.ImportFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

/** Tests for exception handling with GC reference payloads. */
class ExceptionGcRefTest {
    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun basicCatchGc(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance =
            machineInject.apply(Instance.builder(MODULE).withImportValues(makeImports())).build()
        assertEquals(42L, instance.export("basic-catch-gc").apply()[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun sequentialCatchesGc(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance =
            machineInject.apply(Instance.builder(MODULE).withImportValues(makeImports())).build()
        assertEquals(30L, instance.export("sequential-catches-gc").apply()[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun catchFromCallGc(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance =
            machineInject.apply(Instance.builder(MODULE).withImportValues(makeImports())).build()
        assertEquals(30L, instance.export("catch-from-call-gc").apply()[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun deepCatchGc(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance =
            machineInject.apply(Instance.builder(MODULE).withImportValues(makeImports())).build()
        assertEquals(30L, instance.export("deep-catch-gc").apply()[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun catchInLoopGc(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance =
            machineInject.apply(Instance.builder(MODULE).withImportValues(makeImports())).build()
        assertEquals(4L, instance.export("catch-in-loop-gc").apply()[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun deepCatchInLoopGc(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance =
            machineInject.apply(Instance.builder(MODULE).withImportValues(makeImports())).build()
        assertEquals(4L, instance.export("deep-catch-in-loop-gc").apply()[0])
    }

    companion object {
        private val MODULE: WasmModule =
            Parser.parse(CorpusResources.getResource("compiled/exception_gc_ref.wat.wasm"))

        @JvmStatic
        fun machineImplementations(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    Function<Instance.Builder, Instance.Builder> { builder ->
                        builder.withMachineFactory(
                            Function<Instance, Machine> { instance -> InterpreterMachine(instance) }
                        )
                    }
                ),
                Arguments.of(
                    Function<Instance.Builder, Instance.Builder> { builder ->
                        builder.withMachineFactory(
                            Function<Instance, Machine> { instance ->
                                MachineFactoryCompiler.compile(instance)
                            }
                        )
                    }
                ),
            )

        private fun makeImports(): ImportValues =
            ImportValues.builder()
                .addFunction(
                    ImportFunction(
                        "host",
                        "on_catch",
                        FunctionType.of(listOf(ValType.I32), listOf(ValType.I32)),
                        WasmFunctionHandle { _: Instance, args: LongArray -> longArrayOf(args[0]) },
                    )
                )
                .build()
    }
}
