package uk.shusek.krwa.testing

import java.util.ArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ImportMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits

class SievePrimesTest {
    @Test
    @Timeout(60)
    fun sievePrimesTest() {
        val memory = ByteArrayMemory(MemoryLimits(20, 100, true))

        val instance = newInstance(memory)
        val parallelSieve = instance.export("parallel_sieve_bitset")

        val numWorkers = 4
        val workers = ArrayList<Thread>()
        for (i in 0..<numWorkers) {
            val thread = newThread(instance)
            workers.add(thread)
            thread.start()
        }

        val limit = 1_000_000
        val result = parallelSieve.apply(limit.toLong())
        val primeCount = result[0].toInt()

        assertEquals(78498, primeCount, "Expected 78498 primes up to 1,000,000")

        instance.export("shutdown").apply()
        for (thread in workers) {
            thread.join()
        }
    }

    companion object {
        private val module: WasmModule =
            Parser.parse(CorpusResources.getResource("compiled/sieve-primes.rs.wasm"))

        private fun newInstance(memory: Memory): Instance =
            Instance.builder(module)
                .withImportValues(
                    ImportValues.builder().addMemory(ImportMemory("env", "memory", memory)).build()
                )
                .build()

        private fun newThread(parent: Instance): Thread {
            val memory = parent.imports().memory(0).memory()!!
            val stackSize = parent.exports().global("__stack_pointer").value
            val tlsSize = parent.exports().global("__tls_size").value
            val tlsAlign = parent.exports().global("__tls_align").value

            val stackPtr = parent.export("__malloc").apply(stackSize, 16L)[0] + stackSize
            val child = newInstance(memory)
            child.exports().global("__stack_pointer").value = stackPtr
            val tlsPtr = child.export("__malloc").apply(tlsSize, tlsAlign)
            child.export("__wasm_init_tls").apply(*tlsPtr)
            return Thread { child.export("register_thread").apply() }
        }
    }
}
