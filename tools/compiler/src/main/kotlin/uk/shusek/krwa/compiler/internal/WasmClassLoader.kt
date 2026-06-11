package uk.shusek.krwa.compiler.internal

import org.objectweb.asm.ClassReader

class WasmClassLoader : ClassLoader(WasmClassLoader::class.java.classLoader) {
    fun loadFromBytes(bytes: ByteArray): Class<*> {
        val name = ClassReader(bytes).className.replace('/', '.')
        return defineClass(name, bytes, 0, bytes.size)
    }
}
