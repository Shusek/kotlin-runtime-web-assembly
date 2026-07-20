import java.security.MessageDigest
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import uk.shusek.krwa.gradle.configureKrwaPom
import uk.shusek.krwa.gradle.configureKrwaRepositories
import uk.shusek.krwa.gradle.prepareVerifiedReleaseDownload

group = rootProject.group

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("maven-publish")
}

extensions.configure<BasePluginExtension> {
    archivesName.set("runtime-wasmtime-android")
}

data class AndroidAbiConfig(
    val jniDirectory: String,
    val rustTarget: String,
    val ndkClangTarget: String,
    val elfClass: Int,
    val elfMachine: Int,
) {
    val cargoTargetSuffix: String = rustTarget.replace("-", "_").uppercase()
    val compilerEnvironmentSuffix: String = rustTarget.replace("-", "_")
}

val arm64V8a = AndroidAbiConfig(
    jniDirectory = "arm64-v8a",
    rustTarget = "aarch64-linux-android",
    ndkClangTarget = "aarch64-linux-android",
    elfClass = 2,
    elfMachine = 183,
)
val armeabiV7a = AndroidAbiConfig(
    jniDirectory = "armeabi-v7a",
    rustTarget = "armv7-linux-androideabi",
    ndkClangTarget = "armv7a-linux-androideabi",
    elfClass = 1,
    elfMachine = 40,
)
val androidAbis = listOf(arm64V8a, armeabiV7a)
val wasmtimePulleyVersion = libs.versions.wasmtime.get()
val rustReleaseVersion = libs.versions.rustRelease.get()
val rustReleaseCommitHash = libs.versions.rustReleaseCommit.get()
val wasmtimePulleyFeatures =
    "pulley,cranelift,gc,gc-drc,gc-null,all-arch,component-model,component-model-async,wasi,wasi-http,disable-logging"
val wasmtimePulleyAndroidArchiveName =
    "wasmtime-v$wasmtimePulleyVersion-aarch64-android-c-api.tar.xz"
val wasmtimePulleyAndroidArchiveSha256 =
    "94b75abf63c6e16fa8c47189e3c5c569ecc34f3f3814ec5541dcf29268a03661"
val wasmtimePulleyAndroidLibSha256 =
    "74141aa32ef91b12c0ed743d515f7384a6bcf35317b434a63aaba7b0511aaeb3"

fun androidJniLib(abi: AndroidAbiConfig, name: String) =
    layout.projectDirectory.file("src/androidMain/jniLibs/${abi.jniDirectory}/$name")

val wasmtimePulleyAndroidJniLib =
    androidJniLib(arm64V8a, "libwasmtime.so")
val wasmtimePulleyArmeabiV7aJniLib =
    androidJniLib(armeabiV7a, "libwasmtime.so")
val krwaPulleyAndroidJniLib =
    androidJniLib(arm64V8a, "libkrwa_pulley_android.so")
val krwaPulleyArmeabiV7aJniLib =
    androidJniLib(armeabiV7a, "libkrwa_pulley_android.so")
val krwaWasmtimeP3BridgeAndroidJniLib =
    androidJniLib(arm64V8a, "libkrwa_wasmtime_p3_bridge.so")
val krwaWasmtimeP3BridgeArmeabiV7aJniLib =
    androidJniLib(armeabiV7a, "libkrwa_wasmtime_p3_bridge.so")
val krwaWasmtimeP3AndroidJniLib =
    androidJniLib(arm64V8a, "libkrwa_wasmtime_p3_android.so")
val krwaWasmtimeP3ArmeabiV7aJniLib =
    androidJniLib(armeabiV7a, "libkrwa_wasmtime_p3_android.so")
val androidJniLibsByAbi =
    listOf(
        arm64V8a to wasmtimePulleyAndroidJniLib,
        arm64V8a to krwaPulleyAndroidJniLib,
        arm64V8a to krwaWasmtimeP3BridgeAndroidJniLib,
        arm64V8a to krwaWasmtimeP3AndroidJniLib,
        armeabiV7a to wasmtimePulleyArmeabiV7aJniLib,
        armeabiV7a to krwaPulleyArmeabiV7aJniLib,
        armeabiV7a to krwaWasmtimeP3BridgeArmeabiV7aJniLib,
        armeabiV7a to krwaWasmtimeP3ArmeabiV7aJniLib,
    )
val androidJniLibs = androidJniLibsByAbi.map { (_, jniLib) -> jniLib }
val wasmtimePulleySourceDirectory = project(":runtime").layout.buildDirectory.dir("wasmtime-pulley/source")
val wasmtimeP3BridgeDirectory = project(":runtime").layout.projectDirectory.dir("src/wasmtime-p3-bridge")
val wasmtimeP3BridgeAndroidTargetDirectory = layout.buildDirectory.dir("wasmtime-p3-bridge-android/target")
val wasmtimeP3BridgeArmeabiV7aTargetDirectory =
    layout.buildDirectory.dir("wasmtime-p3-bridge-android-armeabi-v7a/target")
val wasmtimePulleyArmeabiV7aTargetDirectory =
    layout.buildDirectory.dir("wasmtime-pulley-android-armeabi-v7a/target")
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

fun cmakeBinary(): File? = executableFromEnvOrPath("CMAKE", "cmake")
    ?: androidSdkDirectory()
        .resolve("cmake")
        .listFiles()
        ?.asSequence()
        ?.map { cmakeVersionDirectory -> cmakeVersionDirectory.resolve("bin/cmake") }
        ?.filter { executable -> executable.isFile && executable.canExecute() }
        ?.maxByOrNull { executable -> executable.parentFile.parentFile.name }

fun cargoReleaseCommand(cargo: File): List<String> {
    val path = cargo.absolutePath.replace('\\', '/')
    return if ("/toolchains/" in path) {
        val toolchain = path.substringAfter("/toolchains/").substringBefore('/')
        check(
            toolchain == rustReleaseVersion ||
                toolchain.startsWith("$rustReleaseVersion-")
        ) {
            "CARGO points to Rust toolchain '$toolchain', but release builds require " +
                "'$rustReleaseVersion'. Use the rustup cargo proxy or the pinned toolchain directly."
        }
        listOf(cargo.absolutePath)
    } else {
        listOf(cargo.absolutePath, "+$rustReleaseVersion")
    }
}

fun cargoNetworkArguments(): List<String> =
    if (gradle.startParameter.isOffline) listOf("--offline") else emptyList()

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

fun androidToolchainBin(): File =
    androidNdkDirectory().resolve("toolchains/llvm/prebuilt/${androidNdkHostTag()}/bin")

fun androidClang(abi: AndroidAbiConfig, cxx: Boolean = false): File =
    androidToolchainBin().resolve(
        "${abi.ndkClangTarget}$androidMinSdk-clang${if (cxx) "++" else ""}",
    )

fun androidRustEnvironment(
    cargo: File,
    abi: AndroidAbiConfig,
    rustFlags: String = android16KbElfRustFlags,
): Map<String, String> {
    val clang = androidClang(abi)
    val clangxx = androidClang(abi, cxx = true)
    val ar = androidToolchainBin().resolve("llvm-ar")
    check(clang.isFile) {
        "Expected Android NDK clang at ${clang.invariantSeparatorsPath}."
    }
    check(clangxx.isFile) {
        "Expected Android NDK clang++ at ${clangxx.invariantSeparatorsPath}."
    }
    check(ar.isFile) {
        "Expected Android NDK llvm-ar at ${ar.invariantSeparatorsPath}."
    }
    return localCodexRustEnvironment() +
        codexRustEnvironmentFor(cargo) +
        mapOf(
            "CC_${abi.compilerEnvironmentSuffix}" to clang.absolutePath,
            "CXX_${abi.compilerEnvironmentSuffix}" to clangxx.absolutePath,
            "AR_${abi.compilerEnvironmentSuffix}" to ar.absolutePath,
            "CARGO_TARGET_${abi.cargoTargetSuffix}_LINKER" to clang.absolutePath,
            "CARGO_TARGET_${abi.cargoTargetSuffix}_AR" to ar.absolutePath,
            "CARGO_TARGET_${abi.cargoTargetSuffix}_RUSTFLAGS" to rustFlags,
        )
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

fun androidElfAbiFailure(abi: AndroidAbiConfig, file: File): String? {
    val header = ByteArray(20)
    val bytesRead = file.inputStream().use { input -> input.readNBytes(header, 0, header.size) }
    if (bytesRead != header.size) {
        return "${file.invariantSeparatorsPath}: truncated ELF header"
    }
    if (
        header[0] != 0x7f.toByte() ||
        header[1] != 'E'.code.toByte() ||
        header[2] != 'L'.code.toByte() ||
        header[3] != 'F'.code.toByte()
    ) {
        return "${file.invariantSeparatorsPath}: not an ELF library"
    }
    val elfClass = header[4].toInt() and 0xff
    val elfData = header[5].toInt() and 0xff
    val elfMachine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
    if (elfClass != abi.elfClass || elfData != 1 || elfMachine != abi.elfMachine) {
        return "${file.invariantSeparatorsPath}: ELF class=$elfClass, data=$elfData, machine=$elfMachine; " +
            "expected class=${abi.elfClass}, data=1, machine=${abi.elfMachine} for ${abi.jniDirectory}"
    }
    return null
}

val downloadWasmtimePulleyAndroidJniLib by tasks.registering {
    description = "Downloads the Wasmtime Pulley Android C API shared library for arm64-v8a."
    inputs.property("wasmtimePulleyVersion", wasmtimePulleyVersion)
    inputs.property("wasmtimePulleyAndroidArchiveSha256", wasmtimePulleyAndroidArchiveSha256)
    inputs.property("wasmtimePulleyAndroidLibSha256", wasmtimePulleyAndroidLibSha256)
    doNotTrackState(
        "The verified Android runtime is durable local state and must survive Gradle version changes.",
    )

    doLast {
        val downloadDir = layout.buildDirectory.dir("wasmtime-pulley-android").get().asFile
        val archive = downloadDir.resolve(wasmtimePulleyAndroidArchiveName)
        val extractedDir = downloadDir.resolve("extracted")
        val output = wasmtimePulleyAndroidJniLib.asFile

        downloadDir.mkdirs()
        val archiveUrl =
            "https://github.com/bytecodealliance/wasmtime/releases/download/" +
                "v$wasmtimePulleyVersion/$wasmtimePulleyAndroidArchiveName"
        prepareVerifiedReleaseDownload(
            description = "Wasmtime $wasmtimePulleyVersion Android C API archive",
            url = archiveUrl,
            target = archive,
            expectedSha256 = wasmtimePulleyAndroidArchiveSha256,
        )

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

val buildWasmtimePulleyArmeabiV7aJniLib by tasks.registering {
    description = "Builds the pinned Wasmtime Pulley Android C API shared library for armeabi-v7a."
    notCompatibleWithConfigurationCache("Runs cargo against the pinned Wasmtime source checkout.")
    dependsOn(project(":runtime").tasks.named("prepareRustReleaseDependencies"))
    inputs.property("wasmtimePulleyVersion", wasmtimePulleyVersion)
    inputs.property("wasmtimePulleyFeatures", wasmtimePulleyFeatures)
    inputs.property("rustReleaseVersion", rustReleaseVersion)
    inputs.property("rustReleaseCommitHash", rustReleaseCommitHash)
    inputs.property("androidAbi", armeabiV7a.jniDirectory)
    inputs.property("androidNdkVersion", androidNdkVersion)
    inputs.property("androidMinSdk", androidMinSdk)
    inputs.property("android16KbElfRustFlags", android16KbElfRustFlags)
    outputs.file(wasmtimePulleyArmeabiV7aJniLib)

    doLast {
        val cargo = cargoBinary()
            ?: error("cargo is required to build the Wasmtime Android C API. Install Rust or set CARGO.")
        val cmake = cmakeBinary()
            ?: error("cmake is required to build the Wasmtime Android C API. Install CMake or set CMAKE.")
        val sourceDirectory = wasmtimePulleySourceDirectory.get().asFile
        val targetDirectory = wasmtimePulleyArmeabiV7aTargetDirectory.get().asFile
        val environment =
            androidRustEnvironment(cargo, armeabiV7a) +
                mapOf(
                    "CMAKE" to cmake.absolutePath,
                    "PATH" to cmake.parentFile.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty(),
                )
        runProcess(
            cargoReleaseCommand(cargo) +
                listOf("build") +
                cargoNetworkArguments() +
                listOf(
                    "-p",
                    "wasmtime-c-api",
                    "--release",
                    "--target",
                    armeabiV7a.rustTarget,
                    "--no-default-features",
                    "--features",
                    wasmtimePulleyFeatures,
                    "--target-dir",
                    targetDirectory.absolutePath,
                ),
            sourceDirectory,
            environment,
        )

        val source = targetDirectory.resolve(
            "${armeabiV7a.rustTarget}/release/libwasmtime.so",
        )
        check(source.isFile) {
            "Expected Wasmtime Android C API library at ${source.invariantSeparatorsPath}"
        }
        val output = wasmtimePulleyArmeabiV7aJniLib.asFile
        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
    }
}

fun registerAndroidCppJniTask(
    taskName: String,
    descriptionText: String,
    abi: AndroidAbiConfig,
    sourceName: String,
    output: File,
    linkLibraries: List<String>,
) = tasks.register(taskName) {
    description = descriptionText
    inputs.property("androidAbi", abi.jniDirectory)
    inputs.property("androidNdkVersion", androidNdkVersion)
    inputs.property("androidMinSdk", androidMinSdk)
    inputs.property("cxxRuntime", "static")
    val source = layout.projectDirectory.file("src/androidMain/cpp/$sourceName")
    inputs.file(source)
    outputs.file(output)

    doLast {
        val clang = androidClang(abi, cxx = true)
        check(clang.isFile) {
            "Expected Android NDK clang++ at ${clang.invariantSeparatorsPath}."
        }

        output.parentFile.mkdirs()
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
                source.asFile.absolutePath,
                "-o",
                output.absolutePath,
                *linkLibraries.toTypedArray(),
            )
                .inheritIO()
                .start()
                .waitFor()
        check(exitCode == 0) {
            "Failed to build ${output.invariantSeparatorsPath}, clang++ exit code $exitCode"
        }
    }
}

val buildKrwaPulleyAndroidJniLib = registerAndroidCppJniTask(
    taskName = "buildKrwaPulleyAndroidJniLib",
    descriptionText = "Builds the KRWA Pulley Android JNI bridge for arm64-v8a.",
    abi = arm64V8a,
    sourceName = "krwa_pulley_android.cpp",
    output = krwaPulleyAndroidJniLib.asFile,
    linkLibraries = listOf("-ldl", "-llog"),
)
val buildKrwaPulleyArmeabiV7aJniLib = registerAndroidCppJniTask(
    taskName = "buildKrwaPulleyArmeabiV7aJniLib",
    descriptionText = "Builds the KRWA Pulley Android JNI bridge for armeabi-v7a.",
    abi = armeabiV7a,
    sourceName = "krwa_pulley_android.cpp",
    output = krwaPulleyArmeabiV7aJniLib.asFile,
    linkLibraries = listOf("-ldl", "-llog"),
)

fun registerWasmtimeP3BridgeAndroidTask(
    taskName: String,
    abi: AndroidAbiConfig,
    targetDirectory: File,
    output: File,
) = tasks.register(taskName) {
    description = "Builds the KRWA Wasmtime Preview3 bridge for Android ${abi.jniDirectory}."
    notCompatibleWithConfigurationCache("Runs cargo against the pinned Wasmtime source checkout.")
    dependsOn(project(":runtime").tasks.named("prepareRustReleaseDependencies"))
    inputs.property("rustReleaseVersion", rustReleaseVersion)
    inputs.property("rustReleaseCommitHash", rustReleaseCommitHash)
    inputs.property("androidAbi", abi.jniDirectory)
    inputs.property("androidNdkVersion", androidNdkVersion)
    inputs.property("androidMinSdk", androidMinSdk)
    inputs.property("android16KbElfRustFlags", android16KbElfRustFlags)
    inputs.files(
        fileTree(wasmtimeP3BridgeDirectory) {
            include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
        },
    )
    outputs.file(output)

    doLast {
        val cargo = cargoBinary()
            ?: error("cargo is required to build the Wasmtime Preview3 Android bridge. Install Rust or set CARGO.")
        runProcess(
            cargoReleaseCommand(cargo) +
                listOf("build") +
                cargoNetworkArguments() +
                listOf(
                    "--release",
                    "--target",
                    abi.rustTarget,
                    "--manifest-path",
                    wasmtimeP3BridgeDirectory.file("Cargo.toml").asFile.absolutePath,
                    "--target-dir",
                    targetDirectory.absolutePath,
                ),
            projectDir,
            androidRustEnvironment(cargo, abi),
        )

        val source = targetDirectory.resolve(
            "${abi.rustTarget}/release/libkrwa_wasmtime_p3_bridge.so",
        )
        check(source.isFile) {
            "Expected Wasmtime Preview3 Android bridge library at ${source.invariantSeparatorsPath}"
        }
        output.parentFile.mkdirs()
        source.copyTo(output, overwrite = true)
    }
}

val buildKrwaWasmtimeP3BridgeAndroidJniLib = registerWasmtimeP3BridgeAndroidTask(
    taskName = "buildKrwaWasmtimeP3BridgeAndroidJniLib",
    abi = arm64V8a,
    targetDirectory = wasmtimeP3BridgeAndroidTargetDirectory.get().asFile,
    output = krwaWasmtimeP3BridgeAndroidJniLib.asFile,
)
val buildKrwaWasmtimeP3BridgeArmeabiV7aJniLib = registerWasmtimeP3BridgeAndroidTask(
    taskName = "buildKrwaWasmtimeP3BridgeArmeabiV7aJniLib",
    abi = armeabiV7a,
    targetDirectory = wasmtimeP3BridgeArmeabiV7aTargetDirectory.get().asFile,
    output = krwaWasmtimeP3BridgeArmeabiV7aJniLib.asFile,
)

val buildKrwaWasmtimeP3AndroidJniLib = registerAndroidCppJniTask(
    taskName = "buildKrwaWasmtimeP3AndroidJniLib",
    descriptionText = "Builds the KRWA Wasmtime Preview3 Android JNI bridge for arm64-v8a.",
    abi = arm64V8a,
    sourceName = "krwa_wasmtime_p3_android.cpp",
    output = krwaWasmtimeP3AndroidJniLib.asFile,
    linkLibraries = listOf("-ldl"),
)
val buildKrwaWasmtimeP3ArmeabiV7aJniLib = registerAndroidCppJniTask(
    taskName = "buildKrwaWasmtimeP3ArmeabiV7aJniLib",
    descriptionText = "Builds the KRWA Wasmtime Preview3 Android JNI bridge for armeabi-v7a.",
    abi = armeabiV7a,
    sourceName = "krwa_wasmtime_p3_android.cpp",
    output = krwaWasmtimeP3ArmeabiV7aJniLib.asFile,
    linkLibraries = listOf("-ldl"),
)

val androidJniLibBuildTasks =
    listOf(
        downloadWasmtimePulleyAndroidJniLib,
        buildWasmtimePulleyArmeabiV7aJniLib,
        buildKrwaPulleyAndroidJniLib,
        buildKrwaPulleyArmeabiV7aJniLib,
        buildKrwaWasmtimeP3BridgeAndroidJniLib,
        buildKrwaWasmtimeP3BridgeArmeabiV7aJniLib,
        buildKrwaWasmtimeP3AndroidJniLib,
        buildKrwaWasmtimeP3ArmeabiV7aJniLib,
    )

tasks.matching { task ->
    task.name == "mergeAndroidMainJniLibFolders" ||
        task.name == "copyAndroidMainJniLibsProjectOnly" ||
        task.name == "bundleAndroidMainAar"
}.configureEach {
    dependsOn(androidJniLibBuildTasks)
}

val verifyAndroidJniLib16KbAlignment by tasks.registering {
    description = "Verifies Android JNI libraries use at least 16 KB ELF LOAD segment alignment."
    dependsOn(androidJniLibBuildTasks)
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

val verifyAndroidJniLibAbis by tasks.registering {
    description = "Verifies Android JNI libraries match their packaged ABIs."
    dependsOn(androidJniLibBuildTasks)
    inputs.property(
        "androidAbis",
        androidAbis.joinToString { abi ->
            "${abi.jniDirectory}:${abi.elfClass}:${abi.elfMachine}"
        },
    )
    inputs.files(androidJniLibs)

    doLast {
        val failures =
            androidJniLibsByAbi.mapNotNull { (abi, jniLib) ->
                val file = jniLib.asFile
                check(file.isFile) {
                    "Expected Android JNI library at ${file.invariantSeparatorsPath}."
                }
                androidElfAbiFailure(abi, file)
            }
        check(failures.isEmpty()) {
            "Android JNI libraries must match their packaged ABIs:\n" + failures.joinToString("\n")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyAndroidJniLib16KbAlignment)
    dependsOn(verifyAndroidJniLibAbis)
}

tasks.matching { task -> task.name == "bundleAndroidMainAar" }.configureEach {
    dependsOn(verifyAndroidJniLib16KbAlignment)
    dependsOn(verifyAndroidJniLibAbis)
}

kotlin {
    android {
        namespace = "uk.shusek.krwa.runtime.wasmtime.android"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = androidMinSdk

        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-rules.pro")
            }
        }

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        withHostTest {}
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
