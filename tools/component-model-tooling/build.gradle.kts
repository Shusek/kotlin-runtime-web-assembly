import uk.shusek.krwa.gradle.krwa

dependencies {
    api(project(":component-model"))
    implementation(libs.kotlinxIoCore)
    implementation(krwa("log"))
    implementation(krwa("runtime"))
    implementation(krwa("wasi"))
    implementation(krwa("wasm"))
    implementation(krwa("wasm-tools"))
}
