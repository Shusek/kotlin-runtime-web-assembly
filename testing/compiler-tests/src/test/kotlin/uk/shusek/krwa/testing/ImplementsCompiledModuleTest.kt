package uk.shusek.krwa.testing

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import uk.shusek.krwa.runtime.CompiledModule
import uk.shusek.krwa.wabt.Wat2WasmModule

class ImplementsCompiledModuleTest {
    @Test
    fun testImplementsCompiledModule() {
        val module: CompiledModule = Wat2WasmModule()
        assertNotNull(module.wasmModule())
        assertNotNull(module.machineFactory())
    }
}
