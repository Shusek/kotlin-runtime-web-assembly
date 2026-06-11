import java.io.File
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

dependencies {
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wasm"))
    add("testImplementation", libs.zerofs)
    add("testImplementation", libs.junitJupiterParams)
    add("testImplementation", krwa("compiler"))
    add("testImplementation", krwa("wasi"))
    add("testImplementation", krwa("wasm-corpus"))
}

registerKrwaCompile(
    taskName = "generateQuickJsModule",
    generatedType = "uk.shusek.krwa.testing.gen.QuickJS",
    wasmFile = layout.projectDirectory.file("../wasm-corpus/src/main/resources/compiled/quickjs-provider.javy-dynamic.wasm"),
)
registerKrwaCompile(
    taskName = "generateDynamicHelloJsModule",
    generatedType = "uk.shusek.krwa.testing.gen.DynamicHelloJS",
    wasmFile = layout.projectDirectory.file("../wasm-corpus/src/main/resources/compiled/hello-world.js.javy-dynamic.wasm"),
)
registerKrwaCompile(
    taskName = "generateWat2WasmModule",
    generatedType = "uk.shusek.krwa.wabt.Wat2Wasm",
    wasmFile = layout.projectDirectory.file("../../tools/wabt/src/main/resources/wat2wasm"),
)
registerKrwaCompile(
    taskName = "generateThreadsExampleModule",
    generatedType = "uk.shusek.krwa.testing.ThreadsExampleModule",
    wasmFile = layout.projectDirectory.file("../wasm-corpus/src/main/resources/compiled/threads-example.wat.wasm"),
)
