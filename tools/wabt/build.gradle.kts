import java.io.File
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

dependencies {
    add("implementation", libs.zerofs)
    add("implementation", krwa("log"))
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wasi"))
    add("implementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-corpus"))
}
registerKrwaCompile(
    taskName = "generateWast2JsonModule",
    generatedType = "uk.shusek.krwa.wabt.Wast2JsonModule",
    wasmFile = layout.projectDirectory.file("src/main/resources/wast2json"),
)
registerKrwaCompile(
    taskName = "generateWat2WasmModule",
    generatedType = "uk.shusek.krwa.wabt.Wat2WasmModule",
    wasmFile = layout.projectDirectory.file("src/main/resources/wat2wasm"),
)
