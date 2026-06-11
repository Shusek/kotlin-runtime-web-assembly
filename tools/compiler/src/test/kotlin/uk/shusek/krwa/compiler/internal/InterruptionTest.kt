package uk.shusek.krwa.compiler.internal

import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule

class InterruptionTest {
    @Test
    fun shouldInterruptLoop() {
        val module = Parser.parse(CorpusResources.getResource("compiled/infinite-loop.c.wasm"))
        val instance =
            Instance.builder(module).withMachineFactory(MachineFactoryCompiler::compile).build()

        val function = instance.export("run")
        assertInterruption(Runnable { function.apply() }, functionIdx(module, "run"))
    }

    @Test
    fun shouldInterruptCall() {
        val module = Parser.parse(CorpusResources.getResource("compiled/power.c.wasm"))
        val instance =
            Instance.builder(module).withMachineFactory(MachineFactoryCompiler::compile).build()
        val function = instance.export("run")
        assertInterruption(Runnable { function.apply(100) }, functionIdx(module, "run"))
    }

    private fun functionIdx(module: WasmModule, name: String): Int {
        for (i in 0 until module.exportSection().exportCount()) {
            val export = module.exportSection().getExport(i)
            if (export.name() == name) {
                return export.index()
            }
        }
        throw IllegalArgumentException("Function not found")
    }

    private fun assertInterruption(function: Runnable, funcIdx: Int) {
        val interrupted = AtomicBoolean()
        val runnable = Runnable {
            val e = assertThrows(WasmEngineException::class.java, function::run)
            assertEquals("Thread interrupted", e.message)
            interrupted.set(true)
        }

        val thread = Thread(runnable)
        thread.start()
        waitForWasmExecution(thread, funcIdx)

        thread.interrupt()
        SECONDS.timedJoin(thread, 10)
        assertTrue(interrupted.get())
    }

    private fun waitForWasmExecution(thread: Thread, funcIdx: Int) {
        val start = System.nanoTime()
        while (true) {
            if ((System.nanoTime() - start) >= SECONDS.toNanos(10)) {
                throw AssertionError("Timed out waiting for execution to start")
            }

            for (element in thread.stackTrace) {
                val className = element.className
                val methodName = element.methodName
                if (
                    className.startsWith(Compiler.DEFAULT_CLASS_NAME + "FuncGroup_") &&
                        methodName == CompilerUtil.methodNameForFunc(funcIdx)
                ) {
                    return
                }
            }

            MILLISECONDS.sleep(10)
        }
    }
}
