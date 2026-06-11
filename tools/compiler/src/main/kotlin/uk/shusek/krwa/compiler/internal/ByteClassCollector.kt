package uk.shusek.krwa.compiler.internal

import java.util.Collections
import java.util.HashMap

/**
 * A simple [ClassCollector] that stores all the classes in a map. It resolves a given class to
 * bytes by looking into classpath.
 */
class ByteClassCollector : ClassCollector {
    private val classBytes = HashMap<String, ByteArray>()
    private var mainClass: String? = null

    override fun mainClassName(): String = mainClass!!

    override fun putMainClass(className: String, bytes: ByteArray) {
        mainClass = className
        classBytes[className] = bytes
    }

    override fun put(name: String, data: ByteArray) {
        classBytes[name] = data
    }

    override fun putAll(collector: ClassCollector) {
        classBytes.putAll(collector.classBytes())
    }

    override fun classBytes(): Map<String, ByteArray> = Collections.unmodifiableMap(classBytes)

    override fun resolve(clazz: Class<*>): ByteArray = Shader.getBytecode(clazz)
}
