package uk.shusek.krwa.testing

import java.nio.file.Files
import org.junit.jupiter.api.Test
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasi.WasiExitException
import uk.shusek.krwa.wasi.WasiOptions
import uk.shusek.krwa.wasi.WasiPreview1

class ZigTestsuiteTest {
    @Test
    @Throws(Exception::class)
    fun shouldRunZigStdlibTestsuite() {
        val target = Files.createTempDirectory("krwa-zig-testsuite-")
        try {
            Files.createDirectories(target.resolve(".zig-cache").resolve("tmp"))

            val wasiOpts =
                WasiOptions.builder()
                    .inheritSystem()
                    .withArguments(listOf("test.wasm"))
                    .withDirectory(".", target)
                    .build()
            val wasi = WasiPreview1.builder().withLogger(SystemLogger()).withOptions(wasiOpts).build()

            val instance =
                Instance.builder(ZigModule.load())
                    .withImportValues(
                        ImportValues.builder().addFunction(*wasi.toHostFunctions()).build()
                    )
                    .withMachineFactory(ZigModule::create)

            try {
                instance.build()
            } catch (e: WasiExitException) {
                if (e.exitCode() != 0) {
                    throw RuntimeException("exit with errors: " + e.exitCode())
                }
                println("Success!!!")
            }
        } finally {
            target.toFile().deleteRecursively()
        }
    }
}
