import uk.shusek.krwa.gradle.*

dependencies {
    add("implementation", libs.jacksonAnnotations)
    add("implementation", libs.jacksonDatabind)
    add("implementation", libs.zip4j)
    add("implementation", krwa("wasm-tools"))
}

val wasmSpecTestSuiteFolder =
    rootProject.layout.buildDirectory.dir("external-testsuites/wasm")
val wasmSpecTestSuiteArchive =
    rootProject.layout.buildDirectory.file(
        "external-testsuites/wasm-testsuite-$WASM_TEST_SUITE_REVISION.zip"
    )
val offline = gradle.startParameter.isOffline

tasks.register<JavaExec>("prepareWasmSpecTestsuite") {
    group = "build setup"
    description = "Downloads, verifies, and extracts the pinned WebAssembly testsuite."
    dependsOn(tasks.named("classes"))
    inputs.property("testSuiteRepository", WASM_TEST_SUITE_REPOSITORY)
    inputs.property("testSuiteRevision", WASM_TEST_SUITE_REVISION)
    inputs.property("testSuiteArchiveSha256", WASM_TEST_SUITE_ARCHIVE_SHA256)
    inputs.property("offline", offline)
    doNotTrackState(
        "The verified specification checkout is durable local state and must survive Gradle version changes.",
    )
    mainClass.set("uk.shusek.krwa.testgen.TestSuitePrepareCli")
    classpath = mainSourceSet().runtimeClasspath
    doFirst {
        setArgs(
            listOf(
                WASM_TEST_SUITE_REPOSITORY,
                WASM_TEST_SUITE_REVISION,
                WASM_TEST_SUITE_ARCHIVE_SHA256,
                wasmSpecTestSuiteFolder.get().asFile.absolutePath,
                offline.toString(),
            )
        )
    }
}
