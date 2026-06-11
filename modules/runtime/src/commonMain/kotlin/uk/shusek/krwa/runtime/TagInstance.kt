package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.TagType

open class TagInstance(private val tag: TagType) {
    private var typeValue: FunctionType? = null

    constructor(tag: TagType, type: FunctionType) : this(tag) {
        typeValue = type
    }

    fun tagType(): TagType = tag

    fun setType(type: FunctionType) {
        typeValue = type
    }

    fun type(): FunctionType? = typeValue
}
