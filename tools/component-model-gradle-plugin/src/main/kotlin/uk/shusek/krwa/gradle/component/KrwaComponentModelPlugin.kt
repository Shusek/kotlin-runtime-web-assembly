package uk.shusek.krwa.gradle.component

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.TaskProvider

private const val KrwaTaskGroup = "krwa"

class KrwaComponentModelPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "krwaComponentModel",
            KrwaComponentModelExtension::class.java,
        )

        val generateBindings = project.tasks.register(
            "generateKrwaKotlinWitBindings",
            KrwaGenerateKotlinWitBindingsTask::class.java,
        ) { task ->
            task.group = KrwaTaskGroup
            task.description = "Generates Kotlin WIT bindings with KRWA."
            task.outputFile.set(extension.bindingsOutputFile)
            task.outputDirectory.set(extension.bindingsOutputDirectory)
            task.splitFiles.set(extension.bindingsSplitFiles)
            task.packageName.set(extension.bindingsPackage)
            task.runtimePackageName.set(extension.bindingsRuntimePackage)
            task.runtimeTypes.set(extension.runtimeTypes)
            task.pluginHelpers.set(extension.pluginHelpers)
            task.guestExports.set(extension.guestExports)
        }

        val packageComponent = project.tasks.register(
            "packageKrwaComponent",
            KrwaPackageWasmComponentTask::class.java,
        ) { task ->
            task.group = KrwaTaskGroup
            task.description = "Packages a core WebAssembly module as a KRWA Component Model module."
            task.world.set(extension.componentWorld)
            task.coreModuleFile.set(extension.componentCoreModuleFile)
            task.outputFile.set(extension.componentOutputFile)
            task.adapterFiles.from(extension.componentAdapters)
            task.validateComponent.set(extension.validateComponent)
            task.asyncCallback.set(extension.asyncCallback)
        }

        project.afterEvaluate {
            generateBindings.configure { task ->
                task.configureWitInput(extension.witFile, extension.witPackageDirectory)
            }
            packageComponent.configure { task ->
                task.configureWitInput(extension.componentWitFile, extension.componentWitPackageDirectory)
            }
            project.configureGeneratedBindingsSourceSet(extension, generateBindings)
        }
    }
}

private fun KrwaGenerateKotlinWitBindingsTask.configureWitInput(
    file: RegularFileProperty,
    directory: DirectoryProperty,
) {
    if (directory.isPresent) {
        witPackageDirectory.set(directory)
    } else {
        witFile.set(file)
    }
}

private fun KrwaPackageWasmComponentTask.configureWitInput(
    file: RegularFileProperty,
    directory: DirectoryProperty,
) {
    if (directory.isPresent) {
        witPackageDirectory.set(directory)
    } else {
        witFile.set(file)
    }
}

private fun Project.configureGeneratedBindingsSourceSet(
    extension: KrwaComponentModelExtension,
    generateBindings: TaskProvider<KrwaGenerateKotlinWitBindingsTask>,
) {
    if (!extension.autoWireKotlinSourceSet.get()) return
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        addGeneratedBindingsToSourceSet(extension)
        tasks.matching { task ->
            task.name.startsWith("compile") && (task.name.contains("Kotlin") || task.name.endsWith("Main"))
        }.configureEach { task ->
            task.dependsOn(generateBindings)
        }
    }
}

private fun Project.addGeneratedBindingsToSourceSet(extension: KrwaComponentModelExtension) {
    val kotlinExtension = extensions.findByName("kotlin") ?: return
    val sourceSets = kotlinExtension.invokeNoArg("getSourceSets") ?: return
    val sourceSet = sourceSets.invokeOneArg("findByName", extension.bindingsSourceSetName.get()) ?: return
    val kotlinSourceSet = sourceSet.invokeNoArg("getKotlin") ?: return
    kotlinSourceSet.invokeOneArg("srcDir", extension.bindingsOutputDirectory)
}

private fun Any.invokeNoArg(methodName: String): Any? =
    javaClass.methods.firstOrNull { method -> method.name == methodName && method.parameterCount == 0 }?.invoke(this)

private fun Any.invokeOneArg(methodName: String, argument: Any): Any? =
    javaClass.methods.firstOrNull { method -> method.name == methodName && method.parameterCount == 1 }?.invoke(
        this,
        argument,
    )
