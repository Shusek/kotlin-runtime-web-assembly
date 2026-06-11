package uk.shusek.krwa.testing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.compiler.InterpreterFallback
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.tools.wasm.Wat2Wasm
import uk.shusek.krwa.wasm.Parser

class TryTableTempSlotTest {
    @Test
    fun compilerSeparatesTrySaveSlotsFromBoxingTempSlots() {
        val module = Parser.parse(Wat2Wasm.parse(TRY_TABLE_WITH_BOXING_WAT))
        val factory =
            MachineFactoryCompiler.builder(module)
                .withInterpreterFallback(InterpreterFallback.FAIL)
                .compile()
        val instance = Instance.builder(module).withMachineFactory(factory).build()

        assertEquals(1L, instance.export("run").apply()[0])
    }

    companion object {
        private val TRY_TABLE_WITH_BOXING_WAT =
            """
            (module
              (tag ${'$'}e (param i32 i32 i32 i32 i64))

              (func (export "run") (result i32)
                (block ${'$'}catch
                  (i32.const 1)
                  (i32.const 2)
                  (i32.const 3)
                  (i32.const 4)
                  (try_table (result i32) (catch_all ${'$'}catch)
                    (i32.const 10)
                    (i32.const 11)
                    (i32.const 12)
                    (i32.const 13)
                    (i64.const 14)
                    (throw ${'$'}e))
                  (drop)
                  (drop)
                  (drop)
                  (drop)
                  (drop))
                (i32.const 1))
            )
            """
                .trimIndent()
    }
}
