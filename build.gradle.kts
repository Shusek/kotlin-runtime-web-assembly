import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.TaskProvider
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
val releaseLine = providers.gradleProperty("krwa.releaseLine")
val hostOs = providers.gradleProperty("krwa.host.os")
    .orElse(System.getProperty("os.name"))
    .get()
    .lowercase()
val hostIsMacOs = "mac" in hostOs || "darwin" in hostOs

tasks.register("verifyImmutablePublicationVersion") {
    group = "verification"
    description = "Rejects mutable or malformed versions before publishing repository artifacts."
    val publicationVersion = providers.gradleProperty("version")
    val requiredReleaseLine = releaseLine
    inputs.property("publicationVersion", publicationVersion)
    inputs.property("releaseLine", requiredReleaseLine)
    doLast {
        val value = publicationVersion.get()
        val line = requiredReleaseLine.get()
        check(Regex("[0-9]+\\.[0-9]+").matches(line)) {
            "Configured KRWA release line has an unsupported format: $line"
        }
        check(!value.endsWith("-SNAPSHOT", ignoreCase = true)) {
            "Published KRWA versions must be immutable; got $value"
        }
        check(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?").matches(value)) {
            "Published KRWA version has an unsupported format: $value"
        }
        check(value.startsWith("$line.")) {
            "Published KRWA versions must remain in the $line.x release line; got $value"
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
        ":runtime-tests",
        ":test-gen-lib",
        ":wabt",
        ":wasi-test-gen",
        ":wasi-tests",
        ":wasm-corpus",
        ":wasm-tools",
    )

allprojects {
    version = rootProject.version
    tasks.withType<Test>().configureEach {
        systemProperty("krwa.gradle.offline", gradle.startParameter.isOffline.toString())
    }
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

val releaseStagingRepository = layout.buildDirectory.dir("release-staging-repository")
val verifyPrebuiltReleaseStagingRepository =
    providers.gradleProperty("krwa.release.verifyOnly")
        .map(String::toBooleanStrict)
        .orElse(false)
val releaseJavadocJars = mutableMapOf<String, TaskProvider<Jar>>()
val releasePublicationDescriptorSeparator = "\u001f"
data class ExpectedReleasePublication(
    val projectPath: String,
    val publicationName: String,
    val groupId: String,
    val artifactId: String,
    val primaryExtension: String,
)

val krwaReleaseGroupId = "uk.shusek.krwa"
val multiplatformReleaseArtifacts =
    linkedMapOf(
        ":annotations:annotations" to "annotations",
        ":component-model" to "component-model",
        ":runtime" to "runtime",
        ":wasi" to "wasi",
        ":wasi-preview3" to "wasi-preview3",
        ":wasm" to "wasm",
    )
val jvmReleaseArtifacts =
    linkedMapOf(
        ":annotations:processor" to "annotations-processor",
        ":cli" to "cli-experimental",
        ":codegen" to "codegen",
        ":log" to "log",
        ":wabt" to "wabt",
        ":wasm-tools" to "wasm-tools",
    )
val expectedReleasePublicationMatrix =
    buildList {
        multiplatformReleaseArtifacts.forEach { (projectPath, artifactId) ->
            add(
                ExpectedReleasePublication(
                    projectPath,
                    "kotlinMultiplatform",
                    krwaReleaseGroupId,
                    artifactId,
                    "jar",
                ),
            )
            add(
                ExpectedReleasePublication(
                    projectPath,
                    "jvm",
                    krwaReleaseGroupId,
                    "$artifactId-jvm",
                    "jar",
                ),
            )
            add(
                ExpectedReleasePublication(
                    projectPath,
                    "wasmJs",
                    krwaReleaseGroupId,
                    "$artifactId-wasm-js",
                    "klib",
                ),
            )
            add(
                ExpectedReleasePublication(
                    projectPath,
                    "iosArm64",
                    krwaReleaseGroupId,
                    "$artifactId-iosarm64",
                    "klib",
                ),
            )
            add(
                ExpectedReleasePublication(
                    projectPath,
                    "iosSimulatorArm64",
                    krwaReleaseGroupId,
                    "$artifactId-iossimulatorarm64",
                    "klib",
                ),
            )
        }
        jvmReleaseArtifacts.forEach { (projectPath, artifactId) ->
            add(
                ExpectedReleasePublication(
                    projectPath,
                    "maven",
                    krwaReleaseGroupId,
                    artifactId,
                    "jar",
                ),
            )
        }
        add(
            ExpectedReleasePublication(
                ":runtime-wasmtime-android",
                "kotlinMultiplatform",
                krwaReleaseGroupId,
                "runtime-wasmtime-android",
                "jar",
            ),
        )
        add(
            ExpectedReleasePublication(
                ":runtime-wasmtime-android",
                "android",
                krwaReleaseGroupId,
                "runtime-wasmtime-android-android",
                "aar",
            ),
        )
        add(
            ExpectedReleasePublication(
                ":component-model-gradle-plugin",
                "pluginMaven",
                krwaReleaseGroupId,
                "component-model-gradle-plugin",
                "jar",
            ),
        )
        add(
            ExpectedReleasePublication(
                ":component-model-gradle-plugin",
                "componentModelPluginMarkerMaven",
                "uk.shusek.krwa.component-model",
                "uk.shusek.krwa.component-model.gradle.plugin",
                "",
            ),
        )
        add(
            ExpectedReleasePublication(
                ":bom",
                "maven",
                krwaReleaseGroupId,
                "bom",
                "",
            ),
        )
    }
val expectedReleaseVersion = project.version.toString()
val expectedReleasePublicationDescriptors =
    expectedReleasePublicationMatrix.map { publication ->
        listOf(
            publication.projectPath,
            publication.publicationName,
            publication.groupId,
            publication.artifactId,
            expectedReleaseVersion,
            publication.primaryExtension,
        ).joinToString(releasePublicationDescriptorSeparator)
    }
val actualReleasePublicationDescriptors = mutableListOf<String>()
val verifyReleasePublicationMatrix =
    tasks.register("verifyReleasePublicationMatrix") {
        group = "verification"
        description =
            "Verifies that the current release host configures every required Maven publication."
        inputs.property(
            "expectedReleasePublications",
            expectedReleasePublicationDescriptors.sorted(),
        )
        inputs.property(
            "actualReleasePublications",
            providers.provider { actualReleasePublicationDescriptors.sorted() },
        )
        doLast {
            val expectedReleasePublicationSet = expectedReleasePublicationDescriptors.toSet()
            val actualReleasePublicationSet = actualReleasePublicationDescriptors.toSet()
            check(
                expectedReleasePublicationSet.size == expectedReleasePublicationDescriptors.size
            ) {
                "Static release publication matrix contains duplicate descriptors"
            }
            check(actualReleasePublicationSet.size == actualReleasePublicationDescriptors.size) {
                "Configured Maven publications contain duplicate descriptors"
            }
            check(actualReleasePublicationSet == expectedReleasePublicationSet) {
                val missing =
                    (expectedReleasePublicationSet - actualReleasePublicationSet)
                        .sorted()
                        .map { descriptor ->
                            descriptor.replace(releasePublicationDescriptorSeparator, " | ")
                        }
                val unexpected =
                    (actualReleasePublicationSet - expectedReleasePublicationSet)
                        .sorted()
                        .map { descriptor ->
                            descriptor.replace(releasePublicationDescriptorSeparator, " | ")
                        }
                "Release publication matrix mismatch; missing=$missing, unexpected=$unexpected"
            }
        }
    }
val prepareGradleReleaseDependencies =
    tasks.register("prepareGradleReleaseDependencies") {
        group = "build setup"
        description =
            "Resolves every project dependency artifact required by an offline releaseGate."
    }
allprojects {
    val dependencyProject = this
    val prepareProjectReleaseDependencies =
        tasks.register("prepareProjectReleaseDependencies") {
            group = "build setup"
            description =
                "Resolves dependency artifacts required by ${dependencyProject.path} offline."
            doLast {
                val failures = mutableListOf<String>()
                dependencyProject.configurations
                    .filter { configuration ->
                        configuration.isCanBeResolved &&
                            !configuration.name.endsWith("CInterop") &&
                            !(
                                dependencyProject.path == ":runtime-wasmtime-android" &&
                                    (
                                        configuration.name.startsWith("androidDeviceTest") ||
                                            configuration.name.startsWith("androidHostTest")
                                    )
                            )
                    }
                    .sortedBy { configuration -> configuration.name }
                    .forEach { configuration ->
                        runCatching {
                            configuration.incoming
                                .artifactView {
                                    componentFilter { component ->
                                        component is ModuleComponentIdentifier
                                    }
                                }
                                .artifacts
                                .artifactFiles
                                .files
                        }.onFailure { failure ->
                            failures +=
                                "${dependencyProject.path}:${configuration.name}: " +
                                    (failure.message ?: failure.javaClass.name)
                        }
                    }
                check(failures.isEmpty()) {
                    "Could not prepare Gradle dependency artifacts:\n" +
                        failures.joinToString(separator = "\n")
                }
            }
        }
    rootProject.tasks.named("prepareGradleReleaseDependencies") {
        dependsOn(prepareProjectReleaseDependencies)
    }
}

val nestedGradleWrapperName =
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        "gradlew.bat"
    } else {
        "gradlew"
    }
val prepareExternalReleaseDependenciesInitScript =
    layout.projectDirectory.file("gradle/prepare-external-release-dependencies.init.gradle.kts")
val releaseDependencyPlaceholderRepository =
    layout.buildDirectory.dir("release-dependency-placeholder-repository")

fun registerNestedReleaseDependencyPreparation(
    name: String,
    nestedBuildPath: String,
    gradleWrapperPath: String,
    allowMissingKrwaModules: Boolean,
    additionalTasks: List<String> = emptyList(),
): TaskProvider<Exec> =
    tasks.register<Exec>(name) {
        group = "build setup"
        description =
            "Resolves external dependency artifacts for the $nestedBuildPath nested build."
        workingDir(layout.projectDirectory.dir(nestedBuildPath))
        inputs.file(prepareExternalReleaseDependenciesInitScript)
        outputs.upToDateWhen { false }
        doFirst {
            val placeholderRepository =
                releaseDependencyPlaceholderRepository.get().asFile.apply { mkdirs() }
            val arguments =
                mutableListOf(
                    "--no-daemon",
                    "--init-script",
                    prepareExternalReleaseDependenciesInitScript.asFile.absolutePath,
                )
            if (allowMissingKrwaModules) {
                arguments +=
                    listOf(
                        "-Pkrwa.releaseRepository=${placeholderRepository.absolutePath}",
                        "-Pkrwa.prepare.allowMissingKrwa=true",
                    )
            }
            arguments += "prepareExternalReleaseDependencies"
            arguments += additionalTasks
            commandLine(
                layout.projectDirectory.file(gradleWrapperPath).asFile.absolutePath,
                *arguments.toTypedArray(),
            )
        }
    }

val prepareStandaloneSampleReleaseDependencies =
    registerNestedReleaseDependencyPreparation(
        name = "prepareStandaloneSampleReleaseDependencies",
        nestedBuildPath = "samples/sample",
        gradleWrapperPath = "samples/sample/$nestedGradleWrapperName",
        allowMissingKrwaModules = true,
        additionalTasks = listOf("kotlinWasmBinaryenSetup"),
    )
val prepareAndroidSampleReleaseDependencies =
    registerNestedReleaseDependencyPreparation(
        name = "prepareAndroidSampleReleaseDependencies",
        nestedBuildPath = "samples/android-tests",
        gradleWrapperPath = "samples/android-tests/$nestedGradleWrapperName",
        allowMissingKrwaModules = true,
    )
val asmBomVersion = libs.versions.asm.get()
val releasePomDescriptorDependencies =
    configurations.detachedConfiguration(
        dependencies.create("org.ow2.asm:asm-bom:$asmBomVersion@pom"),
    ).apply {
        isTransitive = false
    }
val prepareReleasePomDescriptorDependencies =
    tasks.register("prepareReleasePomDescriptorDependencies") {
        group = "build setup"
        description =
            "Downloads Maven POM descriptors referenced by staged publications and offline consumers."
        inputs.property("asmBomVersion", asmBomVersion)
        doLast {
            val descriptors = releasePomDescriptorDependencies.files
            check(descriptors.any { file -> file.name == "asm-bom-$asmBomVersion.pom" }) {
                "ASM BOM POM was not prepared for offline release consumers"
            }
        }
    }

val prepareReleaseDependencies =
    tasks.register("prepareReleaseDependencies") {
        group = "build setup"
        description =
            "Downloads and verifies pinned external inputs required by an offline releaseGate."
        dependsOn(
            prepareGradleReleaseDependencies,
            prepareReleasePomDescriptorDependencies,
            ":component-model:downloadWasiPreview1Adapters",
            ":wasm-tools:downloadWasmTools",
            ":runtime:prepareRustReleaseDependencies",
            ":runtime-wasmtime-android:downloadWasmtimePulleyAndroidJniLib",
            ":test-gen-lib:prepareWasmSpecTestsuite",
            ":wasi-tests:prepareWasiSpecTestsuite",
        )
        if (!gradle.startParameter.isOffline) {
            dependsOn(
                prepareStandaloneSampleReleaseDependencies,
                prepareAndroidSampleReleaseDependencies,
            )
        }
    }
val cleanReleaseStagingRepository =
    tasks.register<Delete>("cleanReleaseStagingRepository") {
        group = "build"
        description = "Removes the task-owned local Maven repository used by releaseGate."
        doFirst {
            releaseStagingRepository.get().asFile
                .takeIf(File::exists)
                ?.walkTopDown()
                ?.forEach { file ->
                    check(file.setWritable(true, true) || file.canWrite()) {
                        "Could not make prior release staging writable for cleanup: " +
                            file.invariantSeparatorsPath
                    }
                }
        }
        delete(releaseStagingRepository)
        outputs.upToDateWhen { false }
    }

fun ExpectedReleasePublication.releaseStagingTaskPath(): String {
    val publicationTaskSegment =
        publicationName.replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase() else character.toString()
        }
    return "$projectPath:publish${publicationTaskSegment}PublicationToReleaseStagingRepository"
}

val iosReleasePublicationNames = setOf("iosArm64", "iosSimulatorArm64")
val jvmAndWebReleasePublicationNames =
    setOf(
        "kotlinMultiplatform",
        "jvm",
        "wasmJs",
        "maven",
        "pluginMaven",
        "componentModelPluginMarkerMaven",
    )
val iosReleasePublications =
    expectedReleasePublicationMatrix.filter { publication ->
        publication.publicationName in iosReleasePublicationNames
    }
val androidReleasePublications =
    expectedReleasePublicationMatrix.filter { publication ->
        publication.projectPath == ":runtime-wasmtime-android"
    }
val jvmAndWebReleasePublications =
    expectedReleasePublicationMatrix.filter { publication ->
        publication.projectPath != ":runtime-wasmtime-android" &&
            publication.publicationName in jvmAndWebReleasePublicationNames
    }
val classifiedReleasePublicationList =
    iosReleasePublications + androidReleasePublications + jvmAndWebReleasePublications
val classifiedReleasePublications = classifiedReleasePublicationList.toSet()
check(
    classifiedReleasePublications == expectedReleasePublicationMatrix.toSet() &&
        classifiedReleasePublicationList.size == expectedReleasePublicationMatrix.size,
) {
    val missing = expectedReleasePublicationMatrix.toSet() - classifiedReleasePublications
    val duplicated =
        classifiedReleasePublicationList
            .groupingBy { publication -> publication }
            .eachCount()
            .filterValues { count -> count != 1 }
            .keys
    "Every release publication must belong to exactly one CI shard; " +
        "missing=$missing, duplicated=$duplicated"
}

fun registerReleasePublicationShard(
    name: String,
    descriptionText: String,
    publications: List<ExpectedReleasePublication>,
) = tasks.register(name) {
    group = "publishing"
    description = descriptionText
    dependsOn(
        cleanReleaseStagingRepository,
        verifyReleasePublicationMatrix,
        tasks.named("verifyImmutablePublicationVersion"),
    )
    dependsOn(publications.map(ExpectedReleasePublication::releaseStagingTaskPath))
}

val stageJvmAndWebReleasePublications =
    registerReleasePublicationShard(
        name = "stageJvmAndWebReleasePublications",
        descriptionText =
            "Stages JVM, Wasm JS, KMP metadata, Gradle plugin, and BOM publications.",
        publications = jvmAndWebReleasePublications,
    )
val stageAndroidReleasePublications =
    registerReleasePublicationShard(
        name = "stageAndroidReleasePublications",
        descriptionText = "Stages Android AAR and Android KMP metadata publications.",
        publications = androidReleasePublications,
    )
val stageIosReleasePublications =
    registerReleasePublicationShard(
        name = "stageIosReleasePublications",
        descriptionText = "Stages iOS device and simulator publications.",
        publications = iosReleasePublications,
    )

val abiVerificationProjectPaths =
    (multiplatformReleaseArtifacts.keys + jvmReleaseArtifacts.keys).toSortedSet()

val ciJvmGate =
    tasks.register("ciJvmGate") {
        group = "verification"
        description = "Runs JVM, build-logic, and ABI verification for CI."
        dependsOn(
            "verifyNoUnjustifiedDisabledTests",
            "verifySampleDependencyVersions",
            ":bom:check",
            ":component-model-gradle-plugin:check",
            ":component-model-gradle-plugin:validatePlugins",
        )
        dependsOn(jvmProjectPaths.map { projectPath -> "$projectPath:check" })
        dependsOn(
            multiplatformTestTasks.keys.map { projectPath -> "$projectPath:jvmTest" },
        )
        dependsOn(
            abiVerificationProjectPaths.map { projectPath -> "$projectPath:checkKotlinAbi" },
        )
    }

val ciHostNativeGate =
    tasks.register("ciHostNativeGate") {
        group = "verification"
        description = "Builds and tests the host Wasmtime Preview3 bridge in isolation."
        dependsOn(
            ":runtime:testWasmtimeP3Bridge",
            ":runtime:testWasmtimeP3JvmProbe",
        )
    }

val ciWebPublicationGate =
    tasks.register("ciWebPublicationGate") {
        group = "verification"
        description = "Runs browser and Node Wasm tests and stages non-native publications."
        val wasmTestProjectPaths =
            multiplatformTestTasks.filterValues { taskNames ->
                "wasmJsNodeTest" in taskNames
            }.keys
        dependsOn(
            wasmTestProjectPaths.flatMap { projectPath ->
                listOf(
                    "$projectPath:wasmJsBrowserTest",
                    "$projectPath:wasmJsNodeTest",
                )
            },
        )
        dependsOn(stageJvmAndWebReleasePublications)
    }

val ciAndroidGate =
    tasks.register("ciAndroidGate") {
        group = "verification"
        description = "Runs Android host/native checks and stages both Android publications."
        dependsOn(":runtime-wasmtime-android:check")
        dependsOn(stageAndroidReleasePublications)
    }

val ciIosGate =
    tasks.register("ciIosGate") {
        group = "verification"
        description = "Runs iOS simulator tests and stages device and simulator publications."
        dependsOn(
            multiplatformTestTasks.mapNotNull { (projectPath, taskNames) ->
                "iosSimulatorArm64Test"
                    .takeIf(taskNames::contains)
                    ?.let { taskName -> "$projectPath:$taskName" }
            },
        )
        dependsOn(stageIosReleasePublications)
    }

fun File.isReleaseStagingHostMetadata(): Boolean =
    isFile && (name == ".DS_Store" || name.startsWith("._"))

fun removeReleaseStagingHostMetadata(repository: File) {
    repository.walkTopDown()
        .filter(File::isReleaseStagingHostMetadata)
        .toList()
        .forEach { file ->
            check(file.delete() || !file.exists()) {
                "Could not remove host metadata from release staging: " +
                    file.relativeTo(repository).invariantSeparatorsPath
            }
        }
}

val verifyNoUnjustifiedDisabledTests =
    tasks.register<VerifyNoUnjustifiedDisabledTests>("verifyNoUnjustifiedDisabledTests") {
        group = "verification"
        description = "Rejects disabled tests without a useful reason and durable issue reference."
        rootDirectory.set(layout.projectDirectory)
        testSources.from(
            fileTree(layout.projectDirectory) {
                include("**/src/test/**/*.kt")
                include("**/src/test/**/*.java")
                include("**/src/*Test/**/*.kt")
                include("**/src/*Test/**/*.java")
                exclude("**/build/**")
                exclude("**/.gradle/**")
            },
        )
        buildScripts.from(
            fileTree(layout.projectDirectory) {
                include("**/*.gradle")
                include("**/*.gradle.kts")
                exclude("**/build/**")
                exclude("**/.gradle/**")
            },
        )
    }

val sampleVersionCatalogs =
    files(
        "samples/sample/gradle/libs.versions.toml",
        "samples/android-tests/gradle/libs.versions.toml",
    )
val verifySampleDependencyVersions =
    tasks.register("verifySampleDependencyVersions") {
        group = "verification"
        description = "Verifies that checked-in samples consume the exact release candidate version."
        val expectedVersion = providers.gradleProperty("version")
        inputs.files(sampleVersionCatalogs)
        inputs.property("expectedVersion", expectedVersion)
        doLast {
            val expected = expectedVersion.get()
            sampleVersionCatalogs.files.forEach { catalog ->
                check(catalog.isFile) {
                    "Sample version catalog is missing: ${catalog.invariantSeparatorsPath}"
                }
                val declared =
                    catalog.useLines { lines ->
                        lines
                            .map(String::trim)
                            .firstOrNull { line -> line.startsWith("krwa = ") }
                            ?.substringAfter('=')
                            ?.trim()
                            ?.removeSurrounding("\"")
                    }
                check(declared == expected) {
                    "Sample ${catalog.invariantSeparatorsPath} pins KRWA $declared; expected $expected"
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyNoUnjustifiedDisabledTests)
    dependsOn(verifySampleDependencyVersions)
}

val releaseGate =
    tasks.register("releaseGate") {
        group = "verification"
        description =
            "Runs release checks and publishes all Maven artifacts only to build/release-staging-repository."
        dependsOn(
            cleanReleaseStagingRepository,
            prepareReleaseDependencies,
            verifyNoUnjustifiedDisabledTests,
            verifySampleDependencyVersions,
            verifyReleasePublicationMatrix,
            tasks.named("verifyImmutablePublicationVersion"),
        )
    }

val verifyReleaseStagingRepository =
    tasks.register("verifyReleaseStagingRepository") {
        group = "verification"
        description =
            "Verifies staged Maven artifacts and writes deterministic SHA-256 evidence."
        dependsOn(verifyReleasePublicationMatrix)
        val checksumManifest =
            releaseStagingRepository.map { directory -> directory.file("SHA256SUMS") }
        inputs.property("releaseVersion", expectedReleaseVersion)
        outputs.file(checksumManifest)
        outputs.upToDateWhen { false }
        doLast {
            val repository = releaseStagingRepository.get().asFile
            check(repository.isDirectory) {
                "Release staging repository was not created at ${repository.invariantSeparatorsPath}"
            }
            removeReleaseStagingHostMetadata(repository)
            val manifest = checksumManifest.get().asFile
            val files =
                repository.walkTopDown()
                    .filter(File::isFile)
                    .filterNot(File::isReleaseStagingHostMetadata)
                    .filterNot { file -> file == manifest }
                    .sortedBy { file -> file.relativeTo(repository).invariantSeparatorsPath }
                    .toList()
            check(files.any { file -> file.extension == "pom" }) {
                "Release staging repository contains no Maven POMs"
            }
            check(
                files.any { file ->
                    file.extension in setOf("aar", "jar", "klib", "module")
                }
            ) {
                "Release staging repository contains no consumable artifacts"
            }
            val filesByRelativePath =
                files.associateBy { file -> file.relativeTo(repository).invariantSeparatorsPath }
            val expectedPomPaths = linkedMapOf<String, String>()
            val expectedArtifactDirectories = linkedMapOf<String, String>()
            expectedReleasePublicationDescriptors.sorted().forEach { descriptor ->
                val fields = descriptor.split(releasePublicationDescriptorSeparator)
                check(fields.size == 6) {
                    "Invalid expected release publication descriptor: $descriptor"
                }
                val projectPath = fields[0]
                val publicationName = fields[1]
                val groupId = fields[2]
                val artifactId = fields[3]
                val version = fields[4]
                val extensions = fields[5]
                val publicationLabel =
                    "$projectPath:$publicationName ($groupId:$artifactId:$version)"
                check(groupId.isNotBlank() && artifactId.isNotBlank() && version.isNotBlank()) {
                    "Release publication has incomplete coordinates: $publicationLabel"
                }
                check(version == expectedReleaseVersion) {
                    "Release publication uses $version; expected $expectedReleaseVersion: " +
                        publicationLabel
                }
                val artifactDirectory = "${groupId.replace('.', '/')}/$artifactId"
                val duplicateArtifactDirectory =
                    expectedArtifactDirectories.put(artifactDirectory, publicationLabel)
                check(duplicateArtifactDirectory == null) {
                    "Release publications share an artifact directory: " +
                        "$duplicateArtifactDirectory and $publicationLabel"
                }
                val coordinatePath =
                    "$artifactDirectory/$version/$artifactId-$version"
                val pomPath = "$coordinatePath.pom"
                val duplicate = expectedPomPaths.put(pomPath, publicationLabel)
                check(duplicate == null) {
                    "Release publications share Maven coordinates: $duplicate and " +
                        "$publicationLabel"
                }
                check(pomPath in filesByRelativePath) {
                    "Release publication is missing its POM: $publicationLabel -> $pomPath"
                }

                val primaryExtensions =
                    extensions.split(',').filter(String::isNotBlank).toSortedSet()
                val intentionallyPomOnly =
                    artifactId == "bom" || artifactId.endsWith(".gradle.plugin")
                check(primaryExtensions.isNotEmpty() || intentionallyPomOnly) {
                    "Release publication has no primary artifact: $publicationLabel"
                }
                if (primaryExtensions.isNotEmpty() || artifactId == "bom") {
                    val modulePath = "$coordinatePath.module"
                    check(modulePath in filesByRelativePath) {
                        "Release publication is missing Gradle module metadata: " +
                            "$publicationLabel -> $modulePath"
                    }
                }
                if (primaryExtensions.isNotEmpty()) {
                    primaryExtensions.forEach { extension ->
                        val artifactPath = "$coordinatePath.$extension"
                        check(artifactPath in filesByRelativePath) {
                            "Release publication is missing its primary artifact: " +
                                "$publicationLabel -> $artifactPath"
                        }
                    }
                    val sourcesPath = "$coordinatePath-sources.jar"
                    val javadocPath = "$coordinatePath-javadoc.jar"
                    check(sourcesPath in filesByRelativePath) {
                        "Release publication is missing its sources JAR: " +
                            "$publicationLabel -> $sourcesPath"
                    }
                    check(javadocPath in filesByRelativePath) {
                        "Release publication is missing its Javadoc JAR: " +
                            "$publicationLabel -> $javadocPath"
                    }
                }
            }
            check(expectedPomPaths.isNotEmpty()) {
                "No Maven publications were configured for the release staging repository"
            }
            val unexpectedPomPaths =
                filesByRelativePath.keys
                    .filter { path -> path.endsWith(".pom") && path !in expectedPomPaths }
                    .sorted()
            check(unexpectedPomPaths.isEmpty()) {
                "Release staging repository contains unexpected POMs: " +
                    unexpectedPomPaths.joinToString()
            }
            val unexpectedReleasePaths =
                filesByRelativePath.keys
                    .filterNot { path ->
                        expectedArtifactDirectories.keys.any { artifactDirectory ->
                            path.startsWith(
                                "$artifactDirectory/$expectedReleaseVersion/",
                            ) ||
                                path == "$artifactDirectory/maven-metadata.xml" ||
                                path.startsWith("$artifactDirectory/maven-metadata.xml.")
                        }
                    }
                    .sorted()
            check(unexpectedReleasePaths.isEmpty()) {
                "Release staging repository contains unexpected coordinates or versions: " +
                    unexpectedReleasePaths.joinToString()
            }
            files.forEach { file ->
                val relativePath = file.relativeTo(repository).invariantSeparatorsPath
                check(file.length() > 0L) {
                    "Release staging artifact is empty: $relativePath"
                }
                check(!relativePath.contains("SNAPSHOT", ignoreCase = true)) {
                    "Mutable snapshot artifact reached release staging: $relativePath"
                }
                if (file.extension == "pom" || file.extension == "module") {
                    val metadata = file.readText()
                    check(!metadata.contains("SNAPSHOT", ignoreCase = true)) {
                        "Mutable snapshot dependency reached release metadata: $relativePath"
                    }
                    val dynamicVersionMarkers =
                        listOf(
                            "<version>+</version>",
                            "<version>latest.release</version>",
                            "<version>latest.integration</version>",
                            "\"requires\": \"+\"",
                            "\"requires\": \"latest.release\"",
                            "\"requires\": \"latest.integration\"",
                        )
                    check(dynamicVersionMarkers.none(metadata::contains)) {
                        "Dynamic dependency version reached release metadata: $relativePath"
                    }
                    check(
                        !Regex("<version>\\s*[\\[(].*[\\])]\\s*</version>")
                            .containsMatchIn(metadata)
                    ) {
                        "Dependency version range reached release metadata: $relativePath"
                    }
                    if (file.extension == "pom") {
                        val requiredPomElements =
                            listOf(
                                "name",
                                "description",
                                "url",
                                "licenses",
                                "developers",
                                "scm",
                            )
                        requiredPomElements.forEach { element ->
                            check(
                                Regex("<$element(?:\\s[^>]*)?>[\\s\\S]*</$element>")
                                    .containsMatchIn(metadata)
                            ) {
                                "Release POM is missing required <$element> metadata: $relativePath"
                            }
                        }
                    }
                }
            }
            files
                .filter { file -> file.extension in setOf("aar", "jar", "klib") }
                .forEach { artifact ->
                    val versionDirectory = artifact.parentFile
                    val artifactDirectory = versionDirectory.parentFile
                    val version = versionDirectory.name
                    val artifactId = artifactDirectory.name
                    val primaryArtifactName = "$artifactId-$version.${artifact.extension}"
                    if (artifact.name != primaryArtifactName) {
                        return@forEach
                    }
                    val versionPath =
                        versionDirectory.relativeTo(repository).invariantSeparatorsPath
                    val artifactPath = artifact.relativeTo(repository).invariantSeparatorsPath
                    val sourcesPath = "$versionPath/$artifactId-$version-sources.jar"
                    val javadocPath = "$versionPath/$artifactId-$version-javadoc.jar"
                    check(sourcesPath in filesByRelativePath) {
                        "Release artifact is missing its sources JAR: $artifactPath -> $sourcesPath"
                    }
                    check(javadocPath in filesByRelativePath) {
                        "Release artifact is missing its Javadoc JAR: $artifactPath -> $javadocPath"
                    }
                }
            val checksumLines =
                files.map { file ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val hash =
                        digest.digest().joinToString("") { byte ->
                            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                        }
                    "$hash  ${file.relativeTo(repository).invariantSeparatorsPath}"
                }
            manifest.writeText(checksumLines.joinToString(separator = "\n", postfix = "\n"))
            removeReleaseStagingHostMetadata(repository)
        }
    }

val sampleGradleWrapperName =
    if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
        "gradlew.bat"
    } else {
        "gradlew"
    }

val verifyStandaloneSampleAgainstReleaseStaging =
    tasks.register<Exec>("verifyStandaloneSampleAgainstReleaseStaging") {
        group = "verification"
        description =
            "Compiles every supported standalone sample target against only the staged release artifacts."
        dependsOn(verifyReleaseStagingRepository)
        workingDir(layout.projectDirectory.dir("samples/sample"))
        doFirst {
            val repository = releaseStagingRepository.get().asFile.absolutePath
            val arguments =
                mutableListOf(
                    "--no-daemon",
                    "--offline",
                    "-Pkrwa.releaseRepository=$repository",
                    "compileKotlinJvm",
                    "compileKotlinWasmJs",
                    "compileKotlinWasmWasi",
                )
            if (hostIsMacOs) {
                arguments += "compileKotlinIosSimulatorArm64"
            }
            commandLine(
                layout.projectDirectory
                    .file("samples/sample/$sampleGradleWrapperName")
                    .asFile
                    .absolutePath,
                *arguments.toTypedArray(),
            )
        }
    }

val verifyAndroidSampleAgainstReleaseStaging =
    tasks.register<Exec>("verifyAndroidSampleAgainstReleaseStaging") {
        group = "verification"
        description =
            "Compiles the Android instrumentation sample against only the staged release artifacts."
        dependsOn(verifyReleaseStagingRepository)
        workingDir(layout.projectDirectory.dir("samples/android-tests"))
        doFirst {
            val repository = releaseStagingRepository.get().asFile.absolutePath
            commandLine(
                layout.projectDirectory
                    .file("samples/android-tests/$sampleGradleWrapperName")
                    .asFile
                    .absolutePath,
                "--no-daemon",
                "--offline",
                "-Pkrwa.releaseRepository=$repository",
                ":device-tests:compileRuntimeReleaseAndroidTestKotlin",
            )
        }
    }

val finalizeReleaseStagingRepository =
    tasks.register("finalizeReleaseStagingRepository") {
        group = "verification"
        description =
            "Removes host metadata created by consumers, then re-verifies the exact staged checksums."
        dependsOn(
            verifyStandaloneSampleAgainstReleaseStaging,
            verifyAndroidSampleAgainstReleaseStaging,
        )
        outputs.upToDateWhen { false }
        doLast {
            val repository = releaseStagingRepository.get().asFile
            val manifest = repository.resolve("SHA256SUMS")
            check(manifest.isFile) {
                "Release staging checksum manifest is missing after consumer verification"
            }

            removeReleaseStagingHostMetadata(repository)

            val expectedChecksums = linkedMapOf<String, String>()
            manifest.useLines { lines ->
                lines.filter(String::isNotBlank).forEach { line ->
                    val match = Regex("^([0-9a-f]{64})  (.+)$").matchEntire(line)
                        ?: error("Invalid release checksum manifest line: $line")
                    val path = match.groupValues[2]
                    check(expectedChecksums.put(path, match.groupValues[1]) == null) {
                        "Duplicate release checksum manifest path: $path"
                    }
                }
            }
            val stagedFiles =
                repository.walkTopDown()
                    .filter(File::isFile)
                    .filterNot { file -> file == manifest }
                    .associateBy { file -> file.relativeTo(repository).invariantSeparatorsPath }
            check(stagedFiles.keys == expectedChecksums.keys) {
                val missing = (expectedChecksums.keys - stagedFiles.keys).sorted()
                val unexpected = (stagedFiles.keys - expectedChecksums.keys).sorted()
                "Release staging changed during consumer verification; " +
                    "missing=$missing, unexpected=$unexpected"
            }
            stagedFiles.forEach { (path, file) ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual =
                    digest.digest().joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
                check(actual == expectedChecksums.getValue(path)) {
                    "Release staging artifact changed during consumer verification: $path"
                }
            }
            repository.walkBottomUp().forEach { file ->
                check(file.setWritable(false, false) || !file.canWrite()) {
                    "Could not make finalized release staging read-only: " +
                        file.relativeTo(repository).invariantSeparatorsPath
                }
            }
        }
    }

val verifyMergedReleaseRepository =
    tasks.register("verifyMergedReleaseRepository") {
        group = "verification"
        description =
            "Verifies and consumer-tests a prebuilt, merged release staging repository."
        if (verifyPrebuiltReleaseStagingRepository.get()) {
            dependsOn(
                tasks.named("verifyImmutablePublicationVersion"),
                verifySampleDependencyVersions,
                finalizeReleaseStagingRepository,
            )
        } else {
            doLast {
                error(
                    "verifyMergedReleaseRepository requires " +
                        "-Pkrwa.release.verifyOnly=true",
                )
            }
        }
    }

subprojects {
    pluginManager.withPlugin("maven-publish") {
        if (path != ":bom") {
            val releaseJavadocJar =
                tasks.register<Jar>("releaseJavadocJar") {
                    group = "documentation"
                    description =
                        "Assembles the Maven Central Javadoc artifact from Dokka output or release documentation."
                    archiveClassifier.set("javadoc")
                    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                    from(rootProject.file("README.md")) {
                        into("META-INF")
                    }
                    from(rootProject.file("LICENSE")) {
                        into("META-INF")
                    }
                }
            releaseJavadocJars[path] = releaseJavadocJar
            pluginManager.withPlugin("org.jetbrains.dokka") {
                releaseJavadocJar.configure {
                    dependsOn("dokkaGeneratePublicationHtml")
                    from(layout.buildDirectory.dir("dokka/html"))
                }
            }
        }
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "releaseStaging"
                    url = rootProject.layout.buildDirectory
                        .dir("release-staging-repository")
                        .get()
                        .asFile
                        .toURI()
                }
            }
        }
    }
}

gradle.projectsEvaluated {
    val generatedWasmSpecTestTasks =
        subprojects.mapNotNull { project -> project.tasks.findByName("generateWasmSpecTests") }
    if (!gradle.startParameter.isOffline) {
        val releaseToolchainTaskNames =
            listOf(
                "downloadKotlinNativeDistribution",
                "kotlinWasmNpmInstall",
                "kotlinWasmToolingSetup",
            )
        val releaseToolchainTasks =
            releaseToolchainTaskNames.mapNotNull { taskName ->
                allprojects
                    .asSequence()
                    .mapNotNull { project -> project.tasks.findByName(taskName) }
                    .firstOrNull()
            }
        prepareReleaseDependencies.configure {
            dependsOn(releaseToolchainTasks)
        }
    }
    verifyNoUnjustifiedDisabledTests.configure {
        dependsOn(generatedWasmSpecTestTasks)
        generatedWasmSpecTestTasks.forEach { generationTask ->
            testSources.from(
                generationTask.outputs.files.asFileTree.matching {
                    include("**/*.kt")
                    include("**/*.java")
                },
            )
        }
    }
    subprojects.forEach { project ->
        val javadocJar = releaseJavadocJars[project.path] ?: return@forEach
        project.extensions
            .findByType(PublishingExtension::class.java)
            ?.publications
            ?.withType(org.gradle.api.publish.maven.MavenPublication::class.java)
            ?.configureEach {
                val hasPrimaryArtifact =
                    artifacts.any { artifact ->
                        artifact.classifier.isNullOrBlank() &&
                            artifact.extension in setOf("aar", "jar", "klib")
                    }
                val hasJavadocArtifact =
                    artifacts.any { artifact -> artifact.classifier == "javadoc" }
                if (hasPrimaryArtifact && !hasJavadocArtifact) {
                    artifact(javadocJar)
                }
            }
    }
    val expectedPublishingProjectPaths =
        expectedReleasePublicationMatrix.map { publication -> publication.projectPath }.toSortedSet()
    val declaredPublicProjectPaths =
        (krwaPublicModuleByArtifact.values + ":bom").toSortedSet()
    check(expectedPublishingProjectPaths == declaredPublicProjectPaths) {
        val missing = declaredPublicProjectPaths - expectedPublishingProjectPaths
        val unexpected = expectedPublishingProjectPaths - declaredPublicProjectPaths
        "Static release matrix does not match public modules; missing=$missing, " +
            "unexpected=$unexpected"
    }
    val actualPublishingProjectPaths =
        subprojects
            .filter { project -> project.pluginManager.hasPlugin("maven-publish") }
            .map { project -> project.path }
            .toSortedSet()
    check(actualPublishingProjectPaths == expectedPublishingProjectPaths) {
        val missing = expectedPublishingProjectPaths - actualPublishingProjectPaths
        val unexpected = actualPublishingProjectPaths - expectedPublishingProjectPaths
        "Release publication project mismatch; missing=$missing, unexpected=$unexpected"
    }
    subprojects.forEach { project ->
        project.extensions
            .findByType(PublishingExtension::class.java)
            ?.publications
            ?.withType(org.gradle.api.publish.maven.MavenPublication::class.java)
            ?.forEach { publication ->
                check(publication.version == expectedReleaseVersion) {
                    "Publication ${project.path}:${publication.name} uses " +
                        "${publication.version}; expected $expectedReleaseVersion"
                }
                val primaryExtensions =
                    publication.artifacts
                        .asSequence()
                        .filter { artifact ->
                            artifact.classifier.isNullOrBlank() &&
                                artifact.extension in setOf("aar", "jar", "klib")
                        }
                        .map { artifact -> artifact.extension }
                        .distinct()
                        .sorted()
                        .toList()
                val descriptorFields =
                    listOf(
                        project.path,
                        publication.name,
                        publication.groupId,
                        publication.artifactId,
                        publication.version,
                        primaryExtensions.joinToString(","),
                    )
                check(
                    descriptorFields.none { field ->
                        field.contains(releasePublicationDescriptorSeparator)
                    }
                ) {
                    "Release publication descriptor contains an unsupported separator: " +
                        "${project.path}:${publication.name}"
                }
                actualReleasePublicationDescriptors +=
                    descriptorFields.joinToString(releasePublicationDescriptorSeparator)
            }
    }
    val dependencyTaskNames =
        setOf(
            "check",
            "checkKotlinAbi",
            "validatePlugins",
            "publishAllPublicationsToReleaseStagingRepository",
        )
    val dependencyTasks =
        subprojects.flatMap { project ->
            dependencyTaskNames.mapNotNull(project.tasks::findByName)
        }
    val publicationTasks =
        dependencyTasks.filter { task ->
            task.name == "publishAllPublicationsToReleaseStagingRepository"
        }
    val shardedPublicationTaskPaths =
        expectedReleasePublicationMatrix
            .map(ExpectedReleasePublication::releaseStagingTaskPath)
            .toSet()
    val shardedPublicationTasks =
        allprojects.flatMap { project -> project.tasks }
            .filter { task -> task.path in shardedPublicationTaskPaths }
    check(shardedPublicationTasks.map { task -> task.path }.toSet() == shardedPublicationTaskPaths) {
        val missing =
            shardedPublicationTaskPaths -
                shardedPublicationTasks.map { task -> task.path }.toSet()
        "Release publication shard tasks are missing: $missing"
    }
    val verificationTasks = dependencyTasks - publicationTasks.toSet()
    publicationTasks.forEach { task ->
        task.mustRunAfter(
            listOf(
                cleanReleaseStagingRepository,
                verifyNoUnjustifiedDisabledTests,
                tasks.named("verifyImmutablePublicationVersion"),
            ) + verificationTasks,
        )
    }
    shardedPublicationTasks.forEach { task ->
        task.mustRunAfter(cleanReleaseStagingRepository)
    }
    verifyReleaseStagingRepository.configure {
        inputs.property(
            "expectedReleasePublications",
            expectedReleasePublicationDescriptors.sorted(),
        )
        if (!verifyPrebuiltReleaseStagingRepository.get()) {
            dependsOn(publicationTasks)
        }
        mustRunAfter(verificationTasks)
    }
    releaseGate.configure {
        doFirst {
            check(generatedWasmSpecTestTasks.isNotEmpty()) {
                "releaseGate requires at least one active generateWasmSpecTests task"
            }
        }
        dependsOn(dependencyTasks)
        dependsOn(verifyReleaseStagingRepository)
        dependsOn(verifyStandaloneSampleAgainstReleaseStaging)
        dependsOn(verifyAndroidSampleAgainstReleaseStaging)
        dependsOn(finalizeReleaseStagingRepository)
        dependsOn(":runtime:testWasmtimeP3Bridge")
        dependsOn(":runtime:testWasmtimeP3JvmProbe")
    }
}
