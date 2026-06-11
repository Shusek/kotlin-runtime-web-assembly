import uk.shusek.krwa.gradle.*

dependencies {
    add("implementation", libs.commonsLang3)
    add("implementation", krwa("log"))
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wasm"))
    add("implementation", krwa("wasm-tools"))
    add("testImplementation", libs.junitJupiterParams)
    add("testImplementation", krwa("compiler"))
}
