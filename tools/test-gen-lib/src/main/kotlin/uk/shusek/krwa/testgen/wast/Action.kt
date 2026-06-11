package uk.shusek.krwa.testgen.wast

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Arrays

@JsonIgnoreProperties
open class Action {
    @field:JsonProperty("type") private var type: ActionType? = null

    @field:JsonProperty("module") private var module: String? = null

    @field:JsonProperty("field") private var field: String? = null

    @field:JsonProperty("args") private var args: Array<WasmValue>? = null

    open fun type(): ActionType? = type

    open fun module(): String? = module

    open fun field(): String? = field

    open fun args(): Array<WasmValue>? = args

    override fun toString(): String =
        "Action{" +
            "type=" +
            type +
            ", module='" +
            module +
            '\'' +
            ", field='" +
            field +
            '\'' +
            ", args=" +
            Arrays.toString(args) +
            '}'
}
