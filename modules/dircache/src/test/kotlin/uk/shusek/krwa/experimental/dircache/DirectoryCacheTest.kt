package uk.shusek.krwa.experimental.dircache

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.compiler.Cache
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.Parser

class DirectoryCacheTest {
    class CacheWithHitCounter(private val cache: Cache) : Cache {
        val hits = AtomicInteger(0)

        @Throws(IOException::class)
        override fun get(key: String): ByteArray? {
            val result = cache.get(key)
            if (result != null) {
                hits.incrementAndGet()
            }
            return result
        }

        @Throws(IOException::class)
        override fun putIfAbsent(key: String, data: ByteArray) {
            cache.putIfAbsent(key, data)
        }
    }

    class NotFailingDirCache(baseDir: Path) : DirectoryCache(baseDir) {
        @Throws(IOException::class)
        override fun get(key: String): ByteArray? {
            val target = toFilePath(key)
            return try {
                if (Files.isRegularFile(target)) Files.readAllBytes(target) else null
            } catch (e: IOException) {
                e.printStackTrace()
                // Retry.
                get(key)
            }
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
    fun shouldCacheCompiledResultInMemFS() {
        val fs = ZeroFs.newFileSystem(Configuration.unix())
        val cache = CacheWithHitCounter(DirectoryCache(fs.getPath("/cache")))
        val module =
            Parser.parse(
                DirectoryCacheTest::class.java.getResourceAsStream("/compiled/count_vowels.rs.wasm")
            )

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

    @Test
    fun shouldCacheCompiledResultNativeFS(@TempDir cacheDir: Path) {
        val module =
            Parser.parse(
                DirectoryCacheTest::class.java.getResourceAsStream("/compiled/count_vowels.rs.wasm")
            )

        val cache = CacheWithHitCounter(DirectoryCache(cacheDir))

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

    @Test
    fun testConcurrentAccessNativeFS(@TempDir cacheDir: Path) {
        val module =
            Parser.parse(
                DirectoryCacheTest::class.java.getResourceAsStream("/compiled/count_vowels.rs.wasm")
            )

        // Execute the section concurrently 10 times.
        val concurrency = 10
        val executor = Executors.newFixedThreadPool(concurrency)
        val hits = AtomicInteger(0)
        val futures =
            Array(concurrency) {
                CompletableFuture.runAsync(
                    {
                        // Each thread gets its own DirectoryCache so it simulates
                        // multiple processes accessing the disk cache concurrently.
                        val cache = CacheWithHitCounter(NotFailingDirCache(cacheDir))

                        val instance1 =
                            Instance.builder(module)
                                .withMachineFactory(
                                    MachineFactoryCompiler.builder(module)
                                        .withCache(cache)
                                        .compile()
                                )
                                .build()
                        exerciseCountVowels(instance1)

                        val instance2 =
                            Instance.builder(module)
                                .withMachineFactory(
                                    MachineFactoryCompiler.builder(module)
                                        .withCache(cache)
                                        .compile()
                                )
                                .build()
                        exerciseCountVowels(instance2)

                        hits.addAndGet(cache.hits.get())
                    },
                    executor,
                )
            }

        // Wait for all tasks to complete.
        CompletableFuture.allOf(*futures).join()
        executor.shutdown()

        // Some first instance creates may result in a cache hit but all second instance
        // creates should result in cache hits.
        assertTrue(
            hits.get() >= concurrency,
            "Expected at least $concurrency hits, but only got: ${hits.get()}",
        )
    }
}
