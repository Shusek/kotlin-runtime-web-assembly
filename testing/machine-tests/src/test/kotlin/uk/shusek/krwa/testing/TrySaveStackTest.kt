package uk.shusek.krwa.testing

import java.util.function.Function
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

/**
 * Tests that values below a try_table scope are preserved when a catch fires. This exercises the
 * TRY_SAVE_STACK / TRY_RESTORE_STACK mechanism in the compiler.
 */
class TrySaveStackTest {
    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun valueBelowTry(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance = machineInject.apply(Instance.builder(MODULE)).build()
        assertEquals(42L, instance.export("value-below-try").apply()[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun twoValuesBelowTry(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance = machineInject.apply(Instance.builder(MODULE)).build()
        assertEquals(305L, instance.export("two-values-below-try").apply()[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun nestedTryValues(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance = machineInject.apply(Instance.builder(MODULE)).build()
        assertEquals(6L, instance.export("nested-try-values").apply()[0])
    }

    companion object {
        private val MODULE: WasmModule =
            Parser.parse(CorpusResources.getResource("compiled/try_save_stack.wat.wasm"))

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
    }
}
