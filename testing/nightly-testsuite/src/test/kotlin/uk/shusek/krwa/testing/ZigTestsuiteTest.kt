package uk.shusek.krwa.testing

import io.roastedroot.zerofs.Configuration
import io.roastedroot.zerofs.ZeroFs
import java.util.List
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
        ZeroFs.newFileSystem(Configuration.unix().toBuilder().setAttributeViews("unix").build())
            .use { fs ->
                val target = fs.getPath(".")

                val wasiOpts =
                    WasiOptions.builder()
                        .inheritSystem()
                        .withArguments(List.of("test.wasm"))
                        .withDirectory(target.toString(), target)
                        .build()
                val wasi =
                    WasiPreview1.builder().withLogger(SystemLogger()).withOptions(wasiOpts).build()

                val instance =
                    Instance.builder(ZigModule.load())
                        .withImportValues(
                            ImportValues.builder().addFunction(wasi.toHostFunctions()).build()
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
            }
    }
}
