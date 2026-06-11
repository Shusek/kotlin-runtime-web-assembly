import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import uk.shusek.krwa.gradle.*

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
