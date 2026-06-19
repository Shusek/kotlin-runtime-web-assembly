package uk.shusek.krwa.wasi

import java.io.File
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.component.WasiPreview2
import uk.shusek.krwa.component.WasiPreview3
import uk.shusek.krwa.component.WasmPlugin
import uk.shusek.krwa.component.WitResult
import uk.shusek.krwa.component.WitValue

class WasiPreview3TestsuiteTest {
    @TempDir lateinit var tempDir: java.nio.file.Path

    @Test
    fun officialPreview3RandomComponentRuns() {
        runCommandComponent("random.wasm")
    }

    @Test
    fun officialPreview3CliEnvironmentComponentRunsWithSpecifiedArgsAndEnv() {
        runCommandComponent(
            "cli-env.wasm",
            arguments = listOf("cli-env.wasm", "a", "b", "42"),
            environment = mapOf("foo" to "bar", "baz" to "42"),
        )
    }

    @Test
    fun officialPreview3CliExitComponentReturnsFailureCode() {
        runCommandComponent("cli-exit.wasm", expectedExitCode = 1)
    }

    @Test
    fun officialPreview3RunWithErrComponentReturnsFailureCode() {
        runCommandComponent("run-with-err.wasm", expectedExitCode = 1)
    }

    @Test
    fun officialPreview3CliStdioRoundtripComponentEchoesStreams() {
        runCommandComponent(
            "cli-stdio-roundtrip.wasm",
            stdin = "Hello, world!",
            expectedStdout = "Hello, world!",
            expectedStderr = "Hello, world!",
        )
    }

    @Test
    fun officialPreview3CliStdioComponentRuns() {
        runCommandComponent(
            "cli-stdio.wasm",
            expectedStdout = "\u0000",
            expectedStderr = "\u0000",
        )
    }

    @TestFactory
    fun officialPreview3CliTerminalAndClockComponentsRun(): List<DynamicTest> =
        listOf(
            "cli-terminal.wasm",
            "monotonic-clock.wasm",
            "multi-clock-wait.wasm",
            "wall-clock.wasm",
        )
            .map { wasmName -> dynamicTest(wasmName) { runCommandComponent(wasmName) } }

    @Test
    fun officialPreview3HttpFieldsComponentRuns() {
        runCommandComponent("http-fields.wasm")
    }

    @TestFactory
    fun officialPreview3HttpMessageTypeComponentsRun(): List<DynamicTest> =
        listOf("http-request.wasm", "http-response.wasm").map { wasmName ->
            dynamicTest(wasmName) { runCommandComponent(wasmName) }
        }

    @TestFactory
    fun officialPreview3SocketPropertyComponentsRun(): List<DynamicTest> =
        listOf("sockets-tcp-properties.wasm", "sockets-udp-properties.wasm").map { wasmName ->
            dynamicTest(wasmName) { runCommandComponent(wasmName, networking = true) }
        }

    @TestFactory
    fun officialPreview3SocketBindComponentsRun(): List<DynamicTest> =
        listOf("sockets-udp-bind.wasm").map { wasmName ->
            dynamicTest(wasmName) { runCommandComponent(wasmName, networking = true) }
        }

    @TestFactory
    fun officialPreview3UdpSocketComponentsRun(): List<DynamicTest> =
        listOf(
            "sockets-udp-connect.wasm",
            "sockets-udp-receive.wasm",
            "sockets-udp-send.wasm",
        ).map { wasmName ->
            dynamicTest(wasmName) { runCommandComponent(wasmName, networking = true) }
        }

    @Test
    fun officialPreview3HttpServiceComponentHandlesRequests() {
        val component = preview3Component("http-service.wasm")
        assertTrue(component.isFile, "missing WASI Preview 3 testsuite component: $component")
        val wasi = WasiPreview3.builder().build()
        val wasiPreview2 = WasiPreview2.builder().build()
        try {
            val plugin =
                WasmPlugin.builderFromComponent(component.toOkioPath())
                    .withComponentModule(component.toOkioPath(), "unbundled-module0.wasm")
                    .withWasiPreview2(wasiPreview2)
                    .withWasiPreview3(wasi)
                    .build()
            assertTrue(
                "handler.handle" in plugin.exports().keys,
                "expected handler.handle export, got ${plugin.exports().keys}",
            )

            val root = handleHttp(wasi, plugin, "GET", "/")
            assertEquals(200, root.statusCode(), "GET / status")
            assertEquals("hey\n", root.body().decodeToString(), "GET / body")
            assertHeader(root, "content-type", "text/plain")
            assertHeader(root, "content-length", "4")

            val missing = handleHttp(wasi, plugin, "GET", "/missing")
            assertEquals(404, missing.statusCode(), "GET /missing status")
            assertEquals("", missing.body().decodeToString(), "GET /missing body")

            val post = handleHttp(wasi, plugin, "POST", "/")
            assertEquals(405, post.statusCode(), "POST / status")
            assertEquals("", post.body().decodeToString(), "POST / body")
        } finally {
            wasi.close()
        }
    }

    @TestFactory
    fun officialPreview3FilesystemComponentsRunAgainstPreopen(): List<DynamicTest> =
        listOf(
            "filesystem-advise.wasm",
            "filesystem-dotdot.wasm",
            "filesystem-flags-and-type.wasm",
            "filesystem-hard-links.wasm",
            "filesystem-io.wasm",
            "filesystem-is-same-object.wasm",
            "filesystem-metadata-hash.wasm",
            "filesystem-mkdir-rmdir.wasm",
            "filesystem-open-errors.wasm",
            "filesystem-read-directory.wasm",
            "filesystem-rename.wasm",
            "filesystem-set-size.wasm",
            "filesystem-stat.wasm",
            "filesystem-unlink-errors.wasm",
        )
            .map { wasmName ->
                dynamicTest(wasmName) {
                    runCommandComponent(wasmName, preopenedRoot = "fs-tests.dir")
                }
            }

    private fun runCommandComponent(
        wasmName: String,
        arguments: List<String> = listOf(wasmName),
        environment: Map<String, String> = emptyMap(),
        stdin: String = "",
        expectedStdout: String = "",
        expectedStderr: String = "",
        expectedExitCode: Int = 0,
        preopenedRoot: String? = null,
        preopenedDirectories: List<String> = emptyList(),
        networking: Boolean = false,
    ) {
        val component = preview3Component(wasmName)
        assertTrue(component.isFile, "missing WASI Preview 3 testsuite component: $component")
        val preview3Stdin = Buffer().also { it.writeString(stdin) }
        val preview2Stdin = Buffer().also { it.writeString(stdin) }
        val stdout = Buffer()
        val stderr = Buffer()
        val wasiBuilder =
            WasiPreview3.builder()
                .withArguments(arguments)
                .withEnvironment(environment)
                .withStdin(preview3Stdin)
                .withStdout(stdout)
                .withStderr(stderr)
        val wasiPreview2Builder =
            WasiPreview2.builder()
                .withArguments(arguments)
                .withEnvironment(environment)
                .withStdin(preview2Stdin)
                .withStdout(stdout)
                .withStderr(stderr)

        if (networking) {
            wasiBuilder.withNetworking()
        }

        if (preopenedRoot != null) {
            val source = component.parentFile.toPath().resolve(preopenedRoot)
            val target = tempDir.resolve("${wasmName.removeSuffix(".wasm")}-$preopenedRoot")
            Files.copyDirectory(source, target)
            wasiBuilder.withPreopenedDirectory("/", target.toString())
            wasiPreview2Builder.withPreopenedDirectory("/", target.toString())
        }
        for (directory in preopenedDirectories) {
            val source = component.parentFile.toPath().resolve(directory)
            val target = tempDir.resolve("${wasmName.removeSuffix(".wasm")}-$directory")
            Files.copyDirectory(source, target)
            wasiBuilder.withPreopenedDirectory(directory, target.toString())
            wasiPreview2Builder.withPreopenedDirectory(directory, target.toString())
        }

        val wasi = wasiBuilder.build()
        val wasiPreview2 = wasiPreview2Builder.build()
        try {
            val plugin =
                WasmPlugin.builderFromComponent(component.toOkioPath())
                    .withComponentModule(component.toOkioPath(), "unbundled-module0.wasm")
                    .withWasiPreview2(wasiPreview2)
                    .withWasiPreview3(wasi)
                    .build()
            assertTrue(
                "run" in plugin.exports().keys,
                "expected run export, got ${plugin.exports().keys}",
            )
            val actualExitCode =
                try {
                    commandExitCode(plugin.call("run"))
                } catch (exit: WasiPreview3.ExitException) {
                    exit.statusCode()
                } catch (exit: WasiPreview2.ExitException) {
                    exit.statusCode()
                } catch (failure: Throwable) {
                    val capturedStdout = stdout.readByteArray().decodeToString()
                    val capturedStderr = stderr.readByteArray().decodeToString()
                    throw AssertionError(
                        "$wasmName failed; stdout=$capturedStdout stderr=$capturedStderr",
                        failure,
                    )
                }
            assertEquals(expectedExitCode, actualExitCode, "exit code")
            assertEquals(expectedStdout, stdout.readByteArray().decodeToString(), "stdout")
            assertEquals(expectedStderr, stderr.readByteArray().decodeToString(), "stderr")
        } finally {
            wasi.close()
        }
    }

    private fun preview3Component(name: String): File =
        File("../../build/external-testsuites/wasi/tests/rust/testsuite/wasm32-wasip3/$name")

    private fun File.toOkioPath(): okio.Path = absolutePath.toPath(normalize = true)

    private fun handleHttp(
        wasi: WasiPreview3,
        plugin: WasmPlugin,
        method: String,
        pathWithQuery: String,
    ): WasiPreview2.HttpResponseSnapshot =
        wasi.handleHttpRequest(
            plugin,
            method,
            pathWithQuery,
            "http",
            "localhost",
            emptyMap(),
            ByteArray(0),
        )

    private fun assertHeader(
        response: WasiPreview2.HttpResponseSnapshot,
        name: String,
        expected: String,
    ) {
        val actual =
            response.headers()[name.lowercase()]
                ?: throw AssertionError("missing response header $name")
        assertEquals(expected, actual.single().decodeToString(), "header $name")
    }

    private fun commandExitCode(result: Any?): Int {
        return when (result) {
            null -> 0
            is WitResult.Ok<*, *> -> 0
            is WitResult.Err<*, *> -> 1
            is WitValue.Variant ->
                when (result.label()) {
                    "ok" -> 0
                    "err" -> 1
                    else -> throw AssertionError("unexpected command status: $result")
                }
            else -> throw AssertionError("expected command status result, got $result")
        }
    }
}
