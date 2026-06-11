package uk.shusek.krwa.wasi

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8

internal class MockPrintStream : PrintStream(ByteArrayOutputStream()) {
    private val baos = out as ByteArrayOutputStream

    override fun println(s: String?) {
        super.println(s)
    }

    fun output(): String = baos.toString(UTF_8)
}
