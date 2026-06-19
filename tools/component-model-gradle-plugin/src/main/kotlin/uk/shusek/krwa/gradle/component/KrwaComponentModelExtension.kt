package uk.shusek.krwa.gradle.component

import javax.inject.Inject
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.Property

abstract class KrwaComponentModelExtension
    @Inject
    constructor(
        objects: ObjectFactory,
        layout: ProjectLayout,
        providers: ProviderFactory,
    ) {
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
