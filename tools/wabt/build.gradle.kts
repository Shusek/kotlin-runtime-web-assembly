import uk.shusek.krwa.gradle.krwa

dependencies {
    add("implementation", libs.zerofs)
    add("implementation", krwa("log"))
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wasi"))
    add("implementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-corpus"))
}
