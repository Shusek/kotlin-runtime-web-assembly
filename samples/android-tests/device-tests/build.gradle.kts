plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.android.junit5)
}

val androidBenchmarkBuildType = "release"

android {
    namespace = "uk.shusek.krwa.runtimeTests"
    compileSdk = 35

    defaultConfig {
        minSdk = 28

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    commandLine(
        repoRoot.resolve("gradlew").absolutePath,
        "--no-daemon",
        "--stacktrace",
        "-q",
        "compileProductionExecutableKotlinWasmWasi",
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
    androidTestImplementation(libs.krwa.wasm)
    androidTestImplementation(libs.krwa.wasmCorpus)
    androidTestImplementation(libs.kotlinx.io.core.jvm)
    androidTestImplementation(libs.junit.jupiter.api)
}
