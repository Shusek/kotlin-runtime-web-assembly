package uk.shusek.krwa.testgen.wast

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Arrays

@JsonIgnoreProperties
open class Command {
    @field:JsonProperty("type") private var type: CommandType? = null

    @field:JsonProperty("name") private var name: String? = null

    @field:JsonProperty("line") private var line = 0

    @field:JsonProperty("filename") private var filename: String? = null

    @Suppress("UnusedPrivateMember")
    @field:JsonProperty("binary_filename")
    private var binaryFilename: String? = null

    @field:JsonProperty("module_type") private var moduleType: String? = null

    @field:JsonProperty("action") private var action: Action? = null

    @field:JsonProperty("expected") private var expected: Array<WasmValue>? = null

    @field:JsonProperty("text") private var text: String? = null

    @field:JsonProperty("as") private var asValue: String? = null

    override fun toString(): String =
        "Command{" +
            "type=" +
            type +
            ", name=" +
            name +
            ", line=" +
            line +
            ", filename='" +
            filename +
            '\'' +
            ", action=" +
            action +
            ", expected=" +
            Arrays.toString(expected) +
            ", text='" +
            text +
            '\'' +
            ", moduleType='" +
            moduleType +
            '\'' +
            '}'

    open fun type(): CommandType? = type

    open fun name(): String? = name

    open fun line(): Int = line

    open fun filename(): String? = filename

    open fun action(): Action? = action

    open fun expected(): Array<WasmValue>? = expected

    open fun text(): String? = text

    open fun moduleType(): String? = moduleType

    open fun `as`(): String? = asValue
}
