package uk.shusek.krwa.fuzz

import java.io.File

fun interface WasmRunner {
    @Throws(Exception::class)
    fun run(wasmFile: File, functionName: String, params: List<String>): String
}
