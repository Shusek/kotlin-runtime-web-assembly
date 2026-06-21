package uk.shusek.krwa.bench

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.jar.JarInputStream
import uk.shusek.krwa.compiler.Cache
import uk.shusek.krwa.compiler.MachineFactoryCompiler

fun main() {
    val outputDir =
        Paths.get(
            System.getProperty(
                "krwa.coremark.dump.dir",
                "/private/tmp/krwa-coremark-compiled-classes",
            ),
        )
    Files.createDirectories(outputDir)

    val cache = DumpingCache(outputDir)
    val builder = MachineFactoryCompiler.builder(ChasmCoremark.loadModule())
    builder.withCache(cache).compile()

    println("CoreMark compiled class dump")
    println("output_dir=$outputDir")
    println("cache_key=${cache.key}")
    println("jar=${cache.jarPath}")
    println("main_class=${cache.mainClass}")
    println("classes:")
    cache.classes.forEachIndexed { index, className ->
        println("%02d %s".format(index + 1, className))
    }
}

private class DumpingCache(private val outputDir: Path) : Cache {
    var key: String? = null
        private set
    var jarPath: Path? = null
        private set
    var mainClass: String? = null
        private set
    val classes = ArrayList<String>()

    override fun get(key: String): ByteArray? = null

    override fun putIfAbsent(key: String, data: ByteArray) {
        this.key = key
        val jar = outputDir.resolve("coremark-compiled-classes.jar")
        Files.write(jar, data)
        jarPath = jar
        extractJar(data)
    }

    private fun extractJar(data: ByteArray) {
        JarInputStream(ByteArrayInputStream(data)).use { jar ->
            while (true) {
                val entry = jar.nextJarEntry ?: break
                if (entry.isDirectory) {
                    continue
                }

                val entryBytes = jar.readBytes()
                when {
                    entry.name == "wasm-module.properties" -> {
                        val properties = Properties()
                        properties.load(ByteArrayInputStream(entryBytes))
                        mainClass = properties.getProperty("mainClass")
                    }
                    entry.name.endsWith(".class") -> {
                        val classPath = outputDir.resolve("classes").resolve(entry.name).normalize()
                        require(classPath.startsWith(outputDir.resolve("classes").normalize())) {
                            "Unsafe jar entry path: ${entry.name}"
                        }
                        Files.createDirectories(classPath.parent)
                        Files.write(classPath, entryBytes)
                        classes += entry.name.removeSuffix(".class").replace('/', '.')
                    }
                }
            }
        }
        classes.sort()
    }
}
