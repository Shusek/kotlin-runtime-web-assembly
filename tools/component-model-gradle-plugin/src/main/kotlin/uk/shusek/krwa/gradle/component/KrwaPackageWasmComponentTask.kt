package uk.shusek.krwa.gradle.component

import java.io.File
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
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
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import uk.shusek.krwa.component.WasmComponentPackager

@CacheableTask
abstract class KrwaPackageWasmComponentTask : DefaultTask() {
    @get:Inject
    internal abstract val workerExecutor: WorkerExecutor

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
        val stagedOutput = temporaryDir.resolve("component.wasm")
        Files.deleteIfExists(stagedOutput.toPath())

        val queue = workerExecutor.processIsolation { worker ->
            worker.forkOptions.maxHeapSize = componentPackagerWorkerMaxHeap()
            worker.forkOptions.jvmArgs("--enable-native-access=ALL-UNNAMED")
            configuredWasmtimeLibrary()?.let { library ->
                worker.forkOptions.systemProperty(WasmtimeLibraryProperty, library)
            }
            worker.classpath.from(componentPackagerWorkerClasspath())
        }
        queue.submit(KrwaPackageWasmComponentWorkAction::class.java) { parameters ->
            parameters.witInputPath.set(witInput().absolutePath)
            parameters.world.set(world)
            parameters.coreModulePath.set(coreModuleFile.get().asFile.absolutePath)
            parameters.adapterPaths.set(
                adapterFiles.files
                    .sortedBy { file -> file.absolutePath }
                    .map(File::getAbsolutePath),
            )
            parameters.outputPath.set(stagedOutput.absolutePath)
            parameters.validateComponent.set(validateComponent)
            parameters.asyncCallback.set(asyncCallback)
        }
        queue.await()

        if (!stagedOutput.isFile) {
            throw GradleException("WasmComponentPackager completed without creating ${stagedOutput.absolutePath}.")
        }
        replaceOutput(stagedOutput, output)
    }

    private fun witInput(): File = when {
        witPackageDirectory.isPresent -> witPackageDirectory.get().asFile
        witFile.isPresent -> witFile.get().asFile
        else -> throw GradleException(
            "Configure krwaComponentModel.componentWitFile or krwaComponentModel.componentWitPackageDirectory.",
        )
    }
}

internal interface KrwaPackageWasmComponentWorkParameters : WorkParameters {
    val witInputPath: Property<String>
    val world: Property<String>
    val coreModulePath: Property<String>
    val adapterPaths: ListProperty<String>
    val outputPath: Property<String>
    val validateComponent: Property<Boolean>
    val asyncCallback: Property<Boolean>
}

internal abstract class KrwaPackageWasmComponentWorkAction :
    WorkAction<KrwaPackageWasmComponentWorkParameters> {
    override fun execute() {
        val args = buildList {
            add("--wit")
            add(parameters.witInputPath.get())
            add("--world")
            add(parameters.world.get())
            add("--core")
            add(parameters.coreModulePath.get())
            add("--out")
            add(parameters.outputPath.get())
            parameters.adapterPaths.get().forEach { adapter ->
                add("--adapt")
                add(adapter)
            }
            if (parameters.asyncCallback.get()) add("--async-callback")
            if (!parameters.validateComponent.get()) add("--skip-validate")
        }
        val result = runTool("WasmComponentPackager") { stdout, stderr ->
            WasmComponentPackager.run(args.toTypedArray(), stdout, stderr)
        }
        if (result.stdout.isNotBlank()) {
            WorkerLogger.lifecycle(result.stdout.trimEnd())
        }
    }

    private companion object {
        val WorkerLogger = Logging.getLogger(KrwaPackageWasmComponentWorkAction::class.java)
    }
}

private fun KrwaPackageWasmComponentTask.componentPackagerWorkerClasspath(): Set<File> {
    val files = linkedSetOf<File>()
    (javaClass.classLoader as? URLClassLoader)?.urLs
        ?.mapNotNullTo(files) { url -> runCatching { File(url.toURI()) }.getOrNull() }
    ComponentPackagerWorkerClassNames.forEach { className ->
        val type = Class.forName(className, false, javaClass.classLoader)
        type.protectionDomain?.codeSource?.location?.let { location ->
            runCatching { File(location.toURI()) }.getOrNull()?.let(files::add)
        }
    }
    if (files.isEmpty()) {
        throw GradleException("Unable to determine the KRWA component packager worker classpath.")
    }
    return files
}

private fun componentPackagerWorkerMaxHeap(): String =
    System.getProperty(ComponentPackagerWorkerMaxHeapProperty)
        ?.takeIf { value -> value.matches(Regex("[1-9][0-9]*[kKmMgG]")) }
        ?: DefaultComponentPackagerWorkerMaxHeap

private fun configuredWasmtimeLibrary(): String? =
    System.getProperty(WasmtimeLibraryProperty)
        ?.takeIf(String::isNotBlank)
        ?: System.getenv(WasmtimeLibraryEnvironmentVariable)
            ?.takeIf(String::isNotBlank)

private fun replaceOutput(stagedOutput: File, output: File) {
    try {
        Files.move(
            stagedOutput.toPath(),
            output.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(stagedOutput.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private const val ComponentPackagerWorkerMaxHeapProperty = "krwa.component.packager.workerMaxHeap"
private const val DefaultComponentPackagerWorkerMaxHeap = "1g"
private const val WasmtimeLibraryProperty = "krwa.wasmtime.library"
private const val WasmtimeLibraryEnvironmentVariable = "KRWA_WASMTIME_LIBRARY"

private val ComponentPackagerWorkerClassNames = listOf(
    KrwaPackageWasmComponentWorkAction::class.java.name,
    "uk.shusek.krwa.component.WasmComponentPackager",
    "uk.shusek.krwa.component.tooling.DefaultWasmToolsExecutionProvider",
    "uk.shusek.krwa.log.Logger",
    "uk.shusek.krwa.runtime.Instance",
    "uk.shusek.krwa.tools.wasm.WasmToolsRuntime",
    "uk.shusek.krwa.wasi.WasiPreview1",
    "uk.shusek.krwa.wasm.WasmModule",
    "kotlinx.io.Buffer",
    "okio.FileSystem",
)
