package uk.shusek.krwa.compiler

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UncheckedIOException
import java.util.HashMap
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import uk.shusek.krwa.compiler.internal.ClassLoadingCollector
import uk.shusek.krwa.compiler.internal.MachineFactory
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmModule

/**
 * Compiles WASM function bodies to JVM byte code that can be used as a machine factory for
 * [Instance]s.
 */
object MachineFactoryCompiler {
    /**
     * The compile method reference can be used as machine factory in instance builders.
     *
     * Every instance created by the builder will pay the cost of compiling the module.
     *
     * @see compile If you want to compile the module only once for multiple instances.
     */
    @JvmStatic fun compile(instance: Instance): Machine = compile(instance.module())(instance)

    /**
     * Compiles a machine factory that can used in instance builders. The module is only compiled
     * once and the machine factory is reused for every instance created by the builder.
     */
    @JvmStatic fun compile(module: WasmModule): (Instance) -> Machine = MachineFactory(module)

    /**
     * Configures a compiler that can compile a machine factory that can used in instance builders.
     * The builder allows configuring the compiler options used to compile the module to byte code.
     */
    @JvmStatic fun builder(module: WasmModule): Builder = Builder(module)

    class Builder internal constructor(private val module: WasmModule) {
        private val compilerBuilder = uk.shusek.krwa.compiler.internal.Compiler.builder(module)
        private var cache: Cache? = null

        fun withClassName(className: String): Builder {
            compilerBuilder.withClassName(className)
            return this
        }

        fun withMaxFunctionsPerClass(maxFunctionsPerClass: Int): Builder {
            compilerBuilder.withMaxFunctionsPerClass(maxFunctionsPerClass)
            return this
        }

        fun withInterpreterFallback(interpreterFallback: InterpreterFallback): Builder {
            compilerBuilder.withInterpreterFallback(interpreterFallback)
            return this
        }

        fun withInterpretedFunctions(interpretedFunctions: Set<Int>): Builder {
            compilerBuilder.withInterpretedFunctions(interpretedFunctions)
            return this
        }

        fun withCache(cache: Cache): Builder {
            this.cache = cache
            return this
        }

        fun compile(): (Instance) -> Machine =
            try {
                val cache = cache
                val digest = module.digest()

                // Can we load the byte codes from the cache?
                if (cache != null && digest != null) {
                    val cachedData = cache.get(digest)
                    if (cachedData != null) {
                        val collector = loadClassLoadingCollector(cachedData)
                        return MachineFactory(module, collector.machineFactory())
                    }
                }

                // Compile the byte codes.
                val result =
                    compilerBuilder
                        .withClassCollectorFactory { ClassLoadingCollector() }
                        .build()
                        .compile()
                val collector = result.collector() as ClassLoadingCollector

                if (cache != null && digest != null) {
                    // Store results in the cache to speed the next time.
                    cache.putIfAbsent(digest, storeClassLoadingCollector(collector))
                }

                MachineFactory(module, collector.machineFactory())
            } catch (e: IOException) {
                throw WasmEngineException(e)
            }
    }

    private fun storeClassLoadingCollector(collector: ClassLoadingCollector): ByteArray =
        try {
            val baos = ByteArrayOutputStream()
            JarOutputStream(baos).use { jos ->
                val properties = Properties()
                properties["mainClass"] = collector.mainClassName()
                val propsBaos = ByteArrayOutputStream()
                properties.store(propsBaos, "")

                val propsEntry = JarEntry("wasm-module.properties")
                jos.putNextEntry(propsEntry)
                jos.write(propsBaos.toByteArray())
                jos.closeEntry()

                for ((name, bytes) in collector.classBytes()) {
                    val className = name.replace('.', '/') + ".class"
                    val classEntry = JarEntry(className)
                    jos.putNextEntry(classEntry)
                    jos.write(bytes)
                    jos.closeEntry()
                }
            }
            baos.toByteArray()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }

    @Throws(IOException::class)
    private fun loadClassLoadingCollector(jarData: ByteArray): ClassLoadingCollector {
        val collector = ClassLoadingCollector()

        // It was previously compiled, just load it from JAR.
        JarInputStream(ByteArrayInputStream(jarData)).use { jis ->
            val properties = Properties()
            var mainClass: String? = null
            val classes = HashMap<String, ByteArray>()

            while (true) {
                val entry = jis.nextJarEntry ?: break
                if (entry.name == "wasm-module.properties") {
                    properties.load(jis)
                    mainClass = properties.getProperty("mainClass")
                } else if (entry.name.endsWith(".class")) {
                    val className = entry.name.replace('/', '.').replace(".class", "")
                    val baos = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val bytesRead = jis.read(buffer)
                        if (bytesRead == -1) {
                            break
                        }
                        baos.write(buffer, 0, bytesRead)
                    }
                    classes[className] = baos.toByteArray()
                }
                jis.closeEntry()
            }

            for ((className, bytes) in classes) {
                if (className == mainClass) {
                    collector.putMainClass(className, bytes)
                } else {
                    collector.put(className, bytes)
                }
            }
        }
        return collector
    }
}
