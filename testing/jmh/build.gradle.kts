import java.io.File
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

apply(plugin = "org.jetbrains.kotlin.kapt")

dependencies {
    add("implementation", libs.jmhCore)
    add("implementation", krwa("compiler"))
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wabt"))
    add("implementation", krwa("wasm"))
    add("implementation", krwa("wasm-corpus"))
    add("kapt", libs.jmhGeneratorAnnprocess)
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs JMH benchmarks."
    dependsOn("classes")
    workingDir = rootProject.layout.projectDirectory.asFile
    mainClass.set("org.openjdk.jmh.Main")
    classpath = mainSourceSet().runtimeClasspath
}
