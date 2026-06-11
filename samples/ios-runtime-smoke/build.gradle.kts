import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import uk.shusek.krwa.gradle.*

group = rootProject.group

apply(plugin = "org.jetbrains.kotlin.multiplatform")

extensions.configure<KotlinMultiplatformExtension> {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget("25"))
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets.named("commonMain") {
        dependencies {
            implementation(project(":runtime"))
        }
    }
}
