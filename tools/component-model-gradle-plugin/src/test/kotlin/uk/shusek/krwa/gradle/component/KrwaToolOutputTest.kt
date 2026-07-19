package uk.shusek.krwa.gradle.component

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KrwaToolOutputTest {
    @Test
    fun `bounds successful tool stdout and stderr`() {
        val emittedBytes = KrwaToolOutputLimitBytes * 16
        val stdoutChunk = ByteArray(emittedBytes) { 'o'.code.toByte() }
        val stderrChunk = ByteArray(emittedBytes) { 'e'.code.toByte() }

        val result = runTool("noisy-tool") { stdout, stderr ->
            stdout.write(stdoutChunk)
            stderr.write(stderrChunk)
            0
        }

        assertBounded(result.stdout, emittedBytes)
        assertBounded(result.stderr, emittedBytes)
    }

    @Test
    fun `bounds failed tool diagnostics`() {
        val emittedBytes = KrwaToolOutputLimitBytes * 16
        val stderrChunk = ByteArray(emittedBytes) { 'e'.code.toByte() }

        val failure = assertThrows(GradleException::class.java) {
            runTool("noisy-tool") { _, stderr ->
                stderr.write(stderrChunk)
                23
            }
        }

        assertTrue(failure.message.orEmpty().startsWith("noisy-tool failed with exit code 23"))
        assertTrue(failure.message.orEmpty().contains("[KRWA tool output truncated after"))
        assertTrue(failure.message.orEmpty().toByteArray(Charsets.UTF_8).size < KrwaToolOutputLimitBytes + 256)
    }

    private fun assertBounded(output: String, emittedBytes: Int) {
        assertTrue(output.contains("[KRWA tool output truncated after"))
        assertTrue(output.contains("discarded ${emittedBytes - KrwaToolOutputLimitBytes} bytes"))
        assertTrue(output.toByteArray(Charsets.UTF_8).size < KrwaToolOutputLimitBytes + 128)
        assertEquals(KrwaToolOutputLimitBytes, output.substringBefore("\n[KRWA").length)
    }
}
