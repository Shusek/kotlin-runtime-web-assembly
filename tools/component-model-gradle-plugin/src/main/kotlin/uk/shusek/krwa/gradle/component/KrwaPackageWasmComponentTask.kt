package uk.shusek.krwa.gradle.component

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import uk.shusek.krwa.component.WasmComponentPackager

@CacheableTask
abstract class KrwaPackageWasmComponentTask : DefaultTask() {
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witFile: RegularFileProperty

    @get:Optional
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val witPackageDirectory: DirectoryProperty

    @get:Input
    abstract val world: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreModuleFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val adapterFiles: ConfigurableFileCollection = project.objects.fileCollection()

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val validateComponent: Property<Boolean>

    @get:Input
    abstract val asyncCallback: Property<Boolean>

    @TaskAction
    fun packageComponent() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        val args = buildList {
            add("--wit")
            add(witInput().absolutePath)
            add("--world")
            add(world.get())
            add("--core")
            add(coreModuleFile.get().asFile.absolutePath)
            add("--out")
            add(output.absolutePath)
            adapterFiles.files.sortedBy { file -> file.absolutePath }.forEach { adapter ->
                add("--adapt")
                add(adapter.absolutePath)
            }
            if (asyncCallback.get()) add("--async-callback")
            if (!validateComponent.get()) add("--skip-validate")
        }
        val result = runTool("WasmComponentPackager") { stdout, stderr ->
            WasmComponentPackager.run(args.toTypedArray(), stdout, stderr)
        }
        if (result.stdout.isNotBlank()) {
            logger.lifecycle(result.stdout.trimEnd())
        }
    }

    private fun witInput(): File = when {
        witPackageDirectory.isPresent -> witPackageDirectory.get().asFile
        witFile.isPresent -> witFile.get().asFile
        else -> throw GradleException(
            "Configure krwaComponentModel.componentWitFile or krwaComponentModel.componentWitPackageDirectory.",
        )
    }
}
