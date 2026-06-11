package uk.shusek.krwa.fuzz

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Properties

object CrashReproducer {
    private val CRASH_DIR: Path = Path.of("target/crash-reproducers")

    @JvmStatic
    @Throws(IOException::class)
    fun save(
        sourceWasm: File,
        instructionType: String,
        functionName: String,
        oracleResult: String?,
        subjectResult: String?,
    ) {
        val hash = shortHash(sourceWasm.absolutePath + functionName)
        val folderName = "crash-$instructionType-$hash"
        val crashDir = CRASH_DIR.resolve(folderName)
        Files.createDirectories(crashDir)

        Files.copy(
            sourceWasm.toPath(),
            crashDir.resolve("test.wasm"),
            StandardCopyOption.REPLACE_EXISTING,
        )

        val seedFile = sourceWasm.toPath().parent.resolve("seed.txt")
        if (Files.exists(seedFile)) {
            Files.copy(seedFile, crashDir.resolve("seed.txt"), StandardCopyOption.REPLACE_EXISTING)
        }

        val props = Properties()
        props.setProperty("instructionType", instructionType)
        props.setProperty("functionName", functionName)
        props.setProperty("oracleResult", oracleResult ?: "")
        props.setProperty("subjectResult", subjectResult ?: "")
        FileOutputStream(crashDir.resolve("crash-info.properties").toFile()).use { out ->
            props.store(out, "Fuzz crash reproducer")
        }
    }

    private fun shortHash(input: String): String =
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
            val sb = StringBuilder()
            for (i in 0..<8) {
                sb.append(String.format("%02x", hashBytes[i]))
            }
            sb.toString()
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException(e)
        }
}
