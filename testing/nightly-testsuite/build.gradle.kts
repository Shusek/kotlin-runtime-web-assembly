import java.io.File
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

val zigWasm = rootProject.layout.buildDirectory.file("external-testsuites/zig/test-opt.wasm")

dependencies {
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wasm"))
    add("testImplementation", libs.zerofs)
    add("testImplementation", krwa("log"))
    add("testImplementation", krwa("wasi"))
    add("testImplementation", krwa("wasm-corpus"))
}

registerKrwaCompile(
    taskName = "generateZigTestsuiteModule",
    generatedType = "uk.shusek.krwa.testing.ZigModule",
    wasmFile = zigWasm,
    interpreterFallback = "WARN",
    skipWhenWasmMissing = true,
)

tasks.matching {
    it.name in setOf("compileTestKotlin", "compileTestJava", "testClasses", "test")
}.configureEach {
    onlyIf("Zig testsuite wasm exists") {
        zigWasm.get().asFile.isFile
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = TestExceptionFormat.FULL
    }
}
