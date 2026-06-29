package wasi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileSystemException
import java.nio.file.Files as JFiles
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Store
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasi.WasiErrno
import uk.shusek.krwa.wasi.WasiFdFlags
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1
import uk.shusek.krwa.wasi.WasiRights
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.UnlinkableException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.ValType

@Timeout(600)
class WasiPreview1Test {
    @TempDir lateinit var tempDir: Path

    @Test
    fun shouldCreateFileUnderPreopenedDirectory() {
        JFiles.createDirectories(tempDir.resolve("modules"))
        val wasiOpts = WasiOptions.builder().withDirectory(".", tempDir).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val memory = ByteArrayMemory(MemoryLimits(1))

        val errno =
            wasi.pathOpen(
                memory,
                3,
                WASI_LOOKUPFLAGS_SYMLINK_FOLLOW,
                "modules/probe.wasm",
                WASI_OFLAGS_CREAT or WASI_OFLAGS_TRUNC,
                WASI_RIGHTS_FD_WRITE.toLong(),
                WASI_RIGHTS_FD_WRITE.toLong(),
                0,
                0,
            )

        assertEquals(WASI_ESUCCESS, errno)
        assertTrue(JFiles.exists(tempDir.resolve("modules/probe.wasm")))
    }

    @Test
    fun shouldSetPathFilestatTimesOnSymlinkOrFollowedTarget() {
        val target = tempDir.resolve("target.txt")
        val link = tempDir.resolve("link.txt")
        JFiles.writeString(target, "target", UTF_8)
        try {
            JFiles.createSymbolicLink(link, target.fileName)
            JFiles.getFileAttributeView(link, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                .setTimes(FileTime.from(1_000L, TimeUnit.MILLISECONDS), null, null)
        } catch (_: UnsupportedOperationException) {
            assumeTrue(false, "host filesystem does not support symbolic links")
        } catch (_: FileSystemException) {
            assumeTrue(false, "host filesystem does not allow symbolic link timestamp updates")
        }

        val wasiOpts = WasiOptions.builder().withDirectory(".", tempDir).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val linkMillis = 2_000L
        val targetMillis = 3_000L

        val linkErrno =
            wasi.pathFilestatSetTimes(
                3,
                0,
                "link.txt",
                0L,
                TimeUnit.MILLISECONDS.toNanos(linkMillis),
                WASI_FSTFLAGS_MTIM,
            )
        val targetErrno =
            wasi.pathFilestatSetTimes(
                3,
                WASI_LOOKUPFLAGS_SYMLINK_FOLLOW,
                "link.txt",
                0L,
                TimeUnit.MILLISECONDS.toNanos(targetMillis),
                WASI_FSTFLAGS_MTIM,
            )

        assertEquals(WASI_ESUCCESS, linkErrno)
        assertEquals(WASI_ESUCCESS, targetErrno)
        assertEquals(linkMillis, JFiles.getLastModifiedTime(link, LinkOption.NOFOLLOW_LINKS).toMillis())
        assertEquals(targetMillis, JFiles.getLastModifiedTime(target).toMillis())
    }

    @Test
    fun fdFdstatSetRightsShouldReduceOpenFileCapabilities() {
        JFiles.writeString(tempDir.resolve("hello.txt"), "hello", UTF_8)
        val wasiOpts = WasiOptions.builder().withDirectory(".", tempDir).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val memory = ByteArrayMemory(MemoryLimits(1))

        val fdPtr = 0
        var errno =
            wasi.pathOpen(
                memory,
                3,
                0,
                "hello.txt",
                0,
                (WasiRights.FD_READ or WasiRights.FD_WRITE).toLong(),
                0,
                0,
                fdPtr,
            )
        assertEquals(WasiErrno.ESUCCESS.value(), errno)
        val fd = memory.readInt(fdPtr)

        errno = wasi.fdFdstatSetRights(fd, WasiRights.FD_WRITE.toLong(), 0)
        assertEquals(WasiErrno.ESUCCESS.value(), errno)

        val fdstatPtr = 64
        errno = wasi.fdFdstatGet(memory, fd, fdstatPtr)
        assertEquals(WasiErrno.ESUCCESS.value(), errno)
        assertEquals(WasiRights.FD_WRITE.toLong(), memory.readLong(fdstatPtr + 8))
        assertEquals(0L, memory.readLong(fdstatPtr + 16))

        writeIov(memory, iovs = 128, dataPtr = 256, data = ByteArray(5))
        errno = wasi.fdRead(memory, fd, 128, 1, 384)
        assertEquals(WasiErrno.ENOTCAPABLE.value(), errno)

        writeIov(memory, iovs = 128, dataPtr = 256, data = "!".toByteArray(UTF_8))
        errno = wasi.fdWrite(memory, fd, 128, 1, 384)
        assertEquals(WasiErrno.ESUCCESS.value(), errno)
        assertEquals(1, memory.readInt(384))

        errno =
            wasi.fdFdstatSetRights(
                fd,
                (WasiRights.FD_READ or WasiRights.FD_WRITE).toLong(),
                0,
            )
        assertEquals(WasiErrno.ENOTCAPABLE.value(), errno)
    }

    @Test
    fun fdFdstatSetRightsShouldLimitPreopenPathOpenCapability() {
        JFiles.writeString(tempDir.resolve("hello.txt"), "hello", UTF_8)
        val wasiOpts = WasiOptions.builder().withDirectory(".", tempDir).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val memory = ByteArrayMemory(MemoryLimits(1))

        val withoutPathOpen =
            WasiRights.DIRECTORY_RIGHTS_BASE.toLong() and WasiRights.PATH_OPEN.toLong().inv()
        var errno = wasi.fdFdstatSetRights(3, withoutPathOpen, 0)
        assertEquals(WasiErrno.ESUCCESS.value(), errno)

        val fdstatPtr = 0
        errno = wasi.fdFdstatGet(memory, 3, fdstatPtr)
        assertEquals(WasiErrno.ESUCCESS.value(), errno)
        assertEquals(withoutPathOpen, memory.readLong(fdstatPtr + 8))
        assertEquals(0L, memory.readLong(fdstatPtr + 16))

        errno = wasi.pathOpen(memory, 3, 0, "hello.txt", 0, 0, 0, 0, 128)
        assertEquals(WasiErrno.ENOTCAPABLE.value(), errno)

        errno =
            wasi.fdFdstatSetRights(
                3,
                WasiRights.DIRECTORY_RIGHTS_BASE.toLong(),
                WasiRights.FILE_RIGHTS_BASE.toLong(),
            )
        assertEquals(WasiErrno.ENOTCAPABLE.value(), errno)
    }

    @Test
    fun fdFdstatSetFlagsShouldUpdateOpenFileFlags() {
        JFiles.writeString(tempDir.resolve("append.txt"), "first", UTF_8)
        val wasiOpts = WasiOptions.builder().withDirectory(".", tempDir).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val memory = ByteArrayMemory(MemoryLimits(1))

        val fdPtr = 0
        var errno =
            wasi.pathOpen(
                memory,
                3,
                0,
                "append.txt",
                0,
                WasiRights.FD_WRITE.toLong(),
                0,
                0,
                fdPtr,
            )
        assertEquals(WasiErrno.ESUCCESS.value(), errno)
        val fd = memory.readInt(fdPtr)

        errno = wasi.fdFdstatSetFlags(fd, WasiFdFlags.APPEND)
        assertEquals(WasiErrno.ESUCCESS.value(), errno)

        val fdstatPtr = 64
        errno = wasi.fdFdstatGet(memory, fd, fdstatPtr)
        assertEquals(WasiErrno.ESUCCESS.value(), errno)
        assertEquals(WasiFdFlags.APPEND, memory.readShort(fdstatPtr + 2).toInt())

        writeIov(memory, iovs = 128, dataPtr = 256, data = "-second".toByteArray(UTF_8))
        errno = wasi.fdWrite(memory, fd, 128, 1, 384)
        assertEquals(WasiErrno.ESUCCESS.value(), errno)
        assertEquals("first-second", JFiles.readString(tempDir.resolve("append.txt"), UTF_8))
    }

    @Test
    fun shouldRunWasiModule() {
        val fakeStdout = MockPrintStream()
        val wasi =
            WasiPreview1.builder()
                .withOptions(WasiOptions.builder().withStdout(fakeStdout).build())
                .build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
        Instance.builder(loadModule("compiled/hello-wasi.wat.wasm"))
            .withImportValues(imports)
            .build()
        assertEquals("hello world", fakeStdout.output().trim())
    }

    @Test
    fun shouldRunWasiRustModule() {
        val expected = "Hello, World!"
        val stdout = MockPrintStream()
        val wasi =
            WasiPreview1.builder()
                .withOptions(WasiOptions.builder().withStdout(stdout).build())
                .build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
        Instance.builder(loadModule("compiled/hello-wasi.rs.wasm"))
            .withImportValues(imports)
            .build()
        assertEquals(expected, stdout.output().trim())
    }

    @Test
    fun shouldRunWasiGreetRustModule() {
        val fakeStdin = ByteArrayInputStream("Benjamin".toByteArray(UTF_8))
        val fakeStdout = MockPrintStream()
        val wasiOpts = WasiOptions.builder().withStdout(fakeStdout).withStdin(fakeStdin).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
        Instance.builder(loadModule("compiled/greet-wasi.rs.wasm"))
            .withImportValues(imports)
            .build()
        assertEquals("Hello, Benjamin!", fakeStdout.output().trim())
    }

    @Test
    fun shouldRunWasiGreetRustModuleWithKotlinxIoStdio() {
        val stdin = Buffer()
        stdin.write("Benjamin".encodeToByteArray())
        val stdout = Buffer()
        val wasiOpts = WasiOptions.builder().withStdout(stdout).withStdin(stdin).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
        Instance.builder(loadModule("compiled/greet-wasi.rs.wasm"))
            .withImportValues(imports)
            .build()
        assertEquals("Hello, Benjamin!", stdout.readByteArray().decodeToString().trim())
    }

    @Test
    fun shouldRunWasiDemoJavyModule() {
        val fakeStdin = ByteArrayInputStream("{ \"n\": 2, \"bar\": \"baz\"}".toByteArray(UTF_8))
        val fakeStdout = MockPrintStream()
        val wasiOpts = WasiOptions.builder().withStdout(fakeStdout).withStdin(fakeStdin).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
        Instance.builder(loadModule("compiled/javy-demo.js.javy.wasm"))
            .withImportValues(imports)
            .build()

        assertEquals("{\"foo\":3,\"newBar\":\"baz!\"}", fakeStdout.output())
    }

    @Test
    fun shouldUseQuickJsProvider() {
        val stdin = ByteArrayInputStream("".toByteArray(UTF_8))
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val wasiOpts =
            WasiOptions.builder().withStdout(stdout).withStderr(stderr).withStdin(stdin).build()

        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val quickjs =
            Instance.builder(loadModule("compiled/quickjs-provider.javy-dynamic.wasm"))
                .withImportValues(
                    ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                )
                .build()

        val greetingMsg = "Hello QuickJS!"

        val jsCode = "console.log(\"$greetingMsg\");".toByteArray(UTF_8)
        val ptr = quickjs.export("canonical_abi_realloc").apply(0L, 0L, 1L, jsCode.size.toLong())[0]

        quickjs.memory().write(ptr.toInt(), jsCode)
        val aggregatedCodePtr = quickjs.export("compile_src").apply(ptr, jsCode.size.toLong())[0]

        val codePtr = quickjs.memory().readI32(aggregatedCodePtr.toInt())
        val codeLength = quickjs.memory().readU32(aggregatedCodePtr.toInt() + 4)

        quickjs.export("eval_bytecode").apply(codePtr, codeLength)

        assertEquals("$greetingMsg\n", String(stderr.toByteArray(), UTF_8))
    }

    @Test
    fun shouldRejectDynamicallyLinkedJavyModulesWithImportedMemory() {
        val stdin = ByteArrayInputStream("".toByteArray(UTF_8))
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val wasiOpts =
            WasiOptions.builder().withStdout(stdout).withStderr(stderr).withStdin(stdin).build()

        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val quickjs =
            Instance.builder(loadModule("compiled/quickjs-provider.javy-dynamic.wasm"))
                .withImportValues(
                    ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                )
                .build()

        val store = Store()
        store.register("javy_quickjs_provider_v1", quickjs)
        store.addMemory(
            ImportMemory(
                "javy_quickjs_provider_v1",
                "memory",
                ByteArrayMemory(MemoryLimits(1)),
            )
        )

        val exception =
            assertThrows(UnlinkableException::class.java) {
                Instance.builder(loadModule("compiled/hello-world.js.javy-dynamic.wasm"))
                    .withImportValues(store.toImportValues())
                    .build()
            }

        assertTrue(exception.message!!.contains("function imports only"))
        assertEquals("", String(stderr.toByteArray(), UTF_8))
    }

    @Test
    fun shouldRunTinyGoModule() {
        val wasiOpts = WasiOptions.builder().build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
        val module = loadModule("compiled/sum.go.tiny.wasm")
        val instance = Instance.builder(module).withImportValues(imports).build()
        val sum = instance.export("add")
        val result = sum.apply(20L, 22L)[0]

        assertEquals(42L, result)
    }

    @Test
    fun shouldRunWasiGoModule() {
        val fakeStdout = MockPrintStream()
        val wasiOpts = WasiOptions.builder().withStdout(fakeStdout).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
        val module = loadModule("compiled/main.go.wasm")
        val exit =
            assertThrows(WasiExitException::class.java) {
                Instance.builder(module).withImportValues(imports).build()
            }
        assertEquals(0, exit.exitCode())
        assertEquals("Hello, WebAssembly!\n", fakeStdout.output())
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    fun shouldRunWasiDemoDotnetModule() {
        val fakeStdout = MockPrintStream()
        val wasiOpts =
            WasiOptions.builder().withStdout(fakeStdout).withArguments(listOf("")).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()

        val module = loadModule("compiled/basic.dotnet.wasm")
        Instance.builder(module).withImportValues(imports).build()

        assertEquals("Hello, Wasi Console!\n", fakeStdout.output())
    }

    @Test
    fun shouldRunWasiSwiftModule() {
        val fakeStdout = MockPrintStream()
        val wasiOpts = WasiOptions.builder().withStdout(fakeStdout).build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val imports = ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()

        val module = loadModule("compiled/hello-world.swift.wasm")
        Instance.builder(module).withImportValues(imports).build()

        assertEquals("Hello, Swift world!\n", fakeStdout.output())
    }

    @Test
    fun shouldRunWasiSwiftModuleWithImportExport() {
        val wasiOpts = WasiOptions.builder().build()
        val wasi = WasiPreview1.builder().withOptions(wasiOpts).build()
        val imports =
            ImportValues.builder()
                .addFunction(*wasi.toHostFunctions())
                .addFunction(
                    HostFunction(
                        "env",
                        "operation",
                        FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32)),
                        WasmFunctionHandle { _, args ->
                            val x = args[0]
                            val y = args[1]

                            longArrayOf(x * y)
                        },
                    )
                )
                .build()

        val module = loadModule("compiled/calculator.swift.wasm")
        val instance = Instance.builder(module).withImportValues(imports).build()

        val result = instance.exports().function("run").apply(2L, 3L)[0].toInt()

        assertEquals(6, result)
    }

    companion object {
        private const val WASI_ESUCCESS = 0
        private const val WASI_LOOKUPFLAGS_SYMLINK_FOLLOW = 1
        private const val WASI_FSTFLAGS_MTIM = 4
        private const val WASI_OFLAGS_CREAT = 1
        private const val WASI_OFLAGS_TRUNC = 1 shl 3
        private const val WASI_RIGHTS_FD_WRITE = 1 shl 6

        private fun writeIov(memory: ByteArrayMemory, iovs: Int, dataPtr: Int, data: ByteArray) {
            memory.write(dataPtr, data)
            memory.writeI32(iovs, dataPtr)
            memory.writeI32(iovs + 4, data.size)
        }

        private fun loadModule(fileName: String): WasmModule =
            Parser.parse(CorpusResources.getResource(fileName))
    }
}
