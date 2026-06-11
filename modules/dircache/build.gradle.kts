import uk.shusek.krwa.gradle.*

dependencies {
    add("implementation", krwa("compiler"))
    add("testImplementation", libs.zerofs)
    add("testImplementation", krwa("runtime"))
    add("testImplementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-corpus"))
}
