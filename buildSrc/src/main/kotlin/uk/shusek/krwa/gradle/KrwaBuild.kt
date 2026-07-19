package uk.shusek.krwa.gradle

import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

val krwaPublicModuleByArtifact =
    mapOf(
        "annotations" to ":annotations:annotations",
        "annotations-processor" to ":annotations:processor",
        "cli-experimental" to ":cli",
        "codegen" to ":codegen",
        "component-model-gradle-plugin" to ":component-model-gradle-plugin",
        "component-model" to ":component-model",
        "log" to ":log",
        "runtime" to ":runtime",
        "runtime-wasmtime-android" to ":runtime-wasmtime-android",
        "wabt" to ":wabt",
        "wasi" to ":wasi",
        "wasi-preview3" to ":wasi-preview3",
        "wasm" to ":wasm",
        "wasm-tools" to ":wasm-tools",
    )

private val krwaInternalModuleByArtifact =
    mapOf(
        "jmh" to ":jmh",
        "runtime-tests" to ":runtime-tests",
        "test-gen-lib" to ":test-gen-lib",
        "wasi-test-gen" to ":wasi-test-gen",
        "wasi-tests" to ":wasi-tests",
        "wasm-corpus" to ":wasm-corpus",
    )

val krwaModuleByArtifact = krwaPublicModuleByArtifact + krwaInternalModuleByArtifact
val krwaArtifactByProject = krwaModuleByArtifact.entries.associate { (artifact, path) -> path to artifact }
private val krwaPublicProjectPaths = krwaPublicModuleByArtifact.values.toSet()

fun Project.krwa(artifactId: String) = project(krwaModuleByArtifact.getValue(artifactId))

fun MavenPublication.configureKrwaPom() {
    pom {
        name.set("Kotlin Runtime Web Assembly")
        description.set(
            "Kotlin-first WebAssembly runtime, WASI host, and Component Model toolchain.",
        )
        url.set("https://github.com/Shusek/kotlin-runtime-web-assembly")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("shusek")
                name.set("Shusek")
                url.set("https://github.com/Shusek")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/Shusek/kotlin-runtime-web-assembly.git")
            developerConnection.set("scm:git:ssh://git@github.com/Shusek/kotlin-runtime-web-assembly.git")
            url.set("https://github.com/Shusek/kotlin-runtime-web-assembly")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Shusek/kotlin-runtime-web-assembly/issues")
        }
    }
}

fun PublishingExtension.configureKrwaRepositories(project: Project) {
    val githubPagesMavenRepository =
        project.providers.gradleProperty("krwa.githubPagesMavenRepository")
            .orElse(project.providers.environmentVariable("KRWA_GITHUB_PAGES_MAVEN_REPOSITORY"))
            .orNull

    repositories {
        githubPagesMavenRepository?.let { repositoryPath ->
            maven {
                name = "githubPages"
                url = project.uri(repositoryPath)
            }
        }
    }
}

private fun Project.lib(alias: String) =
    rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs").findLibrary(alias).get()

@OptIn(ExperimentalAbiValidation::class)
fun Project.configureKrwaJvmProject() {
    group = rootProject.group
    val publish = path in krwaPublicProjectPaths

    pluginManager.apply("java-library")
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    if (publish) {
        pluginManager.apply("maven-publish")
    }

    extensions.configure<BasePluginExtension> {
        archivesName.set(krwaArtifactByProject.getValue(path))
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        modularity.inferModulePath.set(true)
    }

    extensions.configure<KotlinJvmProjectExtension> {
        if (publish) {
            abiValidation {}
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("25"))
            javaParameters.set(true)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("25"))
            javaParameters.set(true)
        }
    }

    patchJvmModuleInfo()

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        manifest {
            attributes(mapOf("Implementation-Version" to project.version))
        }
    }

    dependencies.add("implementation", dependencies.platform(lib("asmBom")))
    dependencies.add("compileOnly", lib("jetbrainsAnnotations"))
    dependencies.add("testCompileOnly", lib("jetbrainsAnnotations"))
    dependencies.add("testImplementation", dependencies.platform(lib("junitBom")))
    dependencies.add("testImplementation", lib("junitJupiterApi"))
    dependencies.add("testRuntimeOnly", lib("junitJupiterEngine"))
    dependencies.add("testRuntimeOnly", lib("junitPlatformLauncher"))

    if (publish) {
        extensions.configure<PublishingExtension> {
            configureKrwaRepositories(project)
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    artifactId = krwaArtifactByProject.getValue(path)
                    configureKrwaPom()
                }
            }
        }
    }
}

fun Project.patchJvmModuleInfo() {
    val moduleInfo = layout.projectDirectory.file("src/main/java/module-info.java").asFile
    if (moduleInfo.isFile) {
        val moduleName =
            Regex("""module\s+([A-Za-z0-9_.]+)\s*\{""")
                .find(moduleInfo.readText())
                ?.groupValues
                ?.get(1)
                ?: error("Cannot find module name in $moduleInfo")
        val patchModuleDirs =
            objects.fileCollection().from(layout.buildDirectory.dir("classes/kotlin/main"))
        extensions.extraProperties["mainPatchModuleDirs"] = patchModuleDirs
        tasks.named<JavaCompile>("compileJava") {
            dependsOn("compileKotlin")
            options.compilerArgumentProviders.add(
                CommandLineArgumentProvider {
                    listOf("--patch-module", "$moduleName=${patchModuleDirs.asPath}")
                }
            )
        }
    }
}

fun Project.mainSourceSet() = extensions.getByType<SourceSetContainer>().named("main").get()

fun Project.testSourceSet() = extensions.getByType<SourceSetContainer>().named("test").get()

fun Project.addKotlinSource(sourceSetName: String, sourceDir: Any) {
    extensions.configure<KotlinJvmProjectExtension> {
        sourceSets.named(sourceSetName) {
            kotlin.srcDir(sourceDir)
        }
    }
}

fun Project.patchKmpJvmModuleInfo() {
    val moduleInfo = layout.projectDirectory.file("src/jvmMain/java/module-info.java").asFile
    if (moduleInfo.isFile) {
        val moduleName =
            Regex("""module\s+([A-Za-z0-9_.]+)\s*\{""")
                .find(moduleInfo.readText())
                ?.groupValues
                ?.get(1)
                ?: error("Cannot find module name in $moduleInfo")
        val patchModuleDirs =
            objects.fileCollection().from(layout.buildDirectory.dir("classes/kotlin/jvm/main"))
        tasks.named<JavaCompile>("compileJvmMainJava") {
            dependsOn("compileKotlinJvm")
            options.compilerArgumentProviders.add(
                CommandLineArgumentProvider {
                    listOf("--patch-module", "$moduleName=${patchModuleDirs.asPath}")
                }
            )
        }
    }
}

fun Project.registerTaskAlias(alias: String, target: String) {
    if (tasks.names.contains(alias)) {
        tasks.named(alias) {
            dependsOn(target)
        }
    } else {
        tasks.register(alias) {
            dependsOn(target)
        }
    }
}

@OptIn(ExperimentalWasmDsl::class, ExperimentalAbiValidation::class)
fun KotlinMultiplatformExtension.krwaArmIosAndWebWasmTargets() {
    abiValidation {}
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("25"))
        }
    }
    iosArm64()
    iosSimulatorArm64()
    wasmJs {
        browser()
        nodejs()
    }
}

fun Project.filterKotlinTemplates(
    taskName: String,
    sourceSetName: String,
    templateDir: String,
) {
    val outputDir = layout.buildDirectory.dir("generated/sources/kotlinTemplates/$sourceSetName")
    val task =
        tasks.register<Copy>(taskName) {
            from(layout.projectDirectory.dir(templateDir))
            into(outputDir)
            filteringCharset = "UTF-8"
            filter { line: String -> line.replace("\${project.version}", project.version.toString()) }
        }
    addKotlinSource(sourceSetName, outputDir)
    tasks.named(if (sourceSetName == "main") "compileKotlin" else "compileTestKotlin") {
        dependsOn(task)
    }
    if (sourceSetName == "main") {
        tasks.matching { it.name == "sourcesJar" }.configureEach {
            dependsOn(task)
        }
    }
}

data class WasmSpecTestGenConfig(
    val includedWasts: List<String>,
    val excludedTests: List<String>,
    val excludedRuntimeTests: List<String>,
    val excludedMalformedWasts: List<String>,
    val excludedInvalidWasts: List<String>,
    val excludedUninstantiableWasts: List<String>,
    val excludedUnlinkableWasts: List<String>,
    val excludedWasts: List<String>,
    val excludedRuntimeWasts: List<String>,
)

data class WasiSpecTestGenConfig(
    val includes: List<String>,
    val excludes: List<String>,
)

const val WASM_TEST_SUITE_REPOSITORY =
    "https://github.com/WebAssembly/testsuite"
const val WASM_TEST_SUITE_REVISION =
    "88e97b0f742f4c3ee01fea683da130f344dd7b02"
const val WASM_TEST_SUITE_ARCHIVE_SHA256 =
    "8dda64df353a3fbe38c3acdbcda4524eba951b53c7d4d1474ab86b0878f474e4"
private const val WASI_TEST_SUITE_REPOSITORY =
    "https://github.com/WebAssembly/wasi-testsuite"
private const val WASI_TEST_SUITE_REVISION =
    "caf3b66fa3457cc17156864d971387a7e9f5933b"
private const val WASI_TEST_SUITE_ARCHIVE_SHA256 =
    "5bc6471f2ccf57f2c4241fb74bb59a57b897607f3eb2769fc7a2ff97c9e928b3"

fun File.readListFile(): List<String> =
    if (!isFile) {
        emptyList()
    } else {
        readLines()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
    }

fun File.readWasmSpecTestGenConfig(): WasmSpecTestGenConfig {
    require(isDirectory) { "Wasm spec test-gen config directory not found: $this" }
    return WasmSpecTestGenConfig(
        includedWasts = resolve("included-wasts.txt").readListFile(),
        excludedTests = resolve("excluded-tests.txt").readListFile(),
        excludedRuntimeTests = resolve("excluded-runtime-tests.txt").readListFile(),
        excludedMalformedWasts = resolve("excluded-malformed-wasts.txt").readListFile(),
        excludedInvalidWasts = resolve("excluded-invalid-wasts.txt").readListFile(),
        excludedUninstantiableWasts = resolve("excluded-uninstantiable-wasts.txt").readListFile(),
        excludedUnlinkableWasts = resolve("excluded-unlinkable-wasts.txt").readListFile(),
        excludedWasts = resolve("excluded-wasts.txt").readListFile(),
        excludedRuntimeWasts = resolve("excluded-runtime-wasts.txt").readListFile(),
    )
}

fun File.readWasiSpecTestGenConfig(): WasiSpecTestGenConfig {
    require(isDirectory) { "WASI spec test-gen config directory not found: $this" }
    return WasiSpecTestGenConfig(
        includes = resolve("includes.txt").readListFile(),
        excludes = resolve("excludes.txt").readListFile(),
    )
}

private fun jsonString(value: String): String =
    buildString {
        append('"')
        for (char in value) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

private fun jsonArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }

fun wasmSpecTestGenJson(
    config: WasmSpecTestGenConfig,
    testsuiteFolder: File,
    sourceDestinationFolder: File,
    compiledWastTargetFolder: File,
    offline: Boolean,
): String =
    """
    {
      "testSuiteRepo": ${jsonString(WASM_TEST_SUITE_REPOSITORY)},
      "testSuiteRepoRef": ${jsonString(WASM_TEST_SUITE_REVISION)},
      "testSuiteArchiveSha256": ${jsonString(WASM_TEST_SUITE_ARCHIVE_SHA256)},
      "testsuiteFolder": ${jsonString(testsuiteFolder.absolutePath)},
      "sourceDestinationFolder": ${jsonString(sourceDestinationFolder.absolutePath)},
      "compiledWastTargetFolder": ${jsonString(compiledWastTargetFolder.absolutePath)},
      "offline": $offline,
      "includedWasts": ${jsonArray(config.includedWasts)},
      "excludedTests": ${jsonArray(config.excludedTests)},
      "excludedRuntimeTests": ${jsonArray(config.excludedRuntimeTests)},
      "excludedMalformedWasts": ${jsonArray(config.excludedMalformedWasts)},
      "excludedInvalidWasts": ${jsonArray(config.excludedInvalidWasts)},
      "excludedUninstantiableWasts": ${jsonArray(config.excludedUninstantiableWasts)},
      "excludedUnlinkableWasts": ${jsonArray(config.excludedUnlinkableWasts)},
      "excludedWasts": ${jsonArray(config.excludedWasts)},
      "excludedRuntimeWasts": ${jsonArray(config.excludedRuntimeWasts)}
    }
    """.trimIndent()

fun wasiSpecTestGenJson(
    config: WasiSpecTestGenConfig,
    testSuiteFolder: File,
    sourceDestinationFolder: File,
    projectDirectory: File,
    offline: Boolean,
): String =
    """
    {
      "testSuiteRepo": ${jsonString(WASI_TEST_SUITE_REPOSITORY)},
      "testSuiteRepoRef": ${jsonString(WASI_TEST_SUITE_REVISION)},
      "testSuiteArchiveSha256": ${jsonString(WASI_TEST_SUITE_ARCHIVE_SHA256)},
      "testSuiteFolder": ${jsonString(testSuiteFolder.absolutePath)},
      "sourceDestinationFolder": ${jsonString(sourceDestinationFolder.absolutePath)},
      "projectDirectory": ${jsonString(projectDirectory.absolutePath)},
      "offline": $offline,
      "includes": ${jsonArray(config.includes)},
      "excludes": ${jsonArray(config.excludes)}
    }
    """.trimIndent()

fun Project.registerWasmSpecTests(
    profileId: String? = null,
    additionalExcludedTests: List<String> = emptyList(),
    additionalExcludedWasts: List<String> = emptyList(),
) {
    val configDir =
        layout.projectDirectory.dir("src/test-gen/wasm-spec/${profileId ?: "base"}").asFile
    val config =
        configDir
            .readWasmSpecTestGenConfig()
            .let { base ->
                val parserExcludedWasts =
                    (base.excludedWasts + additionalExcludedWasts).distinct().sorted()
                val fullyExcludedWasts =
                    (parserExcludedWasts + base.excludedRuntimeWasts).toSet()
                base.copy(
                    includedWasts = base.includedWasts.filterNot(fullyExcludedWasts::contains),
                    excludedTests = (base.excludedTests + additionalExcludedTests).distinct(),
                    excludedWasts = parserExcludedWasts,
                )
            }
    val configFile = layout.buildDirectory.file("generated/test-gen/config.json")
    val generatedSourceDir = layout.buildDirectory.dir("generated/test-sources/test-gen")
    val compiledWastDir = layout.buildDirectory.dir("generated/test-resources/compiled-wast")
    val testsuiteFolder =
        rootProject.layout.buildDirectory.dir("external-testsuites/wasm").get().asFile
    val offline = gradle.startParameter.isOffline
    val writeConfigTask =
        tasks.register("writeWasmSpecTestGenConfig") {
            inputs.dir(configDir)
            inputs.property("testSuiteRepository", WASM_TEST_SUITE_REPOSITORY)
            inputs.property("testSuiteRevision", WASM_TEST_SUITE_REVISION)
            inputs.property("testSuiteArchiveSha256", WASM_TEST_SUITE_ARCHIVE_SHA256)
            inputs.property("offline", offline)
            inputs.property("includedWasts", config.includedWasts)
            inputs.property("excludedTests", config.excludedTests)
            inputs.property("excludedRuntimeTests", config.excludedRuntimeTests)
            inputs.property("excludedMalformedWasts", config.excludedMalformedWasts)
            inputs.property("excludedInvalidWasts", config.excludedInvalidWasts)
            inputs.property("excludedUninstantiableWasts", config.excludedUninstantiableWasts)
            inputs.property("excludedUnlinkableWasts", config.excludedUnlinkableWasts)
            inputs.property("excludedWasts", config.excludedWasts)
            inputs.property("excludedRuntimeWasts", config.excludedRuntimeWasts)
            outputs.file(configFile)
            doLast {
                val output = configFile.get().asFile
                output.parentFile.mkdirs()
                output.writeText(
                    wasmSpecTestGenJson(
                        config,
                        testsuiteFolder,
                        generatedSourceDir.get().asFile,
                        compiledWastDir.get().asFile,
                        offline,
                    )
                )
            }
        }

    val generateTask =
        tasks.register<JavaExec>("generateWasmSpecTests") {
            dependsOn(writeConfigTask)
            dependsOn(":test-gen-lib:prepareWasmSpecTestsuite")
            dependsOn(":test-gen-lib:classes")
            dependsOn(":wasm-tools:classes")
            inputs.dir(configDir)
            inputs.dir(testsuiteFolder)
            inputs.property("wasmToolsMode", "embedded")
            outputs.dir(generatedSourceDir)
            outputs.dir(compiledWastDir)
            mainClass.set("uk.shusek.krwa.testgen.TestGenCli")
            classpath = rootProject.project(":test-gen-lib").mainSourceSet().runtimeClasspath
            systemProperty("krwa.wasmTools.forceEmbedded", "true")
            doFirst {
                project.delete(generatedSourceDir.get().asFile, compiledWastDir.get().asFile)
                setArgs(listOf(configFile.get().asFile.absolutePath))
            }
        }

    addKotlinSource("test", files(generatedSourceDir).builtBy(generateTask))
    val sourceSet = testSourceSet()
    sourceSet.resources.srcDir(compiledWastDir)
    tasks.named("compileTestKotlin") {
        dependsOn(generateTask)
    }
    tasks.named<JavaCompile>("compileTestJava") {
        dependsOn(generateTask)
        doFirst {
            project.delete(destinationDirectory.get().asFile)
        }
    }
    tasks.named("processTestResources") {
        dependsOn(generateTask)
    }
}

fun Project.registerWasiSpecTests() {
    val configDir = layout.projectDirectory.dir("src/test-gen/wasi-spec").asFile
    val config = configDir.readWasiSpecTestGenConfig()
    val configFile = layout.buildDirectory.file("generated/wasi-test-gen/config.json")
    val generatedSourceDir = layout.buildDirectory.dir("generated/test-sources/wasi-test-gen")
    val testSuiteFolder =
        rootProject.layout.buildDirectory.dir("external-testsuites/wasi").get().asFile
    val testSuiteArchive =
        rootProject.layout.buildDirectory.file(
            "external-testsuites/wasi-testsuite-$WASI_TEST_SUITE_REVISION.zip"
        )
    val offline = gradle.startParameter.isOffline
    val writeConfigTask =
        tasks.register("writeWasiSpecTestGenConfig") {
            inputs.dir(configDir)
            inputs.property("testSuiteRepository", WASI_TEST_SUITE_REPOSITORY)
            inputs.property("testSuiteRevision", WASI_TEST_SUITE_REVISION)
            inputs.property("testSuiteArchiveSha256", WASI_TEST_SUITE_ARCHIVE_SHA256)
            inputs.property("offline", offline)
            inputs.property("includes", config.includes)
            inputs.property("excludes", config.excludes)
            outputs.file(configFile)
            doLast {
                val output = configFile.get().asFile
                output.parentFile.mkdirs()
                output.writeText(
                    wasiSpecTestGenJson(
                        config,
                        testSuiteFolder,
                        generatedSourceDir.get().asFile,
                        layout.projectDirectory.asFile,
                        offline,
                    )
                )
            }
        }

    val prepareTask =
        tasks.register<JavaExec>("prepareWasiSpecTestsuite") {
            dependsOn(writeConfigTask)
            dependsOn(":wasi-test-gen:classes")
            inputs.file(configFile)
            doNotTrackState(
                "The verified specification checkout is durable local state and must survive Gradle version changes.",
            )
            mainClass.set("uk.shusek.krwa.wasitestgen.WasiTestGenPrepareCli")
            classpath = rootProject.project(":wasi-test-gen").mainSourceSet().runtimeClasspath
            doFirst {
                setArgs(listOf(configFile.get().asFile.absolutePath))
            }
        }

    val generateTask =
        tasks.register<JavaExec>("generateWasiSpecTests") {
            dependsOn(writeConfigTask)
            dependsOn(prepareTask)
            dependsOn(":wasi-test-gen:classes")
            inputs.dir(configDir)
            outputs.dir(generatedSourceDir)
            outputs.dir(testSuiteFolder)
            mainClass.set("uk.shusek.krwa.wasitestgen.WasiTestGenCli")
            classpath = rootProject.project(":wasi-test-gen").mainSourceSet().runtimeClasspath
            doFirst {
                project.delete(generatedSourceDir.get().asFile)
                setArgs(listOf(configFile.get().asFile.absolutePath))
            }
        }

    addKotlinSource("test", files(generatedSourceDir).builtBy(generateTask))
    tasks.named("compileTestKotlin") {
        dependsOn(generateTask)
    }
    tasks.named<JavaCompile>("compileTestJava") {
        dependsOn(generateTask)
        doFirst {
            project.delete(destinationDirectory.get().asFile)
        }
    }
    tasks.named("test") {
        dependsOn(generateTask)
    }
}
