package uk.shusek.krwa.gradle.component

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class KrwaPrepareKotlinWasmCoreModuleTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiledOutputs: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val wasmFiles = compiledOutputs.asFileTree
            .matching { pattern -> pattern.include("**/*.wasm") }
            .files
        val source = wasmFiles.singleOrNull()
            ?: throw GradleException(
                "A Kotlin/Wasm executable compilation must produce exactly one .wasm file; " +
                    "found ${wasmFiles.sorted().joinToString()}.",
            )
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
    }
}
