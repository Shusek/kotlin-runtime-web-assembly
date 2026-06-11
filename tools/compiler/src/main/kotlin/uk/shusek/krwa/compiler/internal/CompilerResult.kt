package uk.shusek.krwa.compiler.internal

class CompilerResult(
    private val collector: ClassCollector,
    private val interpretedFunctions: Set<Int>,
) {
    fun classBytes(): Map<String, ByteArray> = collector.classBytes()

    fun collector(): ClassCollector = collector

    fun interpretedFunctions(): Set<Int> = interpretedFunctions
}
