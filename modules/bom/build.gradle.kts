import org.gradle.api.plugins.JavaPlatformExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import uk.shusek.krwa.gradle.*

group = rootProject.group

apply(plugin = "java-platform")
apply(plugin = "maven-publish")

extensions.configure<JavaPlatformExtension> {
    allowDependencies()
}

dependencies {
    constraints {
        krwaModuleByArtifact.forEach { (artifact, projectPath) ->
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
