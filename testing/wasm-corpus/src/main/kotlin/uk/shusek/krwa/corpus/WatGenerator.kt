package uk.shusek.krwa.corpus

import java.io.StringWriter
import java.util.ArrayDeque
import org.apache.velocity.VelocityContext
import org.apache.velocity.app.VelocityEngine
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader

object WatGenerator {
    @JvmStatic
    fun bigWat(funcCount: Int, funcSize: Int): String {
        val functions = (1..funcCount).toList()
        val instructions = (1..funcSize).toList()

        return render(
            "/uk/shusek/krwa/corpus/big.wat",
            mapOf("functions" to functions, "instructions" to instructions),
        )
    }

    @JvmStatic
    fun methodTooLarge(funcSize: Int): String {
        val instructions = (1..funcSize).toList()

        return render(
            "/uk/shusek/krwa/corpus/method_too_large.wat",
            mapOf("instructions" to instructions),
        )
    }

    private fun render(template: String, map: Map<String, Any>): String {
        val velocityEngine = VelocityEngine()
        velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath")
        velocityEngine.setProperty(
            "classpath.resource.loader.class",
            ClasspathResourceLoader::class.java.name,
        )
        velocityEngine.init()

        val velocityTemplate = velocityEngine.getTemplate(template)
        val context = VelocityContext()
        for ((key, value) in map) {
            context.put(key, value)
        }

        val writer = StringWriter()
        velocityTemplate.merge(context, writer)
        writer.flush()
        return writer.toString()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val remaining = ArrayDeque(args.toList())
        var funcCount = 50_000
        var funcSize = 0
        if (!remaining.isEmpty()) {
            funcCount = remaining.removeFirst().toInt()
        }
        if (!remaining.isEmpty()) {
            funcSize = remaining.removeFirst().toInt()
        }
        println(bigWat(funcCount, funcSize))
    }
}
