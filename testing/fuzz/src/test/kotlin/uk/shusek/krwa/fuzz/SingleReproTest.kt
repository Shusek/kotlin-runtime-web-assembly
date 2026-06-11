package uk.shusek.krwa.fuzz

import java.nio.file.Files
import java.nio.file.Paths
import java.util.function.Function
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.Parser

class SingleReproTest : TestModule() {
    private val smith = WasmSmithWrapper()
    private val interpreterRunner: WasmRunner = DefaultRunner()
    private val compilerRunner: WasmRunner =
        DefaultRunner(
            Function<Instance, Machine> { instance -> MachineFactoryCompiler.compile(instance) }
        )

    fun enableSingleReproducer(): Boolean =
        System.getenv(CHICORY_FUZZ_SEED_KEY) != null &&
            System.getenv(CHICORY_FUZZ_TYPES_KEY) != null

    @Test
    @EnabledIf("enableSingleReproducer")
    fun singleReproducer() {
        val seed = Files.readString(Paths.get(System.getenv(CHICORY_FUZZ_SEED_KEY)))
        val types = InstructionTypes.fromString(System.getenv(CHICORY_FUZZ_TYPES_KEY))
        val targetWasm = smith.run(seed.substring(0, minOf(seed.length, 32)), "test.wasm", types)

        val module = Parser.parse(targetWasm)
        val instance = Instance.builder(module).withInitialize(true).withStart(false).build()

        testModule(targetWasm, module, instance, interpreterRunner, compilerRunner, "repro", true)
        assertDoesNotThrow { Instance.builder(module).build() }
    }

    companion object {
        private const val CHICORY_FUZZ_SEED_KEY = "CHICORY_FUZZ_SEED"
        private const val CHICORY_FUZZ_TYPES_KEY = "CHICORY_FUZZ_TYPES"
    }
}
