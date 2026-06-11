import org.gradle.api.JavaVersion
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import uk.shusek.krwa.gradle.*

val javaMajor = JavaVersion.current().majorVersion.toInt()
dependencies {
    add("implementation", krwa("runtime"))
    add("testImplementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-corpus"))
    add("testImplementation", krwa("wasm-tools"))
    if (javaMajor >= 21) {
        add("testImplementation", krwa("simd"))
    }
}
filterKotlinTemplates(
    taskName = "filterRuntimeTestTemplates",
    sourceSetName = "test",
    templateDir = if (javaMajor >= 21) "src/test/kotlin-templates-21" else "src/test/kotlin-templates",
)
if (javaMajor >= 21) {
    tasks.withType<Test>().configureEach {
        jvmArgs("--add-modules=jdk.incubator.vector")
        maxHeapSize = "2g"
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.compilerArgs.addAll(listOf("--add-modules", "jdk.incubator.vector"))
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget("25"))
    }
}
registerWasmSpecTests(if (javaMajor >= 21) "java21" else null)
