import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

dependencies {
    add("testImplementation", libs.zerofs)
    add("testImplementation", krwa("component-model"))
    add("testImplementation", krwa("log"))
    add("testImplementation", krwa("runtime"))
    add("testImplementation", krwa("wasi"))
    add("testImplementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-corpus"))
}
registerWasiSpecTests()
