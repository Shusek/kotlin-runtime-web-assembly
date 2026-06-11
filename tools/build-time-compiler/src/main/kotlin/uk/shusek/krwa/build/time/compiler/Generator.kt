package uk.shusek.krwa.build.time.compiler

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import uk.shusek.krwa.codegen.ModuleInterfaceCodegen
import uk.shusek.krwa.compiler.internal.ByteClassCollector
import uk.shusek.krwa.compiler.internal.Compiler
import uk.shusek.krwa.wasm.Encoding
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmWriter
import uk.shusek.krwa.wasm.types.OpCode
import uk.shusek.krwa.wasm.types.RawSection
import uk.shusek.krwa.wasm.types.SectionId

open class Generator(private val config: Config) {
    @Throws(IOException::class)
    open fun generateResources(): Set<Int> {
        val module = Parser.parse(config.wasmFile()!!)
        val machineName = config.name()!! + "Machine"
        val compiler =
            Compiler.builder(module)
                .withClassName(machineName)
                .withClassCollectorFactory { ByteClassCollector() }
                .withInterpreterFallback(config.interpreterFallback())
                .withInterpretedFunctions(config.interpretedFunctions())
                .build()
        val result = compiler.compile()

        val finalFolder = config.targetClassFolder()!!

        createFolders(finalFolder, config.name()!!.split("\\.".toRegex()).toTypedArray())

        for ((className, bytes) in result.classBytes()) {
            val binaryName = className.replace('.', '/') + ".class"
            val targetFile = config.targetClassFolder()!!.resolve(binaryName)
            Files.write(targetFile, bytes)
        }

        return result.interpretedFunctions()
    }

    @Throws(IOException::class)
    open fun generateSources() {
        val machineName = config.name()!! + "Machine"
        val split = config.name()!!.split("\\.".toRegex()).toTypedArray()

        val finalSourceFolder = config.targetSourceFolder()!!

        createFolders(finalSourceFolder, split)

        val packageName = config.getPackageName()

        val baseName = config.getBaseName()
        val moduleName = baseName
        val wasmName = "$baseName.meta"

        writeKotlinSource(
            finalSourceFolder,
            packageName,
            moduleName,
            renderCompiledModule(packageName, moduleName, machineName, wasmName),
        )
    }

    @Throws(IOException::class)
    open fun generateModuleInterface(moduleInterfaceName: String) {
        val module = Parser.parse(config.wasmFile()!!)

        val lastDot = moduleInterfaceName.lastIndexOf('.')
        val packageName = if (lastDot > 0) moduleInterfaceName.substring(0, lastDot) else ""
        val typeName = moduleInterfaceName.substring(lastDot + 1)

        val codegen =
            ModuleInterfaceCodegen.builder(module)
                .withPackageName(packageName)
                .withTypeName(typeName)
                .withGeneratorName("uk.shusek.krwa.build.time.compiler.Generator")
                .build()
        val classes = codegen.generate()

        for ((className, source) in classes) {
            val filePath =
                config.targetSourceFolder()!!.resolve(className.replace('.', '/') + ".kt")
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, source)
        }
    }

    @Throws(IOException::class)
    open fun generateMetaWasm(interpretedFunctions: Set<Int>) {
        val wasmBytes = Files.readAllBytes(config.wasmFile()!!)
        val module = Parser.parse(wasmBytes)

        val writer = WasmWriter()
        Parser.parseWithoutDecoding(wasmBytes) { section ->
            if (section.sectionId() == SectionId.CODE) {
                val source = ByteBuffer.wrap((section as RawSection).contents())

                val out = Buffer()
                val importFuncs = module.importSection().importCount()
                val count = module.codeSection().functionBodyCount()
                WasmWriter.writeVarUInt32(out, count)
                val actual = Encoding.readVarUInt32(source)
                assert(count.toLong() == actual)
                for (i in 0 until count) {
                    val funcId = importFuncs + i
                    if (interpretedFunctions.contains(funcId)) {
                        val bodySize = Encoding.readVarUInt32(source).toInt()
                        WasmWriter.writeVarUInt32(out, bodySize)
                        val bodyBytes = ByteArray(bodySize)
                        source.get(bodyBytes)
                        out.write(bodyBytes)
                    } else {
                        val bodySize = Encoding.readVarUInt32(source).toInt()
                        source.position(source.position() + bodySize - 1)
                        val endOp = source.get().toInt()
                        assert(endOp == OpCode.END.opcode())

                        WasmWriter.writeVarUInt32(out, 3)
                        WasmWriter.writeVarUInt32(out, 0)
                        out.writeByte(OpCode.UNREACHABLE.opcode().toByte())
                        out.writeByte(OpCode.END.opcode().toByte())
                    }
                }
                writer.writeSection(SectionId.CODE, out.readByteArray())
            } else if (section.sectionId() != SectionId.CUSTOM) {
                writer.writeSection(section as RawSection)
            }
        }

        val newWasmFile =
            config
                .targetWasmFolder()!!
                .resolve(config.getPackageName().replace('.', '/'))
                .resolve(config.getBaseName() + ".meta")
        Files.createDirectories(newWasmFile.parent)
        Files.write(newWasmFile, writer.bytes())
    }

    companion object {
        @Throws(IOException::class)
        private fun createFolders(filesFolder: Path, split: Array<String>) {
            var folder = filesFolder
            for (i in 0 until split.size - 1) {
                folder = folder.resolve(split[i])
            }
            Files.createDirectories(folder)
        }

        private fun writeKotlinSource(
            sourceFolder: Path,
            packageName: String,
            typeName: String,
            source: String,
        ) {
            val packagePath = if (packageName.isEmpty()) "" else packageName.replace('.', '/')
            val filePath = sourceFolder.resolve(packagePath).resolve("$typeName.kt")
            Files.createDirectories(filePath.parent)
            Files.writeString(filePath, source)
        }

        private fun renderCompiledModule(
            packageName: String,
            moduleName: String,
            machineName: String,
            wasmName: String,
        ): String = buildString {
            if (packageName.isNotEmpty()) {
                appendLine("package $packageName")
                appendLine()
            }
            appendLine("import java.io.IOException")
            appendLine("import java.io.UncheckedIOException")
            appendLine("import uk.shusek.krwa.runtime.CompiledModule")
            appendLine("import uk.shusek.krwa.runtime.Instance")
            appendLine("import uk.shusek.krwa.runtime.Machine")
            appendLine("import uk.shusek.krwa.wasm.Parser")
            appendLine("import uk.shusek.krwa.wasm.WasmModule")
            appendLine()
            appendLine("class $moduleName : CompiledModule {")
            appendLine("    override fun machineFactory(): (Instance) -> Machine =")
            appendLine("        { instance -> create(instance) }")
            appendLine()
            appendLine("    override fun wasmModule(): WasmModule = load()")
            appendLine()
            appendLine("    companion object {")
            appendLine("        @JvmStatic")
            appendLine("        fun create(instance: Instance): Machine =")
            appendLine("            try {")
            appendLine("                Class.forName(${machineName.kotlinLiteral()})")
            appendLine("                    .getConstructor(Instance::class.java)")
            appendLine("                    .newInstance(instance) as Machine")
            appendLine("            } catch (e: ReflectiveOperationException) {")
            appendLine(
                "                throw IllegalStateException(\"Failed to create compiled machine: $machineName\", e)"
            )
            appendLine("            }")
            appendLine()
            appendLine("        @JvmStatic")
            appendLine("        fun load(): WasmModule = WasmModuleHolder.INSTANCE")
            appendLine("    }")
            appendLine()
            appendLine("    private object WasmModuleHolder {")
            appendLine("        val INSTANCE: WasmModule =")
            appendLine("            try {")
            appendLine("                val input =")
            appendLine(
                "                    $moduleName::class.java.getResourceAsStream(${wasmName.kotlinLiteral()})"
            )
            appendLine(
                "                        ?: throw IOException(\"Missing .meta WASM module resource: $wasmName\")"
            )
            appendLine("                input.use { Parser.parse(it) }")
            appendLine("            } catch (e: IOException) {")
            appendLine(
                "                throw UncheckedIOException(\"Failed to load .meta WASM module\", e)"
            )
            appendLine("            }")
            appendLine("    }")
            appendLine("}")
        }

        private fun String.kotlinLiteral(): String = buildString {
            append('"')
            for (char in this@kotlinLiteral) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
    }
}
