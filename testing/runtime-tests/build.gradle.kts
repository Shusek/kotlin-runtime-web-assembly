import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import uk.shusek.krwa.gradle.*

dependencies {
    add("testImplementation", krwa("runtime"))
    add("testImplementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-tools"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    maxParallelForks =
        providers.gradleProperty("krwa.runtimeTests.maxParallelForks")
            .map(String::toInt)
            .map { configuredForks ->
                require(configuredForks > 0) {
                    "krwa.runtimeTests.maxParallelForks must be greater than zero"
                }
                configuredForks
            }
            .getOrElse(1)
    systemProperty("krwa.wasmTools.forceEmbedded", "true")
}

private val wasmSpecProfile = "wasmtime-jvm"
private val wasmSpecProfileDirectory =
    layout.projectDirectory.dir("src/test-gen/wasm-spec/$wasmSpecProfile").asFile

registerWasmSpecTests(wasmSpecProfile)

val generatedWasmSpecSources =
    layout.buildDirectory.dir("generated/test-sources/test-gen")
val verifyGeneratedWasmSpecInventory =
    tasks.register("verifyGeneratedWasmSpecInventory") {
        group = "verification"
        description = "Verifies the pinned generated WebAssembly specification-test inventory."
        dependsOn("generateWasmSpecTests")
        inputs.dir(generatedWasmSpecSources)
        inputs.dir(wasmSpecProfileDirectory)
        doLast {
            val includedWasts =
                wasmSpecProfileDirectory.resolve("included-wasts.txt").readListFile()
            val parserExcludedWasts =
                wasmSpecProfileDirectory.resolve("excluded-wasts.txt").readListFile()
            val runtimeExcludedWasts =
                wasmSpecProfileDirectory.resolve("excluded-runtime-wasts.txt").readListFile()
            val parserExcludedTests =
                wasmSpecProfileDirectory.resolve("excluded-tests.txt").readListFile()
            val runtimeExcludedTests =
                wasmSpecProfileDirectory.resolve("excluded-runtime-tests.txt").readListFile()
            val generatedSources =
                generatedWasmSpecSources.get().asFile
                    .walkTopDown()
                    .filter { file -> file.isFile && file.extension == "kt" }
                    .toList()
            val testCount =
                generatedSources.sumOf { file ->
                    file.useLines { lines -> lines.count { line -> line.trim() == "@Test" } }
                }
            val disabledCount =
                generatedSources.sumOf { file ->
                    file.useLines { lines ->
                        lines.count { line -> line.trim().startsWith("@Disabled(") }
                    }
                }

            check(includedWasts.size == 80) {
                "Expected 80 included Wasmtime/Pulley WAST files, got ${includedWasts.size}"
            }
            check(parserExcludedWasts.size == 155) {
                "Expected 155 parser/spec WAST exclusions, got ${parserExcludedWasts.size}"
            }
            check(runtimeExcludedWasts.size == 210) {
                "Expected 210 Wasmtime/Pulley WAST exclusions, got " +
                    runtimeExcludedWasts.size
            }
            check(parserExcludedTests.size == 18) {
                "Expected 18 parser/spec method exclusions, got ${parserExcludedTests.size}"
            }
            check(runtimeExcludedTests.size == 19) {
                "Expected 19 Wasmtime/Pulley method exclusions, got " +
                    runtimeExcludedTests.size
            }
            check(
                includedWasts.size +
                    parserExcludedWasts.size +
                    runtimeExcludedWasts.size == 445
            ) {
                "Expected the complete 445-file pinned WebAssembly WAST classification"
            }
            check(generatedSources.size == includedWasts.size) {
                "Expected ${includedWasts.size} generated WebAssembly spec test sources, got " +
                    generatedSources.size
            }
            check(testCount == 27_382) {
                "Expected the pinned Wasmtime/Pulley generated test budget, got $testCount"
            }
            check(disabledCount == parserExcludedTests.size + runtimeExcludedTests.size) {
                "Expected ${parserExcludedTests.size + runtimeExcludedTests.size} disabled " +
                    "WebAssembly spec tests, got $disabledCount"
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyGeneratedWasmSpecInventory)
}
