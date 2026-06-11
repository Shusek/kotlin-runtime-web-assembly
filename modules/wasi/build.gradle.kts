import java.io.File
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
            api(libs.okio)
            api(libs.kotlinCryptoRand)
            api(libs.kotlinxIoCore)
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
            implementation(libs.junitJupiterApi)
            runtimeOnly(libs.junitJupiterEngine)
            runtimeOnly(libs.junitPlatformLauncher)
        }
    }
    sourceSets.named("wasmJsTest") {
        dependencies {
            implementation(kotlin("test"))
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
    add("jvmTestImplementation", platform(libs.junitBom))
}

extensions.configure<PublishingExtension> {
    configureKrwaRepositories(project)
    publications.withType<MavenPublication>().configureEach {
        configureKrwaPom()
    }
}
