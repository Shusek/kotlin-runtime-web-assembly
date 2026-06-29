package uk.shusek.krwa.runtime

import kotlin.test.assertEquals
import kotlin.time.TimeSource
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

internal object RuntimeBenchmarkSupport {
    fun run(
        backend: ExecutionBackend,
        iterations: Int,
        repetitions: Int,
        warmupRepetitions: Int,
    ): RuntimeBenchmarkResult {
        val module = WasmParser.parse(LOOP_WASM)
        val instance =
            Instance.builder(module)
                .withExecutionBackend(backend)
                .build()
        val run = instance.export("run")
        val expected = triangularI32(iterations)

        repeat(warmupRepetitions) {
            assertEquals(expected, run.apply(iterations.toLong())[0].toInt())
        }

        var checksum = 0L
        val mark = TimeSource.Monotonic.markNow()
        repeat(repetitions) {
            val result = run.apply(iterations.toLong())[0].toInt()
            assertEquals(expected, result)
            checksum += result.toLong()
        }
        val elapsedNs = mark.elapsedNow().inWholeNanoseconds
        return RuntimeBenchmarkResult(
            iterations = iterations,
            repetitions = repetitions,
            elapsedNs = elapsedNs,
            checksum = checksum,
            workload = "loop",
            backend = backend,
        )
    }

    fun runHostCallback(
        backend: ExecutionBackend,
        repetitions: Int,
        warmupRepetitions: Int,
    ): RuntimeBenchmarkResult {
        val hostCalls = repetitions * HostCallsPerExportCall
        var checksum = 0L
        val imports =
            ImportValues.builder()
                .addFunction(
                    HostFunction(
                        "console",
                        "log",
                        FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList()),
                        WasmFunctionHandle { instance, args ->
                            checksum += instance.memory().read(args[1].toInt()).toLong() + args[0]
                            null
                        },
                    ),
                )
                .build()
        val instance =
            Instance.builder(WasmParser.parse(HOST_CALLBACK_WASM))
                .withExecutionBackend(backend)
                .withImportValues(imports)
                .build()
        val logIt = instance.export("logIt")

        repeat(warmupRepetitions) {
            logIt.apply()
        }

        val mark = TimeSource.Monotonic.markNow()
        repeat(repetitions) {
            logIt.apply()
        }
        val elapsedNs = mark.elapsedNow().inWholeNanoseconds
        return RuntimeBenchmarkResult(
            iterations = HostCallsPerExportCall,
            repetitions = repetitions,
            elapsedNs = elapsedNs,
            checksum = checksum,
            workload = "host-callback",
            backend = backend,
        )
    }

    fun runMemoryScan(
        backend: ExecutionBackend,
        iterations: Int,
        repetitions: Int,
        warmupRepetitions: Int,
    ): RuntimeBenchmarkResult {
        val module = WasmParser.parse(MEMORY_SCAN_WASM)
        val instance =
            Instance.builder(module)
                .withExecutionBackend(backend)
                .build()
        val scan = instance.export("scan")
        val expected = iterations / MemoryScanStride

        repeat(warmupRepetitions) {
            assertEquals(expected, scan.apply(iterations.toLong())[0].toInt())
        }

        var checksum = 0L
        val mark = TimeSource.Monotonic.markNow()
        repeat(repetitions) {
            val result = scan.apply(iterations.toLong())[0].toInt()
            assertEquals(expected, result)
            checksum += result.toLong()
        }
        val elapsedNs = mark.elapsedNow().inWholeNanoseconds
        return RuntimeBenchmarkResult(
            iterations = iterations,
            repetitions = repetitions,
            elapsedNs = elapsedNs,
            checksum = checksum,
            workload = "memory-scan",
            backend = backend,
        )
    }

    private fun triangularI32(n: Int): Int =
        (n.toLong() * (n.toLong() + 1L) / 2L).toInt()

    private const val HostCallsPerExportCall = 10
    private const val MemoryScanStride = 64

    private val LOOP_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x06,
            0x01, 0x60, 0x01, 0x7F, 0x01, 0x7F,
            0x03, 0x02,
            0x01, 0x00,
            0x07, 0x07,
            0x01, 0x03, 0x72, 0x75, 0x6E, 0x00, 0x00,
            0x0A, 0x1F,
            0x01,
            0x1D, 0x01, 0x01, 0x7F,
            0x41, 0x00,
            0x21, 0x01,
            0x03, 0x40,
            0x20, 0x01,
            0x20, 0x00,
            0x6A,
            0x21, 0x01,
            0x20, 0x00,
            0x41, 0x01,
            0x6B,
            0x22, 0x00,
            0x0D, 0x00,
            0x0B,
            0x20, 0x01,
            0x0B,
        )

    private val HOST_CALLBACK_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x09,
            0x02, 0x60, 0x02, 0x7F, 0x7F, 0x00, 0x60, 0x00, 0x00,
            0x02, 0x0F,
            0x01, 0x07, 0x63, 0x6F, 0x6E, 0x73, 0x6F, 0x6C, 0x65, 0x03, 0x6C, 0x6F, 0x67, 0x00, 0x00,
            0x03, 0x02,
            0x01, 0x01,
            0x05, 0x03,
            0x01, 0x00, 0x01,
            0x07, 0x09,
            0x01, 0x05, 0x6C, 0x6F, 0x67, 0x49, 0x74, 0x00, 0x01,
            0x0A, 0x21,
            0x01, 0x1F, 0x01, 0x01, 0x7F, 0x01, 0x01, 0x41, 0x01, 0x1A, 0x41, 0x0A, 0x21, 0x00,
            0x03, 0x40,
            0x41, 0x0D, 0x41, 0x00, 0x10, 0x00,
            0x20, 0x00, 0x41, 0x01, 0x6B, 0x22, 0x00, 0x0D, 0x00,
            0x0B, 0x0B,
            0x0B, 0x14,
            0x01, 0x00, 0x41, 0x00, 0x0B, 0x0E,
            0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x2C, 0x20, 0x57, 0x6F, 0x72, 0x6C, 0x64, 0x21, 0x00,
            0x00, 0x23,
            0x04, 0x6E, 0x61, 0x6D, 0x65,
            0x01, 0x06, 0x01, 0x00, 0x03, 0x6C, 0x6F, 0x67,
            0x02, 0x08, 0x01, 0x01, 0x01, 0x00, 0x03, 0x76, 0x61, 0x72,
            0x09, 0x0A, 0x01, 0x00, 0x07, 0x2E, 0x72, 0x6F, 0x64, 0x61, 0x74, 0x61,
        )

    private val MEMORY_SCAN_WASM =
        byteArrayOf(
            0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00, 0x01, 0x06, 0x01, 0x60,
            0x01, 0x7F, 0x01, 0x7F, 0x03, 0x02, 0x01, 0x00, 0x05, 0x03, 0x01, 0x00,
            0x01, 0x07, 0x11, 0x02, 0x06, 0x6D, 0x65, 0x6D, 0x6F, 0x72, 0x79, 0x02,
            0x00, 0x04, 0x73, 0x63, 0x61, 0x6E, 0x00, 0x00, 0x0A, 0x35, 0x01, 0x33,
            0x01, 0x03, 0x7F, 0x03, 0x40, 0x20, 0x01, 0x20, 0x00, 0x49, 0x04, 0x40,
            0x20, 0x01, 0x41, 0x3F, 0x71, 0x2D, 0x00, 0x00, 0x21, 0x03, 0x20, 0x03,
            0x41, 0x22, 0x46, 0x04, 0x40, 0x20, 0x02, 0x41, 0x01, 0x6A, 0x21, 0x02,
            0x0B, 0x20, 0x01, 0x41, 0x01, 0x6A, 0x21, 0x01, 0x0C, 0x01, 0x0B, 0x0B,
            0x20, 0x02, 0x0B, 0x0B, 0x46, 0x01, 0x00, 0x41, 0x00, 0x0B, 0x40, 0x61,
            0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61,
            0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61,
            0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61,
            0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61,
            0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61, 0x61,
            0x61, 0x61, 0x22, 0x00, 0x23, 0x04, 0x6E, 0x61, 0x6D, 0x65, 0x02, 0x11,
            0x01, 0x00, 0x04, 0x00, 0x01, 0x6E, 0x01, 0x01, 0x69, 0x02, 0x03, 0x73,
            0x75, 0x6D, 0x03, 0x01, 0x62, 0x03, 0x09, 0x01, 0x00, 0x01, 0x00, 0x04,
            0x6C, 0x6F, 0x6F, 0x70,
        )
}

internal data class RuntimeBenchmarkResult(
    val iterations: Int,
    val repetitions: Int,
    val elapsedNs: Long,
    val checksum: Long,
    val workload: String,
    val backend: ExecutionBackend,
) {
    fun formatted(platform: String): String {
        val totalIterations = iterations.toLong() * repetitions.toLong()
        val elapsedMs = elapsedNs / 1_000_000
        val nsPerIteration = if (totalIterations == 0L) 0L else elapsedNs / totalIterations
        return "KRWA runtime benchmark: platform=$platform, workload=$workload, backend=$backend, " +
            "iterations=$iterations, repetitions=$repetitions, " +
            "elapsedMs=$elapsedMs, nsPerIteration=$nsPerIteration, checksum=$checksum"
    }
}
