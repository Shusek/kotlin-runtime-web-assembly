package uk.shusek.krwa.testgen

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import net.lingala.zip4j.ZipFile

open class TestSuiteDownloader {
    @Throws(IOException::class)
    open fun downloadTestsuite(
        testSuiteRepo: String,
        testSuiteRepoRef: String,
        testSuiteFolder: File,
    ) {
        if (
            testSuiteFolder.exists() &&
                testSuiteFolder.list { _, name -> name.endsWith(".wast") }?.isEmpty() == true
        ) {
            Files.walk(testSuiteFolder.toPath()).use { files ->
                files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete)
            }
        }

        if (!testSuiteFolder.exists()) {
            val parent = testSuiteFolder.parentFile ?: File(".")
            parent.mkdirs()
            val archiveName = "${testSuiteRepoRef.replace('/', '-')}.zip"
            val archive = File(parent, archiveName)
            val url = URI.create("$testSuiteRepo/archive/$testSuiteRepoRef.zip").toURL()
            val con = url.openConnection() as HttpURLConnection
            con.requestMethod = "GET"

            try {
                con.inputStream.use { input ->
                    Files.write(archive.toPath(), input.readAllBytes())
                    ZipFile(archive).use { zip ->
                        zip.renameFile("testsuite-$testSuiteRepoRef/", testSuiteFolder.name)
                        zip.extractAll(parent.absolutePath)
                    }
                }
            } finally {
                con.disconnect()
            }
        }
    }
}
