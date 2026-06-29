import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import uk.shusek.krwa.gradle.configureKrwaPom
import uk.shusek.krwa.gradle.configureKrwaRepositories
import uk.shusek.krwa.gradle.krwa

plugins {
    `java-gradle-plugin`
    `maven-publish`
}

apply(plugin = "org.jetbrains.kotlin.jvm")

group = rootProject.group

extensions.configure<BasePluginExtension> {
    archivesName.set("component-model-gradle-plugin")
}

java {
    withSourcesJar()
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

gradlePlugin {
    plugins {
        create("componentModel") {
            id = "uk.shusek.krwa.component-model"
            implementationClass = "uk.shusek.krwa.gradle.component.KrwaComponentModelPlugin"
            displayName = "KRWA Component Model Gradle plugin"
            description = "Generates Kotlin WIT bindings and packages WebAssembly components with KRWA."
        }
    }
}

dependencies {
    implementation(project(":component-model"))

    testImplementation(gradleTestKit())
    testImplementation(krwa("wasm-tools"))
    testImplementation(platform(libs.junitBom))
    testImplementation(libs.junitJupiterApi)
    testRuntimeOnly(libs.junitJupiterEngine)
    testRuntimeOnly(libs.junitPlatformLauncher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
    manifest {
        attributes(mapOf("Implementation-Version" to project.version))
    }
}

extensions.configure<PublishingExtension> {
    configureKrwaRepositories(project)
    publications.withType<MavenPublication>().configureEach {
        configureKrwaPom()
    }
}
