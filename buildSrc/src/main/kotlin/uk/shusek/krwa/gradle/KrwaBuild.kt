package uk.shusek.krwa.gradle

import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.provider.Provider
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
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

val krwaModuleByArtifact =
    mapOf(
        "annotations" to ":annotations:annotations",
        "annotations-processor" to ":annotations:processor",
        "build-time-compiler" to ":build-time-compiler",
        "build-time-compiler-cli-experimental" to ":build-time-compiler-cli",
        "cli-experimental" to ":cli",
        "codegen" to ":codegen",
        "compiler" to ":compiler",
        "compiler-tests" to ":compiler-tests",
        "component-model-gradle-plugin" to ":component-model-gradle-plugin",
        "component-model" to ":component-model",
        "dircache-experimental" to ":dircache",
        "fuzz" to ":fuzz",
        "jmh" to ":jmh",
        "log" to ":log",
        "machine-tests" to ":machine-tests",
        "nightly-testsuite" to ":nightly-testsuite",
        "runtime" to ":runtime",
        "runtime-tests" to ":runtime-tests",
        "simd" to ":simd",
        "test-gen-lib" to ":test-gen-lib",
        "wabt" to ":wabt",
        "wasi" to ":wasi",
        "wasi-preview3" to ":wasi-preview3",
        "wasi-test-gen" to ":wasi-test-gen",
        "wasi-tests" to ":wasi-tests",
        "wasm" to ":wasm",
        "wasm-corpus" to ":wasm-corpus",
        "wasm-tools" to ":wasm-tools",
    )

val krwaArtifactByProject = krwaModuleByArtifact.entries.associate { (artifact, path) -> path to artifact }

fun Project.krwa(artifactId: String) = project(krwaModuleByArtifact.getValue(artifactId))

fun MavenPublication.configureKrwaPom() {
    pom {
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
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

fun Project.configureKrwaJvmProject() {
    group = rootProject.group

    pluginManager.apply("java-library")
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    pluginManager.apply("maven-publish")

    extensions.configure<BasePluginExtension> {
        archivesName.set(krwaArtifactByProject.getValue(path))
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        modularity.inferModulePath.set(true)
    }

    extensions.configure<KotlinJvmProjectExtension> {
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

@OptIn(ExperimentalWasmDsl::class)
fun KotlinMultiplatformExtension.krwaArmIosAndWebWasmTargets() {
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
    val excludedMalformedWasts: List<String>,
    val excludedInvalidWasts: List<String>,
    val excludedUninstantiableWasts: List<String>,
    val excludedUnlinkableWasts: List<String>,
    val excludedWasts: List<String>,
)

data class WasiSpecTestGenConfig(
    val includes: List<String>,
    val excludes: List<String>,
)

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
        excludedMalformedWasts = resolve("excluded-malformed-wasts.txt").readListFile(),
        excludedInvalidWasts = resolve("excluded-invalid-wasts.txt").readListFile(),
        excludedUninstantiableWasts = resolve("excluded-uninstantiable-wasts.txt").readListFile(),
        excludedUnlinkableWasts = resolve("excluded-unlinkable-wasts.txt").readListFile(),
        excludedWasts = resolve("excluded-wasts.txt").readListFile(),
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
): String =
    """
    {
      "testsuiteFolder": ${jsonString(testsuiteFolder.absolutePath)},
      "sourceDestinationFolder": ${jsonString(sourceDestinationFolder.absolutePath)},
      "compiledWastTargetFolder": ${jsonString(compiledWastTargetFolder.absolutePath)},
      "includedWasts": ${jsonArray(config.includedWasts)},
      "excludedTests": ${jsonArray(config.excludedTests)},
      "excludedMalformedWasts": ${jsonArray(config.excludedMalformedWasts)},
      "excludedInvalidWasts": ${jsonArray(config.excludedInvalidWasts)},
      "excludedUninstantiableWasts": ${jsonArray(config.excludedUninstantiableWasts)},
      "excludedUnlinkableWasts": ${jsonArray(config.excludedUnlinkableWasts)},
      "excludedWasts": ${jsonArray(config.excludedWasts)}
    }
    """.trimIndent()

fun wasiSpecTestGenJson(
    config: WasiSpecTestGenConfig,
    testSuiteFolder: File,
    sourceDestinationFolder: File,
    projectDirectory: File,
): String =
    """
    {
      "testSuiteFolder": ${jsonString(testSuiteFolder.absolutePath)},
      "sourceDestinationFolder": ${jsonString(sourceDestinationFolder.absolutePath)},
      "projectDirectory": ${jsonString(projectDirectory.absolutePath)},
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
                val excludedWasts =
                    (base.excludedWasts + additionalExcludedWasts).distinct().sorted()
                base.copy(
                    includedWasts = base.includedWasts.filterNot(excludedWasts::contains),
                    excludedTests = (base.excludedTests + additionalExcludedTests).distinct(),
                    excludedWasts = excludedWasts,
                )
            }
    val configFile = layout.buildDirectory.file("generated/test-gen/config.json")
    val generatedSourceDir = layout.buildDirectory.dir("generated/test-sources/test-gen")
    val compiledWastDir = layout.buildDirectory.dir("generated/test-resources/compiled-wast")
    val testsuiteFolder =
        rootProject.layout.buildDirectory.dir("external-testsuites/wasm").get().asFile
    val writeConfigTask =
        tasks.register("writeWasmSpecTestGenConfig") {
            inputs.dir(configDir)
            inputs.property("includedWasts", config.includedWasts)
            inputs.property("excludedTests", config.excludedTests)
            inputs.property("excludedMalformedWasts", config.excludedMalformedWasts)
            inputs.property("excludedInvalidWasts", config.excludedInvalidWasts)
            inputs.property("excludedUninstantiableWasts", config.excludedUninstantiableWasts)
            inputs.property("excludedUnlinkableWasts", config.excludedUnlinkableWasts)
            inputs.property("excludedWasts", config.excludedWasts)
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
                    )
                )
            }
        }

    val generateTask =
        tasks.register<JavaExec>("generateWasmSpecTests") {
            dependsOn(writeConfigTask)
            dependsOn(":test-gen-lib:classes")
            dependsOn(":wasm-tools:classes")
            inputs.dir(configDir)
            inputs.dir(testsuiteFolder)
            outputs.dir(generatedSourceDir)
            outputs.dir(compiledWastDir)
            mainClass.set("uk.shusek.krwa.testgen.TestGenCli")
            classpath = rootProject.project(":test-gen-lib").mainSourceSet().runtimeClasspath
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
    val writeConfigTask =
        tasks.register("writeWasiSpecTestGenConfig") {
            inputs.dir(configDir)
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
                    )
                )
            }
        }

    val generateTask =
        tasks.register<JavaExec>("generateWasiSpecTests") {
            dependsOn(writeConfigTask)
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

fun Project.registerKrwaCompile(
    taskName: String,
    generatedType: String,
    wasmFile: Provider<RegularFile>,
    sourceSetName: String = "main",
    moduleInterface: String? = null,
    interpretedFunctions: List<Int> = emptyList(),
    interpreterFallback: String? = null,
    dependsOnTasks: List<Any> = emptyList(),
    skipWhenWasmMissing: Boolean = false,
) {
    val generatedSourceDir = layout.buildDirectory.dir("generated/sources/krwaCompiler/$taskName")
    val generatedClassDir = layout.buildDirectory.dir("generated/classes/krwaCompiler/$taskName")
    val generatedResourceDir = layout.buildDirectory.dir("generated/resources/krwaCompiler/$taskName")
    val compilerRuntimeClasspath =
        rootProject.project(":build-time-compiler-cli").mainSourceSet().runtimeClasspath

    val cleanMissingInputTask =
        if (skipWhenWasmMissing) {
            tasks.register("${taskName}CleanMissingInput") {
                onlyIf {
                    !wasmFile.get().asFile.isFile
                }
                doLast {
                    project.delete(
                        generatedSourceDir.get().asFile,
                        generatedClassDir.get().asFile,
                        generatedResourceDir.get().asFile,
                    )
                }
            }
        } else {
            null
        }

    val generateTask =
        tasks.register<JavaExec>(taskName) {
            dependsOn(":build-time-compiler-cli:classes")
            dependsOn(dependsOnTasks)
            cleanMissingInputTask?.let { dependsOn(it) }
            inputs.file(wasmFile).optional(skipWhenWasmMissing)
            outputs.dir(generatedSourceDir)
            outputs.dir(generatedClassDir)
            outputs.dir(generatedResourceDir)
            onlyIf {
                !skipWhenWasmMissing || wasmFile.get().asFile.isFile
            }
            classpath = compilerRuntimeClasspath
            mainClass.set("uk.shusek.krwa.experimental.compiler.cli.Cli")
            doFirst {
                project.delete(
                    generatedSourceDir.get().asFile,
                    generatedClassDir.get().asFile,
                    generatedResourceDir.get().asFile,
                )
                val cliArgs =
                    mutableListOf(
                        wasmFile.get().asFile.absolutePath,
                        "--prefix",
                        generatedType,
                        "--source-dir",
                        generatedSourceDir.get().asFile.absolutePath,
                        "--class-dir",
                        generatedClassDir.get().asFile.absolutePath,
                        "--wasm-dir",
                        generatedResourceDir.get().asFile.absolutePath,
                    )
                if (interpretedFunctions.isNotEmpty()) {
                    cliArgs.add("--interpreted-functions")
                    cliArgs.add(interpretedFunctions.joinToString(","))
                }
                if (interpreterFallback != null) {
                    cliArgs.add("--interpreter-fallback")
                    cliArgs.add(interpreterFallback)
                }
                if (moduleInterface != null) {
                    cliArgs.add("--module-interface")
                    cliArgs.add(moduleInterface)
                }
                setArgs(cliArgs)
            }
        }

    val sourceSet = extensions.getByType<SourceSetContainer>().named(sourceSetName).get()
    addKotlinSource(sourceSetName, files(generatedSourceDir).builtBy(generateTask))
    sourceSet.output.dir(mapOf("builtBy" to generateTask), generatedClassDir)
    sourceSet.output.dir(mapOf("builtBy" to generateTask), generatedResourceDir)
    if (sourceSetName == "main") {
        tasks.matching { it.name == "sourcesJar" }.configureEach {
            dependsOn(generateTask)
        }
    }

    tasks.named<JavaCompile>(sourceSet.compileJavaTaskName) {
        dependsOn(generateTask)
        classpath += files(generatedClassDir)
        val moduleInfo = layout.projectDirectory.file("src/main/java/module-info.java").asFile
        if (sourceSetName == "main" && moduleInfo.isFile) {
            @Suppress("UNCHECKED_CAST")
            val patchModuleDirs =
                project.extensions.extraProperties["mainPatchModuleDirs"]
                    as ConfigurableFileCollection
            patchModuleDirs.from(generatedClassDir)
        }
    }
    tasks.named<KotlinJvmCompile>(if (sourceSetName == "main") "compileKotlin" else "compileTestKotlin") {
        dependsOn(generateTask)
        libraries.from(files(generatedClassDir))
    }
}

fun Project.registerKrwaCompile(
    taskName: String,
    generatedType: String,
    wasmFile: RegularFile,
    sourceSetName: String = "main",
    moduleInterface: String? = null,
    interpretedFunctions: List<Int> = emptyList(),
    dependsOnTasks: List<Any> = emptyList(),
    skipWhenWasmMissing: Boolean = false,
) =
    registerKrwaCompile(
        taskName = taskName,
        generatedType = generatedType,
        wasmFile = providers.provider { wasmFile },
        sourceSetName = sourceSetName,
        moduleInterface = moduleInterface,
        interpretedFunctions = interpretedFunctions,
        dependsOnTasks = dependsOnTasks,
        skipWhenWasmMissing = skipWhenWasmMissing,
    )
