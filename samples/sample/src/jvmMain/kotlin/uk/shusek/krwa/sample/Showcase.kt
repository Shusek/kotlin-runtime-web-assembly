@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.sample

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.atomic.AtomicReference
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.Path.Companion.toPath
import uk.shusek.krwa.component.WasiPreview3
import uk.shusek.krwa.component.WasmComponentTools
import uk.shusek.krwa.component.WasmPlugin
import uk.shusek.krwa.component.withSecureRandom
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

fun main() {
    val guestWasm =
        Path.of(
            requireNotNull(System.getProperty("krwa.sample.kotlinWasiWasm")) {
                "Missing -Dkrwa.sample.kotlinWasiWasm"
            }
        )

    Showcase(guestWasm).run()
}

private class Showcase(private val kotlinWasiGuest: Path) {
    private data class KotlinWasiSandboxSentinel(val escapePath: String, val hostPath: Path)

    private val capabilities = ArrayList<ShowcaseCapability>()

    fun run() {
        capabilities.addAll(runKmpShowcase())
        kotlinWasiPreview1()
        kotlinWasiPreview1Component()
        kotlinWasiPreview3Bridge()
        kotlinWasiMalformedStdin()
        runWasiPreview3RuntimeShowcase(capabilities)
        runComponentModelPluginShowcase(capabilities)
        printShowcaseReport("Kotlin Runtime Web Assembly showcase", capabilities)
    }

    private fun kotlinWasiPreview1() {
        require(Files.isRegularFile(kotlinWasiGuest)) {
            "Kotlin/WASI guest was not built: $kotlinWasiGuest"
        }

        val tempDir = Files.createTempDirectory("krwa-sample-wasi1")
        val sandboxSentinel = createKotlinWasiSandboxSentinel(tempDir)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        try {
            withKotlinWasiHttpProbe { _, httpFetch, _ ->
                val wasi =
                    WasiPreview1.builder()
                        .withOptions(
                            WasiOptions.builder()
                                .withRandom(Random(7))
                                .withStdout(stdout)
                                .withStderr(stderr)
                                .withStdin(ByteArrayInputStream(kotlinWasiProductsJson.toByteArray(UTF_8)))
                                .withArguments(listOf("kotlin-guest.wasm", "alpha", "beta"))
                                .withEnvironment("KRWA_SAMPLE", "preview1")
                                .withEnvironment("KRWA_PRODUCT_JSON", kotlinWasiProductJson)
                                .withEnvironment("KRWA_SANDBOX_ESCAPE_PATH", sandboxSentinel.escapePath)
                                .withDirectory("/", tempDir.toOkioPath())
                                .build()
                        )
                        .build()

                try {
                    Instance.builder(Parser.parse(kotlinWasiGuest))
                        .withImportValues(
                            ImportValues.builder()
                                .addFunction(*wasi.toHostFunctions())
                                .addFunction(httpFetch)
                                .build()
                        )
                        .build()
                } catch (exit: WasiExitException) {
                    requireValue(0, exit.exitCode(), "Kotlin/WASI guest exit code")
                }
            }

            assertKotlinWasiPreview1Output(stdout.toString(UTF_8), stderr.toString(UTF_8))
            assertKotlinWasiGuestFiles(
                kotlinWasiFileSnapshot(tempDir, sandboxSentinel),
                "Kotlin/WASI guest",
            )
        } finally {
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-products-report.json"))
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-products-report.txt"))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiFdFlagsFile))
            cleanupKotlinWasiPathLinkProbe(tempDir)
            cleanupKotlinWasiPathSymlinkProbe(tempDir)
            Files.deleteIfExists(tempDir.resolve(kotlinWasiCapabilityFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiMutationFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiMetadataSyncFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiRandomAccessFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiSeekAppendFile))
            cleanupKotlinWasiDirectoryProbe(tempDir)
            cleanupKotlinWasiReaddirProbe(tempDir)
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-probe.txt"))
            Files.deleteIfExists(sandboxSentinel.hostPath)
            Files.deleteIfExists(tempDir)
        }
        capabilities.demonstrate(
            "Kotlin/WASI Guest",
            "Host a real Kotlin 2.4 wasmWasi application",
            "The JVM host runs the guest on WASI Preview 1 with HTTP import, kotlinx.serialization JSON, chunked stdin, stdio, clocks, random, environment, sandboxed filesystem reports, and capability-safe file semantics.",
        )
    }

    private fun kotlinWasiPreview1Component() {
        require(Files.isRegularFile(kotlinWasiGuest)) {
            "Kotlin/WASI guest was not built: $kotlinWasiGuest"
        }

        val tempDir = Files.createTempDirectory("krwa-sample-wasi1-component")
        val sandboxSentinel = createKotlinWasiSandboxSentinel(tempDir)
        val witPath = tempDir.resolve("plugin.wit")
        val componentPath = tempDir.resolve("kotlin-wasi.component.wasm")
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        Files.writeString(witPath, kotlinWasiPluginWit())

        try {
            Files.write(
                componentPath,
                WasmComponentTools.componentFromCore(
                    witPath.toOkioPath(),
                    "plugin",
                    kotlinWasiGuest.toOkioPath(),
                ),
            )

            withKotlinWasiHttpProbe { _, _, httpFetch ->
                val wasi =
                    WasiPreview1.builder()
                        .withOptions(
                            WasiOptions.builder()
                                .withRandom(Random(11))
                                .withStdout(stdout)
                                .withStderr(stderr)
                                .withStdin(ByteArrayInputStream(kotlinWasiProductsJson.toByteArray(UTF_8)))
                                .withArguments(listOf("component.wasm", "--component"))
                                .withEnvironment("KRWA_SAMPLE", "component")
                                .withEnvironment("KRWA_PRODUCT_JSON", kotlinWasiProductJson)
                                .withEnvironment("KRWA_SANDBOX_ESCAPE_PATH", sandboxSentinel.escapePath)
                                .withDirectory("/", tempDir.toOkioPath())
                                .build()
                        )
                        .build()
                val plugin =
                    WasmPlugin.builderFromComponent(componentPath.toOkioPath())
                        .withHostImport("host-http", "fetch") { arguments -> httpFetch(arguments) }
                        .withWasiPreview1(wasi)
                        .build()

                requireValue(42L, plugin.call("api.run"), "Kotlin/WASI component Preview1 probe")
            }
            assertKotlinWasiComponentOutput(
                stdout.toString(UTF_8),
                stderr.toString(UTF_8),
                "component",
            )
            assertKotlinWasiGuestFiles(
                kotlinWasiFileSnapshot(tempDir, sandboxSentinel),
                "Kotlin/WASI component",
            )
        } finally {
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-products-report.json"))
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-products-report.txt"))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiFdFlagsFile))
            cleanupKotlinWasiPathLinkProbe(tempDir)
            cleanupKotlinWasiPathSymlinkProbe(tempDir)
            Files.deleteIfExists(tempDir.resolve(kotlinWasiCapabilityFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiMutationFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiMetadataSyncFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiRandomAccessFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiSeekAppendFile))
            cleanupKotlinWasiDirectoryProbe(tempDir)
            cleanupKotlinWasiReaddirProbe(tempDir)
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-probe.txt"))
            Files.deleteIfExists(componentPath)
            Files.deleteIfExists(witPath)
            Files.deleteIfExists(sandboxSentinel.hostPath)
            Files.deleteIfExists(tempDir)
        }

        capabilities.demonstrate(
            "Kotlin/WASI Guest",
            "Package the same guest as a component",
            "The host wraps the Kotlin wasmWasi core module with WIT and the bundled Preview 1 adapter, then calls it through WasmPlugin with the same HTTP, streaming JSON, and filesystem behavior.",
        )
    }

    private fun kotlinWasiPreview3Bridge() {
        require(Files.isRegularFile(kotlinWasiGuest)) {
            "Kotlin/WASI guest was not built: $kotlinWasiGuest"
        }

        val tempDir = Files.createTempDirectory("krwa-sample-kotlin-wasi-p3")
        val sandboxSentinel = createKotlinWasiSandboxSentinel(tempDir)
        val witPath = tempDir.resolve("plugin.wit")
        val componentPath = tempDir.resolve("kotlin-wasi-p3.component.wasm")
        val stdout = Buffer()
        val stderr = Buffer()
        Files.writeString(witPath, kotlinWasiPluginWit())

        try {
            Files.write(
                componentPath,
                WasmComponentTools.componentFromCore(
                    witPath.toOkioPath(),
                    "plugin",
                    kotlinWasiGuest.toOkioPath(),
                ),
            )

            withKotlinWasiHttpProbe { _, _, httpFetch ->
                val wasi =
                    WasiPreview3.builder()
                        .withSecureRandom(kotlin.random.Random(13L))
                        .withStdout(stdout)
                        .withStderr(stderr)
                        .withStdin(kotlinWasiStdinBuffer())
                        .withArguments("kotlin-p3-component.wasm", "--preview3-bridge")
                        .withEnvironment("KRWA_SAMPLE", "component")
                        .withEnvironment("KRWA_PRODUCT_JSON", kotlinWasiProductJson)
                        .withEnvironment("KRWA_SANDBOX_ESCAPE_PATH", sandboxSentinel.escapePath)
                        .withPreopenedDirectory("/", tempDir.toString())
                        .build()
                val plugin =
                    WasmPlugin.builderFromComponent(componentPath.toOkioPath())
                        .withHostImport("host-http", "fetch") { arguments -> httpFetch(arguments) }
                        .withWasiPreview3(wasi)
                        .build()

                requireValue(42L, plugin.call("api.run"), "Kotlin/WASI component via WASIp3 bridge")
            }
            val stdoutText = stdout.readByteArray().decodeToString()
            val stderrText = stderr.readByteArray().decodeToString()
            assertKotlinWasiComponentOutput(stdoutText, stderrText, "WASIp3 bridge")
            assertKotlinWasiGuestFiles(
                kotlinWasiFileSnapshot(tempDir, sandboxSentinel),
                "Kotlin/WASI component through WASIp3 bridge",
            )
        } finally {
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-products-report.json"))
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-products-report.txt"))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiFdFlagsFile))
            cleanupKotlinWasiPathLinkProbe(tempDir)
            cleanupKotlinWasiPathSymlinkProbe(tempDir)
            Files.deleteIfExists(tempDir.resolve(kotlinWasiCapabilityFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiMutationFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiMetadataSyncFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiRandomAccessFile))
            Files.deleteIfExists(tempDir.resolve(kotlinWasiSeekAppendFile))
            cleanupKotlinWasiDirectoryProbe(tempDir)
            cleanupKotlinWasiReaddirProbe(tempDir)
            Files.deleteIfExists(tempDir.resolve("krwa-wasi-probe.txt"))
            Files.deleteIfExists(componentPath)
            Files.deleteIfExists(witPath)
            Files.deleteIfExists(sandboxSentinel.hostPath)
            Files.deleteIfExists(tempDir)
        }

        capabilities.demonstrate(
            "Kotlin/WASI Guest",
            "Run the component through a WASIp3 host bridge",
            "The same Kotlin component is hosted with WASIp3 configuration while preserving stdio, environment, random, HTTP, streaming JSON, and filesystem behavior.",
        )
    }

    private fun kotlinWasiMalformedStdin() {
        require(Files.isRegularFile(kotlinWasiGuest)) {
            "Kotlin/WASI guest was not built: $kotlinWasiGuest"
        }

        val directTempDir = Files.createTempDirectory("krwa-sample-wasi-invalid")
        val directStdout = ByteArrayOutputStream()
        val directStderr = ByteArrayOutputStream()
        var directHttpCalled = false
        try {
            val wasi =
                WasiPreview1.builder()
                    .withOptions(
                        WasiOptions.builder()
                            .withRandom(Random(17))
                            .withStdout(directStdout)
                            .withStderr(directStderr)
                            .withStdin(ByteArrayInputStream(kotlinWasiMalformedProductsJson.toByteArray(UTF_8)))
                            .withArguments(listOf("kotlin-guest.wasm", kotlinWasiInvalidJsonArgument))
                            .withEnvironment("KRWA_SAMPLE", "preview1-invalid")
                            .withDirectory("/", directTempDir.toOkioPath())
                            .build()
                    )
                    .build()

            try {
                Instance.builder(Parser.parse(kotlinWasiGuest))
                    .withImportValues(
                        ImportValues.builder()
                            .addFunction(*wasi.toHostFunctions())
                            .addFunction(kotlinWasiUnusedHttpHostFunction { directHttpCalled = true })
                            .build()
                    )
                    .build()
            } catch (exit: WasiExitException) {
                requireValue(0, exit.exitCode(), "Kotlin/WASI invalid stdin exit code")
            }
            require(!directHttpCalled) { "Invalid stdin path should not call HTTP" }
            assertMalformedStdinOutput(
                directStdout.toString(UTF_8),
                "stdin.invalid-json=",
                "Kotlin/WASI invalid stdin",
            )
            require(directStderr.toString(UTF_8).contains("stderr.invalid-json=handled")) {
                "Unexpected invalid stdin stderr: ${directStderr.toString(UTF_8)}"
            }
        } finally {
            Files.deleteIfExists(directTempDir)
        }

        val componentTempDir = Files.createTempDirectory("krwa-sample-wasi-component-invalid")
        val witPath = componentTempDir.resolve("plugin.wit")
        val componentPath = componentTempDir.resolve("kotlin-wasi-invalid.component.wasm")
        Files.writeString(witPath, kotlinWasiPluginWit())

        try {
            Files.write(
                componentPath,
                WasmComponentTools.componentFromCore(
                    witPath.toOkioPath(),
                    "plugin",
                    kotlinWasiGuest.toOkioPath(),
                ),
            )

            val componentStdout = ByteArrayOutputStream()
            val componentStderr = ByteArrayOutputStream()
            var componentHttpCalled = false
            val componentWasi =
                WasiPreview1.builder()
                    .withOptions(
                        WasiOptions.builder()
                            .withRandom(Random(19))
                            .withStdout(componentStdout)
                            .withStderr(componentStderr)
                            .withStdin(ByteArrayInputStream(kotlinWasiMalformedProductsJson.toByteArray(UTF_8)))
                            .withArguments(
                                listOf(
                                    "component.wasm",
                                    "--component",
                                    kotlinWasiInvalidJsonArgument,
                                )
                            )
                            .withEnvironment("KRWA_SAMPLE", "component")
                            .withDirectory("/", componentTempDir.toOkioPath())
                            .build()
                    )
                    .build()
            val componentPlugin =
                WasmPlugin.builderFromComponent(componentPath.toOkioPath())
                    .withHostImport("host-http", "fetch") {
                        componentHttpCalled = true
                        599L
                    }
                    .withWasiPreview1(componentWasi)
                    .build()

            val componentResult = componentPlugin.call("api.run")
            require(componentResult == 43L) {
                "Kotlin/WASI component invalid stdin probe: expected <43>, got <$componentResult>; " +
                    "stdout=${componentStdout.toString(UTF_8)}; stderr=${componentStderr.toString(UTF_8)}"
            }
            require(!componentHttpCalled) { "Invalid component stdin path should not call HTTP" }
            assertMalformedStdinOutput(
                componentStdout.toString(UTF_8),
                "component.stdin.invalid-json=",
                "Kotlin/WASI component invalid stdin",
            )
            require(componentStderr.toString(UTF_8).contains("component.stderr.invalid-json=handled")) {
                "Unexpected component invalid stdin stderr: ${componentStderr.toString(UTF_8)}"
            }

            val preview3Stdout = Buffer()
            val preview3Stderr = Buffer()
            var preview3HttpCalled = false
            val preview3Wasi =
                WasiPreview3.builder()
                    .withSecureRandom(kotlin.random.Random(23L))
                    .withStdout(preview3Stdout)
                    .withStderr(preview3Stderr)
                    .withStdin(kotlinWasiMalformedStdinBuffer())
                    .withArguments(
                        "kotlin-p3-component.wasm",
                        "--preview3-bridge",
                        kotlinWasiInvalidJsonArgument,
                    )
                    .withEnvironment("KRWA_SAMPLE", "component")
                    .withPreopenedDirectory("/", componentTempDir.toString())
                    .build()
            val preview3Plugin =
                WasmPlugin.builderFromComponent(componentPath.toOkioPath())
                    .withHostImport("host-http", "fetch") {
                        preview3HttpCalled = true
                        599L
                    }
                    .withWasiPreview3(preview3Wasi)
                    .build()

            val preview3Result = preview3Plugin.call("api.run")
            require(preview3Result == 43L) {
                "Kotlin/WASI component invalid stdin via WASIp3 bridge: expected <43>, got <$preview3Result>"
            }
            require(!preview3HttpCalled) { "Invalid WASIp3 bridge stdin path should not call HTTP" }
            assertMalformedStdinOutput(
                preview3Stdout.readByteArray().decodeToString(),
                "component.stdin.invalid-json=",
                "Kotlin/WASI WASIp3 bridge invalid stdin",
            )
            require(preview3Stderr.readByteArray().decodeToString().contains("component.stderr.invalid-json=handled")) {
                "Unexpected WASIp3 bridge invalid stdin stderr"
            }
        } finally {
            Files.deleteIfExists(componentPath)
            Files.deleteIfExists(witPath)
            Files.deleteIfExists(componentTempDir)
        }

        capabilities.demonstrate(
            "Kotlin/WASI Guest",
            "Keep guest failures host-controlled",
            "Malformed stdin JSON is handled predictably across direct Preview 1, component packaging, and the WASIp3 bridge without leaking into host HTTP side effects.",
        )
    }

    private fun kotlinWasiStdinBuffer(): Buffer =
        Buffer().apply { write(kotlinWasiProductsJson.toByteArray(UTF_8)) }

    private fun kotlinWasiMalformedStdinBuffer(): Buffer =
        Buffer().apply { write(kotlinWasiMalformedProductsJson.toByteArray(UTF_8)) }

    private fun createKotlinWasiSandboxSentinel(tempDir: Path): KotlinWasiSandboxSentinel {
        val fileName = "${tempDir.fileName}-outside.txt"
        val hostPath = tempDir.parent.resolve(fileName)
        Files.writeString(hostPath, kotlinWasiSandboxPayload)
        return KotlinWasiSandboxSentinel("../$fileName", hostPath)
    }

    private fun kotlinWasiFileSnapshot(
        root: Path,
        sandboxSentinel: KotlinWasiSandboxSentinel,
    ): KotlinWasiGuestFileSnapshot =
        KotlinWasiGuestFileSnapshot(
            probeText = Files.readString(root.resolve("krwa-wasi-probe.txt")),
            seekAppendText = Files.readString(root.resolve(kotlinWasiSeekAppendFile)),
            randomAccessText = Files.readString(root.resolve(kotlinWasiRandomAccessFile)),
            metadataSyncText = Files.readString(root.resolve(kotlinWasiMetadataSyncFile)),
            mutationText = Files.readString(root.resolve(kotlinWasiMutationFile)),
            capabilityText = Files.readString(root.resolve(kotlinWasiCapabilityFile)),
            sandboxText = Files.readString(sandboxSentinel.hostPath),
            fdFlagsText = Files.readString(root.resolve(kotlinWasiFdFlagsFile)),
            pathLinkProbeExists = Files.exists(root.resolve(kotlinWasiPathLinkProbeDir)),
            pathSymlinkProbeExists = Files.exists(root.resolve(kotlinWasiPathSymlinkProbeDir)),
            directoryProbeExists = Files.exists(root.resolve(kotlinWasiDirectoryProbeDir)),
            readdirProbeExists = Files.exists(root.resolve(kotlinWasiReaddirProbeDir)),
            productsReportText = Files.readString(root.resolve("krwa-wasi-products-report.txt")),
            productsReportJsonText = Files.readString(root.resolve("krwa-wasi-products-report.json")),
        )

    private fun cleanupKotlinWasiDirectoryProbe(root: Path) {
        val dir = root.resolve(kotlinWasiDirectoryProbeDir)
        Files.deleteIfExists(dir.resolve("source.txt"))
        Files.deleteIfExists(dir.resolve("renamed.txt"))
        Files.deleteIfExists(dir)
    }

    private fun cleanupKotlinWasiPathLinkProbe(root: Path) {
        val dir = root.resolve(kotlinWasiPathLinkProbeDir)
        Files.deleteIfExists(dir.resolve("source.txt"))
        Files.deleteIfExists(dir.resolve("linked.txt"))
        Files.deleteIfExists(dir)
    }

    private fun cleanupKotlinWasiPathSymlinkProbe(root: Path) {
        val dir = root.resolve(kotlinWasiPathSymlinkProbeDir)
        Files.deleteIfExists(dir.resolve("link.txt"))
        Files.deleteIfExists(dir.resolve("target.txt"))
        Files.deleteIfExists(dir)
    }

    private fun cleanupKotlinWasiReaddirProbe(root: Path) {
        val dir = root.resolve(kotlinWasiReaddirProbeDir)
        Files.deleteIfExists(dir.resolve("alpha.txt"))
        Files.deleteIfExists(dir.resolve("beta.txt"))
        Files.deleteIfExists(dir)
    }

    private fun <T> withKotlinWasiHttpProbe(
        block: (authority: String, hostFunction: HostFunction, hostImport: (List<Any?>) -> Any?) -> T
    ): T =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val requestLine = AtomicReference<String?>()
            val serverFailure = AtomicReference<Throwable?>()
            val serverThread =
                Thread {
                    while (!server.isClosed) {
                        try {
                            server.accept().use { socket ->
                                socket.soTimeout = 2_000
                                val reader =
                                    BufferedReader(
                                        InputStreamReader(
                                            socket.getInputStream(),
                                            StandardCharsets.ISO_8859_1,
                                        )
                                    )
                                requestLine.set(reader.readLine())
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                }

                                val body = kotlinWasiProductJson.toByteArray(UTF_8)
                                val response =
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n" +
                                        "\r\n"
                                socket.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1))
                                socket.getOutputStream().write(body)
                                socket.getOutputStream().flush()
                            }
                        } catch (failure: Throwable) {
                            if (!server.isClosed) {
                                serverFailure.set(failure)
                            }
                            break
                        }
                    }
                }
                    .apply {
                        name = "krwa-kotlin-wasi-http-probe"
                        isDaemon = true
                        start()
                    }
            val authority = "127.0.0.1:${server.localPort}"
            val httpFetch: (String) -> HttpFetchResponse = { requestedPath ->
                val requestedAuthority = authority
                requireValue(authority, requestedAuthority, "Kotlin/WASI HTTP authority")
                requireValue(kotlinWasiHttpPath, requestedPath, "Kotlin/WASI HTTP path")
                readHttpResponse(server.localPort, requestedAuthority, requestedPath)
            }
            val hostFunction =
                HostFunction(
                    kotlinWasiHttpModule,
                    "fetch",
                    FunctionType.of(
                        listOf(ValType.I32, ValType.I32, ValType.I32, ValType.I32),
                        listOf(ValType.I32),
                    ),
                    WasmFunctionHandle { instance, args ->
                        val requestedPath =
                            instance.memory().readString(args[0].toInt(), args[1].toInt())
                        val response = httpFetch(requestedPath)
                        val bodyPtr = args[2].toInt()
                        val bodyCapacity = args[3].toInt()
                        val bodyBytes = response.body.toByteArray(UTF_8)
                        if (bodyCapacity > 0) {
                            require(bodyBytes.size <= bodyCapacity) {
                                "Kotlin/WASI HTTP body too large: ${bodyBytes.size}"
                            }
                            instance.memory().writeI32(bodyPtr, bodyBytes.size)
                            instance.memory().write(bodyPtr + 4, bodyBytes)
                        }
                        longArrayOf(response.status.toLong())
                    },
                )
            val hostImport: (List<Any?>) -> Any? = { arguments ->
                val response = httpFetch(arguments[0] as String)
                response.status.toLong()
            }
            val result = block(authority, hostFunction, hostImport)
            server.close()
            serverThread.join(2_000)
            serverFailure.get()?.let { throw IllegalStateException("Kotlin/WASI HTTP probe failed", it) }
            requireValue(
                "GET $kotlinWasiHttpPath HTTP/1.1",
                requestLine.get(),
                "Kotlin/WASI HTTP request line",
            )
            result
        }

    private fun readHttpResponse(port: Int, authority: String, path: String): HttpFetchResponse =
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.soTimeout = 2_000
            val request =
                "GET $path HTTP/1.1\r\n" +
                    "Host: $authority\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1))
            val statusLine = reader.readLine()
            val parts = statusLine?.split(" ").orEmpty()
            require(parts.size >= 2) { "Invalid HTTP status line from probe server: $statusLine" }
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            HttpFetchResponse(parts[1].toInt(), reader.readText())
        }

}

private fun Path.toOkioPath(): okio.Path = toString().toPath(normalize = true)
