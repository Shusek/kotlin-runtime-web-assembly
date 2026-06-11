import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import uk.shusek.krwa.gradle.*

dependencies {
    add("testImplementation", libs.approvalTests)
    add("testImplementation", libs.asm)
    add("testImplementation", libs.asmUtil)
    add("testImplementation", krwa("build-time-compiler"))
    add("testImplementation", krwa("compiler"))
    add("testImplementation", krwa("runtime"))
    add("testImplementation", krwa("wabt"))
    add("testImplementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-corpus"))
    add("testImplementation", krwa("wasm-tools"))
}
tasks.withType<Test>().configureEach {
    systemProperty("krwa.compiler.printUseOfInterpretedFunctions", "true")
}
registerWasmSpecTests()
