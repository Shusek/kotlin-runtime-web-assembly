import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType
import uk.shusek.krwa.gradle.*

private data class RuntimeSpecTestShard(
    val index: Int,
    val count: Int,
)

private fun parseRuntimeSpecTestShard(value: String): RuntimeSpecTestShard {
    val parts = value.split('/')
    require(parts.size == 2) {
        "krwa.runtimeTests.shard must use the zero-based <index>/<count> format"
    }
    val index = parts[0].toIntOrNull()
        ?: error("krwa.runtimeTests.shard index must be an integer: $value")
    val count = parts[1].toIntOrNull()
        ?: error("krwa.runtimeTests.shard count must be an integer: $value")
    require(count > 0) { "krwa.runtimeTests.shard count must be greater than zero" }
    require(index in 0 until count) {
        "krwa.runtimeTests.shard index must be between zero and ${count - 1}: $value"
    }
    return RuntimeSpecTestShard(index, count)
}

private fun escapedUpperCamelCase(value: String): String =
    buildString {
        var capitalize = true
        value.forEach { character ->
            when {
                character.isDigit() -> append(character)
                character.isLetter() -> {
                    append(if (capitalize) character.uppercaseChar() else character)
                    capitalize = false
                }
                else -> capitalize = true
            }
        }
    }

private fun runtimeSpecTestClassName(wastPath: String): String {
    val segments = wastPath.replace('\\', '/').split('/')
    val baseName = segments.last().removeSuffix(".wast")
    val logicalName =
        if (segments.size >= 3 && segments[segments.lastIndex - 2].equals("proposals", true)) {
            segments[segments.lastIndex - 1] + "-" + baseName
        } else {
            baseName
        }
    return "uk.shusek.krwa.test.gen.SpecV1${escapedUpperCamelCase(logicalName)}Test"
}

private fun readRuntimeSpecTestWeights(lines: List<String>): Map<String, Int> =
    buildMap {
        lines.filter(String::isNotBlank).forEach { line ->
            val (wastPath, testCountText) = line.split('=', limit = 2)
                .takeIf { parts -> parts.size == 2 }
                ?: error("Invalid runtime specification-test weight: $line")
            val testCount = testCountText.toIntOrNull()
                ?: error("Invalid runtime specification-test count: $line")
            require(testCount > 0) { "Runtime specification-test count must be positive: $line" }
            require(put(wastPath, testCount) == null) {
                "Duplicate runtime specification-test weight: $wastPath"
            }
        }
    }

dependencies {
    add("testImplementation", krwa("runtime"))
    add("testImplementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-tools"))
}

private val runtimeSpecTestShard =
    providers.gradleProperty("krwa.runtimeTests.shard")
        .map(::parseRuntimeSpecTestShard)

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

    runtimeSpecTestShard.orNull?.let { shard ->
        val profileDirectory =
            layout.projectDirectory.dir("src/test-gen/wasm-spec/wasmtime-jvm")
        val includedWasts = profileDirectory.file("included-wasts.txt").asFile.readListFile()
        val weightsFile = profileDirectory.file("test-counts.txt").asFile
        val testWeights = readRuntimeSpecTestWeights(weightsFile.readLines())
        require(testWeights.keys == includedWasts.toSet()) {
            "Runtime specification-test weights must match included-wasts.txt exactly"
        }
        require(testWeights.values.sum() == 27_382) {
            "Expected runtime specification-test weights to cover 27382 tests"
        }
        val assignments = List(shard.count) { mutableListOf<String>() }
        val assignedTestCounts = IntArray(shard.count)
        includedWasts
            .map { wastPath -> wastPath to checkNotNull(testWeights[wastPath]) }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { (_, count) -> count }
                    .thenBy { it.first },
            )
            .forEach { (wastPath, testCount) ->
                val lightestShard =
                    assignedTestCounts.indices.minWith(
                        compareBy<Int> { index -> assignedTestCounts[index] }
                            .thenBy { index -> index },
                    )
                assignments[lightestShard] += wastPath
                assignedTestCounts[lightestShard] += testCount
            }
        val selectedTestClasses =
            assignments[shard.index]
                .map(::runtimeSpecTestClassName)
        require(selectedTestClasses.isNotEmpty()) {
            "Runtime specification-test shard ${shard.index}/${shard.count} is empty"
        }
        inputs.property("runtimeSpecTestShard", "${shard.index}/${shard.count}")
        inputs.property("runtimeSpecTestClasses", selectedTestClasses)
        filter {
            selectedTestClasses.forEach(::includeTestsMatching)
            isFailOnNoMatchingTests = true
        }
    }
}

private val wasmSpecProfile = "wasmtime-jvm"
private val wasmSpecProfileDirectory =
    layout.projectDirectory.dir("src/test-gen/wasm-spec/$wasmSpecProfile").asFile
private val wasmSpecTestWeights =
    readRuntimeSpecTestWeights(wasmSpecProfileDirectory.resolve("test-counts.txt").readLines())

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

            check(wasmSpecTestWeights.keys == includedWasts.toSet()) {
                "Expected test-counts.txt to classify every included WAST exactly once"
            }
            check(wasmSpecTestWeights.values.sum() == testCount) {
                "Expected test-counts.txt to cover $testCount tests, got " +
                    wasmSpecTestWeights.values.sum()
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
