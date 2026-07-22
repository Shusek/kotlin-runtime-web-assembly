import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import uk.shusek.krwa.gradle.*

val wasmtimeVersion = libs.versions.wasmtime.get()

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
        "CanonicalAsyncLowerTasks.kt",
        "CanonicalFutureIntrinsics.kt",
        "CanonicalStreamIntrinsics.kt",
        "ContextualHostHandler.kt",
        "HostHandler.kt",
        "HostImportIds.kt",
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
        "WasiPreview3ResourceLimits.kt",
        "WasmPlugin.kt",
        "WasmPluginIntrinsics.kt",
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
val wasiPreview1AdapterPackage = "uk/shusek/krwa/component/wasi-preview1"
val wasiPreview1Adapters =
    mapOf(
        "wasi_snapshot_preview1.command.wasm" to
            "9f1b05a33589372b5c2407b02e5646500edabb4c604e589facd3c440dcfeed14",
        "wasi_snapshot_preview1.reactor.wasm" to
            "928546f9b8f704e0e01e656a2c12f08f6e0da6f5b29da0179ee282a4138ef5c4",
    )

val downloadWasiPreview1Adapters =
    tasks.register("downloadWasiPreview1Adapters") {
        inputs.property("wasmtimeVersion", wasmtimeVersion)
        inputs.property("wasiPreview1Adapters", wasiPreview1Adapters)
        doNotTrackState(
            "Verified WASI adapters are durable local state and must survive Gradle version changes.",
        )
        doLast {
            val targetDir =
                wasiPreview1AdapterResources.get().asFile.resolve(wasiPreview1AdapterPackage)
            for ((name, expectedSha256) in wasiPreview1Adapters) {
                val target = targetDir.resolve(name)
                val url =
                    "https://github.com/bytecodealliance/wasmtime/releases/download/" +
                        "v$wasmtimeVersion/$name"
                prepareVerifiedReleaseDownload(
                    description = "WASI Preview 1 adapter $name",
                    url = url,
                    target = target,
                    expectedSha256 = expectedSha256,
                )
            }
        }
    }

extensions.configure<KotlinMultiplatformExtension> {
    krwaArmIosAndWebWasmTargets()

    sourceSets.named("commonMain") {
        kotlin.srcDir("src/main/kotlin")
        kotlin.include(commonComponentModelSources)
        dependencies {
            api(libs.okio)
            api(libs.ktorClientCore)
            implementation(libs.ktorNetwork)
            api(libs.kotlinxDatetime)
            api(libs.kotlinxIoCore)
            implementation(libs.kotlinxIoOkio)
            api(libs.kotlinCryptoRand)
            implementation(libs.kotlinxCoroutinesCore)
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
            implementation(libs.ktorClientCore)
            implementation(libs.ktorClientCio)
            implementation(libs.ktorNetwork)
            implementation(libs.kotlinxCoroutinesCore)
            implementation(krwa("log"))
            implementation(krwa("runtime"))
            implementation(krwa("wasi"))
            implementation(krwa("wasm"))
            implementation(krwa("wasm-tools"))
            compileOnly(libs.jetbrainsAnnotations)
        }
    }
    sourceSets.configureEach {
        if (name == "iosMain") {
            dependencies {
                implementation(libs.ktorClientDarwin)
            }
        }
        if (name == "wasmJsMain") {
            dependencies {
                implementation(libs.ktorClientJs)
                implementation(libs.ktorClientWebsockets)
            }
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
            implementation(libs.kotlinCompilerEmbeddable)
            implementation(libs.junitJupiterApi)
            runtimeOnly(libs.junitJupiterEngine)
            runtimeOnly(libs.junitPlatformLauncher)
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
    maxHeapSize = "2g"
    systemProperty("java.util.logging.config.file", "src/test/resources/logging.properties")
    listOf(
        "krwa.jsonSequenceBenchmark",
        "krwa.jsonSequenceHostStreamBenchmark",
        "krwa.jsonSequenceBytes",
        "krwa.jsonSequenceBenchmarkWarmups",
        "krwa.jsonSequenceBenchmarkRepetitions",
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
    add("jvmTestCompileOnly", libs.jetbrainsAnnotations)
    add("jvmTestImplementation", platform(libs.junitBom))
}

extensions.configure<PublishingExtension> {
    configureKrwaRepositories(project)
    publications.withType<MavenPublication>().configureEach {
        configureKrwaPom()
    }
}
