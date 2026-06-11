@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.sample

import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.time.Instant as KotlinInstant
import kotlinx.io.Buffer
import okio.Path.Companion.toPath
import uk.shusek.krwa.component.WasiPreview2
import uk.shusek.krwa.component.WasmComponentTools
import uk.shusek.krwa.component.WasmPlugin
import uk.shusek.krwa.component.withInsecureRandom
import uk.shusek.krwa.component.withSecureRandom
import uk.shusek.krwa.tools.wasm.Wat2Wasm

internal fun runComponentModelPluginShowcase(capabilities: ShowcaseCapabilities) {
    val tempDir = Files.createTempDirectory("krwa-sample")
    val witPath = tempDir.resolve("plugin.wit")
    val corePath = tempDir.resolve("plugin.core.wasm")
    val embeddedPath = tempDir.resolve("plugin.embedded.wasm")
    val componentPath = tempDir.resolve("plugin.component.wasm")
    Files.writeString(witPath, pluginWit())
    Files.write(corePath, componentCoreModule())
    Files.write(
        embeddedPath,
        WasmComponentTools.embedWit(witPath.toOkioPath(), "plugin", corePath.toOkioPath()),
    )
    Files.write(componentPath, WasmComponentTools.componentNew(embeddedPath.toOkioPath()))

    val stdout = Buffer()
    val wasi2 =
        WasiPreview2.builder()
            .withStdout(stdout)
            .withStderr(Buffer())
            .withStdin(Buffer())
            .withArguments("plugin.component.wasm", "--sample")
            .withEnvironment("KRWA_SAMPLE", "wasip2")
            .withInitialCwd("/")
            .withPreopenedDirectory("/", tempDir.toString())
            .withTerminalStdout(true)
            .withNetworking(false)
            .withFixedWallClock(KotlinInstant.parse("2026-06-08T00:00:00Z"))
            .withSecureRandom(Random(7))
            .withInsecureRandom(Random(8))
            .withInsecureSeed(11L, 12L)
            .build()

    val plugin =
        WasmPlugin.builderFromComponent(componentPath.toOkioPath())
            .withWasiPreview2(wasi2)
            .build()

    requireShowcaseValue(6L, plugin.call("api.len", "Kotlin"), "component plugin API")
    require(plugin.exports().containsKey("api.len")) { "Expected api.len export" }
    capabilities.demonstrate(
        "Component Model",
        "Package and host a component",
        "The JVM host embeds WIT, builds a component from a core module, wires WASIp2, and calls the exported plugin API through WasmPlugin.",
    )
}

internal fun compileWat(source: String): ByteArray =
    Wat2Wasm.parse(source.trimIndent().replace("_D_", "$"))

private fun componentCoreModule(): ByteArray =
    compileWat(
        """
        (module
          (memory (export "memory") 1)
          (global (mut i32) (i32.const 1024))
          (func (export "canonical_abi_realloc")
            (param i32) (param i32) (param i32) (param i32)
            (result i32)
            global.get 0
            global.get 0
            local.get 3
            i32.add
            global.set 0)
          (func (export "len") (param i32) (param i32) (result i32)
            local.get 1)
          (export "api.len" (func 1))
          (export "api#len" (func 1))
          (export "api/len" (func 1))
          (export "sample:runtime/api#len" (func 1)))
        """
    )

private fun pluginWit(): String =
    """
    package sample:runtime;

    interface api {
      len: func(input: string) -> u32;
    }

    world plugin {
      export api;
    }
    """
        .trimIndent()

private fun Path.toOkioPath(): okio.Path = toString().toPath(normalize = true)
