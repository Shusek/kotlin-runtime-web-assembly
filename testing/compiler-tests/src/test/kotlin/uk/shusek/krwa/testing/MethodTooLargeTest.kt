package uk.shusek.krwa.testing

import java.io.PrintWriter
import java.io.StringWriter
import org.approvaltests.Approvals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type.getInternalName
import org.objectweb.asm.util.TraceClassVisitor
import uk.shusek.krwa.compiler.internal.Compiler
import uk.shusek.krwa.compiler.internal.Shaded
import uk.shusek.krwa.corpus.WatGenerator.methodTooLarge
import uk.shusek.krwa.wabt.Wat2Wasm
import uk.shusek.krwa.wasm.Parser

class MethodTooLargeTest {
    @Test
    fun testBigFunc() {
        val wat = methodTooLarge(20_000)
        val wasm = Wat2Wasm.parse(wat)

        val module = Parser.parse(wasm)
        val result = Compiler.builder(module).build().compile()

        verifyClass(result.classBytes(), true)
    }

    companion object {
        private fun verifyClass(classBytes: Map<String, ByteArray>, skipMethodsClss: Boolean) {
            val writer = StringWriter()

            for (bytes in classBytes.values) {
                val cr = ClassReader(bytes)
                if (skipMethodsClss && cr.className.endsWith("Methods")) {
                    continue
                }
                cr.accept(
                    object : ClassVisitor(Opcodes.ASM9, TraceClassVisitor(PrintWriter(writer))) {
                        override fun visit(
                            version: Int,
                            access: Int,
                            name: String?,
                            signature: String?,
                            superName: String?,
                            interfaces: Array<out String>?,
                        ) {
                            if (name?.endsWith("CompiledMachineFuncGroup_0") == true) {
                                super.visit(version, access, name, signature, superName, interfaces)
                            }
                        }

                        override fun visitMethod(
                            access: Int,
                            name: String?,
                            descriptor: String?,
                            signature: String?,
                            exceptions: Array<out String>?,
                        ): MethodVisitor? {
                            if (name == "func_2") {
                                return super.visitMethod(
                                    access,
                                    name,
                                    descriptor,
                                    signature,
                                    exceptions,
                                )
                            }
                            return null
                        }
                    },
                    0,
                )
                writer.append("\n")
            }

            var output = writer.toString()
            output = output.replace(Regex("(?m)^ {3}FRAME.*\\n"), "")
            output = output.replace(Regex("(?m)^ {4}MAX(STACK|LOCALS) = \\d+\\n"), "")
            output = output.replace(Regex("(?m)^ {4}(LINENUMBER|LOCALVARIABLE) .*\\n"), "")
            output = output.replace(Regex("(?m)^ *// .*\\n"), "")
            output =
                output.replace(
                    Regex("(?ms)^Shaded\\.kt\\nKotlin\\n\\*S Kotlin\\n.*?\\*E\\n\\n"),
                    "",
                )
            output = output.replace(Regex("(?m)^  @Lkotlin/Metadata;.*\\n"), "")
            output =
                output.replace(Regex("(?m)^  @Lkotlin/jvm/internal/SourceDebugExtension;.*\\n"), "")
            output = output.replace(Regex("(?m)^  public final static INNERCLASS .*\\n"), "")
            output =
                output.replace(
                    Regex(
                        "(?m)^  public final static Luk/shusek/krwa/\\${'$'}gen/CompiledMachineShaded; INSTANCE\\n"
                    ),
                    "",
                )
            output =
                output.replace(
                    Regex("(?m)^  @Lorg/jetbrains/annotations/NotNull;\\(\\).*\\n\\n"),
                    "",
                )
            output = output.replace(Regex("\\n{3,}"), "\n\n")
            output = output.trim() + "\n"

            Approvals.verify(output)

            assertFalse(
                output.contains(getInternalName(Shaded::class.java)),
                "Class contains non-inlined reference to " + Shaded::class.java.name,
            )
        }
    }
}
