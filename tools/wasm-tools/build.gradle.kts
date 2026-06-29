import java.io.File
import java.net.URI
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

val wasmToolsVersion = libs.versions.wasmTools.get()

val archive = layout.buildDirectory.file("downloads/wasm-tools-$wasmToolsVersion-wasm32-wasip1.tar.gz")
val downloadWasmTools =
    tasks.register("downloadWasmTools") {
        outputs.file(archive)
        doLast {
            val archiveFile = archive.get().asFile
            if (!archiveFile.isFile) {
                archiveFile.parentFile.mkdirs()
                val url =
                    "https://github.com/bytecodealliance/wasm-tools/releases/download/v$wasmToolsVersion/wasm-tools-$wasmToolsVersion-wasm32-wasip1.tar.gz"
                URI(url).toURL().openStream().use { input ->
                    archiveFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
val extractedWasmTools =
    tasks.register<Copy>("extractWasmTools") {
        dependsOn(downloadWasmTools)
        from({ tarTree(resources.gzip(archive.get().asFile)) })
        include("**/*.wasm")
        eachFile {
            path = name
        }
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("wasm-tools"))
    }
val wasmToolsResources = files(layout.buildDirectory.dir("wasm-tools")).builtBy(extractedWasmTools)

kotlin {
    sourceSets {
        named("main") {
            resources.srcDir(wasmToolsResources)
        }
    }
}

tasks.named("processResources") {
    dependsOn(extractedWasmTools)
}

dependencies {
    add("implementation", libs.zerofs)
    add("implementation", krwa("log"))
    add("implementation", krwa("runtime"))
    add("implementation", krwa("wasi"))
    add("implementation", krwa("wasm"))
}
