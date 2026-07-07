import java.net.URI
import java.security.MessageDigest
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import uk.shusek.krwa.gradle.configureKrwaPom
import uk.shusek.krwa.gradle.configureKrwaRepositories

group = rootProject.group

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("maven-publish")
}

extensions.configure<BasePluginExtension> {
    archivesName.set("runtime-wasmtime-android")
}

val wasmtimePulleyVersion = libs.versions.wasmtime.get()
val wasmtimePulleyAndroidArchiveName =
    "wasmtime-v$wasmtimePulleyVersion-aarch64-android-c-api.tar.xz"
val wasmtimePulleyAndroidArchiveSha256 =
    "94b75abf63c6e16fa8c47189e3c5c569ecc34f3f3814ec5541dcf29268a03661"
val wasmtimePulleyAndroidLibSha256 =
    "74141aa32ef91b12c0ed743d515f7384a6bcf35317b434a63aaba7b0511aaeb3"
val wasmtimePulleyAndroidJniLib =
    layout.projectDirectory.file("src/androidMain/jniLibs/arm64-v8a/libwasmtime.so")
val krwaPulleyAndroidJniLib =
    layout.projectDirectory.file("src/androidMain/jniLibs/arm64-v8a/libkrwa_pulley_android.so")
val krwaWasmtimeP3BridgeAndroidJniLib =
    layout.projectDirectory.file("src/androidMain/jniLibs/arm64-v8a/libkrwa_wasmtime_p3_bridge.so")
val krwaWasmtimeP3AndroidJniLib =
    layout.projectDirectory.file("src/androidMain/jniLibs/arm64-v8a/libkrwa_wasmtime_p3_android.so")
val androidJniLibs =
    listOf(
        wasmtimePulleyAndroidJniLib,
        krwaPulleyAndroidJniLib,
        krwaWasmtimeP3BridgeAndroidJniLib,
        krwaWasmtimeP3AndroidJniLib,
    )
val wasmtimeP3BridgeDirectory = project(":runtime").layout.projectDirectory.dir("src/wasmtime-p3-bridge")
val wasmtimeP3BridgeAndroidTargetDirectory = layout.buildDirectory.dir("wasmtime-p3-bridge-android/target")
val androidNdkVersion = libs.versions.android.ndk.get()
val androidMinSdk = libs.versions.android.minSdk.get().toInt()
val android16KbPageSize = 16 * 1024
val android16KbElfLinkerFlags =
    listOf(
        "-Wl,-z,max-page-size=$android16KbPageSize",
        "-Wl,-z,common-page-size=$android16KbPageSize",
    )
val android16KbElfRustFlags = android16KbElfLinkerFlags.joinToString(" ") { flag -> "-C link-arg=$flag" }

fun androidSdkDirectory(): File {
    val sdkPath = System.getenv("ANDROID_HOME")?.takeIf(String::isNotBlank)
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf(String::isNotBlank)
        ?: "${System.getProperty("user.home")}/Library/Android/sdk"
    return file(sdkPath)
}

fun androidNdkDirectory(): File {
    val ndkPath = System.getenv("ANDROID_NDK_HOME")?.takeIf(String::isNotBlank)
        ?: System.getenv("ANDROID_NDK_ROOT")?.takeIf(String::isNotBlank)
    return ndkPath?.let(::file) ?: androidSdkDirectory().resolve("ndk/$androidNdkVersion")
}

fun androidNdkHostTag(): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("mac") -> "darwin-x86_64"
        osName.contains("windows") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
}

fun executableFromPath(name: String): File? = System.getenv("PATH")
    .orEmpty()
    .split(File.pathSeparator)
    .asSequence()
    .filter(String::isNotBlank)
    .map { path -> file(path).resolve(name) }
    .firstOrNull { executable -> executable.isFile && executable.canExecute() }

fun executableFromEnvOrPath(envName: String, name: String): File? = System.getenv(envName)
    ?.takeIf(String::isNotBlank)
    ?.let(::file)
    ?.takeIf { executable -> executable.isFile && executable.canExecute() }
    ?: executableFromPath(name)

fun localCodexRustExecutable(name: String): File? =
    listOf(
        layout.projectDirectory.asFile.resolve("build/codex-rust/cargo/bin/$name"),
        rootProject.layout.projectDirectory.asFile.resolve("build/codex-rust/cargo/bin/$name"),
        rootProject.layout.projectDirectory.asFile.parentFile.resolve("suvio/build/codex-rust/cargo/bin/$name"),
    ).firstOrNull { executable -> executable.isFile && executable.canExecute() }

fun cargoBinary(): File? = executableFromEnvOrPath("CARGO", "cargo") ?: localCodexRustExecutable("cargo")

fun rustupBinary(): File? = executableFromEnvOrPath("RUSTUP", "rustup") ?: localCodexRustExecutable("rustup")

fun cargoStableCommand(cargo: File): List<String> {
    val path = cargo.absolutePath.replace('\\', '/')
    return if ("/toolchains/" in path) {
        listOf(cargo.absolutePath)
    } else {
        listOf(cargo.absolutePath, "+stable")
    }
}

fun localCodexRustEnvironment(): Map<String, String> =
    listOf(
        layout.projectDirectory.asFile,
        rootProject.layout.projectDirectory.asFile,
        rootProject.layout.projectDirectory.asFile.parentFile.resolve("suvio"),
    )
        .map { directory -> directory.resolve("build/codex-rust") }
        .firstOrNull { rustDirectory ->
            rustDirectory.resolve("rustup").isDirectory && rustDirectory.resolve("cargo").isDirectory
        }
        ?.let { rustDirectory ->
            mapOf(
                "RUSTUP_HOME" to rustDirectory.resolve("rustup").absolutePath,
                "CARGO_HOME" to rustDirectory.resolve("cargo").absolutePath,
            )
        }
        ?: emptyMap()

fun codexRustEnvironmentFor(executable: File): Map<String, String> {
    val codexRustDirectory = executable.parentFile?.parentFile?.parentFile ?: return emptyMap()
    return if (
        codexRustDirectory.resolve("rustup").isDirectory &&
        codexRustDirectory.resolve("cargo").isDirectory
    ) {
        mapOf(
            "RUSTUP_HOME" to codexRustDirectory.resolve("rustup").absolutePath,
            "CARGO_HOME" to codexRustDirectory.resolve("cargo").absolutePath,
        )
    } else {
        emptyMap()
    }
}

fun runProcess(command: List<String>, workingDirectory: File, environment: Map<String, String> = emptyMap()) {
    val processBuilder = ProcessBuilder(command)
        .directory(workingDirectory)
        .inheritIO()
    processBuilder.environment().putAll(environment)
    val exitCode = processBuilder.start().waitFor()
    check(exitCode == 0) {
        "Command failed with exit code $exitCode: ${command.joinToString(" ")}"
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun androidLlvmObjdumpExecutable(): File {
    val executableName = if (System.getProperty("os.name").lowercase().contains("windows")) {
        "llvm-objdump.exe"
    } else {
        "llvm-objdump"
    }
    return androidNdkDirectory()
        .resolve("toolchains/llvm/prebuilt/${androidNdkHostTag()}/bin")
        .resolve(executableName)
}

fun elfLoadSegmentAlignments(file: File, llvmObjdump: File): List<Int> {
    val process =
        ProcessBuilder(llvmObjdump.absolutePath, "-p", file.absolutePath)
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
    val exitCode = process.waitFor()
    check(exitCode == 0) {
        "Failed to inspect ${file.invariantSeparatorsPath} with ${llvmObjdump.invariantSeparatorsPath}: $output"
    }
    return Regex("""LOAD.* align 2\*\*(\d+)""")
        .findAll(output)
        .map { match -> 1 shl match.groupValues[1].toInt() }
        .toList()
}

val downloadWasmtimePulleyAndroidJniLib by tasks.registering {
    description = "Downloads the Wasmtime Pulley Android C API shared library for arm64-v8a."
    inputs.property("wasmtimePulleyVersion", wasmtimePulleyVersion)
    inputs.property("wasmtimePulleyAndroidArchiveSha256", wasmtimePulleyAndroidArchiveSha256)
    inputs.property("wasmtimePulleyAndroidLibSha256", wasmtimePulleyAndroidLibSha256)
    outputs.file(wasmtimePulleyAndroidJniLib)
    outputs.upToDateWhen {
        val output = wasmtimePulleyAndroidJniLib.asFile
        output.isFile && sha256(output) == wasmtimePulleyAndroidLibSha256
    }

    doLast {
        val downloadDir = layout.buildDirectory.dir("wasmtime-pulley-android").get().asFile
        val archive = downloadDir.resolve(wasmtimePulleyAndroidArchiveName)
        val extractedDir = downloadDir.resolve("extracted")
        val output = wasmtimePulleyAndroidJniLib.asFile

        downloadDir.mkdirs()
        val archiveUrl =
            "https://github.com/bytecodealliance/wasmtime/releases/download/" +
                "v$wasmtimePulleyVersion/$wasmtimePulleyAndroidArchiveName"
        if (!archive.isFile || sha256(archive) != wasmtimePulleyAndroidArchiveSha256) {
            URI(archiveUrl).toURL().openStream().use { input ->
                archive.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }
        check(sha256(archive) == wasmtimePulleyAndroidArchiveSha256) {
            "Downloaded $wasmtimePulleyAndroidArchiveName SHA-256 mismatch"
        }

        extractedDir.deleteRecursively()
        extractedDir.mkdirs()
        val tarExitCode =
            ProcessBuilder("tar", "-xf", archive.absolutePath, "-C", extractedDir.absolutePath)
                .inheritIO()
                .start()
                .waitFor()
        check(tarExitCode == 0) {
            "Failed to extract $wasmtimePulleyAndroidArchiveName with tar, exit code $tarExitCode"
        }

        val source =
            extractedDir
                .resolve("wasmtime-v$wasmtimePulleyVersion-aarch64-android-c-api")
                .resolve("lib/libwasmtime.so")
        check(source.isFile) {
            "Expected libwasmtime.so in $wasmtimePulleyAndroidArchiveName"
        }
        check(sha256(source) == wasmtimePulleyAndroidLibSha256) {
            "Extracted libwasmtime.so SHA-256 mismatch"
        }

        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
    }
}

val buildKrwaPulleyAndroidJniLib by tasks.registering {
    description = "Builds the KRWA Pulley Android JNI bridge for arm64-v8a."
    inputs.property("androidNdkVersion", androidNdkVersion)
    inputs.property("androidMinSdk", androidMinSdk)
    inputs.property("cxxRuntime", "static")
    inputs.file(layout.projectDirectory.file("src/androidMain/cpp/krwa_pulley_android.cpp"))
    outputs.file(krwaPulleyAndroidJniLib)

    doLast {
        val ndkDir = androidNdkDirectory()
        val toolchainBin = ndkDir.resolve("toolchains/llvm/prebuilt/${androidNdkHostTag()}/bin")
        val clang = toolchainBin.resolve("aarch64-linux-android$androidMinSdk-clang++")
        check(clang.isFile) {
            "Expected Android NDK clang++ at ${clang.invariantSeparatorsPath}."
        }

        val output = krwaPulleyAndroidJniLib.asFile
        output.parentFile.mkdirs()
        val source = layout.projectDirectory.file("src/androidMain/cpp/krwa_pulley_android.cpp").asFile
        val exitCode =
            ProcessBuilder(
                clang.absolutePath,
                "-shared",
                "-fPIC",
                "-std=c++17",
                "-O2",
                "-Wall",
                "-Wextra",
                "-static-libstdc++",
                *android16KbElfLinkerFlags.toTypedArray(),
                source.absolutePath,
                "-o",
                output.absolutePath,
                "-ldl",
                "-llog",
            )
                .inheritIO()
                .start()
                .waitFor()
        check(exitCode == 0) {
            "Failed to build ${output.invariantSeparatorsPath}, clang++ exit code $exitCode"
        }
    }
}

val buildKrwaWasmtimeP3BridgeAndroidJniLib by tasks.registering {
    description = "Builds the KRWA Wasmtime Preview3 bridge for Android arm64-v8a."
    notCompatibleWithConfigurationCache("Runs cargo against the pinned Wasmtime source checkout.")
    dependsOn(project(":runtime").tasks.named("prepareWasmtimePulleyIosSource"))
    inputs.property("androidNdkVersion", androidNdkVersion)
    inputs.property("androidMinSdk", androidMinSdk)
    inputs.property("android16KbElfRustFlags", android16KbElfRustFlags)
    inputs.files(
        fileTree(wasmtimeP3BridgeDirectory) {
            include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
        },
    )
    outputs.file(krwaWasmtimeP3BridgeAndroidJniLib)

    doLast {
        val cargo = cargoBinary()
            ?: error("cargo is required to build the Wasmtime Preview3 Android bridge. Install Rust or set CARGO.")
        val rustup = rustupBinary()
        val ndkDir = androidNdkDirectory()
        val toolchainBin = ndkDir.resolve("toolchains/llvm/prebuilt/${androidNdkHostTag()}/bin")
        val clang = toolchainBin.resolve("aarch64-linux-android$androidMinSdk-clang")
        val clangxx = toolchainBin.resolve("aarch64-linux-android$androidMinSdk-clang++")
        val ar = toolchainBin.resolve("llvm-ar")
        check(clang.isFile) {
            "Expected Android NDK clang at ${clang.invariantSeparatorsPath}."
        }
        check(clangxx.isFile) {
            "Expected Android NDK clang++ at ${clangxx.invariantSeparatorsPath}."
        }
        check(ar.isFile) {
            "Expected Android NDK llvm-ar at ${ar.invariantSeparatorsPath}."
        }

        val environment =
            localCodexRustEnvironment() +
                codexRustEnvironmentFor(cargo) +
                mapOf(
                    "CC_aarch64_linux_android" to clang.absolutePath,
                    "CXX_aarch64_linux_android" to clangxx.absolutePath,
                    "AR_aarch64_linux_android" to ar.absolutePath,
                    "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER" to clang.absolutePath,
                    "CARGO_TARGET_AARCH64_LINUX_ANDROID_AR" to ar.absolutePath,
                    "CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS" to android16KbElfRustFlags,
                )
        rustup?.let { rustupBinary ->
            runProcess(
                listOf(rustupBinary.absolutePath, "target", "add", "aarch64-linux-android"),
                projectDir,
                environment,
            )
        }
        runProcess(
            cargoStableCommand(cargo) + listOf(
                "build",
                "--release",
                "--target",
                "aarch64-linux-android",
                "--manifest-path",
                wasmtimeP3BridgeDirectory.file("Cargo.toml").asFile.absolutePath,
                "--target-dir",
                wasmtimeP3BridgeAndroidTargetDirectory.get().asFile.absolutePath,
            ),
            projectDir,
            environment,
        )

        val source = wasmtimeP3BridgeAndroidTargetDirectory
            .get()
            .asFile
            .resolve("aarch64-linux-android/release/libkrwa_wasmtime_p3_bridge.so")
        check(source.isFile) {
            "Expected Wasmtime Preview3 Android bridge library at ${source.invariantSeparatorsPath}"
        }
        val output = krwaWasmtimeP3BridgeAndroidJniLib.asFile
        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
    }
}

val buildKrwaWasmtimeP3AndroidJniLib by tasks.registering {
    description = "Builds the KRWA Wasmtime Preview3 Android JNI bridge for arm64-v8a."
    inputs.property("androidNdkVersion", androidNdkVersion)
    inputs.property("androidMinSdk", androidMinSdk)
    inputs.property("cxxRuntime", "static")
    inputs.file(layout.projectDirectory.file("src/androidMain/cpp/krwa_wasmtime_p3_android.cpp"))
    outputs.file(krwaWasmtimeP3AndroidJniLib)

    doLast {
        val ndkDir = androidNdkDirectory()
        val toolchainBin = ndkDir.resolve("toolchains/llvm/prebuilt/${androidNdkHostTag()}/bin")
        val clang = toolchainBin.resolve("aarch64-linux-android$androidMinSdk-clang++")
        check(clang.isFile) {
            "Expected Android NDK clang++ at ${clang.invariantSeparatorsPath}."
        }

        val output = krwaWasmtimeP3AndroidJniLib.asFile
        output.parentFile.mkdirs()
        val source = layout.projectDirectory.file("src/androidMain/cpp/krwa_wasmtime_p3_android.cpp").asFile
        val exitCode =
            ProcessBuilder(
                clang.absolutePath,
                "-shared",
                "-fPIC",
                "-std=c++17",
                "-O2",
                "-Wall",
                "-Wextra",
                "-static-libstdc++",
                *android16KbElfLinkerFlags.toTypedArray(),
                source.absolutePath,
                "-o",
                output.absolutePath,
                "-ldl",
            )
                .inheritIO()
                .start()
                .waitFor()
        check(exitCode == 0) {
            "Failed to build ${output.invariantSeparatorsPath}, clang++ exit code $exitCode"
        }
    }
}

tasks.matching { task ->
    task.name == "mergeAndroidMainJniLibFolders" ||
        task.name == "copyAndroidMainJniLibsProjectOnly" ||
        task.name == "bundleAndroidMainAar"
}.configureEach {
    dependsOn(downloadWasmtimePulleyAndroidJniLib)
    dependsOn(buildKrwaPulleyAndroidJniLib)
    dependsOn(buildKrwaWasmtimeP3BridgeAndroidJniLib)
    dependsOn(buildKrwaWasmtimeP3AndroidJniLib)
}

val verifyAndroidJniLib16KbAlignment by tasks.registering {
    description = "Verifies Android JNI libraries use at least 16 KB ELF LOAD segment alignment."
    dependsOn(downloadWasmtimePulleyAndroidJniLib)
    dependsOn(buildKrwaPulleyAndroidJniLib)
    dependsOn(buildKrwaWasmtimeP3BridgeAndroidJniLib)
    dependsOn(buildKrwaWasmtimeP3AndroidJniLib)
    inputs.property("androidNdkVersion", androidNdkVersion)
    inputs.property("androidMinLoadSegmentAlignment", android16KbPageSize)
    inputs.files(androidJniLibs)

    doLast {
        val llvmObjdump = androidLlvmObjdumpExecutable()
        check(llvmObjdump.isFile) {
            "Expected Android NDK llvm-objdump at ${llvmObjdump.invariantSeparatorsPath}."
        }

        val failures =
            androidJniLibs.flatMap { jniLib ->
                val file = jniLib.asFile
                check(file.isFile) {
                    "Expected Android JNI library at ${file.invariantSeparatorsPath}."
                }
                val alignments = elfLoadSegmentAlignments(file, llvmObjdump)
                check(alignments.isNotEmpty()) {
                    "No ELF LOAD segments found in ${file.invariantSeparatorsPath}."
                }
                alignments
                    .filter { alignment -> alignment < android16KbPageSize }
                    .map { alignment ->
                        "${file.name}: LOAD align $alignment bytes; expected at least $android16KbPageSize"
                    }
            }

        check(failures.isEmpty()) {
            "Android JNI libraries must support 16 KB page sizes:\n" + failures.joinToString("\n")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyAndroidJniLib16KbAlignment)
}

tasks.matching { task -> task.name == "bundleAndroidMainAar" }.configureEach {
    dependsOn(verifyAndroidJniLib16KbAlignment)
}

kotlin {
    android {
        namespace = "uk.shusek.krwa.runtime.wasmtime.android"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = androidMinSdk

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        withHostTest {}
        packaging {
            resources {
                excludes += "/THIRD-PARTY.txt"
            }
        }
    }

    sourceSets.named("androidMain") {
        dependencies {
            api(project(":runtime"))
        }
    }
    sourceSets.named("androidDeviceTest") {
        dependencies {
            implementation(kotlin("test"))
            implementation(libs.androidxTestExtJunit)
            implementation(libs.androidxTestRunner)
        }
    }
}

extensions.configure<PublishingExtension> {
    configureKrwaRepositories(project)
    publications.withType<MavenPublication>().configureEach {
        configureKrwaPom()
    }
}
