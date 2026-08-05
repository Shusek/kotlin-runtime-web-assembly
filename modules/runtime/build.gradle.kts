import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import uk.shusek.krwa.gradle.*

group = rootProject.group

apply(plugin = "org.jetbrains.kotlin.multiplatform")
apply(plugin = "maven-publish")

val wasmtimePulleyVersion = libs.versions.wasmtime.get()
val wasmtimePulleyGitRevision = "90fed3c6adf53f112c4dea56851728557bb73799"
val rustReleaseVersion = libs.versions.rustRelease.get()
val rustReleaseCommitHash = libs.versions.rustReleaseCommit.get()
val wasmtimePulleyFeatures =
    "pulley,cranelift,gc,gc-drc,gc-null,all-arch,component-model,component-model-async,wasi,wasi-http,disable-logging"
val wasmtimePulleyIosTargets = listOf(
    "aarch64-apple-ios",
    "aarch64-apple-ios-sim",
)
val wasmtimePulleyAndroidSourceTargets = listOf(
    "armv7-linux-androideabi",
)
val wasmtimePulleyAndroidTargets =
    listOf("aarch64-linux-android") + wasmtimePulleyAndroidSourceTargets
val wasmtimePulleySourceDirectory = layout.buildDirectory.dir("wasmtime-pulley/source")
val legacyWasmtimePulleyIosSourceDirectory = layout.buildDirectory.dir("wasmtime-pulley-ios/source")
val wasmtimePulleyIosTargetDirectory = layout.buildDirectory.dir("wasmtime-pulley-ios/target")
val wasmtimePulleyIosLibDirectory = layout.buildDirectory.dir("wasmtime-pulley-ios/lib")
val krwaPulleyIosBridgeLibDirectory = layout.buildDirectory.dir("wasmtime-pulley-ios/bridge")
val wasmtimeP3BridgeDirectory = layout.projectDirectory.dir("src/wasmtime-p3-bridge")
val wasmtimeP3BridgeTargetDirectory = layout.buildDirectory.dir("wasmtime-p3-bridge/target")
val wasmtimeP3BridgeIosLibDirectory = layout.buildDirectory.dir("wasmtime-p3-bridge/ios-lib")
val wasmtimeP3BridgeReleaseLibrary = wasmtimeP3BridgeTargetDirectory.map { targetDirectory ->
    targetDirectory.file("release/${nativeDynamicLibraryName("krwa_wasmtime_p3_bridge")}")
}
val hostOs = providers.gradleProperty("krwa.host.os")
    .orElse(System.getProperty("os.name"))
    .get()
    .lowercase()
val hostIsMacOs = "mac" in hostOs || "darwin" in hostOs

fun nativeDynamicLibraryName(baseName: String): String {
    return when {
        hostIsMacOs -> "lib$baseName.dylib"
        "windows" in hostOs -> "$baseName.dll"
        else -> "lib$baseName.so"
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

fun rustupBinary(): File? = executableFromEnvOrPath("RUSTUP", "rustup") ?: localCodexRustExecutable("rustup")

fun androidSdkDirectory(): File {
    val sdkPath = System.getenv("ANDROID_HOME")?.takeIf(String::isNotBlank)
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf(String::isNotBlank)
        ?: "${System.getProperty("user.home")}/Library/Android/sdk"
    return file(sdkPath)
}

fun cmakeBinary(): File? = executableFromEnvOrPath("CMAKE", "cmake")
    ?: androidSdkDirectory()
        .resolve("cmake")
        .listFiles()
        ?.asSequence()
        ?.map { cmakeVersionDirectory -> cmakeVersionDirectory.resolve("bin/cmake") }
        ?.filter { executable -> executable.isFile && executable.canExecute() }
        ?.maxByOrNull { executable -> executable.parentFile.parentFile.name }

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

fun captureProcess(
    command: List<String>,
    workingDirectory: File,
    environment: Map<String, String> = emptyMap(),
): String {
    val processBuilder =
        ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
    processBuilder.environment().putAll(environment)
    val process = processBuilder.start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    check(exitCode == 0) {
        "Command failed with exit code $exitCode: ${command.joinToString(" ")}"
    }
    return output
}

fun preparePinnedRustToolchain(
    rustup: File,
    environment: Map<String, String>,
): String {
    val toolchains =
        captureProcess(
            listOf(rustup.absolutePath, "toolchain", "list"),
            projectDir,
            environment,
        )
    val pinnedToolchainInstalled =
        toolchains.lineSequence().any { line ->
            val name = line.substringBefore(' ')
            name == rustReleaseVersion || name.startsWith("$rustReleaseVersion-")
        }
    if (!pinnedToolchainInstalled) {
        if (gradle.startParameter.isOffline) {
            throw GradleException(
                "The release-pinned Rust toolchain $rustReleaseVersion is not installed. Run " +
                    "'./gradlew --no-daemon prepareReleaseDependencies' while online, then retry " +
                    "releaseGate with --offline.",
            )
        }
        runProcess(
            listOf(
                rustup.absolutePath,
                "toolchain",
                "install",
                rustReleaseVersion,
                "--profile",
                "minimal",
            ),
            projectDir,
            environment,
        )
    }

    val rustcVersion =
        captureProcess(
            listOf(rustup.absolutePath, "run", rustReleaseVersion, "rustc", "-vV"),
            projectDir,
            environment,
        )
    val actualRustRelease =
        rustcVersion.lineSequence()
            .firstOrNull { line -> line.startsWith("release: ") }
            ?.substringAfter("release: ")
            ?.trim()
    val actualRustCommit =
        rustcVersion.lineSequence()
            .firstOrNull { line -> line.startsWith("commit-hash: ") }
            ?.substringAfter("commit-hash: ")
            ?.trim()
    if (actualRustRelease != rustReleaseVersion || actualRustCommit != rustReleaseCommitHash) {
        throw GradleException(
            "The Rust toolchain $rustReleaseVersion must resolve to the release-pinned compiler " +
                "$rustReleaseVersion ($rustReleaseCommitHash), but rustc -vV reported " +
                "${actualRustRelease ?: "an unknown release"} " +
                "(${actualRustCommit ?: "an unknown commit"}). Update the release pin and native " +
                "artifacts deliberately before running releaseGate.",
        )
    }
    val hostTarget =
        rustcVersion.lineSequence()
            .firstOrNull { line -> line.startsWith("host: ") }
            ?.substringAfter("host: ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw GradleException(
                "Cannot determine the host target for Rust toolchain $rustReleaseVersion",
            )
    val installedTargets =
        captureProcess(
            listOf(
                rustup.absolutePath,
                "target",
                "list",
                "--installed",
                "--toolchain",
                rustReleaseVersion,
            ),
            projectDir,
            environment,
        ).lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
    val requiredTargets =
        (listOf(hostTarget) + wasmtimePulleyIosTargets + wasmtimePulleyAndroidTargets).distinct()
    val missingTargets = requiredTargets.filterNot(installedTargets::contains)
    if (missingTargets.isNotEmpty() && gradle.startParameter.isOffline) {
        throw GradleException(
            "Rust $rustReleaseVersion is missing release targets ${missingTargets.joinToString()}. Run " +
                "'./gradlew --no-daemon prepareReleaseDependencies' while online, then retry " +
                "releaseGate with --offline.",
        )
    }
    for (target in missingTargets) {
        runProcess(
            listOf(
                rustup.absolutePath,
                "target",
                "add",
                "--toolchain",
                rustReleaseVersion,
                target,
            ),
            projectDir,
            environment,
        )
    }
    return hostTarget
}

fun isPinnedWasmtimeSource(directory: File): Boolean {
    if (!directory.resolve(".git").isDirectory) {
        return false
    }
    return runCatching {
        captureProcess(listOf("git", "rev-parse", "HEAD"), directory) ==
            wasmtimePulleyGitRevision &&
            captureProcess(
                listOf("git", "status", "--porcelain", "--untracked-files=normal"),
                directory,
            ).isEmpty()
    }.getOrDefault(false)
}

fun moveWasmtimeSource(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    }
}

fun xcrun(sdk: String, vararg args: String): String = captureProcess(listOf("xcrun", "--sdk", sdk, *args), projectDir)

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

extensions.configure<BasePluginExtension> {
    archivesName.set("runtime")
}

val prepareWasmtimePulleyIosSource by tasks.registering {
    description = "Fetches the pinned Wasmtime source used to build KRWA Wasmtime bridges."
    notCompatibleWithConfigurationCache("Fetches a pinned native dependency through external git commands.")
    inputs.property("wasmtimePulleyVersion", wasmtimePulleyVersion)
    inputs.property("wasmtimePulleyGitRevision", wasmtimePulleyGitRevision)
    doNotTrackState(
        "The verified Wasmtime checkout is durable local state and must survive Gradle version changes.",
    )

    doLast {
        val sourceDirectory = wasmtimePulleySourceDirectory.get().asFile
        val legacySourceDirectory = legacyWasmtimePulleyIosSourceDirectory.get().asFile
        val parentDirectory = sourceDirectory.parentFile
        parentDirectory.mkdirs()
        if (isPinnedWasmtimeSource(sourceDirectory)) {
            return@doLast
        }
        if (isPinnedWasmtimeSource(legacySourceDirectory)) {
            sourceDirectory.deleteRecursively()
            check(
                legacySourceDirectory.renameTo(sourceDirectory) ||
                    legacySourceDirectory.copyRecursively(sourceDirectory)
            ) {
                "Failed to migrate Wasmtime source checkout from " +
                    "${legacySourceDirectory.invariantSeparatorsPath} to ${sourceDirectory.invariantSeparatorsPath}"
            }
            check(isPinnedWasmtimeSource(sourceDirectory)) {
                "Migrated Wasmtime source is not the required clean revision $wasmtimePulleyGitRevision"
            }
            return@doLast
        }

        if (gradle.startParameter.isOffline) {
            throw GradleException(
                "Pinned Wasmtime source $wasmtimePulleyGitRevision is missing or not clean at " +
                    "${sourceDirectory.invariantSeparatorsPath}. Run " +
                    "'./gradlew --no-daemon prepareReleaseDependencies' while online, then retry " +
                    "releaseGate with --offline.",
            )
        }

        val temporaryDirectory = parentDirectory.resolve("${sourceDirectory.name}.download.tmp")
        try {
            temporaryDirectory.deleteRecursively()
            runProcess(
                listOf(
                    "git",
                    "clone",
                    "--no-checkout",
                    "--filter=blob:none",
                    "https://github.com/bytecodealliance/wasmtime.git",
                    temporaryDirectory.absolutePath,
                ),
                parentDirectory,
            )
            runProcess(
                listOf("git", "fetch", "origin", "tag", "v$wasmtimePulleyVersion"),
                temporaryDirectory,
            )
            runProcess(
                listOf("git", "checkout", "--detach", wasmtimePulleyGitRevision),
                temporaryDirectory,
            )
            check(isPinnedWasmtimeSource(temporaryDirectory)) {
                "Wasmtime source preparation did not produce clean revision " +
                    wasmtimePulleyGitRevision
            }
            sourceDirectory.deleteRecursively()
            moveWasmtimeSource(temporaryDirectory, sourceDirectory)
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }
}

val prepareRustReleaseDependencies by tasks.registering {
    group = "build setup"
    description =
        "Installs release Rust targets online and verifies locked Cargo inputs for offline builds."
    dependsOn(prepareWasmtimePulleyIosSource)
    inputs.property("wasmtimePulleyGitRevision", wasmtimePulleyGitRevision)
    inputs.property("rustReleaseVersion", rustReleaseVersion)
    inputs.property("rustReleaseCommitHash", rustReleaseCommitHash)
    inputs.file(wasmtimePulleySourceDirectory.map { directory -> directory.file("Cargo.toml") })
    inputs.file(wasmtimePulleySourceDirectory.map { directory -> directory.file("Cargo.lock") })
    inputs.files(
        fileTree(wasmtimeP3BridgeDirectory) {
            include("Cargo.toml", "Cargo.lock")
        },
    )

    doLast {
        val cargo =
            cargoBinary()
                ?: throw GradleException(
                    "cargo is required to prepare release dependencies. Install Rust first.",
                )
        val rustup =
            rustupBinary()
                ?: throw GradleException(
                    "rustup is required to prepare the release-pinned Rust toolchain and targets.",
                )
        val environment = localCodexRustEnvironment() + codexRustEnvironmentFor(cargo)
        val hostTarget = preparePinnedRustToolchain(rustup, environment)
        val sourceManifest =
            wasmtimePulleySourceDirectory.get().asFile.resolve("Cargo.toml")
        val bridgeManifest = wasmtimeP3BridgeDirectory.file("Cargo.toml").asFile
        val fetches =
            listOf(
                sourceManifest to
                    (listOf(hostTarget) + wasmtimePulleyIosTargets + wasmtimePulleyAndroidSourceTargets),
                bridgeManifest to
                    (listOf(hostTarget) + wasmtimePulleyIosTargets + wasmtimePulleyAndroidTargets),
            )
        for ((manifest, targets) in fetches) {
            for (target in targets.distinct()) {
                runProcess(
                    cargoReleaseCommand(cargo) +
                        listOf("fetch", "--locked") +
                        cargoNetworkArguments() +
                        listOf(
                            "--manifest-path",
                            manifest.absolutePath,
                            "--target",
                            target,
                        ),
                    projectDir,
                    environment,
                )
            }
        }
    }
}

val buildWasmtimePulleyIosLibs by tasks.registering {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds pinned Wasmtime Pulley C API static libraries for iOS device and simulator."
    notCompatibleWithConfigurationCache("Builds a native dependency through external cargo/rustup/cmake commands.")
    onlyIf("iOS native bridge libraries require macOS toolchains") { hostIsMacOs }
    dependsOn(prepareRustReleaseDependencies)
    inputs.property("wasmtimePulleyVersion", wasmtimePulleyVersion)
    inputs.property("wasmtimePulleyGitRevision", wasmtimePulleyGitRevision)
    inputs.property("rustReleaseVersion", rustReleaseVersion)
    inputs.property("rustReleaseCommitHash", rustReleaseCommitHash)
    inputs.property("wasmtimePulleyFeatures", wasmtimePulleyFeatures)
    inputs.property("wasmtimePulleyIosTargets", wasmtimePulleyIosTargets.joinToString(","))
    outputs.files(
        wasmtimePulleyIosTargets.map { target ->
            wasmtimePulleyIosLibDirectory.map { directory -> directory.file("$target/libwasmtime.a") }
        },
    )

    doLast {
        val cargo = cargoBinary()
            ?: error("cargo is required to build Wasmtime Pulley iOS libraries. Install Rust or set CARGO.")
        val cmake = cmakeBinary()
            ?: error("cmake is required to build Wasmtime Pulley iOS libraries. Install CMake or set CMAKE.")
        val sourceDirectory = wasmtimePulleySourceDirectory.get().asFile
        val targetDirectory = wasmtimePulleyIosTargetDirectory.get().asFile
        val outputDirectory = wasmtimePulleyIosLibDirectory.get().asFile
        val environment =
            localCodexRustEnvironment() +
                codexRustEnvironmentFor(cargo) +
                mapOf(
                    "CMAKE" to cmake.absolutePath,
                    "PATH" to cmake.parentFile.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty(),
                )
        for (target in wasmtimePulleyIosTargets) {
            runProcess(
                cargoReleaseCommand(cargo) +
                    listOf("build") +
                    cargoNetworkArguments() +
                    listOf(
                        "-p",
                        "wasmtime-c-api",
                        "--release",
                        "--target",
                        target,
                        "--no-default-features",
                        "--features",
                        wasmtimePulleyFeatures,
                        "--target-dir",
                        targetDirectory.absolutePath,
                    ),
                sourceDirectory,
                environment,
            )
            val builtLibrary = targetDirectory.resolve("$target/release/libwasmtime.a")
            check(builtLibrary.isFile) {
                "Expected Wasmtime iOS library at ${builtLibrary.invariantSeparatorsPath}"
            }
            val targetOutputDirectory = outputDirectory.resolve(target)
            targetOutputDirectory.mkdirs()
            builtLibrary.copyTo(targetOutputDirectory.resolve("libwasmtime.a"), overwrite = true)
        }
    }
}

val buildKrwaPulleyIosBridgeLibs by tasks.registering {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds KRWA Pulley bridge static libraries for iOS device and simulator."
    notCompatibleWithConfigurationCache("Builds a native bridge through xcrun/clang/ar commands.")
    onlyIf("iOS native bridge libraries require macOS toolchains") { hostIsMacOs }
    inputs.file(layout.projectDirectory.file("src/iosMain/cpp/krwa_pulley_ios.cpp"))
    outputs.files(
        wasmtimePulleyIosTargets.map { target ->
            krwaPulleyIosBridgeLibDirectory.map { directory -> directory.file("$target/libkrwa_pulley_ios.a") }
        },
    )

    doLast {
        val source = layout.projectDirectory.file("src/iosMain/cpp/krwa_pulley_ios.cpp").asFile
        for (target in wasmtimePulleyIosTargets) {
            val simulator = target.endsWith("-sim")
            val sdk = if (simulator) "iphonesimulator" else "iphoneos"
            val clang = xcrun(sdk, "--find", "clang++")
            val ar = xcrun(sdk, "--find", "ar")
            val sdkPath = xcrun(sdk, "--show-sdk-path")
            val outputDirectory = krwaPulleyIosBridgeLibDirectory.get().asFile.resolve(target)
            outputDirectory.mkdirs()
            val objectFile = outputDirectory.resolve("krwa_pulley_ios.o")
            val libraryFile = outputDirectory.resolve("libkrwa_pulley_ios.a")
            runProcess(
                listOf(
                    clang,
                    "-std=c++17",
                    "-stdlib=libc++",
                    "-isysroot",
                    sdkPath,
                    "-arch",
                    "arm64",
                    if (simulator) "-mios-simulator-version-min=13.0" else "-miphoneos-version-min=13.0",
                    "-fvisibility=hidden",
                    "-c",
                    source.absolutePath,
                    "-o",
                    objectFile.absolutePath,
                ),
                projectDir,
            )
            runProcess(listOf(ar, "crs", libraryFile.absolutePath, objectFile.absolutePath), projectDir)
        }
    }
}

val buildWasmtimeP3BridgeIosLibs by tasks.registering {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds the Rust Wasmtime Preview3 bridge static libraries for iOS device and simulator."
    notCompatibleWithConfigurationCache("Runs cargo against the pinned Wasmtime source checkout.")
    onlyIf("iOS native bridge libraries require macOS toolchains") { hostIsMacOs }
    dependsOn(prepareRustReleaseDependencies)
    inputs.property("wasmtimePulleyVersion", wasmtimePulleyVersion)
    inputs.property("wasmtimePulleyGitRevision", wasmtimePulleyGitRevision)
    inputs.property("rustReleaseVersion", rustReleaseVersion)
    inputs.property("rustReleaseCommitHash", rustReleaseCommitHash)
    inputs.property("wasmtimePulleyIosTargets", wasmtimePulleyIosTargets.joinToString(","))
    inputs.files(
        fileTree(wasmtimeP3BridgeDirectory) {
            include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
        },
    )
    outputs.files(
        wasmtimePulleyIosTargets.map { target ->
            wasmtimeP3BridgeIosLibDirectory.map { directory ->
                directory.file("$target/libkrwa_wasmtime_p3_bridge.a")
            }
        },
    )

    doLast {
        val cargo = cargoBinary()
            ?: error("cargo is required to build the Wasmtime Preview3 iOS bridge. Install Rust or set CARGO.")
        val targetDirectory = wasmtimeP3BridgeTargetDirectory.get().asFile
        val outputDirectory = wasmtimeP3BridgeIosLibDirectory.get().asFile
        val environment = localCodexRustEnvironment() + codexRustEnvironmentFor(cargo)
        for (target in wasmtimePulleyIosTargets) {
            runProcess(
                cargoReleaseCommand(cargo) +
                    listOf("build") +
                    cargoNetworkArguments() +
                    listOf(
                        "--release",
                        "--target",
                        target,
                        "--manifest-path",
                        wasmtimeP3BridgeDirectory.file("Cargo.toml").asFile.absolutePath,
                        "--target-dir",
                        targetDirectory.absolutePath,
                    ),
                projectDir,
                environment,
            )
            val builtLibrary = targetDirectory.resolve("$target/release/libkrwa_wasmtime_p3_bridge.a")
            check(builtLibrary.isFile) {
                "Expected Wasmtime Preview3 iOS bridge library at ${builtLibrary.invariantSeparatorsPath}"
            }
            val targetOutputDirectory = outputDirectory.resolve(target)
            targetOutputDirectory.mkdirs()
            builtLibrary.copyTo(targetOutputDirectory.resolve("libkrwa_wasmtime_p3_bridge.a"), overwrite = true)
        }
    }
}

val testWasmtimeP3Bridge by tasks.registering {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Builds and tests the Rust Wasmtime Preview3 bridge probe."
    notCompatibleWithConfigurationCache("Runs cargo against the pinned Wasmtime source checkout.")
    dependsOn(prepareRustReleaseDependencies)
    inputs.property("wasmtimePulleyVersion", wasmtimePulleyVersion)
    inputs.property("wasmtimePulleyGitRevision", wasmtimePulleyGitRevision)
    inputs.property("rustReleaseVersion", rustReleaseVersion)
    inputs.property("rustReleaseCommitHash", rustReleaseCommitHash)
    inputs.files(
        fileTree(wasmtimeP3BridgeDirectory) {
            include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
        },
    )
    doNotTrackState(
        "Cargo owns this shared target directory; tracking it lets Gradle race with Cargo or macOS metadata writers while cleaning it.",
    )

    doLast {
        val cargo = cargoBinary()
            ?: error("cargo is required to test the Wasmtime Preview3 bridge. Install Rust or set CARGO.")
        runProcess(
            cargoReleaseCommand(cargo) +
                listOf("test") +
                cargoNetworkArguments() +
                listOf(
                    "--manifest-path",
                    wasmtimeP3BridgeDirectory.file("Cargo.toml").asFile.absolutePath,
                    "--target-dir",
                    wasmtimeP3BridgeTargetDirectory.get().asFile.absolutePath,
                ),
            projectDir,
            localCodexRustEnvironment() + codexRustEnvironmentFor(cargo),
        )
    }
}

val buildWasmtimeP3BridgeLib by tasks.registering {
    group = LifecycleBasePlugin.BUILD_GROUP
    description = "Builds the Rust Wasmtime Preview3 bridge shared library for JVM integration tests."
    notCompatibleWithConfigurationCache("Runs cargo against the pinned Wasmtime source checkout.")
    dependsOn(prepareRustReleaseDependencies)
    inputs.property("wasmtimePulleyVersion", wasmtimePulleyVersion)
    inputs.property("wasmtimePulleyGitRevision", wasmtimePulleyGitRevision)
    inputs.property("rustReleaseVersion", rustReleaseVersion)
    inputs.property("rustReleaseCommitHash", rustReleaseCommitHash)
    inputs.files(
        fileTree(wasmtimeP3BridgeDirectory) {
            include("Cargo.toml", "Cargo.lock", "src/**/*.rs")
        },
    )
    outputs.file(wasmtimeP3BridgeReleaseLibrary)

    doLast {
        val cargo = cargoBinary()
            ?: error("cargo is required to build the Wasmtime Preview3 bridge. Install Rust or set CARGO.")
        runProcess(
            cargoReleaseCommand(cargo) +
                listOf("build") +
                cargoNetworkArguments() +
                listOf(
                    "--release",
                    "--manifest-path",
                    wasmtimeP3BridgeDirectory.file("Cargo.toml").asFile.absolutePath,
                    "--target-dir",
                    wasmtimeP3BridgeTargetDirectory.get().asFile.absolutePath,
                ),
            projectDir,
            localCodexRustEnvironment() + codexRustEnvironmentFor(cargo),
        )
        val library = wasmtimeP3BridgeReleaseLibrary.get().asFile
        check(library.isFile) {
            "Expected Wasmtime Preview3 bridge library at ${library.invariantSeparatorsPath}"
        }
    }
}

extensions.configure<KotlinMultiplatformExtension> {
    krwaArmIosAndWebWasmTargets()

    listOf("iosArm64", "iosSimulatorArm64").forEach { targetName ->
        targets.named<KotlinNativeTarget>(targetName) {
            val wasmtimePulleyRustTarget = when (targetName) {
                "iosArm64" -> "aarch64-apple-ios"
                "iosSimulatorArm64" -> "aarch64-apple-ios-sim"
                else -> error("Unsupported iOS target $targetName")
            }
            val krwaPulleyBridgeLib = krwaPulleyIosBridgeLibDirectory.map { directory ->
                directory.file("$wasmtimePulleyRustTarget/libkrwa_pulley_ios.a")
            }
            val wasmtimeP3BridgeLib = wasmtimeP3BridgeIosLibDirectory.map { directory ->
                directory.file("$wasmtimePulleyRustTarget/libkrwa_wasmtime_p3_bridge.a")
            }
            val wasmtimePulleyLib = wasmtimePulleyIosLibDirectory.map { directory ->
                directory.file("$wasmtimePulleyRustTarget/libwasmtime.a")
            }
            compilations.getByName("main") {
                cinterops.create("wasmtimePulley") {
                    defFile(project.file("src/iosMain/cinterop/wasmtime_pulley.def"))
                    includeDirs(project.file("src/iosMain/cinterop"))
                    extraOpts(
                        "-libraryPath",
                        krwaPulleyBridgeLib.get().asFile.parentFile.absolutePath,
                        "-staticLibrary",
                        "libkrwa_pulley_ios.a",
                        "-libraryPath",
                        wasmtimeP3BridgeLib.get().asFile.parentFile.absolutePath,
                        "-staticLibrary",
                        "libkrwa_wasmtime_p3_bridge.a",
                        "-libraryPath",
                        wasmtimePulleyLib.get().asFile.parentFile.absolutePath,
                        "-staticLibrary",
                        "libwasmtime.a",
                    )
                }
            }
            binaries.all {
                linkerOpts(
                    "-L${krwaPulleyBridgeLib.get().asFile.parentFile.absolutePath}",
                    "-lkrwa_pulley_ios",
                    "-L${wasmtimeP3BridgeLib.get().asFile.parentFile.absolutePath}",
                    "-lkrwa_wasmtime_p3_bridge",
                    "-L${wasmtimePulleyLib.get().asFile.parentFile.absolutePath}",
                    "-lwasmtime",
                    "-lc++",
                )
            }
            if (hostIsMacOs) {
                tasks.named(compilations.getByName("main").cinterops["wasmtimePulley"].interopProcessingTaskName) {
                    dependsOn(buildKrwaPulleyIosBridgeLibs)
                    dependsOn(buildWasmtimeP3BridgeIosLibs)
                    dependsOn(buildWasmtimePulleyIosLibs)
                    inputs.file(krwaPulleyBridgeLib)
                    inputs.file(wasmtimeP3BridgeLib)
                    inputs.file(wasmtimePulleyLib)
                }
                binaries.configureEach {
                    linkTaskProvider.configure {
                        dependsOn(buildKrwaPulleyIosBridgeLibs)
                        dependsOn(buildWasmtimeP3BridgeIosLibs)
                        dependsOn(buildWasmtimePulleyIosLibs)
                        inputs.file(krwaPulleyBridgeLib)
                        inputs.file(wasmtimeP3BridgeLib)
                        inputs.file(wasmtimePulleyLib)
                    }
                }
            }
        }
    }

    sourceSets.named("commonMain") {
        dependencies {
            api(project(":wasm"))
        }
    }
    sourceSets.named("commonTest") {
        kotlin.srcDir("src/commonTest/kotlin")
        dependencies {
            implementation(kotlin("test"))
        }
    }
    sourceSets.named("jvmMain") {
        dependencies {
            compileOnly(libs.graalvmNativeImage)
        }
    }
    sourceSets.named("jvmTest") {
        kotlin.srcDir("src/test/kotlin")
        resources.srcDir("src/test/resources")
        dependencies {
            implementation(libs.junitJupiterApi)
            implementation(libs.junitJupiterParams)
            implementation(project(":wasm-corpus"))
            runtimeOnly(libs.junitJupiterEngine)
            runtimeOnly(libs.junitPlatformLauncher)
        }
    }
    sourceSets.named("wasmJsMain") {
        dependencies {
            implementation(libs.kotlinxBrowser)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
    listOf(
        "krwa.runtimeBenchmark",
        "krwa.runtimeBenchmarkIterations",
        "krwa.runtimeBenchmarkRepetitions",
        "krwa.runtimeBenchmarkHostRepetitions",
        "krwa.runtimeBenchmarkWarmupRepetitions",
        "krwa.runtimeBenchmarkBackends",
    ).forEach { property ->
        providers.systemProperty(property).orNull?.let { value ->
            systemProperty(property, value)
        }
    }
}

val testWasmtimeP3JvmProbe by tasks.registering(Test::class) {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Verifies that the JVM runtime can load and call the built Wasmtime Preview3 bridge library."
    dependsOn(buildWasmtimeP3BridgeLib)
    dependsOn(tasks.named("jvmTestClasses"))
    val jvmTest = tasks.named<Test>("jvmTest")
    testClassesDirs = jvmTest.get().testClassesDirs
    classpath = jvmTest.get().classpath
    useJUnitPlatform()
    filter {
        includeTestsMatching("uk.shusek.krwa.runtime.WasmtimePreview3ComponentProbeTest")
    }
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("krwa.wasmtime.p3.bridge.integration", "true")
    systemProperty("krwa.wasmtime.p3.bridge.library", wasmtimeP3BridgeReleaseLibrary.get().asFile.absolutePath)
    systemProperty("krwa.wasmtime.cli", executableFromEnvOrPath("WASMTIME", "wasmtime")?.absolutePath ?: "wasmtime")
    systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
}

tasks.withType<KotlinNativeTest>().configureEach {
    listOf(
        Triple("KRWA_RUNTIME_BENCHMARK", "krwa.runtimeBenchmark", "--krwa-runtime-benchmark"),
        Triple(
            "KRWA_RUNTIME_BENCHMARK_ITERATIONS",
            "krwa.runtimeBenchmarkIterations",
            "--krwa-runtime-benchmark-iterations",
        ),
        Triple(
            "KRWA_RUNTIME_BENCHMARK_REPETITIONS",
            "krwa.runtimeBenchmarkRepetitions",
            "--krwa-runtime-benchmark-repetitions",
        ),
        Triple(
            "KRWA_RUNTIME_BENCHMARK_WARMUP_REPETITIONS",
            "krwa.runtimeBenchmarkWarmupRepetitions",
            "--krwa-runtime-benchmark-warmup-repetitions",
        ),
    ).forEach { (envName, propertyName, argumentName) ->
        val value =
            providers.environmentVariable(envName).orNull
                ?: providers.systemProperty(propertyName).orNull
        value?.let {
            environment(envName, it)
            args = args + "$argumentName=$it"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
patchKmpJvmModuleInfo()

registerTaskAlias("compileKotlin", "compileKotlinJvm")
registerTaskAlias("compileJava", "compileJvmMainJava")
registerTaskAlias("classes", "jvmMainClasses")
registerTaskAlias("jar", "jvmJar")
registerTaskAlias("testClasses", "jvmTestClasses")
registerTaskAlias("test", "jvmTest")

dependencies {
    add("jvmTestImplementation", platform(libs.junitBom))
}

extensions.configure<PublishingExtension> {
    configureKrwaRepositories(project)
    publications.withType<MavenPublication>().configureEach {
        configureKrwaPom()
    }
}
