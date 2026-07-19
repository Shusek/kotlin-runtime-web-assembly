import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.register
import uk.shusek.krwa.gradle.*

val wasmToolsVersion = libs.versions.wasmTools.get()
val wasmToolsArchiveSha256 = "f653d38daf8bef439fd0224a502c75090922948b469d83cd0942374f8bf1718d"

val archive = layout.buildDirectory.file("downloads/wasm-tools-$wasmToolsVersion-wasm32-wasip1.tar.gz")
val downloadWasmTools =
    tasks.register("downloadWasmTools") {
        inputs.property("wasmToolsVersion", wasmToolsVersion)
        inputs.property("wasmToolsArchiveSha256", wasmToolsArchiveSha256)
        doNotTrackState(
            "The verified release archive is durable local state and must survive Gradle version changes.",
        )
        doLast {
            val archiveFile = archive.get().asFile
            val url =
                "https://github.com/bytecodealliance/wasm-tools/releases/download/" +
                    "v$wasmToolsVersion/wasm-tools-$wasmToolsVersion-wasm32-wasip1.tar.gz"
            prepareVerifiedReleaseDownload(
                description = "wasm-tools $wasmToolsVersion wasm32-wasip1 archive",
                url = url,
                target = archiveFile,
                expectedSha256 = wasmToolsArchiveSha256,
            )
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
