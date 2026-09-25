import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.junit5)
}

val androidBenchmarkBuildType = "release"

android {
    namespace = "uk.shusek.krwa.runtimeTests"
    compileSdk = 37

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["runnerBuilder"] = "de.mannodermaus.junit5.AndroidJUnit5Builder"
        testInstrumentationRunnerArguments["krwaJsonSequenceBytes"] = "65536"
        testInstrumentationRunnerArguments["krwaBenchmarkBuildType"] = androidBenchmarkBuildType
    }
    testBuildType = androidBenchmarkBuildType
    val krwaDimension = "krwaDimension"
    flavorDimensions += krwaDimension
    productFlavors {
        create("runtime") { dimension = krwaDimension }
        // add future modules similar to the runtime configuration above.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }

    sourceSets {
        named("androidTest") {
            assets.directories.add(layout.buildDirectory.dir("generated/krwaJsonSequenceAssets").get().asFile.absolutePath)
        }
    }

    packaging {
        resources {
            pickFirsts.add("logging.properties")
            pickFirsts.add("THIRD-PARTY.txt")
            pickFirsts.add("META-INF/kotlin-project-structure-metadata.json")
            pickFirsts.add("**/default/**")
            pickFirsts.add("linuxMain/default/manifest")
            excludes.add("META-INF/jpms.args")
        }
    }
}

val repoRoot = rootProject.projectDir.parentFile.parentFile
val jsonSequenceGuestDir =
    repoRoot.resolve("modules/component-model/src/test/fixtures/json-sequence-guest")

val compileJsonSequenceGuestWasm by tasks.registering(Exec::class) {
    workingDir = jsonSequenceGuestDir
    val arguments =
        mutableListOf(
            "--no-daemon",
            "--stacktrace",
            "-q",
        )
    if (gradle.startParameter.isOffline) {
        arguments += "--offline"
    }
    arguments += "compileProductionExecutableKotlinWasmWasi"
    commandLine(
        repoRoot.resolve("gradlew").absolutePath,
        *arguments.toTypedArray(),
    )
}

val copyJsonSequenceGuestWasm by tasks.registering(Copy::class) {
    dependsOn(compileJsonSequenceGuestWasm)
    from(jsonSequenceGuestDir.resolve("build/compileSync/wasmWasi/main/productionExecutable/kotlin")) {
        include("*.wasm")
        rename { "krwa-json-sequence-guest.wasm" }
    }
    into(layout.buildDirectory.dir("generated/krwaJsonSequenceAssets"))
}

tasks.matching { it.name.startsWith("mergeRuntime") && it.name.endsWith("AndroidTestAssets") }.configureEach {
    dependsOn(copyJsonSequenceGuestWasm)
}

dependencies {
    // common dependencies can be added here
    // if you need to add a dependency on a specific module, you can use
    // "androidTest<productFlavorName>Implementation"(<your dependency>)
    // e.g.
    // "androidTestRuntimeImplementation"(libs.krwa.runtime)
    androidTestImplementation(libs.krwa.wasi)
    androidTestImplementation(libs.krwa.runtime)
    androidTestImplementation(libs.krwa.runtime.wasmtime.android)
    androidTestImplementation(libs.krwa.wasm)
    androidTestImplementation(libs.kotlinx.io.core.jvm)
    androidTestImplementation(libs.junit.jupiter.api)
}

// Android 17 can reject AGP's implicit user (-2) and still produce a green zero-test report.
// Run this published-artifact acceptance suite for an explicit user and require all four tests.
val adbExecutable = androidComponents.sdkComponents.adb
val acceptanceSerial = providers.gradleProperty("krwa.android.serial")
val acceptanceUser = providers.gradleProperty("krwa.android.user").orElse("0")
tasks.register("connectedKrwaAcceptanceTest") {
    group = "verification"
    description = "Executes and verifies the four published-runtime tests on an Android device."
    dependsOn("assembleRuntimeReleaseAndroidTest")
    doLast {
        val adb = listOf(adbExecutable.get().asFile.absolutePath) +
            (acceptanceSerial.orNull?.let { listOf("-s", it) } ?: emptyList())
        val user = acceptanceUser.get().toInt().also { require(it >= 0) }.toString()
        fun runAdb(vararg arguments: String): String {
            val outputFile = temporaryDir.resolve("adb-output.txt")
            val process = ProcessBuilder(adb + arguments)
                .redirectErrorStream(true).redirectOutput(outputFile).start()
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                error("Android acceptance command timed out after five minutes.")
            }
            val output = outputFile.readText()
            check(process.exitValue() == 0) { "adb failed: $output" }
            return output
        }
        val apk = layout.buildDirectory.file(
            "outputs/apk/androidTest/runtime/release/device-tests-runtime-release-androidTest.apk",
        ).get().asFile
        val testPackage = "uk.shusek.krwa.runtimeTests.test"
        runAdb("install", "--user", user, "-r", "-t", apk.absolutePath)
        try {
            val result = runAdb(
                "shell", "am", "instrument", "--user", user, "-w", "-r",
                "-e", "runnerBuilder", "de.mannodermaus.junit5.AndroidJUnit5Builder",
                "-e", "class", "uk.shusek.krwa.runtimeTests.JsonSequenceDecodeBenchmarkAndroidTest",
                "-e", "krwaBenchmarkBuildType", androidBenchmarkBuildType,
                "-e", "krwaJsonSequenceBytes", "65536",
                "$testPackage/androidx.test.runner.AndroidJUnitRunner",
            )
            layout.buildDirectory.file("reports/krwa-acceptance/instrumentation.txt").get().asFile.apply {
                parentFile.mkdirs()
                writeText(result)
            }
            val passedTests = result.lineSequence().count { it.trim() == "INSTRUMENTATION_STATUS_CODE: 0" }
            check(passedTests == 4 && "OK (4 tests)" in result && "FAILURES!!!" !in result) {
                "Android runtime acceptance did not pass all four tests:\n$result"
            }
            logger.lifecycle("Android runtime acceptance passed: 4 tests, explicit Android user $user.")
        } finally {
            runAdb("uninstall", "--user", user, testPackage)
        }
    }
}
