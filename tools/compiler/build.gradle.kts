import org.gradle.api.tasks.testing.Test
import uk.shusek.krwa.gradle.*

dependencies {
    add("api", krwa("runtime"))
    add("api", krwa("wasm"))
    add("implementation", libs.asm)
    add("implementation", libs.asmCommons)
    add("implementation", libs.asmUtil)
    add("testImplementation", libs.approvalTests)
    add("testImplementation", libs.velocity)
    add("testImplementation", krwa("wasm-corpus"))
}
