package uk.shusek.krwa.compiler.internal

/**
 * A class collector exposes methods to resolve class files from the classpath and collecting bytes
 * representing classes.
 *
 * The ClassCollector may optionally throw on put() and putAll() if the given data is not valid.
 */
interface ClassCollector {
    /** Main entry point. */
    fun mainClassName(): String

    fun putMainClass(className: String, bytes: ByteArray)

    fun put(name: String, data: ByteArray)

    fun putAll(collector: ClassCollector)

    fun classBytes(): Map<String, ByteArray>

    fun resolve(clazz: Class<*>): ByteArray
}
