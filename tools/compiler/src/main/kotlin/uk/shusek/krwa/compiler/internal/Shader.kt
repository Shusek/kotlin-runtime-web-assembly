package uk.shusek.krwa.compiler.internal

import java.io.IOException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type.getInternalName
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import uk.shusek.krwa.compiler.internal.CompilerUtil.internalClassName
import uk.shusek.krwa.wasm.WasmEngineException

/** The Shader class is responsible for creating a shaded version of the Shaded class. */
object Shader {
    @JvmStatic
    fun createShadedClass(className: String, collector: ClassCollector) {
        val shadedClassName = internalClassName(className + "Shaded")
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        var visitor: ClassVisitor = shadedClassRemapper(writer, className)

        visitor =
            object : ClassVisitor(Opcodes.ASM9, visitor) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    super.visit(
                        version,
                        Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
                        shadedClassName,
                        null,
                        superName,
                        null,
                    )
                }
            }

        val reader = ClassReader(collector.resolve(Shaded::class.java))
        reader.accept(visitor, ClassReader.SKIP_FRAMES)

        collector.put(shadedClassName, writer.toByteArray())
    }

    @JvmStatic
    fun shadedClassRemapper(visitor: ClassVisitor, className: String): ClassRemapper {
        val targetInternalName = internalClassName(className + "Shaded")
        val originalInternalName = internalClassName(Shaded::class.java.name)
        val remapper =
            object : Remapper(Opcodes.ASM9) {
                override fun map(internalName: String): String {
                    if (internalName == originalInternalName) {
                        return targetInternalName
                    }
                    return super.map(internalName)
                }
            }
        return ClassRemapper(visitor, remapper)
    }

    @JvmStatic
    fun getBytecode(clazz: Class<*>): ByteArray {
        val name = getInternalName(clazz) + ".class"
        // Okay when running with Java modules (JPMS), because .class are exempted from checks.
        try {
            clazz.classLoader.getResourceAsStream(name).use { input ->
                if (input == null) {
                    throw IOException("Resource not found: $name")
                }
                return input.readAllBytes()
            }
        } catch (e: IOException) {
            throw WasmEngineException("Could not load bytecode for $clazz", e)
        }
    }
}
