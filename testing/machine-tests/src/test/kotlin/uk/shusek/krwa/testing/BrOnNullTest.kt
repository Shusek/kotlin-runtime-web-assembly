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
 * Tests that br_on_null correctly refines the type on the fall-through path from nullable (ref null
 * $T) to non-nullable (ref $T).
 */
class BrOnNullTest {
    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun brOnNullRefinesType(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance = machineInject.apply(Instance.builder(module)).build()

        val newPoint = instance.export("new_point")
        val pointRef = newPoint.apply(42L, 7L)

        val getX = instance.export("get_x_or_default")
        assertEquals(42L, getX.apply(pointRef[0])[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun brOnNullWithNull(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance = machineInject.apply(Instance.builder(module)).build()

        val testNull = instance.export("test_null")
        assertEquals(-1L, testNull.apply()[0])
    }

    companion object {
        private val module: WasmModule =
            Parser.parse(CorpusResources.getResource("compiled/br_on_null.wat.wasm"))

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
