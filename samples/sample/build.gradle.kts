@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("25"))
        }
    }

    wasmWasi {
        nodejs()
        binaries.executable()
    }

    wasmJs {
        nodejs()
        browser()
        binaries.executable()
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val kmpShowcaseMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.krwaComponentModel)
                implementation(libs.krwaRuntime)
                implementation(libs.krwaWasm)
                implementation(libs.krwaWasiPreview3)
                implementation(libs.ktorClientMock)
            }
        }
        val iosMain by creating {
            dependsOn(kmpShowcaseMain)
        }
        val iosTest by creating {
            dependsOn(commonTest)
        }
        val iosArm64Main by getting {
            dependsOn(iosMain)
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }
        val iosArm64Test by getting {
            dependsOn(iosTest)
        }
        val iosSimulatorArm64Test by getting {
            dependsOn(iosTest)
        }
        val wasmWasiMain by getting {
            dependencies {
                implementation(libs.kotlinxCoroutinesCore)
                implementation(libs.kotlinxIoCore)
                implementation(libs.kotlinxSerializationJson)
                implementation(libs.kotlinxSerializationJsonIo)
            }
        }
        val jvmMain by getting {
            dependsOn(kmpShowcaseMain)
            dependencies {
                implementation(libs.krwaAnnotations)
                implementation(libs.krwaRuntime)
                implementation(libs.krwaWasi)
                implementation(libs.krwaWasiPreview3)
                implementation(libs.krwaWasm)
                implementation(libs.krwaWasmTools)
                implementation(libs.krwaComponentModel)
            }
        }
        val wasmJsMain by getting {
            dependsOn(kmpShowcaseMain)
        }
    }
}

fun locateKotlinWasiExecutable(): File {
    val wasmFiles =
        layout.buildDirectory.asFile.get().walkTopDown().filter {
            it.isFile && it.extension == "wasm" && "wasmWasi" in it.invariantSeparatorsPath
        }
            .toList()
    require(wasmFiles.size == 1) {
        "Expected exactly one wasmWasi executable, found: ${wasmFiles.joinToString()}"
    }
    return wasmFiles.single()
}

val runShowcaseHost by tasks.registering(JavaExec::class) {
    group = "showcase internals"
    description = "Builds the Kotlin/WASI guest and runs the Kotlin Runtime Web Assembly runtime showcase host."
    dependsOn("jvmJar", "compileProductionExecutableKotlinWasmWasi")
    mainClass.set("uk.shusek.krwa.sample.ShowcaseKt")
    classpath(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
    doFirst {
        systemProperty("krwa.sample.kotlinWasiWasm", locateKotlinWasiExecutable().absolutePath)
    }
}

val runShowcase by tasks.registering {
    group = "showcase"
    description = "Runs the standalone sample showcase."
    dependsOn(runShowcaseHost)
}

val runWasmJsShowcase by tasks.registering {
    group = "showcase"
    description = "Runs the standalone sample showcase on wasmJs with the native browser/Node WebAssembly engine."
    dependsOn("wasmJsNodeDevelopmentRun")
}

val hostIsMacOs = System.getProperty("os.name").contains("Mac", ignoreCase = true)

val runIosShowcase by tasks.registering {
    group = "showcase"
    description = "Runs the standalone sample showcase on the iOS simulator."
    if (hostIsMacOs) {
        dependsOn("iosSimulatorArm64Test")
    } else {
        doFirst {
            logger.lifecycle("Skipping iOS showcase because the host is not macOS.")
        }
    }
    doLast {
        if (hostIsMacOs) {
            println(
                "Kotlin Runtime Web Assembly iOS simulator showcase demonstrated " +
                    "portable runtime, Store imports, traps, WIT contracts, Ktor-backed WASIp3 HTTP, " +
                    "WASIp3 metadata, and storage."
            )
        }
    }
}

tasks.register("checkSample") {
    group = "verification"
    description = "Runs every standalone sample showcase target."
    dependsOn(runShowcase, runWasmJsShowcase, runIosShowcase)
}
