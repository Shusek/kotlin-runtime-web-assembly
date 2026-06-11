package uk.shusek.krwa.runtime

import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmEngineException

class InterruptionTest {
    @Test
    fun shouldInterruptLoop() {
        val instance =
            Instance.builder(
                    Parser.parse(CorpusResources.getResource("compiled/infinite-loop.c.wasm"))
                )
                .build()
        val function = instance.export("run")
        assertInterruption { function.apply() }
    }

    @Test
    fun shouldInterruptCall() {
        val instance =
            Instance.builder(Parser.parse(CorpusResources.getResource("compiled/power.c.wasm")))
                .build()
        val function = instance.export("run")
        assertInterruption { function.apply(100) }
    }

    private fun assertInterruption(function: Runnable) {
        val interrupted = AtomicBoolean()
        val runnable = Runnable {
            val e = assertThrows(WasmEngineException::class.java, function::run)
            assertEquals("Thread interrupted", e.message)
            interrupted.set(true)
        }

        val thread = Thread(runnable)
        thread.start()
        waitForWasmExecution(thread)

        thread.interrupt()
        SECONDS.timedJoin(thread, 10)
        assertTrue(interrupted.get())
    }

    private fun waitForWasmExecution(thread: Thread) {
        val start = System.nanoTime()
        while (true) {
            if ((System.nanoTime() - start) >= SECONDS.toNanos(10)) {
                throw AssertionError("Timed out waiting for execution to start")
            }

            for (element in thread.stackTrace) {
                if (
                    element.className == InterpreterMachine::class.java.name &&
                        element.methodName == "eval"
                ) {
                    return
                }
            }

            MILLISECONDS.sleep(10)
        }
    }
}
