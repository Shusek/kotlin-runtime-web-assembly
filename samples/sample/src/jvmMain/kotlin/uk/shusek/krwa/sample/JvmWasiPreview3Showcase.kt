@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.sample

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import uk.shusek.krwa.wasi.preview3.KotlinWasiPreview3
import uk.shusek.krwa.wasi.preview3.asByteArray
import uk.shusek.krwa.wasi.preview3.asDeferred
import uk.shusek.krwa.wasi.preview3.readWitByteStream
import uk.shusek.krwa.wasi.preview3.writeWitByteStream

internal fun runWasiPreview3RuntimeShowcase(capabilities: ShowcaseCapabilities) {
    wasiPreview3CliClocksRandom()
    wasiPreview3HttpClient()
    wasiPreview3Filesystem()
    wasiPreview3Sockets()
    wasiPreview3CanonicalIntrinsics()
    wasiPreview3KotlinFacade()
    capabilities.demonstrate(
        "WASI Preview 3 Host Capabilities",
        "Host a broad WASIp3 surface",
        "The host drives CLI/environment, clocks, random, HTTP, filesystem preopens, TCP/UDP sockets, canonical futures/streams, and Kotlin facade helpers.",
    )
}

private fun wasiPreview3CliClocksRandom() {
    runWasiPreview3CliClocksRandomScenario(compileWat(wasiPreview3CliClocksRandomWat()))
}

private fun wasiPreview3HttpClient() {
    runWasiPreview3HttpClientScenario(
        compileWat(
            wasiPreview3HttpClientWat(
                authority = SHOWCASE_WASI3_HTTP_AUTHORITY,
                pathWithQuery = SHOWCASE_WASI3_HTTP_PATH_WITH_QUERY,
            )
        )
    )
}

private fun wasiPreview3Filesystem() {
    val tempDir = Files.createTempDirectory("krwa-sample-wasi3-fs")
    val source = tempDir.resolve("hello.txt")
    try {
        Files.writeString(source, "hello", UTF_8)
        runWasiPreview3FilesystemScenario(
            module = compileWat(wasiPreview3FilesystemWat()),
            hostRoot = tempDir.toString(),
        )
    } finally {
        Files.deleteIfExists(source)
        Files.deleteIfExists(tempDir)
    }
}

private fun wasiPreview3Sockets() {
    val serverFailure = AtomicReference<Throwable?>()
    val tcpAccepted = AtomicReference(false)
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { tcpServer ->
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { udpServer ->
            udpServer.soTimeout = 2_000
            val tcpThread =
                Thread(
                    {
                        try {
                            tcpServer.accept().use {
                                tcpAccepted.set(true)
                            }
                        } catch (e: Throwable) {
                            serverFailure.set(e)
                        }
                    },
                    "krwa-sample-wasi3-tcp",
                )
            tcpThread.isDaemon = true
            tcpThread.start()

            runWasiPreview3SocketsScenario(
                compileWat(
                    wasiPreview3SocketsWat(
                        tcpPort = tcpServer.localPort,
                        udpPort = udpServer.localPort,
                    )
                ),
                tcpPort = tcpServer.localPort,
                udpPort = udpServer.localPort,
            )
            val packet = DatagramPacket(ByteArray(16), 16)
            udpServer.receive(packet)
            tcpThread.join(2_000L)

            requireShowcaseValue(
                "ping",
                String(packet.data, packet.offset, packet.length, StandardCharsets.ISO_8859_1),
                "WASIp3 UDP payload",
            )
            require(tcpAccepted.get()) { "Expected WASIp3 TCP connection to be accepted" }
        }
    }

    serverFailure.get()?.let { throw IllegalStateException("WASIp3 TCP sample server failed", it) }
}

private fun wasiPreview3CanonicalIntrinsics() {
    runWasiPreview3CanonicalIntrinsicsScenarios(
        streamModule = compileWat(wasiPreview3StreamIntrinsicsWat()),
        futureModule = compileWat(wasiPreview3FutureIntrinsicsWat()),
    )
}

private fun wasiPreview3KotlinFacade() {
    val tempDir = Files.createTempDirectory("krwa-sample-wasi3-facade")
    runBlocking {
        try {
            val runtime =
                KotlinWasiPreview3.builder()
                    .withPreopenedDirectory("/", tempDir.toString())
                    .build()
            val future = runtime.completed("first-party")
            val fs = runtime.fileSystem("/")

            requireShowcaseValue("first-party", runtime.await(future), "WASIp3 Kotlin facade await")
            requireShowcaseValue(
                "first-party",
                future.asDeferred(runtime.wasi, this).await(),
                "WASIp3 Kotlin facade Deferred",
            )
            requireShowcaseValue(
                "bytes",
                runtime.byteStream("bytes".toByteArray(UTF_8)).asByteArray(runtime.wasi).toString(UTF_8),
                "WASIp3 Kotlin facade byte stream",
            )

            fs.writeText("facade/file.txt", "file")
            fs.appendText("facade/file.txt", "-facade")
            requireShowcaseValue("file-facade", fs.readText("facade/file.txt"), "WASIp3 Kotlin facade filesystem")
            fs.writeWitByteStream(
                "facade/copy.txt",
                fs.readWitByteStream("facade/file.txt", runtime.wasi),
                runtime.wasi,
            )
            requireShowcaseValue("file-facade", fs.readText("facade/copy.txt"), "WASIp3 Kotlin facade filesystem stream")
        } finally {
            Files.deleteIfExists(tempDir.resolve("facade/copy.txt"))
            Files.deleteIfExists(tempDir.resolve("facade/file.txt"))
            Files.deleteIfExists(tempDir.resolve("facade"))
            Files.deleteIfExists(tempDir)
        }
    }
}
