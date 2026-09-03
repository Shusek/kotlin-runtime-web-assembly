import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import uk.shusek.krwa.gradle.*

dependencies {
    add("testImplementation", libs.zerofs)
    add("testImplementation", krwa("component-model"))
    add("testRuntimeOnly", krwa("component-model-tooling"))
    add("testImplementation", krwa("log"))
    add("testImplementation", krwa("runtime"))
    add("testImplementation", krwa("wasi"))
    add("testImplementation", krwa("wasm"))
    add("testImplementation", krwa("wasm-corpus"))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    jvmArgs("--enable-native-access=ALL-UNNAMED")

    // The official Preview 3 TCP bind test immediately reuses an ephemeral port to verify
    // SO_REUSEADDR. Component-model JVM tests open real loopback listeners and can claim that
    // port between bind and listen when Gradle runs both tasks in parallel.
    mustRunAfter(":component-model:jvmTest")
}

registerWasiSpecTests()
