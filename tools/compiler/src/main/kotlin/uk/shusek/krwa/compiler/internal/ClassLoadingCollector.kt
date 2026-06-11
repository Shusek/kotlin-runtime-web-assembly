package uk.shusek.krwa.compiler.internal

import java.io.PrintWriter
import java.io.StringWriter
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Objects
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.CheckClassAdapter
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.WasmEngineException

/**
 * A [ClassCollector] that stores the classes in an ordered map.
 * - It loads the given bytes into a classloader for verification as they are inserted.
 * - It resolves a given class to bytes by looking into classpath.
 * - It optionally generates a MachineFactory with the internal classloader.
 */
class ClassLoadingCollector : ClassCollector {
    private val classLoader = WasmClassLoader()
    private var classBytes = LinkedHashMap<String, ByteArray>()
    private var mainClass: String? = null
    private var machineFactory: ((Instance) -> Machine)? = null

    override fun mainClassName(): String = mainClass!!

    override fun putMainClass(className: String, bytes: ByteArray) {
        mainClass = className
        loadClass(bytes)

        // Ensure the main class comes first in order. This is not strictly necessary, but it is
        // often enforced in test cases.
        val classBytes = LinkedHashMap<String, ByteArray>()
        classBytes[className] = bytes
        classBytes.putAll(this.classBytes)

        this.classBytes = classBytes
    }

    /** It may throw if the class is invalid, e.g. VerifyError. */
    override fun put(name: String, data: ByteArray) {
        loadClass(data)
        classBytes[name] = data
    }

    override fun putAll(collector: ClassCollector) {
        val classBytes = collector.classBytes()
        this.classBytes.putAll(classBytes)
        for (bytes in classBytes.values) {
            loadClass(bytes)
        }
    }

    override fun classBytes(): Map<String, ByteArray> = Collections.unmodifiableMap(classBytes)

    override fun resolve(clazz: Class<*>): ByteArray = Shader.getBytecode(clazz)

    fun machineFactory(): (Instance) -> Machine {
        if (machineFactory == null) {
            Objects.requireNonNull(mainClass)
            machineFactory = createMachineFactory(mainClass!!)
        }
        return machineFactory!!
    }

    private fun createMachineFactory(name: String): (Instance) -> Machine =
        try {
            val clazz = classLoader.loadClass(name).asSubclass(Machine::class.java)
            val constructor = clazz.getConstructor(Instance::class.java)
            val factory: (Instance) -> Machine = { instance -> constructor.newInstance(instance) }
            factory
        } catch (e: ReflectiveOperationException) {
            throw WasmEngineException(e)
        }

    private fun loadClass(classBytes: ByteArray): Class<*> =
        try {
            val clazz = classLoader.loadFromBytes(classBytes)
            // Force initialization to run JVM verifier.
            Class.forName(clazz.name, true, clazz.classLoader)
            clazz
        } catch (e: ClassNotFoundException) {
            throw AssertionError(e)
        } catch (e: VerifyError) {
            // Run ASM verifier to help with debugging.
            try {
                val out = StringWriter().append("ASM verifier:\n\n")
                CheckClassAdapter.verify(ClassReader(classBytes), true, PrintWriter(out))
                e.addSuppressed(RuntimeException(out.toString()))
            } catch (t: Throwable) {
                e.addSuppressed(t)
            }
            throw e
        }
}
