package uk.shusek.krwa.gradle.component

import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.Property

/**
 * A named Component Model build.
 *
 * One Gradle project may produce multiple isolated components from different
 * Kotlin/Wasm targets while sharing the same Kotlin common sources.
 */
abstract class KrwaComponent
    @Inject
    constructor(
        private val componentName: String,
        objects: ObjectFactory,
        layout: ProjectLayout,
        providers: ProviderFactory,
    ) : Named {
        override fun getName(): String = componentName

        val witFile: RegularFileProperty = objects.fileProperty()
            .convention(layout.projectDirectory.file("src/main/wit/$componentName.wit"))

        val witPackageDirectory: DirectoryProperty = objects.directoryProperty()

        val bindingsPackage: Property<String> = objects.property(String::class.java)
            .convention("uk.shusek.krwa.generated.$componentName")

        val bindingsRuntimePackage: Property<String> = objects.property(String::class.java)
            .convention("uk.shusek.krwa.component")

        val bindingsSourceSetName: Property<String> = objects.property(String::class.java)
            .convention("${componentName}Main")

        val bindingsOutputDirectory: DirectoryProperty = objects.directoryProperty()
            .convention(
                providers.provider {
                    layout.buildDirectory.dir(
                        "generated/wit-bindings/$componentName/kotlin",
                    ).get()
                },
            )

        val bindingsOutputFileName: Property<String> = objects.property(String::class.java)
            .convention("KrwaComponentBindings.kt")

        val bindingsOutputFile: RegularFileProperty = objects.fileProperty()
            .convention(
                bindingsOutputDirectory.file(
                    providers.provider {
                        "${bindingsPackage.get().replace('.', '/')}/${bindingsOutputFileName.get()}"
                    },
                ),
            )

        val bindingsSplitFiles: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val autoWireKotlinSourceSet: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val runtimeTypes: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val pluginHelpers: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val guestExports: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val componentWitFile: RegularFileProperty = objects.fileProperty()
            .convention(witFile)

        val componentWitPackageDirectory: DirectoryProperty = objects.directoryProperty()
            .convention(witPackageDirectory)

        val world: Property<String> = objects.property(String::class.java)
            .convention(componentName)

        val coreModule: KrwaCoreModule = objects.newInstance(KrwaCoreModule::class.java)

        val outputFile: RegularFileProperty = objects.fileProperty()
            .convention(layout.buildDirectory.file("component/$componentName.wasm"))

        val adapters: ConfigurableFileCollection = objects.fileCollection()

        val validate: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(true)

        val asyncCallback: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        fun adapter(source: Any) {
            adapters.from(source)
        }
    }

/**
 * Selects the core module consumed by a named component.
 *
 * [fromKotlinWasm] is resolved against the real Kotlin Gradle model by the
 * plugin, so callers do not need to reconstruct Kotlin/Wasm output paths.
 */
abstract class KrwaCoreModule
    @Inject
    constructor(objects: ObjectFactory) {
        val file: RegularFileProperty = objects.fileProperty()

        internal val kotlinWasmTargetName: Property<String> = objects.property(String::class.java)
        internal val kotlinWasmCompilationName: Property<String> = objects.property(String::class.java)
            .convention("main")

        fun fromKotlinWasm(targetName: String) {
            fromKotlinWasm(targetName, "main")
        }

        fun fromKotlinWasm(targetName: String, compilationName: String) {
            require(targetName.isNotBlank()) { "Kotlin/Wasm target name must not be blank." }
            require(compilationName.isNotBlank()) { "Kotlin/Wasm compilation name must not be blank." }
            kotlinWasmTargetName.set(targetName)
            kotlinWasmCompilationName.set(compilationName)
        }
    }

abstract class KrwaComponentModelExtension
    @Inject
    constructor(
        objects: ObjectFactory,
        layout: ProjectLayout,
        providers: ProviderFactory,
    ) {
        val components: NamedDomainObjectContainer<KrwaComponent> =
            objects.domainObjectContainer(KrwaComponent::class.java) { name ->
                objects.newInstance(KrwaComponent::class.java, name)
            }

        fun components(action: Action<NamedDomainObjectContainer<KrwaComponent>>) {
            action.execute(components)
        }

        fun component(name: String, action: Action<KrwaComponent>) {
            action.execute(components.maybeCreate(name))
        }

        val witFile: RegularFileProperty = objects.fileProperty()
            .convention(layout.projectDirectory.file("src/main/wit/component.wit"))

        val witPackageDirectory: DirectoryProperty = objects.directoryProperty()

        val bindingsPackage: Property<String> = objects.property(String::class.java)
            .convention("uk.shusek.krwa.generated")

        val bindingsRuntimePackage: Property<String> = objects.property(String::class.java)
            .convention("uk.shusek.krwa.component")

        val bindingsSourceSetName: Property<String> = objects.property(String::class.java)
            .convention("wasmWasiMain")

        val bindingsOutputDirectory: DirectoryProperty = objects.directoryProperty()
            .convention(
                providers.provider {
                    layout.buildDirectory.dir(
                        "generated/wit-bindings/${bindingsSourceSetName.get()}/kotlin",
                    ).get()
                },
            )

        val bindingsOutputFileName: Property<String> = objects.property(String::class.java)
            .convention("KrwaComponentBindings.kt")

        val bindingsOutputFile: RegularFileProperty = objects.fileProperty()
            .convention(
                bindingsOutputDirectory.file(
                    providers.provider {
                        "${bindingsPackage.get().replace('.', '/')}/${bindingsOutputFileName.get()}"
                    },
                ),
            )

        val bindingsSplitFiles: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val autoWireKotlinSourceSet: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val runtimeTypes: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val pluginHelpers: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val guestExports: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        val componentWitFile: RegularFileProperty = objects.fileProperty()
            .convention(witFile)

        val componentWitPackageDirectory: DirectoryProperty = objects.directoryProperty()
            .convention(witPackageDirectory)

        val componentWorld: Property<String> = objects.property(String::class.java)
            .convention("component")

        val componentCoreModuleFile: RegularFileProperty = objects.fileProperty()

        val componentOutputFile: RegularFileProperty = objects.fileProperty()
            .convention(layout.buildDirectory.file("component/main/component.wasm"))

        val componentAdapters: ConfigurableFileCollection = objects.fileCollection()

        val validateComponent: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(true)

        val asyncCallback: Property<Boolean> = objects.property(Boolean::class.java)
            .convention(false)

        fun adapter(source: Any) {
            componentAdapters.from(source)
        }
    }
