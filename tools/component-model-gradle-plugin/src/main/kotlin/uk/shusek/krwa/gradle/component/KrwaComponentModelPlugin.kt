package uk.shusek.krwa.gradle.component

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider

private const val KrwaTaskGroup = "krwa"

class KrwaComponentModelPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "krwaComponentModel",
            KrwaComponentModelExtension::class.java,
        )

        extension.components.all { component ->
            project.registerNamedComponent(component)
        }

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
            project.configureGeneratedBindingsSourceSet(
                autoWire = extension.autoWireKotlinSourceSet,
                sourceSetName = extension.bindingsSourceSetName,
                outputDirectory = extension.bindingsOutputDirectory,
                generateBindings = generateBindings,
            )
        }
    }
}

private fun Project.registerNamedComponent(component: KrwaComponent) {
    val taskSuffix = component.name.toUpperCamelIdentifier()
    val generateBindings = tasks.register(
        "generate${taskSuffix}KrwaKotlinWitBindings",
        KrwaGenerateKotlinWitBindingsTask::class.java,
    ) { task ->
        task.group = KrwaTaskGroup
        task.description = "Generates Kotlin WIT bindings for the '${component.name}' KRWA component."
        task.outputFile.set(component.bindingsOutputFile)
        task.outputDirectory.set(component.bindingsOutputDirectory)
        task.splitFiles.set(component.bindingsSplitFiles)
        task.packageName.set(component.bindingsPackage)
        task.runtimePackageName.set(component.bindingsRuntimePackage)
        task.runtimeTypes.set(component.runtimeTypes)
        task.pluginHelpers.set(component.pluginHelpers)
        task.guestExports.set(component.guestExports)
    }
    val packageComponent = tasks.register(
        "package${taskSuffix}KrwaComponent",
        KrwaPackageWasmComponentTask::class.java,
    ) { task ->
        task.group = KrwaTaskGroup
        task.description = "Packages the '${component.name}' core module as a KRWA Component Model module."
        task.world.set(component.world)
        task.coreModuleFile.set(component.coreModule.file)
        task.outputFile.set(component.outputFile)
        task.adapterFiles.from(component.adapters)
        task.validateComponent.set(component.validate)
        task.asyncCallback.set(component.asyncCallback)
    }

    afterEvaluate {
        generateBindings.configure { task ->
            task.configureWitInput(component.witFile, component.witPackageDirectory)
        }
        packageComponent.configure { task ->
            task.configureWitInput(component.componentWitFile, component.componentWitPackageDirectory)
        }
        configureKotlinWasmCoreModule(component, packageComponent)
        configureGeneratedBindingsSourceSet(
            autoWire = component.autoWireKotlinSourceSet,
            sourceSetName = component.bindingsSourceSetName,
            outputDirectory = component.bindingsOutputDirectory,
            generateBindings = generateBindings,
        )
    }
}

private fun Project.configureKotlinWasmCoreModule(
    component: KrwaComponent,
    packageComponent: TaskProvider<KrwaPackageWasmComponentTask>,
) {
    val targetName = component.coreModule.kotlinWasmTargetName.orNull ?: return
    val compilationName = component.coreModule.kotlinWasmCompilationName.get()
    val compilationPrefix = if (compilationName == "main") {
        ""
    } else {
        compilationName.toUpperCamelIdentifier()
    }
    val optimizeTaskName =
        "compile${compilationPrefix}ProductionExecutableKotlin${targetName.toUpperCamelIdentifier()}Optimize"
    val optimizeTask = tasks.findByName(optimizeTaskName)
        ?: throw GradleException(
            "KRWA component '${component.name}' references Kotlin/Wasm compilation " +
                "'$targetName/$compilationName', " +
                "but task '$optimizeTaskName' does not exist. Declare a WASI target with " +
                "an executable binary for that compilation before configuring the component.",
        )
    val taskSuffix = component.name.toUpperCamelIdentifier()
    val prepareCoreModule = tasks.register(
        "prepare${taskSuffix}KrwaCoreModule",
        KrwaPrepareKotlinWasmCoreModuleTask::class.java,
    ) { task ->
        task.group = KrwaTaskGroup
        task.description =
            "Normalizes the '${component.name}' Kotlin/Wasm executable for KRWA packaging."
        task.compiledOutputs.from(optimizeTask.outputs.files)
        task.outputFile.set(
            layout.buildDirectory.file("krwa/core-modules/${component.name}.wasm"),
        )
        task.dependsOn(optimizeTask)
    }
    component.coreModule.file.set(
        prepareCoreModule.flatMap(KrwaPrepareKotlinWasmCoreModuleTask::outputFile),
    )
    packageComponent.configure { task ->
        task.dependsOn(prepareCoreModule)
    }
}

private fun String.toUpperCamelIdentifier(): String {
    val words = split(Regex("[^A-Za-z0-9]+")).filter(String::isNotBlank)
    val result = words.joinToString(separator = "") { word ->
        word.replaceFirstChar { character -> character.uppercase() }
    }
    require(result.isNotBlank() && result.first().isLetter()) {
        "KRWA component name '$this' must contain an identifier that starts with a letter."
    }
    return result
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
    autoWire: Property<Boolean>,
    sourceSetName: Property<String>,
    outputDirectory: DirectoryProperty,
    generateBindings: TaskProvider<KrwaGenerateKotlinWitBindingsTask>,
) {
    if (!autoWire.get()) return
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        addGeneratedBindingsToSourceSet(sourceSetName.get(), outputDirectory)
        tasks.matching { task ->
            task.name.startsWith("compile") && (task.name.contains("Kotlin") || task.name.endsWith("Main"))
        }.configureEach { task ->
            task.dependsOn(generateBindings)
        }
    }
}

private fun Project.addGeneratedBindingsToSourceSet(
    sourceSetName: String,
    outputDirectory: DirectoryProperty,
) {
    val kotlinExtension = extensions.findByName("kotlin") ?: return
    val sourceSets = kotlinExtension.invokeNoArg("getSourceSets") ?: return
    val sourceSet = sourceSets.invokeOneArg("findByName", sourceSetName) ?: return
    val kotlinSourceSet = sourceSet.invokeNoArg("getKotlin") ?: return
    kotlinSourceSet.invokeOneArg("srcDir", outputDirectory)
}

private fun Any.invokeNoArg(methodName: String): Any? =
    javaClass.methods.firstOrNull { method -> method.name == methodName && method.parameterCount == 0 }?.invoke(this)

private fun Any.invokeOneArg(methodName: String, argument: Any): Any? =
    javaClass.methods.firstOrNull { method -> method.name == methodName && method.parameterCount == 1 }?.invoke(
        this,
        argument,
    )
