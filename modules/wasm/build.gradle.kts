import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
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
apply(plugin = "maven-publish")

extensions.configure<BasePluginExtension> {
    archivesName.set("wasm")
}

extensions.configure<KotlinMultiplatformExtension> {
    krwaArmIosAndWebWasmTargets()

    sourceSets.named("commonMain") {
        dependencies {
            api(libs.kotlinxIoCore)
            implementation(libs.okio)
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
            implementation(libs.junitJupiterApi)
            implementation(project(":wasm-corpus"))
            runtimeOnly(libs.junitJupiterEngine)
            runtimeOnly(libs.junitPlatformLauncher)
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
    add("jvmTestImplementation", platform(libs.junitBom))
}

extensions.configure<PublishingExtension> {
    configureKrwaRepositories(project)
    publications.withType<MavenPublication>().configureEach {
        configureKrwaPom()
    }
}
