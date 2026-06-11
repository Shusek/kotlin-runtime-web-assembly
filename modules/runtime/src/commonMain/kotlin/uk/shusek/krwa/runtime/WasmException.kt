package uk.shusek.krwa.runtime

open class WasmException(instance: Instance, tagIdx: Int, args: LongArray) : RuntimeException() {
    private val instanceValue = instance
    private val tagIdxValue = tagIdx
    private val argsValue = args.copyOf()

    fun instance(): Instance = instanceValue

    fun tagIdx(): Int = tagIdxValue

    fun args(): LongArray = argsValue
}
