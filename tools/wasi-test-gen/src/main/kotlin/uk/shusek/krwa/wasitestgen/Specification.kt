package uk.shusek.krwa.wasitestgen

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Optional

class Specification
@JsonCreator
constructor(
    @JsonProperty("args") args: List<String>?,
    @JsonProperty("dirs") dirs: List<String>?,
    @JsonProperty("env") env: Map<String, String>?,
    @param:JsonProperty("exit_code") private val exitCode: Int,
    @JsonProperty("stdout") stdout: String?,
) {
    private val args = args ?: emptyList()
    private val dirs = dirs ?: emptyList()
    private val env = env ?: emptyMap()
    private val stdout = Optional.ofNullable(stdout)

    fun args(): List<String> = args

    fun dirs(): List<String> = dirs

    fun env(): Map<String, String> = env

    fun exitCode(): Int = exitCode

    fun stdout(): Optional<String> = stdout

    companion object {
        @JvmStatic
        fun createDefault(): Specification =
            Specification(emptyList(), emptyList(), emptyMap(), 0, null)
    }
}
