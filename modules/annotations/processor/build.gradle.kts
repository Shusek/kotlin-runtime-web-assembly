import uk.shusek.krwa.gradle.*

dependencies {
    add("implementation", libs.kspSymbolProcessingApi)
    add("implementation", krwa("annotations"))
    add("implementation", krwa("codegen"))
}
