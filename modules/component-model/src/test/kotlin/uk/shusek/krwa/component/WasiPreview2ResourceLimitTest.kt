package uk.shusek.krwa.component

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Comparator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * A guest controls the lengths it passes to host functions; the host must not allocate those
 * lengths blindly.
 */
class WasiPreview2ResourceLimitTest {
    @Test
    fun rejectsOversizedRandomByteRequestsBeforeAllocating() {
        val wasi = WasiPreview2.builder().build()
        val imports = CapturingHostImports()
        wasi.install(imports)
        try {
            assertThrows(ComponentModelException::class.java) {
                imports.call("random", "get-random-bytes", Int.MAX_VALUE.toLong())
            }
            assertThrows(ComponentModelException::class.java) {
                imports.call("random", "get-random-bytes", -1L)
            }
            val bytes = imports.call("random", "get-random-bytes", 16L) as ByteArray
            assertEquals(16, bytes.size)
        } finally {
            wasi.close()
        }
    }

    @Test
    fun clampsDescriptorReadsToTheFileSize() {
        val root = Files.createTempDirectory("krwa-wasi2-read-limit")
        try {
            Files.writeString(root.resolve("data.txt"), "0123456789", StandardCharsets.UTF_8)
            val wasi = WasiPreview2.builder().withPreopenedDirectory("/", root.toString()).build()
            val imports = CapturingHostImports()
            wasi.install(imports)
            try {
                @Suppress("UNCHECKED_CAST")
                val directories = imports.call("preopens", "get-directories") as List<List<Any?>>
                val base = directories.single()[0]
                val file =
                    expectOk(
                        imports.call(
                            "types",
                            "[method]descriptor.open-at",
                            base,
                            emptyList<String>(),
                            "data.txt",
                            emptyList<String>(),
                            listOf("read"),
                        )
                    )

                val whole =
                    expectOk(
                        imports.call("types", "[method]descriptor.read", file, Int.MAX_VALUE.toLong(), 0L)
                    ) as List<*>
                assertArrayEquals(
                    "0123456789".toByteArray(StandardCharsets.UTF_8),
                    whole[0] as ByteArray,
                )
                assertEquals(true, whole[1])

                val partial =
                    expectOk(imports.call("types", "[method]descriptor.read", file, 4L, 2L)) as List<*>
                assertArrayEquals("2345".toByteArray(StandardCharsets.UTF_8), partial[0] as ByteArray)
                assertEquals(false, partial[1])

                val beyond =
                    expectOk(imports.call("types", "[method]descriptor.read", file, 16L, 32L)) as List<*>
                assertEquals(0, (beyond[0] as ByteArray).size)
                assertEquals(true, beyond[1])
            } finally {
                wasi.close()
            }
        } finally {
            Files.walk(root).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun boundsOutputStreamWritesAndRequestBodyBuffering() {
        val wasi = WasiPreview2.builder().build()
        val imports = CapturingHostImports()
        wasi.install(imports)
        try {
            val headers = imports.call("types", "[constructor]fields")
            val request = imports.call("types", "[constructor]outgoing-request", headers)
            val body = expectOk(imports.call("types", "[method]outgoing-request.body", request))
            val stream = expectOk(imports.call("types", "[method]outgoing-body.write", body))

            assertThrows(ComponentModelException::class.java) {
                imports.call("streams", "[method]output-stream.write", stream, ByteArray(4097))
            }

            val chunk = ByteArray(4096)
            var accepted = 0L
            var rejected: Any? = null
            while (rejected == null && accepted < 20_000L) {
                val result = imports.call("streams", "[method]output-stream.write", stream, chunk)
                if (result is WitResult.Err<*, *>) {
                    rejected = result.value()
                } else {
                    expectOk(result)
                    accepted++
                }
            }
            assertEquals(16_384L, accepted)
            assertNotNull(rejected)
        } finally {
            wasi.close()
        }
    }

    private fun expectOk(result: Any?): Any? =
        when (result) {
            is WitResult.Ok<*, *> -> result.value()
            is WitResult.Err<*, *> -> throw AssertionError("expected success, got error ${result.value()}")
            else -> throw AssertionError("expected result, got $result")
        }

    private class CapturingHostImports : WasiHostImportBuilder {
        private val handlers = LinkedHashMap<String, HostHandler>()

        override fun withHostImport(
            interfaceName: String?,
            functionName: String?,
            handler: HostHandler,
        ): WasiHostImportBuilder {
            handlers[key(interfaceName, functionName)] = handler
            return this
        }

        override fun withHostImport(
            qualifiedName: String,
            handler: HostHandler,
        ): WasiHostImportBuilder {
            handlers[qualifiedName] = handler
            return this
        }

        fun call(
            interfaceName: String,
            functionName: String,
            vararg arguments: Any?,
        ): Any? {
            val key = key(interfaceName, functionName)
            val handler = handlers[key] ?: error("missing host import $key")
            return handler.apply(arguments.asList())
        }

        private fun key(
            interfaceName: String?,
            functionName: String?,
        ): String = "$interfaceName::$functionName"
    }
}
