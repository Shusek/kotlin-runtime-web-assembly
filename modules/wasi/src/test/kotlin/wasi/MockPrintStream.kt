package wasi

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.UTF_8

class MockPrintStream private constructor(private val baos: ByteArrayOutputStream) :
    PrintStream(baos) {
    constructor() : this(ByteArrayOutputStream())

    fun output(): String = String(baos.toByteArray(), UTF_8)
}
