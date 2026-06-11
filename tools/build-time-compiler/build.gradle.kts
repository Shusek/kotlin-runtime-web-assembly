import uk.shusek.krwa.gradle.*

dependencies {
    add("implementation", libs.kotlinxIoCore)
    add("implementation", krwa("codegen"))
    add("implementation", krwa("compiler"))
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wasm"))
}
