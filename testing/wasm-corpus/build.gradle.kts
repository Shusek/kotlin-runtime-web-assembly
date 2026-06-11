import java.io.File
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

dependencies {
    add("implementation", libs.velocity)
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
