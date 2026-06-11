@file:OptIn(kotlin.time.ExperimentalTime::class)

package uk.shusek.krwa.component

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Instant as KotlinInstant
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.tools.wasm.Wat2Wasm

class WasiPreview2Test {
    @TempDir lateinit var tempDir: Path

    @Test
    fun linksStdoutStreamsAndEnvironmentWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-preview2;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:cli/stdout@0.2.11;\n" +
                    "  import wasi:cli/environment@0.2.11;\n" +
                    "  import wasi:io/streams@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:io@0.2.11 {\n" +
                    "  interface streams {\n" +
                    "    variant stream-error {\n" +
                    "      closed,\n" +
                    "    }\n" +
                    "    resource output-stream {\n" +
                    "      blocking-write-and-flush: func(contents: list<u8>)" +
                    " -> result<_, stream-error>;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:cli@0.2.11 {\n" +
                    "  interface stdout {\n" +
                    "    use wasi:io/streams@0.2.11.{output-stream};\n" +
                    "    get-stdout: func() -> output-stream;\n" +
                    "  }\n" +
                    "  interface environment {\n" +
                    "    get-environment: func() -> list<tuple<string, string>>;\n" +
                    "    get-arguments: func() -> list<string>;\n" +
                    "    initial-cwd: func() -> option<string>;\n" +
                    "  }\n" +
                    "}\n"
            )
        val stdout = Buffer()
        val wasi =
            WasiPreview2.builder().withStdout(stdout).withArguments("plugin.wasm", "--scan").build()

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/stdout@0.2.11\" \"get-stdout\"" +
                            " (func \$get_stdout (result i32)))\n" +
                            "  (import \"wasi:cli/environment@0.2.11\"" +
                            " \"get-arguments\" (func \$get_arguments (param" +
                            " i32)))\n" +
                            "  (import \"wasi:io/streams@0.2.11\"" +
                            " \"[method]output-stream.blocking-write-and-flush\"" +
                            " (func \$write (param i32) (param i32) (param i32)" +
                            " (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 128))\n" +
                            "  (data (i32.const 16) \"hello\")\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (call \$write      (call \$get_stdout)     " +
                            " (i32.const 16)      (i32.const 5)      (i32.const" +
                            " 72))\n" +
                            "    (call \$get_arguments (i32.const 64))\n" +
                            "    (i32.load (i32.const 68)))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(2L, plugin.call("api.run"))
        assertEquals("hello", stdout.readUtf8())
    }

    @Test
    fun linksStdinAndStderrStreamsWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-stdio;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:cli/stdin@0.2.11;\n" +
                    "  import wasi:cli/stderr@0.2.11;\n" +
                    "  import wasi:io/streams@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:io@0.2.11 {\n" +
                    "  interface streams {\n" +
                    "    variant stream-error {\n" +
                    "      closed,\n" +
                    "    }\n" +
                    "    resource input-stream {\n" +
                    "      blocking-read: func(len: u64)" +
                    " -> result<list<u8>, stream-error>;\n" +
                    "    }\n" +
                    "    resource output-stream {\n" +
                    "      blocking-write-and-flush: func(contents: list<u8>)" +
                    " -> result<_, stream-error>;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:cli@0.2.11 {\n" +
                    "  interface stdin {\n" +
                    "    use wasi:io/streams@0.2.11.{input-stream};\n" +
                    "    get-stdin: func() -> input-stream;\n" +
                    "  }\n" +
                    "  interface stderr {\n" +
                    "    use wasi:io/streams@0.2.11.{output-stream};\n" +
                    "    get-stderr: func() -> output-stream;\n" +
                    "  }\n" +
                    "}\n"
            )
        val stderr = Buffer()
        val wasi =
            WasiPreview2.builder()
                .withStdin(Buffer().writeUtf8("kotlin"))
                .withStderr(stderr)
                .build()

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/stdin@0.2.11\" \"get-stdin\"" +
                            " (func \$get_stdin (result i32)))\n" +
                            "  (import \"wasi:cli/stderr@0.2.11\" \"get-stderr\"" +
                            " (func \$get_stderr (result i32)))\n" +
                            "  (import \"wasi:io/streams@0.2.11\"" +
                            " \"[method]input-stream.blocking-read\" (func \$read" +
                            " (param i32) (param i64) (param i32)))\n" +
                            "  (import \"wasi:io/streams@0.2.11\"" +
                            " \"[method]output-stream.blocking-write-and-flush\"" +
                            " (func \$write (param i32) (param i32) (param i32)" +
                            " (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 128))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$input i32)\n" +
                            "    (local \$output i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local \$len i32)\n" +
                            "    (local.set \$input (call \$get_stdin))\n" +
                            "    (local.set \$output (call \$get_stderr))\n" +
                            "    (call \$read (local.get \$input) (i64.const 6)" +
                            " (i32.const 64))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 99))))\n" +
                            "    (local.set \$ptr (i32.load (i32.const 68)))\n" +
                            "    (local.set \$len (i32.load (i32.const 72)))\n" +
                            "    (call \$write (local.get \$output) (local.get" +
                            " \$ptr) (local.get \$len) (i32.const 80))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 80))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 98))))\n" +
                            "    (local.get \$len))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(6L, plugin.call("api.run"))
        assertEquals("kotlin", stderr.readUtf8())
    }

    @Test
    fun mapsStreamIoErrorsToNetworkErrorCodesWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-network-error;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:cli/stdin@0.2.11;\n" +
                    "  import wasi:io/streams@0.2.11;\n" +
                    "  import wasi:io/error@0.2.11;\n" +
                    "  import wasi:sockets/network@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:io@0.2.11 {\n" +
                    "  interface error {\n" +
                    "    resource error {\n" +
                    "      to-debug-string: func() -> string;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  interface streams {\n" +
                    "    use error.{error};\n" +
                    "    variant stream-error {\n" +
                    "      last-operation-failed(error),\n" +
                    "      closed,\n" +
                    "    }\n" +
                    "    resource input-stream {\n" +
                    "      blocking-read: func(len: u64)" +
                    " -> result<list<u8>, stream-error>;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:cli@0.2.11 {\n" +
                    "  interface stdin {\n" +
                    "    use wasi:io/streams@0.2.11.{input-stream};\n" +
                    "    get-stdin: func() -> input-stream;\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:sockets@0.2.11 {\n" +
                    "  interface network {\n" +
                    "    use wasi:io/error@0.2.11.{error};\n" +
                    "    enum error-code {\n" +
                    "      unknown,\n" +
                    "      connection-reset,\n" +
                    "    }\n" +
                    "    network-error-code: func(err: borrow<error>)" +
                    " -> option<error-code>;\n" +
                    "  }\n" +
                    "}\n"
            )
        val wasi = WasiPreview2.builder().withStdin(failingInput("connection reset")).build()

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/stdin@0.2.11\" \"get-stdin\"" +
                            " (func \$get_stdin (result i32)))\n" +
                            "  (import \"wasi:io/streams@0.2.11\"" +
                            " \"[method]input-stream.blocking-read\" (func \$read" +
                            " (param i32) (param i64) (param i32)))\n" +
                            "  (import \"wasi:io/error@0.2.11\"" +
                            " \"[method]error.to-debug-string\" (func \$debug" +
                            " (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/network@0.2.11\"" +
                            " \"network-error-code\" (func \$network_code (param" +
                            " i32) (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 128))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$err i32)\n" +
                            "    (call \$read (call \$get_stdin) (i64.const 1)" +
                            " (i32.const 32))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 32))" +
                            " (i32.const 1))\n" +
                            "      (then (return (i32.const 90))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 36))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 91))))\n" +
                            "    (local.set \$err (i32.load (i32.const 40)))\n" +
                            "    (call \$debug (local.get \$err) (i32.const 48))\n" +
                            "    (if (i32.ne (i32.load (i32.const 52)) (i32.const" +
                            " 16))\n" +
                            "      (then (return (i32.const 92))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.load (i32.const" +
                            " 48))) (i32.const 99))\n" +
                            "      (then (return (i32.const 93))))\n" +
                            "    (call \$network_code (local.get \$err) (i32.const" +
                            " 64))\n" +
                            "    (i32.add\n" +
                            "      (i32.mul (i32.load8_u (i32.const 64))" +
                            " (i32.const 10))\n" +
                            "      (i32.load8_u (i32.const 65))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(11L, plugin.call("api.run"))
    }

    @Test
    fun mapsStreamIoErrorsToHttpErrorCodesWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-http-error;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:cli/stdin@0.2.11;\n" +
                    "  import wasi:io/streams@0.2.11;\n" +
                    "  import wasi:io/error@0.2.11;\n" +
                    "  import wasi:http/types@0.2.11;\n" +
                    "  import wasi:sockets/network@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:io@0.2.11 {\n" +
                    "  interface error {\n" +
                    "    resource error {\n" +
                    "      to-debug-string: func() -> string;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  interface streams {\n" +
                    "    use error.{error};\n" +
                    "    variant stream-error {\n" +
                    "      last-operation-failed(error),\n" +
                    "      closed,\n" +
                    "    }\n" +
                    "    resource input-stream {\n" +
                    "      blocking-read: func(len: u64)" +
                    " -> result<list<u8>, stream-error>;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:cli@0.2.11 {\n" +
                    "  interface stdin {\n" +
                    "    use wasi:io/streams@0.2.11.{input-stream};\n" +
                    "    get-stdin: func() -> input-stream;\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:http@0.2.11 {\n" +
                    "  interface types {\n" +
                    "    use wasi:io/error@0.2.11.{error as io-error};\n" +
                    "    variant error-code {\n" +
                    "      HTTP-response-incomplete,\n" +
                    "      internal-error(option<string>),\n" +
                    "    }\n" +
                    "    http-error-code: func(err: borrow<io-error>)" +
                    " -> option<error-code>;\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:sockets@0.2.11 {\n" +
                    "  interface network {\n" +
                    "    use wasi:io/error@0.2.11.{error};\n" +
                    "    enum error-code {\n" +
                    "      unknown,\n" +
                    "      connection-reset,\n" +
                    "    }\n" +
                    "    network-error-code: func(err: borrow<error>)" +
                    " -> option<error-code>;\n" +
                    "  }\n" +
                    "}\n"
            )
        val wasi =
            WasiPreview2.builder().withStdin(failingInput("HTTP-response-incomplete")).build()

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/stdin@0.2.11\" \"get-stdin\"" +
                            " (func \$get_stdin (result i32)))\n" +
                            "  (import \"wasi:io/streams@0.2.11\"" +
                            " \"[method]input-stream.blocking-read\" (func \$read" +
                            " (param i32) (param i64) (param i32)))\n" +
                            "  (import \"wasi:io/error@0.2.11\"" +
                            " \"[method]error.to-debug-string\" (func \$debug" +
                            " (param i32) (param i32)))\n" +
                            "  (import \"wasi:http/types@0.2.11\"" +
                            " \"http-error-code\" (func \$http_code (param i32)" +
                            " (param i32)))\n" +
                            "  (import \"wasi:sockets/network@0.2.11\"" +
                            " \"network-error-code\" (func \$network_code (param" +
                            " i32) (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 128))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$err i32)\n" +
                            "    (call \$read (call \$get_stdin) (i64.const 1)" +
                            " (i32.const 32))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 32))" +
                            " (i32.const 1))\n" +
                            "      (then (return (i32.const 90))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 36))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 91))))\n" +
                            "    (local.set \$err (i32.load (i32.const 40)))\n" +
                            "    (call \$debug (local.get \$err) (i32.const 48))\n" +
                            "    (if (i32.ne (i32.load (i32.const 52)) (i32.const" +
                            " 24))\n" +
                            "      (then (return (i32.const 92))))\n" +
                            "    (call \$http_code (local.get \$err) (i32.const" +
                            " 64))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 1))\n" +
                            "      (then (return (i32.const 93))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 68))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 94))))\n" +
                            "    (call \$network_code (local.get \$err) (i32.const" +
                            " 96))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 95))))\n" +
                            "    (i32.const 1))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(1L, plugin.call("api.run"))
    }

    @Test
    fun mapsStreamIoErrorsToFilesystemErrorCodesWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-filesystem-error;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:cli/stdin@0.2.11;\n" +
                    "  import wasi:io/streams@0.2.11;\n" +
                    "  import wasi:io/error@0.2.11;\n" +
                    "  import wasi:filesystem/types@0.2.11;\n" +
                    "  import wasi:sockets/network@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:io@0.2.11 {\n" +
                    "  interface error {\n" +
                    "    resource error {\n" +
                    "      to-debug-string: func() -> string;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  interface streams {\n" +
                    "    use error.{error};\n" +
                    "    variant stream-error {\n" +
                    "      last-operation-failed(error),\n" +
                    "      closed,\n" +
                    "    }\n" +
                    "    resource input-stream {\n" +
                    "      blocking-read: func(len: u64)" +
                    " -> result<list<u8>, stream-error>;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:cli@0.2.11 {\n" +
                    "  interface stdin {\n" +
                    "    use wasi:io/streams@0.2.11.{input-stream};\n" +
                    "    get-stdin: func() -> input-stream;\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:filesystem@0.2.11 {\n" +
                    "  interface types {\n" +
                    "    use wasi:io/error@0.2.11.{error};\n" +
                    "    enum error-code {\n" +
                    "      access,\n" +
                    "      no-entry,\n" +
                    "      io,\n" +
                    "    }\n" +
                    "    filesystem-error-code: func(err: borrow<error>)" +
                    " -> option<error-code>;\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:sockets@0.2.11 {\n" +
                    "  interface network {\n" +
                    "    use wasi:io/error@0.2.11.{error};\n" +
                    "    enum error-code {\n" +
                    "      unknown,\n" +
                    "    }\n" +
                    "    network-error-code: func(err: borrow<error>)" +
                    " -> option<error-code>;\n" +
                    "  }\n" +
                    "}\n"
            )
        val wasi =
            WasiPreview2.builder()
                .withStdin(failingInput(java.nio.file.NoSuchFileException("missing")))
                .build()

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/stdin@0.2.11\" \"get-stdin\"" +
                            " (func \$get_stdin (result i32)))\n" +
                            "  (import \"wasi:io/streams@0.2.11\"" +
                            " \"[method]input-stream.blocking-read\" (func \$read" +
                            " (param i32) (param i64) (param i32)))\n" +
                            "  (import \"wasi:io/error@0.2.11\"" +
                            " \"[method]error.to-debug-string\" (func \$debug" +
                            " (param i32) (param i32)))\n" +
                            "  (import \"wasi:filesystem/types@0.2.11\"" +
                            " \"filesystem-error-code\" (func \$filesystem_code" +
                            " (param i32) (param i32)))\n" +
                            "  (import \"wasi:sockets/network@0.2.11\"" +
                            " \"network-error-code\" (func \$network_code (param" +
                            " i32) (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 128))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$err i32)\n" +
                            "    (call \$read (call \$get_stdin) (i64.const 1)" +
                            " (i32.const 32))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 32))" +
                            " (i32.const 1))\n" +
                            "      (then (return (i32.const 90))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 36))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 91))))\n" +
                            "    (local.set \$err (i32.load (i32.const 40)))\n" +
                            "    (call \$debug (local.get \$err) (i32.const 48))\n" +
                            "    (if (i32.ne (i32.load (i32.const 52)) (i32.const" +
                            " 7))\n" +
                            "      (then (return (i32.const 92))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.load (i32.const" +
                            " 48))) (i32.const 109))\n" +
                            "      (then (return (i32.const 93))))\n" +
                            "    (call \$filesystem_code (local.get \$err)" +
                            " (i32.const 64))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 1))\n" +
                            "      (then (return (i32.const 94))))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 65))" +
                            " (i32.const 1))\n" +
                            "      (then (return (i32.const 95))))\n" +
                            "    (call \$network_code (local.get \$err) (i32.const" +
                            " 80))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 80))" +
                            " (i32.const 0))\n" +
                            "      (then (return (i32.const 96))))\n" +
                            "    (i32.const 1))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(1L, plugin.call("api.run"))
    }

    @Test
    fun linksClocksAndRandomWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-clocks-random;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:clocks/wall-clock@0.2.11;\n" +
                    "  import wasi:clocks/monotonic-clock@0.2.11;\n" +
                    "  import wasi:random/random@0.2.11;\n" +
                    "  import wasi:random/insecure-seed@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:clocks@0.2.11 {\n" +
                    "  interface wall-clock {\n" +
                    "    record datetime {\n" +
                    "      seconds: u64,\n" +
                    "      nanoseconds: u32,\n" +
                    "    }\n" +
                    "    now: func() -> datetime;\n" +
                    "    resolution: func() -> datetime;\n" +
                    "  }\n" +
                    "  interface monotonic-clock {\n" +
                    "    type instant = u64;\n" +
                    "    type duration = u64;\n" +
                    "    now: func() -> instant;\n" +
                    "    resolution: func() -> duration;\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:random@0.2.11 {\n" +
                    "  interface random {\n" +
                    "    get-random-bytes: func(len: u64) -> list<u8>;\n" +
                    "    get-random-u64: func() -> u64;\n" +
                    "  }\n" +
                    "  interface insecure-seed {\n" +
                    "    insecure-seed: func() -> tuple<u64, u64>;\n" +
                    "  }\n" +
                    "}\n"
            )
        val monotonic = AtomicLong(10_000L)
        val wasi =
            WasiPreview2.builder()
                .withFixedWallClock(KotlinInstant.fromEpochSeconds(123L, 456L))
                .withWallClockResolutionNanos(1_000L)
                .withMonotonicClock { monotonic.get() }
                .withMonotonicResolutionNanos(7L)
                .withSecureRandom(Random(1234L))
                .withInsecureSeed(11L, 22L)
                .build()
        monotonic.set(10_042L)

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:clocks/wall-clock@0.2.11\" \"now\"" +
                            " (func \$wall_now (param i32)))\n" +
                            "  (import \"wasi:clocks/wall-clock@0.2.11\"" +
                            " \"resolution\" (func \$wall_resolution (param" +
                            " i32)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@0.2.11\"" +
                            " \"now\" (func \$monotonic_now (result i64)))\n" +
                            "  (import \"wasi:clocks/monotonic-clock@0.2.11\"" +
                            " \"resolution\" (func \$monotonic_resolution (result" +
                            " i64)))\n" +
                            "  (import \"wasi:random/random@0.2.11\"" +
                            " \"get-random-bytes\" (func \$random_bytes (param" +
                            " i64) (param i32)))\n" +
                            "  (import \"wasi:random/random@0.2.11\"" +
                            " \"get-random-u64\" (func \$random_u64 (result" +
                            " i64)))\n" +
                            "  (import \"wasi:random/insecure-seed@0.2.11\"" +
                            " \"insecure-seed\" (func \$insecure_seed (param" +
                            " i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 128))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (call \$wall_now (i32.const 32))\n" +
                            "    (call \$wall_resolution (i32.const 48))\n" +
                            "    (call \$random_bytes (i64.const 4) (i32.const" +
                            " 64))\n" +
                            "    (drop (call \$random_u64))\n" +
                            "    (call \$insecure_seed (i32.const 80))\n" +
                            "    (i32.add\n" +
                            "      (i32.add\n" +
                            "        (i32.add\n" +
                            "          (i32.wrap_i64 (i64.load (i32.const 32)))\n" +
                            "          (i32.load (i32.const 40)))\n" +
                            "        (i32.wrap_i64 (call \$monotonic_now)))\n" +
                            "      (i32.add\n" +
                            "        (i32.add\n" +
                            "          (i32.wrap_i64 (call" +
                            " \$monotonic_resolution))\n" +
                            "          (i32.load (i32.const 68)))\n" +
                            "        (i32.add\n" +
                            "          (i32.wrap_i64 (i64.load (i32.const 80)))\n" +
                            "          (i32.wrap_i64 (i64.load (i32.const" +
                            " 88)))))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(665L, plugin.call("api.run"))
    }

    @Test
    fun linksTimezoneDisplayWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-timezone;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:clocks/timezone@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:clocks@0.2.11 {\n" +
                    "  interface wall-clock {\n" +
                    "    record datetime {\n" +
                    "      seconds: u64,\n" +
                    "      nanoseconds: u32,\n" +
                    "    }\n" +
                    "  }\n" +
                    "  interface timezone {\n" +
                    "    use wall-clock.{datetime};\n" +
                    "    record timezone-display {\n" +
                    "      utc-offset: s32,\n" +
                    "      name: string,\n" +
                    "      in-daylight-saving-time: bool,\n" +
                    "    }\n" +
                    "    display: func(when: datetime) -> timezone-display;\n" +
                    "    utc-offset: func(when: datetime) -> s32;\n" +
                    "  }\n" +
                    "}\n"
            )
        val wasi =
            WasiPreview2.builder().withFixedWallClock(KotlinInstant.fromEpochSeconds(0L)).build()

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:clocks/timezone@0.2.11\"" +
                            " \"display\" (func \$display (param i64) (param i32)" +
                            " (param i32)))\n" +
                            "  (import \"wasi:clocks/timezone@0.2.11\"" +
                            " \"utc-offset\" (func \$utc_offset (param i64) (param" +
                            " i32) (result i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 256))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (call \$display (i64.const 0) (i32.const 0)" +
                            " (i32.const 64))\n" +
                            "    (i32.add\n" +
                            "      (call \$utc_offset (i64.const 0) (i32.const" +
                            " 0))\n" +
                            "      (i32.add\n" +
                            "        (i32.load (i32.const 64))\n" +
                            "        (i32.add\n" +
                            "          (i32.load (i32.const 72))\n" +
                            "          (i32.add\n" +
                            "            (i32.load8_u (i32.load (i32.const" +
                            " 68)))\n" +
                            "            (i32.load8_u (i32.const 76)))))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(88L, plugin.call("api.run"))
    }

    @Test
    fun pollsFirstReadyHandleAcrossMultiplePollablesWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-poll;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:clocks/monotonic-clock@0.2.11;\n" +
                    "  import wasi:io/poll@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:io@0.2.11 {\n" +
                    "  interface poll {\n" +
                    "    resource pollable;\n" +
                    "    poll: func(in: list<borrow<pollable>>) -> list<u32>;\n" +
                    "  }\n" +
                    "}\n" +
                    "package wasi:clocks@0.2.11 {\n" +
                    "  interface monotonic-clock {\n" +
                    "    use wasi:io/poll@0.2.11.{pollable};\n" +
                    "    type duration = u64;\n" +
                    "    subscribe-duration: func(when: duration) -> pollable;\n" +
                    "  }\n" +
                    "}\n"
            )
        val monotonic = AtomicLong()
        val wasi = WasiPreview2.builder().withMonotonicClock { monotonic.getAndIncrement() }.build()

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:clocks/monotonic-clock@0.2.11\"" +
                            " \"subscribe-duration\" (func \$subscribe_duration" +
                            " (param i64) (result i32)))\n" +
                            "  (import \"wasi:io/poll@0.2.11\" \"poll\" (func" +
                            " \$poll (param i32) (param i32) (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 256))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (i32.store (i32.const 64) (call" +
                            " \$subscribe_duration (i64.const 20)))\n" +
                            "    (i32.store (i32.const 68) (call" +
                            " \$subscribe_duration (i64.const 3)))\n" +
                            "    (call \$poll (i32.const 64) (i32.const 2)" +
                            " (i32.const 80))\n" +
                            "    (i32.add\n" +
                            "      (i32.mul (i32.load (i32.const 84)) (i32.const" +
                            " 10))\n" +
                            "      (i32.load (i32.load (i32.const 80)))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(11L, plugin.call("api.run"))
    }

    @Test
    fun linksFilesystemPreopensAndDescriptorWritesWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-filesystem;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:filesystem/types@0.2.11;\n" +
                    "  import wasi:filesystem/preopens@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:filesystem@0.2.11 {\n" +
                    "  interface types {\n" +
                    "    type filesize = u64;\n" +
                    "    flags path-flags { symlink-follow }\n" +
                    "    flags open-flags { create, directory, exclusive, truncate }\n" +
                    "    flags descriptor-flags {\n" +
                    "      read,\n" +
                    "      write,\n" +
                    "      file-integrity-sync,\n" +
                    "      data-integrity-sync,\n" +
                    "      requested-write-sync,\n" +
                    "      mutate-directory,\n" +
                    "    }\n" +
                    "    enum error-code {\n" +
                    "      access,\n" +
                    "      no-entry,\n" +
                    "      exist,\n" +
                    "      read-only,\n" +
                    "      not-permitted,\n" +
                    "      io,\n" +
                    "      bad-descriptor,\n" +
                    "      is-directory,\n" +
                    "      not-directory,\n" +
                    "    }\n" +
                    "    resource descriptor {\n" +
                    "      open-at: func(\n" +
                    "        path-flags: path-flags,\n" +
                    "        path: string,\n" +
                    "        open-flags: open-flags,\n" +
                    "        %flags: descriptor-flags,\n" +
                    "      ) -> result<descriptor, error-code>;\n" +
                    "      write: func(buffer: list<u8>, offset: filesize) ->" +
                    " result<filesize, error-code>;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  interface preopens {\n" +
                    "    use types.{descriptor};\n" +
                    "    get-directories: func() -> list<tuple<descriptor, string>>;\n" +
                    "  }\n" +
                    "}\n"
            )
        val wasi = WasiPreview2.builder().withPreopenedDirectory("/", tempDir.toString()).build()

        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:filesystem/preopens@0.2.11\"" +
                            " \"get-directories\" (func \$get_directories (param" +
                            " i32)))\n" +
                            "  (import \"wasi:filesystem/types@0.2.11\"" +
                            " \"[method]descriptor.open-at\" (func \$open_at" +
                            " (param i32) (param i32) (param i32) (param i32)" +
                            " (param i32) (param i32) (param i32)))\n" +
                            "  (import \"wasi:filesystem/types@0.2.11\"" +
                            " \"[method]descriptor.write\" (func \$write (param" +
                            " i32) (param i32) (param i32) (param i64) (param" +
                            " i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 128))\n" +
                            "  (data (i32.const 16) \"probe.txt\")\n" +
                            "  (data (i32.const 32) \"abc\")\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$base i32)\n" +
                            "    (local \$file i32)\n" +
                            "    (call \$get_directories (i32.const 48))\n" +
                            "    (local.set \$base\n" +
                            "      (i32.load (i32.load (i32.const 48))))\n" +
                            "    (call \$open_at\n" +
                            "      (local.get \$base)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 16)\n" +
                            "      (i32.const 9)\n" +
                            "      (i32.const 9)\n" +
                            "      (i32.const 2)\n" +
                            "      (i32.const 64))\n" +
                            "    (local.set \$file (i32.load (i32.const 68)))\n" +
                            "    (call \$write\n" +
                            "      (local.get \$file)\n" +
                            "      (i32.const 32)\n" +
                            "      (i32.const 3)\n" +
                            "      (i64.const 0)\n" +
                            "      (i32.const 80))\n" +
                            "    (i32.wrap_i64 (i64.load (i32.const 88))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()

        assertEquals(3L, plugin.call("api.run"))
        assertEquals("abc", Files.readString(tempDir.resolve("probe.txt")))
    }

    @Test
    fun linksTerminalHandlesWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-terminal;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:cli/terminal-stdout@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:cli@0.2.11 {\n" +
                    "  interface terminal-output {\n" +
                    "    resource terminal-output;\n" +
                    "  }\n" +
                    "  interface terminal-stdout {\n" +
                    "    use terminal-output.{terminal-output};\n" +
                    "    get-terminal-stdout: func() -> option<terminal-output>;\n" +
                    "  }\n" +
                    "}\n"
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/terminal-stdout@0.2.11\"" +
                            " \"get-terminal-stdout\" (func \$terminal_stdout" +
                            " (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (func \$run (result i32)\n" +
                            "    (call \$terminal_stdout (i32.const 32))\n" +
                            "    (if (result i32)\n" +
                            "      (i32.eq (i32.load8_u (i32.const 32))" +
                            " (i32.const 1))\n" +
                            "      (then (i32.load (i32.const 36)))\n" +
                            "      (else (i32.const 0))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(WasiPreview2.builder().withTerminalStdout(true).build())
                .build()

        assertTrue((plugin.call("api.run") as Long) > 0L)
    }

    @Test
    fun linksSocketsNetworkAndTcpHandlesWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-sockets;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:sockets/instance-network@0.2.11;\n" +
                    "  import wasi:sockets/network@0.2.11;\n" +
                    "  import wasi:sockets/tcp-create-socket@0.2.11;\n" +
                    "  import wasi:sockets/tcp@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:sockets@0.2.11 {\n" +
                    "  interface network {\n" +
                    "    resource network;\n" +
                    "    enum error-code {\n" +
                    "      unknown,\n" +
                    "      access-denied,\n" +
                    "      not-supported,\n" +
                    "      invalid-argument,\n" +
                    "      new-socket-limit,\n" +
                    "    }\n" +
                    "    enum ip-address-family { ipv4, ipv6 }\n" +
                    "  }\n" +
                    "  interface instance-network {\n" +
                    "    use network.{network};\n" +
                    "    instance-network: func() -> network;\n" +
                    "  }\n" +
                    "  interface tcp {\n" +
                    "    use network.{ip-address-family, error-code};\n" +
                    "    resource tcp-socket {\n" +
                    "      address-family: func() -> ip-address-family;\n" +
                    "    }\n" +
                    "  }\n" +
                    "  interface tcp-create-socket {\n" +
                    "    use network.{error-code, ip-address-family};\n" +
                    "    use tcp.{tcp-socket};\n" +
                    "    create-tcp-socket: func(address-family: ip-address-family)" +
                    " -> result<tcp-socket, error-code>;\n" +
                    "  }\n" +
                    "}\n"
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:sockets/instance-network@0.2.11\"" +
                            " \"instance-network\" (func \$network (result" +
                            " i32)))\n" +
                            "  (import \"wasi:sockets/tcp-create-socket@0.2.11\"" +
                            " \"create-tcp-socket\" (func \$create_tcp (param i32)" +
                            " (param i32)))\n" +
                            "  (import \"wasi:sockets/tcp@0.2.11\"" +
                            " \"[method]tcp-socket.address-family\" (func \$family" +
                            " (param i32) (result i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (func \$run (result i32)\n" +
                            "    (drop (call \$network))\n" +
                            "    (call \$create_tcp (i32.const 0) (i32.const" +
                            " 32))\n" +
                            "    (if (result i32)\n" +
                            "      (i32.eq (i32.load8_u (i32.const 32))" +
                            " (i32.const 0))\n" +
                            "      (then (call \$family (i32.load (i32.const" +
                            " 36))))\n" +
                            "      (else (i32.const 99))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(WasiPreview2.builder().withNetworking().build())
                .build()

        assertEquals(0L, plugin.call("api.run"))
    }

    @Test
    fun linksHttpFieldsWithoutJson() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-http;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:http/types@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:http@0.2.11 {\n" +
                    "  interface types {\n" +
                    "    variant header-error {\n" +
                    "      invalid-syntax,\n" +
                    "      forbidden,\n" +
                    "      immutable,\n" +
                    "    }\n" +
                    "    type field-name = string;\n" +
                    "    type field-value = list<u8>;\n" +
                    "    resource fields {\n" +
                    "      constructor();\n" +
                    "      append: func(name: field-name, value: field-value)" +
                    " -> result<_, header-error>;\n" +
                    "      has: func(name: field-name) -> bool;\n" +
                    "    }\n" +
                    "  }\n" +
                    "}\n"
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:http/types@0.2.11\"" +
                            " \"[constructor]fields\" (func \$fields_new" +
                            " (result i32)))\n" +
                            "  (import \"wasi:http/types@0.2.11\"" +
                            " \"[method]fields.append\" (func \$append" +
                            " (param i32) (param i32) (param i32) (param" +
                            " i32) (param i32) (param i32)))\n" +
                            "  (import \"wasi:http/types@0.2.11\"" +
                            " \"[method]fields.has\" (func \$has (param i32)" +
                            " (param i32) (param i32) (result i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (data (i32.const 16) \"x-test\")\n" +
                            "  (data (i32.const 32) \"ok\")\n" +
                            "  (func \$run (result i32)\n" +
                            "    (local \$fields i32)\n" +
                            "    (local.set \$fields (call \$fields_new))\n" +
                            "    (call \$append\n" +
                            "      (local.get \$fields)\n" +
                            "      (i32.const 16)\n" +
                            "      (i32.const 6)\n" +
                            "      (i32.const 32)\n" +
                            "      (i32.const 2)\n" +
                            "      (i32.const 48))\n" +
                            "    (if (result i32)\n" +
                            "      (i32.eq (i32.load8_u (i32.const 48))" +
                            " (i32.const 0))\n" +
                            "      (then (call \$has (local.get \$fields)" +
                            " (i32.const 16) (i32.const 6)))\n" +
                            "      (else (i32.const 99))))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(WasiPreview2.builder().build())
                .build()

        assertEquals(1L, plugin.call("api.run"))
    }

    @Test
    fun linksHttpOutgoingHandlerWithoutJson() {
        val requestLine = AtomicReference<String?>()
        val serverFailure = AtomicReference<Throwable?>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val serverThread =
                Thread(
                    {
                        try {
                            server.accept().use { socket ->
                                val reader =
                                    BufferedReader(
                                        InputStreamReader(
                                            socket.getInputStream(),
                                            StandardCharsets.ISO_8859_1,
                                        )
                                    )
                                requestLine.set(reader.readLine())
                                while (true) {
                                    val line = reader.readLine()
                                    if (line == null || line.isEmpty()) {
                                        break
                                    }
                                    // Drain headers before writing the response.
                                }
                                socket
                                    .getOutputStream()
                                    .write(
                                        ("HTTP/1.1 204 No Content\r\n" +
                                                "Content-Length: 0\r\n" +
                                                "Connection: close\r\n" +
                                                "\r\n")
                                            .toByteArray(StandardCharsets.ISO_8859_1)
                                    )
                            }
                        } catch (e: Throwable) {
                            serverFailure.set(e)
                        }
                    },
                    "wasi-http-outgoing-test",
                )
            serverThread.setDaemon(true)
            serverThread.start()

            val authority = "127.0.0.1:" + server.getLocalPort()
            val pathWithQuery = "/probe?x=kotlin"
            val witPackage =
                WitPackage.parse(
                    "package example:wasi-http-outgoing;\n" +
                        "interface api {\n" +
                        "  run: func() -> u32;\n" +
                        "}\n" +
                        "world plugin {\n" +
                        "  import wasi:http/types@0.2.11;\n" +
                        "  import wasi:http/outgoing-handler@0.2.11;\n" +
                        "  export api;\n" +
                        "}\n" +
                        "package wasi:http@0.2.11 {\n" +
                        "  interface types {\n" +
                        "    variant error-code {\n" +
                        "      HTTP-request-denied,\n" +
                        "      HTTP-request-URI-invalid,\n" +
                        "      internal-error(option<string>),\n" +
                        "    }\n" +
                        "    resource fields {\n" +
                        "      constructor();\n" +
                        "    }\n" +
                        "    resource outgoing-request {\n" +
                        "      constructor(headers: fields);\n" +
                        "      set-authority: func(authority: option<string>)" +
                        " -> result<_, error-code>;\n" +
                        "      set-path-with-query: func(path-with-query:" +
                        " option<string>) -> result<_, error-code>;\n" +
                        "    }\n" +
                        "    resource request-options;\n" +
                        "    resource future-incoming-response;\n" +
                        "  }\n" +
                        "  interface outgoing-handler {\n" +
                        "    use types.{outgoing-request, request-options," +
                        " future-incoming-response, error-code};\n" +
                        "    handle: func(\n" +
                        "      request: outgoing-request,\n" +
                        "      options: option<borrow<request-options>>,\n" +
                        "    ) -> result<future-incoming-response, error-code>;\n" +
                        "  }\n" +
                        "}\n"
                )
            val plugin =
                WasmPlugin.builder(witPackage)
                    .withModule(
                        Wat2Wasm.parse(
                            "(module\n" +
                                "  (import \"wasi:http/types@0.2.11\"" +
                                " \"[constructor]fields\" (func \$fields_new" +
                                " (result i32)))\n" +
                                "  (import \"wasi:http/types@0.2.11\"" +
                                " \"[constructor]outgoing-request\" (func" +
                                " \$request_new (param i32) (result i32)))\n" +
                                "  (import \"wasi:http/types@0.2.11\"" +
                                " \"[method]outgoing-request.set-authority\"" +
                                " (func \$set_authority (param i32) (param i32)" +
                                " (param i32) (param i32) (param i32)))\n" +
                                "  (import \"wasi:http/types@0.2.11\"" +
                                " \"[method]outgoing-request.set-path-with-query\"" +
                                " (func \$set_path (param i32) (param i32) (param" +
                                " i32) (param i32) (param i32)))\n" +
                                "  (import \"wasi:http/outgoing-handler@0.2.11\"" +
                                " \"handle\" (func \$handle (param i32) (param" +
                                " i32) (param i32) (param i32)))\n" +
                                "  (memory (export \"memory\") 1)\n" +
                                "  (data (i32.const 16) \"" +
                                authority +
                                "\")\n" +
                                "  (data (i32.const 64) \"" +
                                pathWithQuery +
                                "\")\n" +
                                "  (func \$run (result i32)\n" +
                                "    (local \$request i32)\n" +
                                "    (local.set \$request\n" +
                                "      (call \$request_new (call" +
                                " \$fields_new)))\n" +
                                "    (call \$set_authority\n" +
                                "      (local.get \$request)\n" +
                                "      (i32.const 1)\n" +
                                "      (i32.const 16)\n" +
                                "      (i32.const " +
                                authority.length +
                                ")\n" +
                                "      (i32.const 128))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const" +
                                " 128)) (i32.const 0))\n" +
                                "      (then (return (i32.const 98))))\n" +
                                "    (call \$set_path\n" +
                                "      (local.get \$request)\n" +
                                "      (i32.const 1)\n" +
                                "      (i32.const 64)\n" +
                                "      (i32.const " +
                                pathWithQuery.length +
                                ")\n" +
                                "      (i32.const 144))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const" +
                                " 144)) (i32.const 0))\n" +
                                "      (then (return (i32.const 97))))\n" +
                                "    (call \$handle\n" +
                                "      (local.get \$request)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 0)\n" +
                                "      (i32.const 160))\n" +
                                "    (if (i32.ne (i32.load8_u (i32.const" +
                                " 160)) (i32.const 0))\n" +
                                "      (then (return (i32.const 99))))\n" +
                                "    (i32.load (i32.const 164)))\n" +
                                "  (export \"api.run\" (func \$run))\n" +
                                ")\n"
                        )
                    )
                    .withWasiPreview2(WasiPreview2.builder().withNetworking().build())
                    .build()

            assertTrue((plugin.call("api.run") as Long) > 0L)
            serverThread.join(2_000L)
        }

        if (serverFailure.get() != null) {
            throw AssertionError("HTTP test server failed", serverFailure.get())
        }
        assertEquals("GET /probe?x=kotlin HTTP/1.1", requestLine.get())
    }

    @Test
    fun linksHttpProxyWorldWithoutJson() {
        val pathWithQuery = "/plugin?name=kotlin"
        val witPackage =
            WitPackage.parse(
                "package example:wasi-http-proxy;\n" +
                    "world plugin {\n" +
                    "  include wasi:http/proxy@0.2.9;\n" +
                    "  import wasi:cli/stdout@0.2.9;\n" +
                    "}\n" +
                    "package wasi:cli@0.2.9 {\n" +
                    "  interface stdout {}\n" +
                    "}\n" +
                    "package wasi:http@0.2.9 {\n" +
                    "  world proxy {\n" +
                    "    export incoming-handler;\n" +
                    "  }\n" +
                    "  interface types {\n" +
                    "    variant error-code {\n" +
                    "      internal-error(option<string>),\n" +
                    "    }\n" +
                    "    variant header-error {\n" +
                    "      invalid-syntax,\n" +
                    "      forbidden,\n" +
                    "      immutable,\n" +
                    "    }\n" +
                    "    resource fields {\n" +
                    "      constructor();\n" +
                    "      append: func(name: string, value: list<u8>)" +
                    " -> result<_, header-error>;\n" +
                    "    }\n" +
                    "    resource incoming-request {\n" +
                    "      path-with-query: func() -> option<string>;\n" +
                    "    }\n" +
                    "    resource outgoing-response {\n" +
                    "      constructor(headers: fields);\n" +
                    "    }\n" +
                    "    resource response-outparam {\n" +
                    "      set: static func(\n" +
                    "        param: response-outparam,\n" +
                    "        response: result<outgoing-response, error-code>,\n" +
                    "      );\n" +
                    "    }\n" +
                    "  }\n" +
                    "  interface incoming-handler {\n" +
                    "    use types.{incoming-request, response-outparam};\n" +
                    "    handle: func(\n" +
                    "      request: incoming-request,\n" +
                    "      response-out: response-outparam,\n" +
                    "    );\n" +
                    "  }\n" +
                    "}\n"
            )
        val wasi = WasiPreview2.builder().build()
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:http/types@0.2.9\"" +
                            " \"[method]incoming-request.path-with-query\" (func" +
                            " \$path (param i32) (param i32)))\n" +
                            "  (import \"wasi:http/types@0.2.9\"" +
                            " \"[constructor]fields\" (func \$fields_new (result" +
                            " i32)))\n" +
                            "  (import \"wasi:http/types@0.2.9\"" +
                            " \"[method]fields.append\" (func \$append (param i32)" +
                            " (param i32) (param i32) (param i32) (param i32)" +
                            " (param i32)))\n" +
                            "  (import \"wasi:http/types@0.2.9\"" +
                            " \"[constructor]outgoing-response\" (func" +
                            " \$response_new (param i32) (result i32)))\n" +
                            "  (import \"wasi:http/types@0.2.9\"" +
                            " \"[static]response-outparam.set\" (func \$set (param" +
                            " i32) (param i32) (param i32) (param i32) (param" +
                            " i32) (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (global \$heap (mut i32) (i32.const 128))\n" +
                            "  (func (export \"canonical_abi_realloc\")\n" +
                            "    (param \$old i32) (param \$old_size i32)\n" +
                            "    (param \$align i32) (param \$new_size i32)\n" +
                            "    (result i32)\n" +
                            "    (local \$ptr i32)\n" +
                            "    (local.set \$ptr (global.get \$heap))\n" +
                            "    (global.set \$heap\n" +
                            "      (i32.add (global.get \$heap) (local.get" +
                            " \$new_size)))\n" +
                            "    (local.get \$ptr))\n" +
                            "  (data (i32.const 16) \"x-plugin\")\n" +
                            "  (data (i32.const 32) \"ok\")\n" +
                            "  (func \$handle (param \$request i32) (param \$out" +
                            " i32)\n" +
                            "    (local \$fields i32)\n" +
                            "    (local \$response i32)\n" +
                            "    (call \$path (local.get \$request) (i32.const" +
                            " 64))\n" +
                            "    (if\n" +
                            "      (i32.or\n" +
                            "        (i32.ne (i32.load8_u (i32.const 64))" +
                            " (i32.const 1))\n" +
                            "        (i32.ne (i32.load (i32.const 72)) (i32.const" +
                            " " +
                            pathWithQuery.length +
                            ")))\n" +
                            "      (then return))\n" +
                            "    (local.set \$fields (call \$fields_new))\n" +
                            "    (call \$append\n" +
                            "      (local.get \$fields)\n" +
                            "      (i32.const 16)\n" +
                            "      (i32.const 8)\n" +
                            "      (i32.const 32)\n" +
                            "      (i32.const 2)\n" +
                            "      (i32.const 96))\n" +
                            "    (if (i32.ne (i32.load8_u (i32.const 96))" +
                            " (i32.const 0))\n" +
                            "      (then return))\n" +
                            "    (local.set \$response\n" +
                            "      (call \$response_new (local.get" +
                            " \$fields)))\n" +
                            "    (call \$set\n" +
                            "      (local.get \$out)\n" +
                            "      (i32.const 0)\n" +
                            "      (local.get \$response)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)\n" +
                            "      (i32.const 0)))\n" +
                            "  (export" +
                            " \"wasi:http/incoming-handler@0.2.9.handle\"" +
                            " (func \$handle))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(wasi)
                .build()
        val response =
            wasi.handleHttpRequest(
                plugin,
                "GET",
                pathWithQuery,
                "http",
                "localhost",
                mapOf<String, List<ByteArray>>(),
                ByteArray(0),
            )

        assertEquals(200, response.statusCode())
        assertTrue(response.bodyFinished())
        assertArrayEquals(ByteArray(0), response.body())
        assertArrayEquals(
            "ok".toByteArray(StandardCharsets.ISO_8859_1),
            response.headers()["x-plugin"]!![0],
        )
    }

    @Test
    fun exposesPreview2ExitAsTypedException() {
        val witPackage =
            WitPackage.parse(
                "package example:wasi-exit;\n" +
                    "interface api {\n" +
                    "  run: func() -> u32;\n" +
                    "}\n" +
                    "world plugin {\n" +
                    "  import wasi:cli/exit@0.2.11;\n" +
                    "  export api;\n" +
                    "}\n" +
                    "package wasi:cli@0.2.11 {\n" +
                    "  interface exit {\n" +
                    "    exit: func(status: result);\n" +
                    "  }\n" +
                    "}\n"
            )
        val plugin =
            WasmPlugin.builder(witPackage)
                .withModule(
                    Wat2Wasm.parse(
                        "(module\n" +
                            "  (import \"wasi:cli/exit@0.2.11\"" +
                            " \"exit\"" +
                            " (func \$exit (param i32)))\n" +
                            "  (memory (export \"memory\") 1)\n" +
                            "  (func \$run (result i32)\n" +
                            "    (call \$exit (i32.const 1))\n" +
                            "    (i32.const 0))\n" +
                            "  (export \"api.run\" (func \$run))\n" +
                            ")\n"
                    )
                )
                .withWasiPreview2(WasiPreview2.builder().build())
                .build()

        val exit = assertThrows(WasiPreview2.ExitException::class.java) { plugin.call("api.run") }

        assertEquals(1, exit.statusCode())
    }

    private fun failingInput(message: String): RawSource = failingInput(IOException(message))

    private fun failingInput(failure: IOException): RawSource =
        object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                throw failure
            }

            override fun close() {}
        }
}

private fun Buffer.writeUtf8(text: String): Buffer {
    write(text.encodeToByteArray())
    return this
}

private fun Buffer.readUtf8(): String = readByteArray().decodeToString()
