package uk.shusek.krwa.fuzz

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.LinkedHashMap
import org.apache.commons.lang3.RandomStringUtils
import uk.shusek.krwa.log.Logger
import uk.shusek.krwa.log.SystemLogger
import uk.shusek.krwa.tools.wasm.WasmSmith

class WasmSmithWrapper {
    private var seed = getSeed(BASE_SEED_SIZE)

    @Throws(IOException::class)
    fun run(subfolder: String, fileName: String, instructionTypes: InstructionTypes): File =
        run(subfolder, fileName, instructionTypes, "/smith.default.properties")

    @Suppress("StringSplitter")
    @Throws(IOException::class)
    fun run(
        subfolder: String,
        fileName: String,
        instructionTypes: InstructionTypes,
        smithProperties: String,
    ): File {
        val targetSubfolder = "target/fuzz/data/$subfolder"
        val targetFolder = File(targetSubfolder)
        targetFolder.mkdirs()
        val targetFile = File("$targetSubfolder/$fileName")
        val seedFile = File("$targetSubfolder/seed.txt")

        val properties = LinkedHashMap<String, String>()
        val propsFile = String(javaClass.getResourceAsStream(smithProperties)!!.readBytes(), UTF_8)
        val props = propsFile.split("\n")
        for (prop in props) {
            if (prop.isNotEmpty()) {
                val split = prop.split("=")
                properties[split[0]] = split[1]
            }
        }

        var retry = 5
        var seedSize = BASE_SEED_SIZE
        while (retry > 0) {
            FileOutputStream(seedFile).use { outputStream ->
                outputStream.write(seed.toByteArray(UTF_8))
                outputStream.flush()
            }

            logger.info(
                "Running wasm-smith with instructions=" +
                    instructionTypes +
                    " seed-size=" +
                    seed.length
            )

            try {
                val wasmBytes =
                    WasmSmith.run(seed.toByteArray(UTF_8), properties, instructionTypes.toString())

                FileOutputStream(targetFile).use { outputStream ->
                    outputStream.write(wasmBytes)
                    outputStream.flush()
                }
                return targetFile
            } catch (e: RuntimeException) {
                logger.error("wasm-smith failed: " + e.message)
                retry--
            } finally {
                seed = getSeed(seedSize)
                seedSize = minOf(seedSize * 2, 100_000)
            }
        }

        throw IOException("wasm-smith failed after 5 retries")
    }

    companion object {
        private val logger: Logger = SystemLogger()
        private const val BASE_SEED_SIZE = 1000

        @Suppress("deprecation")
        private fun getSeed(size: Int): String = RandomStringUtils.randomAlphabetic(size)
    }
}
