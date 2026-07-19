package uk.shusek.krwa.component

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

class KotlinWasmJsonSequenceTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun benchmarksJsonSequenceDecodeFromGuestSourceInKotlinWasm() {
        assumeTrue(
            java.lang.Boolean.getBoolean(BenchmarkEnabledProperty),
            "Enable with -D$BenchmarkEnabledProperty=true",
        )

        val targetBytes =
            Integer.getInteger(BenchmarkPayloadBytesProperty, DefaultBenchmarkPayloadBytes)
        val payload = generatedCatalogJsonPayload(targetBytes)
        val wasm = compileJsonSequenceGuest(tempDir)
        val host = JsonStreamHost(payload.bytes)
        val instance = newJsonSequenceInstanceBuilder(wasm, host).build()

        val decodeWarmups = Integer.getInteger(BenchmarkWarmupsProperty, 0).coerceAtLeast(0)
        val decodeRepetitions = Integer.getInteger(BenchmarkRepetitionsProperty, 1).coerceAtLeast(1)
        repeat(decodeWarmups) {
            assertGuestSourceDecodeResult(instance, host, payload, targetBytes)
        }

        val decodeHostNanos = LongArray(decodeRepetitions)
        val decodeGuestNanos = LongArray(decodeRepetitions)
        repeat(decodeRepetitions) { repetition ->
            val decodeStarted = System.nanoTime()
            assertGuestSourceDecodeResult(instance, host, payload, targetBytes)
            decodeHostNanos[repetition] = System.nanoTime() - decodeStarted
            decodeGuestNanos[repetition] = host.reportedElapsedNanos
        }

        val decodeElapsedNanos = decodeHostNanos.min()
        val decodeGuestElapsedNanos = decodeGuestNanos.min()
        println(
            "KRWA Kotlin/Wasm JSON sequence: mode=guest-bytearray-source, bytes=${payload.bytes.size}, " +
                "items=${payload.itemCount}, public=${payload.publicCount}, " +
                "decodeGuestMs=${decodeGuestElapsedNanos / 1_000_000}, " +
                "decodeHostMs=${decodeElapsedNanos / 1_000_000}" +
                formatSamples("decodeHostMsSamples", decodeHostNanos) +
                formatSamples("decodeGuestMsSamples", decodeGuestNanos) +
                formatAverage("decodeHostMsAvg", decodeHostNanos) +
                formatAverage("decodeGuestMsAvg", decodeGuestNanos)
        )
    }

    @Test
    fun benchmarksJsonSequenceDecodeFromHostStreamInKotlinWasm() {
        assumeTrue(
            java.lang.Boolean.getBoolean(BenchmarkHostStreamEnabledProperty),
            "Enable with -D$BenchmarkHostStreamEnabledProperty=true",
        )

        val targetBytes =
            Integer.getInteger(BenchmarkPayloadBytesProperty, DefaultBenchmarkPayloadBytes)
        val payload = generatedCatalogJsonPayload(targetBytes)
        val wasm = compileJsonSequenceGuest(tempDir)
        val host = JsonStreamHost(payload.bytes)
        val instance = newJsonSequenceInstanceBuilder(wasm, host).build()

        val drainStarted = System.nanoTime()
        val drainedBytes = instance.export("run_drain_source").apply()[0]
        val drainElapsedNanos = System.nanoTime() - drainStarted
        val drainGuestElapsedNanos = host.reportedElapsedNanos
        val drainReads = host.readCalls

        assertEquals(payload.bytes.size.toLong(), drainedBytes)
        assertEquals(payload.bytes.size.toLong(), host.reportedPrimaryValue)
        assertTrue(drainReads > 1, "Expected streaming drain reads, got $drainReads")

        val decodeWarmups = Integer.getInteger(BenchmarkWarmupsProperty, 0).coerceAtLeast(0)
        val decodeRepetitions = Integer.getInteger(BenchmarkRepetitionsProperty, 1).coerceAtLeast(1)
        repeat(decodeWarmups) {
            assertDecodeResult(instance, host, payload)
        }

        val decodeHostNanos = LongArray(decodeRepetitions)
        val decodeGuestNanos = LongArray(decodeRepetitions)
        val decodeReadCounts = IntArray(decodeRepetitions)
        repeat(decodeRepetitions) { repetition ->
            val decodeStarted = System.nanoTime()
            assertDecodeResult(instance, host, payload)
            decodeHostNanos[repetition] = System.nanoTime() - decodeStarted
            decodeGuestNanos[repetition] = host.reportedElapsedNanos
            decodeReadCounts[repetition] = host.readCalls
        }

        val decodeElapsedNanos = decodeHostNanos.min()
        val decodeGuestElapsedNanos = decodeGuestNanos.min()
        val decodeReads = decodeReadCounts.last()
        println(
            "KRWA Kotlin/Wasm JSON sequence: mode=host-stream, bytes=${payload.bytes.size}, " +
                "items=${payload.itemCount}, public=${payload.publicCount}, " +
                "drainReads=$drainReads, drainGuestMs=${drainGuestElapsedNanos / 1_000_000}, " +
                "drainHostMs=${drainElapsedNanos / 1_000_000}, " +
                "decodeReads=$decodeReads, " +
                "decodeGuestMs=${decodeGuestElapsedNanos / 1_000_000}, " +
                "decodeHostMs=${decodeElapsedNanos / 1_000_000}" +
                formatSamples("decodeHostMsSamples", decodeHostNanos) +
                formatSamples("decodeGuestMsSamples", decodeGuestNanos) +
                formatAverage("decodeHostMsAvg", decodeHostNanos) +
                formatAverage("decodeGuestMsAvg", decodeGuestNanos)
        )
    }

    private fun assertDecodeResult(instance: Instance, host: JsonStreamHost, payload: JsonPayload): Long {
        val publicCount = instance.export("run_decode_filter").apply()[0]
        assertEquals(payload.publicCount.toLong(), publicCount)
        assertEquals(payload.itemCount.toLong(), host.reportedPrimaryValue)
        assertEquals(payload.publicCount.toLong(), host.reportedPublicCount)
        assertTrue(host.readCalls > 1, "Expected streaming reads, got ${host.readCalls}")
        return publicCount
    }

    private fun assertGuestSourceDecodeResult(
        instance: Instance,
        host: JsonStreamHost,
        payload: JsonPayload,
        targetBytes: Int,
    ): Long {
        val publicCount = instance.export("run_decode_filter_guest_source").apply(targetBytes.toLong())[0]
        assertEquals(payload.publicCount.toLong(), publicCount)
        assertEquals(payload.itemCount.toLong(), host.reportedPrimaryValue)
        assertEquals(payload.publicCount.toLong(), host.reportedPublicCount)
        return publicCount
    }
}

class KotlinWasmJsonSequenceCorrectnessTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun decodesEscapedObjectKeysInKotlinWasm() {
        val payload = escapedCatalogJsonPayload()
        val wasm = compileJsonSequenceGuest(tempDir)
        val host = JsonStreamHost(payload.bytes)
        val instance = newJsonSequenceInstance(wasm, host)

        val publicCount = instance.export("run_decode_filter").apply()[0]
        assertEquals(payload.publicCount.toLong(), publicCount)
        assertEquals(payload.itemCount.toLong(), host.reportedPrimaryValue)
        assertEquals(payload.publicCount.toLong(), host.reportedPublicCount)
    }

    @Test
    fun decodesGeneratedPayloadFromGuestSourceInKotlinWasm() {
        val targetBytes = 4096
        val payload = generatedCatalogJsonPayload(targetBytes)
        val wasm = compileJsonSequenceGuest(tempDir)
        val host = JsonStreamHost(payload.bytes)
        val instance = newJsonSequenceInstance(wasm, host)

        val publicCount = instance.export("run_decode_filter_guest_source").apply(targetBytes.toLong())[0]
        assertEquals(payload.publicCount.toLong(), publicCount)
        assertEquals(payload.itemCount.toLong(), host.reportedPrimaryValue)
        assertEquals(payload.publicCount.toLong(), host.reportedPublicCount)
    }
}

private fun newJsonSequenceInstance(wasm: Path, host: JsonStreamHost): Instance =
    newJsonSequenceInstanceBuilder(wasm, host).build()

private fun newJsonSequenceInstanceBuilder(wasm: Path, host: JsonStreamHost): Instance.Builder {
    val stdout = ByteArrayOutputStream()
    val wasi = WasiPreview1.builder()
        .withOptions(WasiOptions.builder().withStdout(stdout).build())
        .build()
    val imports = ImportValues.builder()
        .addFunction(*wasi.toHostFunctions())
        .addFunction(*host.functions())
        .build()
    return Instance.builder(parseWasm(wasm))
        .withImportValues(imports)
}

private fun compileJsonSequenceGuest(tempDir: Path): Path {
    val projectDir = tempDir.resolve("json-sequence-guest")
    copyTestFixtureProject("json-sequence-guest", projectDir)

    val outputFile = projectDir.resolve("gradle-output.log")
    val process = ProcessBuilder(
        nestedGradleCommand(
            repoGradlew(),
            "--stacktrace",
            "-q",
            "compileProductionExecutableKotlinWasmWasi",
        ),
    )
        .directory(projectDir.toFile())
        .redirectErrorStream(true)
        .redirectOutput(outputFile.toFile())
        .start()
    val finished = process.waitFor(180, TimeUnit.SECONDS)
    val output = if (Files.exists(outputFile)) Files.readString(outputFile) else ""
    if (!finished) process.destroyForcibly()
    assertTrue(finished, output)
    assertEquals(0, process.exitValue(), output)
    return findCompiledWasm(projectDir)
}

private fun repoGradlew(): Path {
    var current = Path.of("").toAbsolutePath()
    while (current.parent != null && !Files.exists(current.resolve("gradlew"))) {
        current = current.parent
    }
    val gradlew = if (System.getProperty("os.name").startsWith("Windows")) {
        "gradlew.bat"
    } else {
        "gradlew"
    }
    return current.resolve(gradlew)
}

private fun findCompiledWasm(projectDir: Path): Path =
    Files.walk(projectDir.resolve("build")).use { paths ->
        paths
            .filter { path -> path.fileName.toString().endsWith(".wasm") }
            .filter { path -> path.toString().contains("productionExecutable") }
            .findFirst()
            .orElseThrow { AssertionError("Compiled Wasm output was not found.") }
    }

private fun parseWasm(wasm: Path) =
    runCatching { Parser.parse(wasm) }.getOrElse {
        Parser.builder()
            .withValidation(false)
            .build()
            .parse { Files.newInputStream(wasm) }
    }

private class JsonStreamHost(private val bytes: ByteArray) {
    private var offset = 0
    var readCalls = 0
        private set
    var reportedPrimaryValue = -1L
        private set
    var reportedPublicCount = -1L
        private set
    var reportedElapsedNanos = -1L
        private set

    fun functions(): Array<HostFunction> =
        arrayOf(
            HostFunction(
                "bench",
                "read",
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                WasmFunctionHandle { instance, args ->
                    readCalls += 1
                    if (offset >= bytes.size) {
                        longArrayOf(-1L)
                    } else {
                        val ptr = args[0].toInt()
                        val len = args[1].toInt()
                        val count = minOf(len, bytes.size - offset)
                        instance.memory().write(ptr, bytes, offset, count)
                        offset += count
                        longArrayOf(count.toLong())
                    }
                },
            ),
            HostFunction(
                "bench",
                "reset",
                FunctionType.empty(),
                WasmFunctionHandle { _, _ ->
                    offset = 0
                    readCalls = 0
                    reportedPrimaryValue = -1L
                    reportedPublicCount = -1L
                    reportedElapsedNanos = -1L
                    null
                },
            ),
            HostFunction(
                "bench",
                "now-nanos",
                FunctionType.of(emptyList(), listOf(ValType.I64)),
                WasmFunctionHandle { _, _ -> longArrayOf(System.nanoTime()) },
            ),
            HostFunction(
                "bench",
                "report",
                FunctionType.of(listOf(ValType.I32, ValType.I64), emptyList()),
                WasmFunctionHandle { _, args ->
                    when (args[0].toInt()) {
                        1 -> reportedPrimaryValue = args[1]
                        2 -> reportedPublicCount = args[1]
                        3 -> reportedElapsedNanos = args[1]
                    }
                    null
                },
            ),
        )
}

private data class JsonPayload(
    val bytes: ByteArray,
    val itemCount: Int,
    val publicCount: Int,
)

private fun escapedCatalogJsonPayload(): JsonPayload {
    val json =
        """
        [
        {"item${'\\'}u0049d":"entry-0","na${'\\'}u006de":"Synthetic Catalog Entry 0","summary":"Generated summary for catalog item 0","visibilit${'\\'}u0079":"public","license":"standard","lengthSeconds":1200,"sourceLabel":"Synthetic Source","categories":["category-a","category-b"],"contributors":[{"contributorId":"contributor-0","displayName":"Contributor 0"}]},
        {"itemId":"entry-1","name":"Synthetic Catalog Entry 1","summary":"Generated summary for catalog item 1","visibility":"premium","license":"standard","lengthSeconds":1201,"sourceLabel":"Synthetic Source","categories":["category-a"],"contributors":[{"contributorId":"contributor-1","displayName":"Contributor 1"}]}
        ]
        """.trimIndent()
    return JsonPayload(
        bytes = json.toByteArray(StandardCharsets.UTF_8),
        itemCount = 2,
        publicCount = 1,
    )
}

private fun generatedCatalogJsonPayload(targetBytes: Int): JsonPayload {
    val builder = StringBuilder(targetBytes + 1024)
    var itemCount = 0
    var publicCount = 0
    builder.append('[')
    while (builder.length < targetBytes) {
        if (itemCount > 0) builder.append(',')
        val public = itemCount % 5 != 0
        if (public) publicCount += 1
        builder.append(catalogItemJson(itemCount, if (public) "public" else "premium"))
        itemCount += 1
    }
    builder.append(']')
    return JsonPayload(
        bytes = builder.toString().toByteArray(StandardCharsets.UTF_8),
        itemCount = itemCount,
        publicCount = publicCount,
    )
}

private fun catalogItemJson(index: Int, visibility: String): String =
    """
    {"itemId":"entry-$index","name":"Synthetic Catalog Entry $index","summary":"Generated summary for catalog item $index","visibility":"$visibility","license":"standard","lengthSeconds":${1200 + index},"sourceLabel":"Synthetic Source","categories":["category-a","category-b","category-c"],"contributors":[{"contributorId":"contributor-${index % 97}","displayName":"Contributor ${index % 97}"},{"contributorId":"contributor-${index % 53}","displayName":"Alias ${index % 53}"}]}
    """.trimIndent()

private const val BenchmarkEnabledProperty = "krwa.jsonSequenceBenchmark"
private const val BenchmarkHostStreamEnabledProperty = "krwa.jsonSequenceHostStreamBenchmark"
private const val BenchmarkPayloadBytesProperty = "krwa.jsonSequenceBytes"
private const val BenchmarkWarmupsProperty = "krwa.jsonSequenceBenchmarkWarmups"
private const val BenchmarkRepetitionsProperty = "krwa.jsonSequenceBenchmarkRepetitions"
private const val DefaultBenchmarkPayloadBytes = 2 * 1024 * 1024

private fun formatSamples(label: String, nanos: LongArray): String {
    if (nanos.size <= 1) return ""
    return ", $label=[${nanos.joinToString(",") { (it / 1_000_000).toString() }}]"
}

private fun formatAverage(label: String, nanos: LongArray): String {
    if (nanos.size <= 1) return ""
    return ", $label=${nanos.average().toLong() / 1_000_000}"
}
