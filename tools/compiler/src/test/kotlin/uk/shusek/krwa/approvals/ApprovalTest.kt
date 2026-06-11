package uk.shusek.krwa.approvals

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.stream.IntStream
import org.apache.velocity.Template
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader
import org.approvaltests.Approvals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type.getInternalName
import org.objectweb.asm.util.TraceClassVisitor
import uk.shusek.krwa.compiler.internal.Compiler
import uk.shusek.krwa.compiler.internal.Shaded
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.wasm.Parser

// To approve everything use the env var: `APPROVAL_TESTS_USE_REPORTER=AutoApproveReporter`
class ApprovalTest {
    @Test fun verifyBranching() = verifyGeneratedBytecode("branching.wat.wasm")

    @Test fun verifyBrTable() = verifyGeneratedBytecode("br_table.wat.wasm")

    @Test
    fun verifyLotsOfArgs() {
        val destPath =
            Path.of(
                "src/test/resources/uk/shusek/krwa/approvals/ApprovalTest.verifyLotsOfArgs.approved.txt"
            )

        if (!Files.exists(destPath)) {
            Files.writeString(destPath, renderLotsOfArgs(), StandardOpenOption.CREATE_NEW)
        }

        verifyGeneratedBytecode("lots-of-args.wat.wasm", verifyApproval = false)
    }

    @Test fun verifyFloat() = verifyGeneratedBytecode("float.wat.wasm")

    @Test fun verifyHelloWasi() = verifyGeneratedBytecode("hello-wasi.wat.wasm")

    @Test fun verifyI32() = verifyGeneratedBytecode("i32.wat.wasm")

    @Test
    fun verifyI32Renamed() {
        val module = Parser.parse(CorpusResources.getResource("compiled/i32.wat.wasm"))
        val result = Compiler.builder(module).withClassName("FOO").build().compile()
        verifyClass(result.classBytes()) { name -> name != "FOO" }
    }

    @Test fun verifyIterFact() = verifyGeneratedBytecode("iterfact.wat.wasm")

    @Test fun verifyKitchenSink() = verifyGeneratedBytecode("kitchensink.wat.wasm")

    @Test fun verifyMemory() = verifyGeneratedBytecode("memory.wat.wasm")

    @Test fun verifyStart() = verifyGeneratedBytecode("start.wat.wasm")

    @Test fun verifyTrap() = verifyGeneratedBytecode("trap.wat.wasm")

    @Test
    fun verifyExceptions() =
        verifyGeneratedBytecode("exceptions.wat.wasm") { name -> !name.contains("FuncGroup") }

    @Test fun verifyTailCall() = verifyGeneratedBytecode("tail_call_return_call.wat.wasm")

    @Test
    fun verifyGc() = verifyGeneratedBytecode("gc.wat.wasm") { name -> !name.contains("FuncGroup") }

    @Test
    fun functions10() {
        val module = Parser.parse(CorpusResources.getResource("compiled/functions_10.wat.wasm"))
        val result = Compiler.builder(module).withMaxFunctionsPerClass(5).build().compile()
        verifyClass(result.classBytes(), ::skipMethodsClass)
    }

    private fun verifyGeneratedBytecode(name: String) {
        verifyGeneratedBytecode(name, ::skipMethodsClass, verifyApproval = true)
    }

    private fun verifyGeneratedBytecode(name: String, verifyApproval: Boolean) {
        verifyGeneratedBytecode(name, ::skipMethodsClass, verifyApproval)
    }

    private fun verifyGeneratedBytecode(name: String, classSkipper: (String) -> Boolean) {
        verifyGeneratedBytecode(name, classSkipper, verifyApproval = true)
    }

    private fun verifyGeneratedBytecode(
        name: String,
        classSkipper: (String) -> Boolean,
        verifyApproval: Boolean,
    ) {
        val module = Parser.parse(CorpusResources.getResource("compiled/$name"))
        val result = Compiler.builder(module).build().compile()
        verifyClass(result.classBytes(), classSkipper, verifyApproval)
    }

    private fun skipMethodsClass(name: String): Boolean = name.endsWith("Shaded")

    private fun verifyClass(classBytes: Map<String, ByteArray>, classSkipper: (String) -> Boolean) {
        verifyClass(classBytes, classSkipper, verifyApproval = true)
    }

    private fun verifyClass(
        classBytes: Map<String, ByteArray>,
        classSkipper: (String) -> Boolean,
        verifyApproval: Boolean = true,
    ) {
        val writer = StringWriter()

        for (bytes in classBytes.toSortedMap().values) {
            val cr = ClassReader(bytes)
            if (classSkipper(cr.className)) {
                continue
            }
            cr.accept(TraceClassVisitor(PrintWriter(writer)), 0)
            writer.append("\n")
        }

        var output = writer.toString()
        output = output.replace(Regex("(?m)^ {3}FRAME.*\\n"), "")
        output = output.replace(Regex("(?m)^ {4}MAX(STACK|LOCALS) = \\d+\\n"), "")
        output = output.replace(Regex("(?m)^ {4}(LINENUMBER|LOCALVARIABLE) .*\\n"), "")
        output = output.replace(Regex("(?m)^ *// .*\\n"), "")
        output = output.replace(Regex("\\n{3,}"), "\n\n")
        output = output.trim() + "\n"

        if (verifyApproval) {
            Approvals.verify(output)
        }

        assertFalse(
            output.contains(getInternalName(Shaded::class.java)),
            "Class contains non-inlined reference to ${Shaded::class.java.name}",
        )
    }

    private fun renderLotsOfArgs(): String {
        val velocityEngine = VelocityEngine()
        velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath")
        velocityEngine.setProperty(
            "classpath.resource.loader.class",
            ClasspathResourceLoader::class.java.name,
        )
        velocityEngine.init()

        val template: Template =
            velocityEngine.getTemplate("ApprovalTest.verifyLotsOfArgs.approved.template")

        val context = VelocityContext()
        context.put("iconst", IntStream.range(0, 293).toArray())
        context.put("istore", IntStream.range(0, 296).toArray())
        context.put("splats1", IntStream.range(6, 128).toArray())
        context.put("splats2", IntStream.range(128, 300).toArray())
        context.put("splats3", IntStream.range(6, 128).toArray())
        context.put("splats4", IntStream.range(128, 300).toArray())

        val writer = StringWriter()
        template.merge(context, writer)
        writer.flush()
        return writer.toString()
    }
}
