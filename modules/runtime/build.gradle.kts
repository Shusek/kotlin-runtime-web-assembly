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
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import uk.shusek.krwa.gradle.*

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
        "krwa.runtimeBenchmarkWarmupRepetitions",
    ).forEach { property ->
        providers.systemProperty(property).orNull?.let { value ->
            systemProperty(property, value)
        }
    }
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
