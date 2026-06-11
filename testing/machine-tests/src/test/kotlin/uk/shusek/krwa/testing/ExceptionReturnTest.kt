package uk.shusek.krwa.testing

import java.util.function.Function
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule

/** Tests for exception handling when `return` instruction is used in the call chain. */
class ExceptionReturnTest {
    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun catchAfterReturn(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance =
            machineInject
                .apply(Instance.builder(MODULE).withImportValues(ImportValues.builder().build()))
                .build()
        assertEquals(42L, instance.export("catch-after-return").apply()[0])
    }

    @ParameterizedTest
    @MethodSource("machineImplementations")
    fun catchSequentialWithReturn(machineInject: Function<Instance.Builder, Instance.Builder>) {
        val instance =
            machineInject
                .apply(Instance.builder(MODULE).withImportValues(ImportValues.builder().build()))
                .build()
        assertEquals(30L, instance.export("catch-sequential-with-return").apply()[0])
    }

    companion object {
        private val MODULE: WasmModule =
            Parser.parse(CorpusResources.getResource("compiled/exception_return.wat.wasm"))

        @JvmStatic
        fun machineImplementations(): Stream<Arguments> =
            Stream.of(
                Arguments.of(
                    Function<Instance.Builder, Instance.Builder> { builder ->
                        builder.withMachineFactory(
                            Function<Instance, Machine> { instance -> InterpreterMachine(instance) }
                        )
                    }
                )
            )
    }
}
