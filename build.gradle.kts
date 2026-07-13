import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import uk.shusek.krwa.gradle.*

plugins {
    // Kotlin Gradle Plugin is on the buildscript classpath via buildSrc's libs.kotlinGradlePlugin.
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.kotlin.kapt") apply false
    id("org.jetbrains.kotlin.multiplatform") apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.dokka)
}

group = "uk.shusek.krwa"
version = providers.gradleProperty("version").get()
val hostOs = providers.gradleProperty("krwa.host.os")
    .orElse(System.getProperty("os.name"))
    .get()
    .lowercase()
val hostIsMacOs = "mac" in hostOs || "darwin" in hostOs

tasks.register("verifyImmutablePublicationVersion") {
    group = "verification"
    description = "Rejects mutable or malformed versions before publishing repository artifacts."
    val publicationVersion = providers.gradleProperty("version")
    inputs.property("publicationVersion", publicationVersion)
    doLast {
        val value = publicationVersion.get()
        check(!value.endsWith("-SNAPSHOT", ignoreCase = true)) {
            "Published KRWA versions must be immutable; got $value"
        }
        check(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?").matches(value)) {
            "Published KRWA version has an unsupported format: $value"
        }
    }
}

plugins.withType<WasmNodeJsRootPlugin>().configureEach {
    extensions.configure<WasmNodeJsEnvSpec>(WasmNodeJsEnvSpec.EXTENSION_NAME) {
        download.set(false)
        command.set("node")
    }
}

allprojects {
    plugins.withType<WasmNodeJsPlugin>().configureEach {
        extensions.configure<WasmNodeJsEnvSpec>(WasmNodeJsEnvSpec.EXTENSION_NAME) {
            download.set(false)
            command.set("node")
        }
    }
}

val jvmProjectPaths =
    listOf(
        ":annotations:processor",
        ":cli",
        ":codegen",
        ":jmh",
        ":log",
        ":test-gen-lib",
        ":wabt",
        ":wasi-test-gen",
        ":wasi-tests",
        ":wasm-corpus",
        ":wasm-tools",
    )

allprojects {
    version = rootProject.version
    if (!hostIsMacOs) {
        tasks.configureEach {
            if ("IosArm64Publication" in name || "IosSimulatorArm64Publication" in name) {
                onlyIf("iOS publications require macOS target outputs") { false }
            }
        }
    }
}

configure(jvmProjectPaths.map(::project)) {
    configureKrwaJvmProject()
}

val dokkaProjectPaths =
    listOf(
        ":annotations:annotations",
        ":annotations:processor",
        ":cli",
        ":codegen",
        ":component-model",
        ":log",
        ":runtime",
        ":wabt",
        ":wasi",
        ":wasi-preview3",
        ":wasm",
        ":wasm-tools",
    )

configure(dokkaProjectPaths.map(::project)) {
    apply(plugin = "org.jetbrains.dokka")
}

dependencies {
    dokkaProjectPaths.forEach { projectPath ->
        dokka(project(projectPath))
    }
}

val multiplatformTestTasks =
    mapOf(
        ":annotations:annotations" to
            listOf("jvmTest", "iosSimulatorArm64Test", "wasmJsNodeTest"),
        ":component-model" to
            listOf("jvmTest", "iosSimulatorArm64Test", "wasmJsNodeTest"),
        ":ios-runtime-smoke" to
            listOf("jvmTest", "iosSimulatorArm64Test"),
        ":runtime" to
            listOf("jvmTest", "iosSimulatorArm64Test", "wasmJsNodeTest"),
        ":wasi" to
            listOf("jvmTest", "iosSimulatorArm64Test", "wasmJsNodeTest"),
        ":wasi-preview3" to
            listOf("jvmTest", "iosSimulatorArm64Test", "wasmJsNodeTest"),
        ":wasm" to
            listOf("jvmTest", "iosSimulatorArm64Test", "wasmJsNodeTest"),
    )

tasks.register("multiplatformTest") {
    group = "verification"
    description = "Runs JVM, iOS simulator, and Wasm Node tests for Kotlin Multiplatform modules."
    dependsOn(
        multiplatformTestTasks.flatMap { (projectPath, taskNames) ->
            taskNames.map { taskName -> "$projectPath:$taskName" }
        }
    )
}
