package uk.shusek.krwa.testgen.wast

import com.fasterxml.jackson.annotation.JsonProperty
import java.io.File

open class Wast {
    @field:JsonProperty("source_filename") private var sourceFilename: File? = null

    @field:JsonProperty("commands") private var commands: Array<Command>? = null

    open fun sourceFilename(): File? = sourceFilename

    open fun commands(): Array<Command>? = commands
}
