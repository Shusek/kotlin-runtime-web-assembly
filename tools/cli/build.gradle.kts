import org.gradle.api.plugins.JavaApplication
import org.gradle.kotlin.dsl.configure
import uk.shusek.krwa.gradle.*

apply(plugin = "application")

dependencies {
    add("implementation", libs.picocli)
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wasi"))
    add("implementation", krwa("wasm"))
}

extensions.configure<JavaApplication> {
    mainClass.set("uk.shusek.krwa.experimental.cli.Cli")
}
