@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.plugins.JavaPlatformExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("kapt") version "2.4.0" apply false
    kotlin("multiplatform") version "2.4.0" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
}

group = "uk.shusek.krwa"
version = providers.gradleProperty("version").get()

val approvalTestsVersion = "30.1.1"
val asmVersion = "9.10.1"
val commonsLangVersion = "3.20.0"
val errorProneVersion = "2.49.0"
val jacksonAnnotationsVersion = "2.22"
val jacksonDatabindVersion = "2.21.4"
val jetbrainsAnnotationsVersion = "13.0"
val jmhVersion = "1.37"
val junitVersion = "5.14.4"
val kotlinCompilerVersion = "2.4.0"
val kotlinCryptoRandomVersion = "0.6.0"
val kotlinxCoroutinesVersion = "1.10.2"
val kotlinxDatetimeVersion = "0.8.0"
val kotlinxIoVersion = "0.9.0"
val kspVersion = "2.3.9"
val ktorVersion = "3.5.0"
val okioVersion = "3.17.0"
val picocliVersion = "4.7.7"
val velocityVersion = "1.7"
val wasmToolsVersion = "1.240.0"
val wasmtimeVersion = "45.0.1"
val zerofsVersion = "0.1.0"
val zip4jVersion = "2.11.6"

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

val moduleByArtifact =
    mapOf(
        "annotations" to ":annotations:annotations",
        "annotations-processor" to ":annotations:processor",
        "build-time-compiler" to ":build-time-compiler",
        "build-time-compiler-cli-experimental" to ":build-time-compiler-cli",
        "cli-experimental" to ":cli",
        "codegen" to ":codegen",
        "compiler" to ":compiler",
        "compiler-tests" to ":compiler-tests",
        "component-model" to ":component-model",
        "dircache-experimental" to ":dircache",
        "docs-lib" to ":docs-lib",
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

val artifactByProject = moduleByArtifact.entries.associate { (artifact, path) -> path to artifact }

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

val jvmProjectPaths =
    listOf(
        ":annotations:processor",
        ":build-time-compiler",
        ":build-time-compiler-cli",
        ":cli",
        ":codegen",
        ":compiler",
        ":compiler-tests",
        ":dircache",
        ":docs-lib",
        ":fuzz",
        ":jmh",
        ":log",
        ":machine-tests",
        ":nightly-testsuite",
        ":runtime-tests",
        ":simd",
        ":test-gen-lib",
        ":wabt",
        ":wasi-test-gen",
        ":wasi-tests",
        ":wasm-corpus",
        ":wasm-tools",
    )

allprojects {
    version = rootProject.version
}

configure(jvmProjectPaths.map(::project)) {
    group = rootProject.group

    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "maven-publish")

    extensions.configure<BasePluginExtension> {
        archivesName.set(artifactByProject.getValue(path))
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

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
    }

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) {
            into("META-INF")
        }
        manifest {
            attributes("Implementation-Version" to project.version)
        }
    }

    dependencies {
        add("implementation", platform("org.ow2.asm:asm-bom:$asmVersion"))
        add("compileOnly", "org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
        add("testCompileOnly", "org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
        add("testImplementation", platform("org.junit:junit-bom:$junitVersion"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter-api")
        add("testRuntimeOnly", "org.junit.jupiter:junit-jupiter-engine")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    extensions.configure<PublishingExtension> {
        configureKrwaRepositories(project)
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = artifactByProject.getValue(path)
                configureKrwaPom()
            }
        }
    }
}

project(":bom") {
    group = rootProject.group

    apply(plugin = "java-platform")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPlatformExtension> {
        allowDependencies()
    }

    dependencies {
        constraints {
            moduleByArtifact.forEach { (artifact, projectPath) ->
                add("api", project(projectPath))
            }
        }
    }

    extensions.configure<PublishingExtension> {
        configureKrwaRepositories(project)
        publications {
            create<MavenPublication>("maven") {
                from(components["javaPlatform"])
                artifactId = "bom"
                configureKrwaPom()
            }
        }
    }
}

fun Project.krwa(artifactId: String) = project(moduleByArtifact.getValue(artifactId))

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

fun jsonString(value: String): String =
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

fun jsonArray(values: List<String>): String =
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
                delete(generatedSourceDir.get().asFile, compiledWastDir.get().asFile)
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
            delete(destinationDirectory.get().asFile)
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
                delete(generatedSourceDir.get().asFile)
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
            delete(destinationDirectory.get().asFile)
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
    dependsOnTasks: List<Any> = emptyList(),
) {
    val generatedSourceDir = layout.buildDirectory.dir("generated/sources/krwaCompiler/$taskName")
    val generatedClassDir = layout.buildDirectory.dir("generated/classes/krwaCompiler/$taskName")
    val generatedResourceDir = layout.buildDirectory.dir("generated/resources/krwaCompiler/$taskName")
    val compilerRuntimeClasspath =
        rootProject.project(":build-time-compiler-cli").mainSourceSet().runtimeClasspath

    val generateTask =
        tasks.register<JavaExec>(taskName) {
            dependsOn(":build-time-compiler-cli:classes")
            dependsOn(dependsOnTasks)
            inputs.file(wasmFile)
            outputs.dir(generatedSourceDir)
            outputs.dir(generatedClassDir)
            outputs.dir(generatedResourceDir)
            classpath = compilerRuntimeClasspath
            mainClass.set("uk.shusek.krwa.experimental.compiler.cli.Cli")
            doFirst {
                delete(generatedSourceDir.get().asFile)
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
) =
    registerKrwaCompile(
        taskName = taskName,
        generatedType = generatedType,
        wasmFile = providers.provider { wasmFile },
        sourceSetName = sourceSetName,
        moduleInterface = moduleInterface,
        interpretedFunctions = interpretedFunctions,
        dependsOnTasks = dependsOnTasks,
    )

project(":annotations:annotations") {
    group = rootProject.group

    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "maven-publish")

    extensions.configure<BasePluginExtension> {
        archivesName.set("annotations")
    }

    extensions.configure<KotlinMultiplatformExtension> {
        krwaArmIosAndWebWasmTargets()
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

    extensions.configure<PublishingExtension> {
        configureKrwaRepositories(project)
        publications.withType<MavenPublication>().configureEach {
            configureKrwaPom()
        }
    }
}

project(":annotations:processor") {
    dependencies {
        add("implementation", "com.google.devtools.ksp:symbol-processing-api:$kspVersion")
        add("implementation", krwa("annotations"))
        add("implementation", krwa("codegen"))
    }
}

project(":build-time-compiler") {
    dependencies {
        add("implementation", "org.jetbrains.kotlinx:kotlinx-io-core:$kotlinxIoVersion")
        add("implementation", krwa("codegen"))
        add("implementation", krwa("compiler"))
        add("implementation", krwa("runtime"))
        add("implementation", krwa("wasm"))
    }
}

project(":build-time-compiler-cli") {
    apply(plugin = "application")

    dependencies {
        add("implementation", "info.picocli:picocli:$picocliVersion")
        add("implementation", krwa("build-time-compiler"))
        add("implementation", krwa("compiler"))
        add("implementation", krwa("wasm"))
    }

    extensions.configure<JavaApplication> {
        mainClass.set("uk.shusek.krwa.experimental.compiler.cli.Cli")
    }
}

project(":cli") {
    apply(plugin = "application")

    dependencies {
        add("implementation", "info.picocli:picocli:$picocliVersion")
        add("implementation", krwa("runtime"))
        add("implementation", krwa("wasi"))
        add("implementation", krwa("wasm"))
    }

    extensions.configure<JavaApplication> {
        mainClass.set("uk.shusek.krwa.experimental.cli.Cli")
    }
}

project(":codegen") {
    dependencies {
        add("implementation", krwa("annotations"))
        add("implementation", krwa("wasm"))
    }
}

project(":compiler") {
    dependencies {
        add("api", krwa("runtime"))
        add("api", krwa("wasm"))
        add("implementation", "org.ow2.asm:asm")
        add("implementation", "org.ow2.asm:asm-commons")
        add("implementation", "org.ow2.asm:asm-util")
        add("testImplementation", "com.approvaltests:approvaltests:$approvalTestsVersion")
        add("testImplementation", "org.apache.velocity:velocity:$velocityVersion")
        add("testImplementation", krwa("wasm-corpus"))
    }
}

project(":compiler-tests") {
    dependencies {
        add("testImplementation", "com.approvaltests:approvaltests:$approvalTestsVersion")
        add("testImplementation", "org.ow2.asm:asm")
        add("testImplementation", "org.ow2.asm:asm-util")
        add("testImplementation", krwa("build-time-compiler"))
        add("testImplementation", krwa("compiler"))
        add("testImplementation", krwa("runtime"))
        add("testImplementation", krwa("wabt"))
        add("testImplementation", krwa("wasm"))
        add("testImplementation", krwa("wasm-corpus"))
        add("testImplementation", krwa("wasm-tools"))
    }
    tasks.withType<Test>().configureEach {
        systemProperty("krwa.compiler.printUseOfInterpretedFunctions", "true")
    }
    registerWasmSpecTests()
}

project(":component-model") {
    group = rootProject.group

    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "maven-publish")

    extensions.configure<BasePluginExtension> {
        archivesName.set("component-model")
    }

    val commonComponentModelSources =
        listOf(
            "ComponentModelException.kt",
            "ComponentModelJvmClass.kt",
            "ComponentModelJvmAnnotations.kt",
            "CanonicalAbi.kt",
            "CanonicalAbiReflection.kt",
            "CanonicalFutureIntrinsics.kt",
            "CanonicalStreamIntrinsics.kt",
            "HostHandler.kt",
            "RandomAdapters.kt",
            "WasiComponentInvoker.kt",
            "WasiHostImportBuilder.kt",
            "WasiHttpClient.kt",
            "WasiPreview3CanonicalIntrinsicsAdapter.kt",
            "WasiPreviewPlatform.kt",
            "WasiSocketRuntime.kt",
            "WasiPreview.kt",
            "WasiPreview2.kt",
            "WasiPreview3.kt",
            "WasiPreview3CanonicalIntrinsics.kt",
            "WasmPlugin.kt",
            "WasmPluginPlatform.kt",
            "WitFuture.kt",
            "WitNames.kt",
            "WitPackage.kt",
            "WitParseException.kt",
            "WitResource.kt",
            "WitResourceTable.kt",
            "WitResult.kt",
            "WitStream.kt",
            "WitTuple1.kt",
            "WitTuple4.kt",
            "WitTuple5.kt",
            "WitTuple6.kt",
            "WitTuple7.kt",
            "WitTuple8.kt",
            "WitValue.kt",
        ).map { "uk/shusek/krwa/component/$it" }
    val wasiPreview1AdapterResources =
        layout.buildDirectory.dir("generated/wasi-preview1-adapter-resources")
    val wasiPreview1Adapters =
        mapOf(
            "wasi_snapshot_preview1.command.wasm" to
                "9dcd23d56dc521ac884a9d1c17edb9a003e5edd646fff483e08f8a81ff035543",
            "wasi_snapshot_preview1.reactor.wasm" to
                "16196f7d2b13a362661d89a7c3546ed9e88cd58ca3630c566fd5f84243cc4b93",
        )

    fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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

    val downloadWasiPreview1Adapters =
        tasks.register("downloadWasiPreview1Adapters") {
            outputs.dir(wasiPreview1AdapterResources)
            doLast {
                val targetDir =
                    wasiPreview1AdapterResources.get().asFile.resolve(
                        "uk/shusek/krwa/component/wasi-preview1"
                    )
                targetDir.mkdirs()
                for ((name, expectedSha256) in wasiPreview1Adapters) {
                    val target = targetDir.resolve(name)
                    val url =
                        "https://github.com/bytecodealliance/wasmtime/releases/download/" +
                            "v$wasmtimeVersion/$name"
                    if (!target.isFile || sha256(target) != expectedSha256) {
                        java.net.URI(url).toURL().openStream().use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    val actualSha256 = sha256(target)
                    if (actualSha256 != expectedSha256) {
                        throw GradleException(
                            "Downloaded $name SHA-256 mismatch: expected " +
                                "$expectedSha256, got $actualSha256"
                        )
                    }
                }
            }
        }

    extensions.configure<KotlinMultiplatformExtension> {
        krwaArmIosAndWebWasmTargets()

        sourceSets.named("commonMain") {
            kotlin.srcDir("src/main/kotlin")
            kotlin.include(commonComponentModelSources)
            dependencies {
                api("com.squareup.okio:okio:$okioVersion")
                api("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-network:$ktorVersion")
                api("org.jetbrains.kotlinx:kotlinx-datetime:$kotlinxDatetimeVersion")
                api("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinxIoVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-io-okio:$kotlinxIoVersion")
                api("org.kotlincrypto.random:crypto-rand:$kotlinCryptoRandomVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
                api(krwa("runtime"))
                api(krwa("wasi"))
                api(krwa("wasm"))
            }
        }
        sourceSets.named("jvmMain") {
            kotlin.srcDir("src/main/kotlin")
            kotlin.exclude(commonComponentModelSources)
            resources.srcDir(wasiPreview1AdapterResources)
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation("io.ktor:ktor-network:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
                implementation(krwa("compiler"))
                implementation(krwa("log"))
                implementation(krwa("runtime"))
                implementation(krwa("wasi"))
                implementation(krwa("wasm"))
                implementation(krwa("wasm-tools"))
                compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
            }
        }
        sourceSets.configureEach {
            if (name == "iosMain") {
                dependencies {
                    implementation("io.ktor:ktor-client-darwin:$ktorVersion")
                }
            }
        }
        sourceSets.named("jvmTest") {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")
                implementation("org.junit.jupiter:junit-jupiter-api")
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
        sourceSets.configureEach {
            if (name == "iosTest" || name == "iosArm64Test" || name == "iosSimulatorArm64Test") {
                dependencies {
                    implementation(kotlin("test"))
                }
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
        listOf(
            "krwa.jsonSequenceBenchmark",
            "krwa.jsonSequenceBytes",
            "krwa.jsonSequenceProfileOpcodes",
        ).forEach { property ->
            providers.systemProperty(property).orNull?.let { value ->
                systemProperty(property, value)
            }
        }
        providers.systemProperty("krwa.asyncProfilerAgentPath").orNull?.let { agentPath ->
            val outputFile =
                providers.systemProperty("krwa.asyncProfilerFile").orNull
                    ?: layout.buildDirectory.file("reports/async-profiler/component-model.html")
                        .get()
                        .asFile
                        .absolutePath
            jvmArgs("-agentpath:$agentPath=start,event=cpu,interval=1ms,file=$outputFile")
        }
    }
    tasks.named("jvmProcessResources") {
        dependsOn(downloadWasiPreview1Adapters)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    registerTaskAlias("compileKotlin", "compileKotlinJvm")
    registerTaskAlias("compileJava", "compileJvmMainJava")
    registerTaskAlias("classes", "jvmMainClasses")
    registerTaskAlias("jar", "jvmJar")
    registerTaskAlias("testClasses", "jvmTestClasses")
    registerTaskAlias("test", "jvmTest")

    dependencies {
        add("jvmTestCompileOnly", "org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
        add("jvmTestImplementation", platform("org.junit:junit-bom:$junitVersion"))
    }

    extensions.configure<PublishingExtension> {
        configureKrwaRepositories(project)
        publications.withType<MavenPublication>().configureEach {
            configureKrwaPom()
        }
    }
}

project(":dircache") {
    dependencies {
        add("implementation", krwa("compiler"))
        add("testImplementation", "io.roastedroot:zerofs:$zerofsVersion")
        add("testImplementation", krwa("runtime"))
        add("testImplementation", krwa("wasm"))
        add("testImplementation", krwa("wasm-corpus"))
    }
}

project(":fuzz") {
    dependencies {
        add("implementation", "org.apache.commons:commons-lang3:$commonsLangVersion")
        add("implementation", krwa("log"))
        add("implementation", krwa("runtime"))
        add("implementation", krwa("wasm"))
        add("implementation", krwa("wasm-tools"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter-params")
        add("testImplementation", krwa("compiler"))
    }
}

project(":jmh") {
    apply(plugin = "org.jetbrains.kotlin.kapt")

    dependencies {
        add("implementation", "org.openjdk.jmh:jmh-core:$jmhVersion")
        add("implementation", krwa("compiler"))
        add("implementation", krwa("runtime"))
        add("implementation", krwa("wabt"))
        add("implementation", krwa("wasm"))
        add("implementation", krwa("wasm-corpus"))
        add("kapt", "org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
    }

    tasks.register<JavaExec>("jmh") {
        group = "verification"
        description = "Runs JMH benchmarks."
        dependsOn("classes")
        workingDir = rootProject.layout.projectDirectory.asFile
        mainClass.set("org.openjdk.jmh.Main")
        classpath = mainSourceSet().runtimeClasspath
    }
}

project(":log") {
    dependencies {
        add("compileOnly", "com.google.errorprone:error_prone_annotations:$errorProneVersion")
    }
}

project(":machine-tests") {
    dependencies {
        add("implementation", krwa("runtime"))
        add("implementation", krwa("wasm"))
        add("testImplementation", "io.roastedroot:zerofs:$zerofsVersion")
        add("testImplementation", "org.junit.jupiter:junit-jupiter-params")
        add("testImplementation", krwa("compiler"))
        add("testImplementation", krwa("wasi"))
        add("testImplementation", krwa("wasm-corpus"))
    }

    registerKrwaCompile(
        taskName = "generateQuickJsModule",
        generatedType = "uk.shusek.krwa.testing.gen.QuickJS",
        wasmFile = layout.projectDirectory.file("../wasm-corpus/src/main/resources/compiled/quickjs-provider.javy-dynamic.wasm"),
    )
    registerKrwaCompile(
        taskName = "generateDynamicHelloJsModule",
        generatedType = "uk.shusek.krwa.testing.gen.DynamicHelloJS",
        wasmFile = layout.projectDirectory.file("../wasm-corpus/src/main/resources/compiled/hello-world.js.javy-dynamic.wasm"),
    )
    registerKrwaCompile(
        taskName = "generateWat2WasmModule",
        generatedType = "uk.shusek.krwa.wabt.Wat2Wasm",
        wasmFile = layout.projectDirectory.file("../../tools/wabt/src/main/resources/wat2wasm"),
    )
    registerKrwaCompile(
        taskName = "generateThreadsExampleModule",
        generatedType = "uk.shusek.krwa.testing.ThreadsExampleModule",
        wasmFile = layout.projectDirectory.file("../wasm-corpus/src/main/resources/compiled/threads-example.wat.wasm"),
    )
}

project(":nightly-testsuite") {
    val zigWasm = rootProject.layout.buildDirectory.file("external-testsuites/zig/test-opt.wasm")
    if (zigWasm.get().asFile.exists()) {
        dependencies {
            add("implementation", krwa("runtime"))
            add("implementation", krwa("wasm"))
            add("testImplementation", "io.roastedroot:zerofs:$zerofsVersion")
            add("testImplementation", krwa("log"))
            add("testImplementation", krwa("wasi"))
            add("testImplementation", krwa("wasm-corpus"))
        }
        registerKrwaCompile(
            taskName = "generateZigTestsuiteModule",
            generatedType = "uk.shusek.krwa.testing.ZigModule",
            wasmFile = zigWasm,
            interpretedFunctions =
                listOf(1216, 2185, 5699, 5709, 5716, 5983, 5986, 5989, 8184, 8352, 8400, 10138),
        )
    } else {
        extensions.getByType<SourceSetContainer>().named("test") {
            java.setSrcDirs(emptyList<File>())
            resources.setSrcDirs(emptyList<File>())
        }
        extensions.configure<KotlinJvmProjectExtension> {
            sourceSets.named("test") {
                kotlin.setSrcDirs(emptyList<File>())
            }
        }
        tasks.withType<Test>().configureEach {
            enabled = false
        }
    }
}

project(":runtime") {
    group = rootProject.group

    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "maven-publish")

    extensions.configure<BasePluginExtension> {
        archivesName.set("runtime")
    }

    extensions.configure<KotlinMultiplatformExtension> {
        krwaArmIosAndWebWasmTargets()

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
        sourceSets.named("jvmTest") {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api")
                implementation("org.junit.jupiter:junit-jupiter-params")
                implementation(project(":wasm-corpus"))
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
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
        add("jvmTestImplementation", platform("org.junit:junit-bom:$junitVersion"))
    }

    extensions.configure<PublishingExtension> {
        configureKrwaRepositories(project)
        publications.withType<MavenPublication>().configureEach {
            configureKrwaPom()
        }
    }
}

project(":ios-runtime-smoke") {
    group = rootProject.group

    apply(plugin = "org.jetbrains.kotlin.multiplatform")

    extensions.configure<KotlinMultiplatformExtension> {
        jvm {
            compilerOptions {
                jvmTarget.set(JvmTarget.fromTarget("25"))
            }
        }
        iosArm64()
        iosSimulatorArm64()

        sourceSets.named("commonMain") {
            dependencies {
                implementation(project(":runtime"))
            }
        }
    }
}

project(":runtime-tests") {
    val javaMajor = JavaVersion.current().majorVersion.toInt()
    dependencies {
        add("implementation", krwa("runtime"))
        add("testImplementation", krwa("wasm"))
        add("testImplementation", krwa("wasm-corpus"))
        add("testImplementation", krwa("wasm-tools"))
        if (javaMajor >= 21) {
            add("testImplementation", krwa("simd"))
        }
    }
    filterKotlinTemplates(
        taskName = "filterRuntimeTestTemplates",
        sourceSetName = "test",
        templateDir = if (javaMajor >= 21) "src/test/kotlin-templates-21" else "src/test/kotlin-templates",
    )
    if (javaMajor >= 21) {
        tasks.withType<Test>().configureEach {
            jvmArgs("--add-modules=jdk.incubator.vector")
            maxHeapSize = "2g"
        }
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(25)
            options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
        }
        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions.jvmTarget.set(JvmTarget.fromTarget("25"))
        }
    }
    registerWasmSpecTests(if (javaMajor >= 21) "java21" else null)
}

project(":simd") {
    dependencies {
        add("implementation", krwa("runtime"))
        add("implementation", krwa("wasm"))
        add("testImplementation", krwa("wasm-corpus"))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("25"))
            freeCompilerArgs.add("-Xadd-modules=jdk.incubator.vector")
        }
    }
    tasks.withType<Test>().configureEach {
        jvmArgs("--add-modules=jdk.incubator.vector")
    }
}

project(":test-gen-lib") {
    dependencies {
        add("implementation", "com.fasterxml.jackson.core:jackson-annotations:$jacksonAnnotationsVersion")
        add("implementation", "com.fasterxml.jackson.core:jackson-databind:$jacksonDatabindVersion")
        add("implementation", "net.lingala.zip4j:zip4j:$zip4jVersion")
        add("implementation", krwa("wasm-tools"))
    }
}

project(":wabt") {
    dependencies {
        add("implementation", "io.roastedroot:zerofs:$zerofsVersion")
        add("implementation", krwa("log"))
        add("implementation", krwa("runtime"))
        add("implementation", krwa("wasi"))
        add("implementation", krwa("wasm"))
        add("testImplementation", krwa("wasm-corpus"))
    }
    registerKrwaCompile(
        taskName = "generateWast2JsonModule",
        generatedType = "uk.shusek.krwa.wabt.Wast2JsonModule",
        wasmFile = layout.projectDirectory.file("src/main/resources/wast2json"),
    )
    registerKrwaCompile(
        taskName = "generateWat2WasmModule",
        generatedType = "uk.shusek.krwa.wabt.Wat2WasmModule",
        wasmFile = layout.projectDirectory.file("src/main/resources/wat2wasm"),
    )
}

project(":wasi") {
    group = rootProject.group

    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "com.google.devtools.ksp")
    apply(plugin = "maven-publish")

    extensions.configure<BasePluginExtension> {
        archivesName.set("wasi")
    }

    val commonWasiSources =
        listOf(
            "KotlinRandomCryptoRand.kt",
            "WasiClockId.kt",
            "WasiDirectory.kt",
            "WasiErrno.kt",
            "WasiEventType.kt",
            "WasiExitException.kt",
            "WasiFdFlags.kt",
            "WasiFileType.kt",
            "WasiFstFlags.kt",
            "WasiLookupFlags.kt",
            "WasiOpenFlags.kt",
            "WasiOptions.kt",
            "WasiPlatformFileOps.kt",
            "WasiPlatformFileSystem.kt",
            "WasiPlatformTime.kt",
            "WasiPreview1Common.kt",
            "WasiPreview1Engine.kt",
            "WasiPreview1Host.kt",
            "WasiPreview1HostFunctions.kt",
            "WasiRights.kt",
            "WasiSubClockFlags.kt",
            "WasiWhence.kt",
        ).map { "uk/shusek/krwa/wasi/$it" }

    extensions.configure<KotlinMultiplatformExtension> {
        krwaArmIosAndWebWasmTargets()

        sourceSets.named("commonMain") {
            kotlin.srcDir("src/main/kotlin")
            kotlin.include(commonWasiSources)
            dependencies {
                api(project(":runtime"))
                api("com.squareup.okio:okio:$okioVersion")
                api("org.kotlincrypto.random:crypto-rand:$kotlinCryptoRandomVersion")
                api("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinxIoVersion")
            }
        }
        sourceSets.named("jvmMain") {
            kotlin.srcDir("src/main/kotlin")
            kotlin.exclude(commonWasiSources)
            dependencies {
                implementation(project(":log"))
                implementation(project(":wasm"))
                compileOnly(project(":annotations:annotations"))
                runtimeOnly(project(":annotations:annotations"))
            }
        }
        sourceSets.named("jvmTest") {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(project(":wasm-corpus"))
                implementation("org.junit.jupiter:junit-jupiter-api")
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
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
        add("kspJvm", project(":annotations:processor"))
        add("jvmTestImplementation", platform("org.junit:junit-bom:$junitVersion"))
    }

    extensions.configure<PublishingExtension> {
        configureKrwaRepositories(project)
        publications.withType<MavenPublication>().configureEach {
            configureKrwaPom()
        }
    }
}

project(":wasi-preview3") {
    group = rootProject.group

    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "maven-publish")

    extensions.configure<BasePluginExtension> {
        archivesName.set("wasi-preview3")
    }

    extensions.configure<KotlinMultiplatformExtension> {
        krwaArmIosAndWebWasmTargets()

        sourceSets.named("commonMain") {
            dependencies {
                api("com.squareup.okio:okio:$okioVersion")
                api("org.kotlincrypto.random:crypto-rand:$kotlinCryptoRandomVersion")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
            }
        }
        sourceSets.named("jvmMain") {
            dependencies {
                api(project(":component-model"))
            }
        }
        sourceSets.named("jvmTest") {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api")
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    registerTaskAlias("compileKotlin", "compileKotlinJvm")
    registerTaskAlias("compileJava", "compileJvmMainJava")
    registerTaskAlias("classes", "jvmMainClasses")
    registerTaskAlias("jar", "jvmJar")
    registerTaskAlias("testClasses", "jvmTestClasses")
    registerTaskAlias("test", "jvmTest")

    dependencies {
        add("jvmTestImplementation", platform("org.junit:junit-bom:$junitVersion"))
    }

    extensions.configure<PublishingExtension> {
        configureKrwaRepositories(project)
        publications.withType<MavenPublication>().configureEach {
            configureKrwaPom()
        }
    }
}

project(":wasi-test-gen") {
    dependencies {
        add("implementation", "com.fasterxml.jackson.core:jackson-annotations:$jacksonAnnotationsVersion")
        add("implementation", "com.fasterxml.jackson.core:jackson-databind:$jacksonDatabindVersion")
        add("implementation", "net.lingala.zip4j:zip4j:$zip4jVersion")
    }
}

project(":wasi-tests") {
    dependencies {
        add("testImplementation", "io.roastedroot:zerofs:$zerofsVersion")
        add("testImplementation", krwa("log"))
        add("testImplementation", krwa("runtime"))
        add("testImplementation", krwa("wasi"))
        add("testImplementation", krwa("wasm"))
        add("testImplementation", krwa("wasm-corpus"))
    }
    registerWasiSpecTests()
}

project(":wasm") {
    group = rootProject.group

    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    apply(plugin = "maven-publish")

    extensions.configure<BasePluginExtension> {
        archivesName.set("wasm")
    }

    extensions.configure<KotlinMultiplatformExtension> {
        krwaArmIosAndWebWasmTargets()

        sourceSets.named("commonMain") {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-io-core:$kotlinxIoVersion")
                implementation("com.squareup.okio:okio:$okioVersion")
            }
        }
        sourceSets.named("commonTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        sourceSets.named("jvmMain") {
            kotlin.srcDir("src/main/kotlin")
        }
        sourceSets.named("jvmTest") {
            kotlin.srcDir("src/test/kotlin")
            resources.srcDir("src/test/resources")
            dependencies {
                implementation("org.junit.jupiter:junit-jupiter-api")
                implementation(project(":wasm-corpus"))
                runtimeOnly("org.junit.jupiter:junit-jupiter-engine")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }

    val versionOutputDir = layout.buildDirectory.dir("generated/sources/kotlinTemplates/jvmMain")
    val versionTask =
        tasks.register<Copy>("filterWasmVersionTemplate") {
            from(layout.projectDirectory.dir("src/main/kotlin-templates"))
            into(versionOutputDir)
            filteringCharset = "UTF-8"
            filter { line: String -> line.replace("\${project.version}", project.version.toString()) }
        }
    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.named("jvmMain") {
            kotlin.srcDir(versionOutputDir)
        }
    }
    tasks.named("compileKotlinJvm") {
        dependsOn(versionTask)
    }
    tasks.matching { it.name == "jvmSourcesJar" || it.name == "sourcesJar" }.configureEach {
        dependsOn(versionTask)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
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
        add("jvmTestImplementation", platform("org.junit:junit-bom:$junitVersion"))
    }

    extensions.configure<PublishingExtension> {
        configureKrwaRepositories(project)
        publications.withType<MavenPublication>().configureEach {
            configureKrwaPom()
        }
    }
}

project(":wasm-corpus") {
    dependencies {
        add("implementation", "org.apache.velocity:velocity:$velocityVersion")
    }
    tasks.register<JavaExec>("generateWat") {
        group = "generation"
        description = "Regenerates testing/wasm-corpus/src/main/resources/wat/functions_10.wat."
        val outputFile = layout.projectDirectory.file("src/main/resources/wat/functions_10.wat")
        dependsOn("classes")
        mainClass.set("uk.shusek.krwa.corpus.WatGenerator")
        classpath = mainSourceSet().runtimeClasspath
        args("10", "0")
        outputs.file(outputFile)
        doFirst {
            standardOutput = outputFile.asFile.outputStream()
        }
    }
}

project(":wasm-tools") {
    val archive = layout.buildDirectory.file("downloads/wasm-tools-$wasmToolsVersion-wasm32-wasip1.tar.gz")
    val downloadWasmTools =
        tasks.register("downloadWasmTools") {
            outputs.file(archive)
            doLast {
                val archiveFile = archive.get().asFile
                if (!archiveFile.isFile) {
                    archiveFile.parentFile.mkdirs()
                    val url =
                        "https://github.com/bytecodealliance/wasm-tools/releases/download/v$wasmToolsVersion/wasm-tools-$wasmToolsVersion-wasm32-wasip1.tar.gz"
                    java.net.URI(url).toURL().openStream().use { input ->
                        archiveFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    val extractedWasmTools =
        tasks.register<Copy>("extractWasmTools") {
            dependsOn(downloadWasmTools)
            from({ tarTree(resources.gzip(archive.get().asFile)) })
            include("**/*.wasm")
            eachFile {
                path = name
            }
            includeEmptyDirs = false
            into(layout.buildDirectory.dir("wasm-tools"))
        }

    dependencies {
        add("implementation", "io.roastedroot:zerofs:$zerofsVersion")
        add("implementation", krwa("log"))
        add("implementation", krwa("runtime"))
        add("implementation", krwa("wasi"))
        add("implementation", krwa("wasm"))
    }

    registerKrwaCompile(
        taskName = "generateWasmToolsModule",
        generatedType = "uk.shusek.krwa.tools.wasm.WasmToolsModule",
        wasmFile = layout.buildDirectory.file("wasm-tools/wasm-tools.wasm"),
        interpretedFunctions = listOf(4725, 5095, 5422, 7668, 10543),
        dependsOnTasks = listOf(extractedWasmTools),
    )
}
