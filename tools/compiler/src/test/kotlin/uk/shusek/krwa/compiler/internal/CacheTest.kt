package uk.shusek.krwa.compiler.internal

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import uk.shusek.krwa.compiler.Cache
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser

class CacheTest {
    internal class MockCache : Cache {
        private val cache = ConcurrentHashMap<String, ByteArray>()

        override fun get(key: String): ByteArray? = cache[key]

        override fun putIfAbsent(key: String, data: ByteArray) {
            cache.putIfAbsent(key, data)
        }
    }

    class CacheWithHitCounter(private val cache: Cache) : Cache {
        val hits: AtomicInteger = AtomicInteger(0)

        override fun get(key: String): ByteArray? {
            val result = cache.get(key)
            if (result != null) {
                hits.incrementAndGet()
            }
            return result
        }

        override fun putIfAbsent(key: String, data: ByteArray) {
            cache.putIfAbsent(key, data)
        }
    }

    private fun exerciseCountVowels(instance: Instance) {
        val alloc = instance.export("alloc")
        val dealloc = instance.export("dealloc")
        val countVowels = instance.export("count_vowels")
        val memory = instance.memory()
        val message = "Hello, World!"
        val len = message.toByteArray(UTF_8).size
        val ptr = alloc.apply(len.toLong())[0].toInt()
        memory.writeString(ptr, message)
        val result = countVowels.apply(ptr.toLong(), len.toLong())
        dealloc.apply(ptr.toLong(), len.toLong())
        assertEquals(3L, result[0])
    }

    @Test
    fun shouldCacheCompiledResultInMem() {
        val cache = CacheWithHitCounter(MockCache())
        val module = Parser.parse(CorpusResources.getResource("compiled/count_vowels.rs.wasm"))

        val instance1 =
            Instance.builder(module)
                .withMachineFactory(
                    MachineFactoryCompiler.builder(module).withCache(cache).compile()
                )
                .build()

        exerciseCountVowels(instance1)
        assertEquals(0, cache.hits.get())
        val instance2 =
            Instance.builder(module)
                .withMachineFactory(
                    MachineFactoryCompiler.builder(module).withCache(cache).compile()
                )
                .build()
        exerciseCountVowels(instance2)
        assertEquals(1, cache.hits.get())
    }
}
