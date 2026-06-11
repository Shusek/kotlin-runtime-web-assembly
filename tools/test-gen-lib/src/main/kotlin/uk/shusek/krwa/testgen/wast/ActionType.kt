package uk.shusek.krwa.testgen.wast

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

enum class ActionType(private val jsonValue: String) {
    @JsonProperty("invoke") INVOKE("invoke"),
    @JsonProperty("get") GET("get");

    @JsonValue fun value(): String = jsonValue
}
