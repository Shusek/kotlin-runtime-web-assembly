package uk.shusek.krwa.codegen

import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionImport
import uk.shusek.krwa.wasm.types.Import
import uk.shusek.krwa.wasm.types.ValType

class ModuleInterfaceCodegen private constructor(builder: Builder) {
    private val module: WasmModule
    private val packageName: String
    private val typeName: String

    init {
        module = builder.module ?: throw IllegalArgumentException("module is required")
        typeName = builder.typeName ?: throw IllegalArgumentException("typeName is required")
        packageName = builder.packageName ?: ""
    }

    fun generate(): Map<String, String> {
        val result: MutableMap<String, String> = LinkedHashMap()
        val prefix = if (packageName.isEmpty()) "" else "$packageName."

        val functionImports =
            module
                .importSection()
                .imports()
                .asSequence()
                .filter { it.importType() == ExternalType.FUNCTION }
                .map { it as FunctionImport }
                .toList()

        result[prefix + typeName + "_ModuleExports"] = renderExports(functionImports)

        if (module.importSection().importCount() > 0) {
            val importedModules: MutableMap<String, MutableList<Import>> = HashMap()
            for (i in 0 until module.importSection().importCount()) {
                val imprt = module.importSection().getImport(i)
                importedModules.computeIfAbsent(imprt.module()) { ArrayList() }.add(imprt)
            }
            result.putAll(renderImports(prefix, importedModules))
        }

        return result
    }

    private fun renderExports(functionImports: List<FunctionImport>): String =
        KotlinFile(packageName)
            .apply {
                addImport("uk.shusek.krwa.runtime.ExportFunction")
                addImport("uk.shusek.krwa.runtime.GlobalInstance")
                addImport("uk.shusek.krwa.runtime.Instance")
                addImport("uk.shusek.krwa.runtime.Memory")
                addImport("uk.shusek.krwa.runtime.TableInstance")
                addImport("uk.shusek.krwa.wasm.types.Value")

                line("class ${typeName}_ModuleExports(instance: Instance) {")

                val exportNames = ArrayList<String>()
                for (i in 0 until module.exportSection().exportCount()) {
                    val export = module.exportSection().getExport(i)
                    val name =
                        deduplicatedMethodName(
                            CodegenUtils.snakeCaseToCamelCase(export.name(), false),
                            exportNames,
                        )
                    exportNames.add(name)
                    val fieldName = "field_$name"
                    val exportName = export.name().kotlinLiteral()

                    when (export.exportType()) {
                        ExternalType.MEMORY -> {
                            line(
                                "    private val $fieldName: Memory = instance.exports().memory($exportName)"
                            )
                            line("    fun $name(): Memory = $fieldName")
                            line()
                        }
                        ExternalType.GLOBAL -> {
                            line(
                                "    private val $fieldName: GlobalInstance = instance.exports().global($exportName)"
                            )
                            line("    fun $name(): GlobalInstance = $fieldName")
                            line()
                        }
                        ExternalType.TABLE -> {
                            line(
                                "    private val $fieldName: TableInstance = instance.exports().table($exportName)"
                            )
                            line("    fun $name(): TableInstance = $fieldName")
                            line()
                        }
                        ExternalType.FUNCTION -> {
                            val funcType =
                                if (export.index() >= functionImports.size) {
                                    module
                                        .functionSection()
                                        .getFunctionType(export.index() - functionImports.size)
                                } else {
                                    functionImports[export.index()].typeIndex()
                                }
                            val exportType = module.typeSection().getType(funcType)
                            line(
                                "    private val $fieldName: ExportFunction = instance.exports().function($exportName)"
                            )
                            val params =
                                exportType.params().mapIndexed { idx, param ->
                                    "arg$idx: ${kotlinTypeFromValueType(param)}"
                                }
                            val returnType =
                                when (exportType.returns().size) {
                                    0 -> ""
                                    1 -> ": ${kotlinTypeFromValueType(exportType.returns()[0])}"
                                    else -> ": LongArray"
                                }
                            line("    fun $name(${params.joinToString(", ")})$returnType {")
                            val callArguments =
                                exportType.params().mapIndexed { idx, param ->
                                    toLong(param, "arg$idx")
                                }
                            val applyCall = "$fieldName.apply(${callArguments.joinToString(", ")})"
                            when (exportType.returns().size) {
                                0 -> line("        $applyCall")
                                1 -> {
                                    line("        val result = $applyCall[0]")
                                    line(
                                        "        return ${fromLong(exportType.returns()[0], "result")}"
                                    )
                                }
                                else -> line("        return $applyCall")
                            }
                            line("    }")
                            line()
                        }
                        else ->
                            throw IllegalArgumentException(
                                "Unsupported export type: ${export.exportType()}"
                            )
                    }
                }

                line("}")
            }
            .render()

    private fun renderImports(
        prefix: String,
        importedModules: Map<String, List<Import>>,
    ): Map<String, String> {
        val result: MutableMap<String, String> = LinkedHashMap()
        val importsFile = KotlinFile(packageName)
        importsFile.addImport("uk.shusek.krwa.runtime.GlobalInstance")
        importsFile.addImport("uk.shusek.krwa.runtime.HostFunction")
        importsFile.addImport("uk.shusek.krwa.runtime.ImportGlobal")
        importsFile.addImport("uk.shusek.krwa.runtime.ImportMemory")
        importsFile.addImport("uk.shusek.krwa.runtime.ImportTable")
        importsFile.addImport("uk.shusek.krwa.runtime.ImportValues")
        importsFile.addImport("uk.shusek.krwa.runtime.Memory")
        importsFile.addImport("uk.shusek.krwa.runtime.TableInstance")
        importsFile.addImport("uk.shusek.krwa.wasm.types.FunctionType")
        importsFile.addImport("uk.shusek.krwa.wasm.types.ValType")
        importsFile.addImport("uk.shusek.krwa.wasm.types.Value")

        importsFile.line("interface ${typeName}_ModuleImports {")

        val addImportValueLines = ArrayList<String>()
        for ((moduleName, imports) in importedModules) {
            val importClassName =
                typeName + "_" + CodegenUtils.snakeCaseToCamelCase(moduleName, true)
            val moduleMethodName = CodegenUtils.snakeCaseToCamelCase(moduleName, false)
            importsFile.line("    fun $moduleMethodName(): $importClassName")
            result[prefix + importClassName] = renderImportModuleInterface(importClassName, imports)

            for (imprt in imports) {
                val importedName = imprt.name().kotlinLiteral()
                val importedModule = moduleName.kotlinLiteral()
                val importFunctionCall =
                    "$moduleMethodName().${CodegenUtils.snakeCaseToCamelCase(imprt.name(), false)}()"
                when (imprt.importType()) {
                    ExternalType.MEMORY ->
                        addImportValueLines.add(
                            "imports.addMemory(ImportMemory($importedModule, $importedName, $importFunctionCall))"
                        )
                    ExternalType.GLOBAL ->
                        addImportValueLines.add(
                            "imports.addGlobal(ImportGlobal($importedModule, $importedName, $importFunctionCall))"
                        )
                    ExternalType.TABLE ->
                        addImportValueLines.add(
                            "imports.addTable(ImportTable($importedModule, $importedName, $importFunctionCall))"
                        )
                    ExternalType.FUNCTION -> {
                        val importedFun = imprt as FunctionImport
                        val importType = module.typeSection().getType(importedFun.typeIndex())
                        addImportValueLines.add(
                            renderHostFunctionBinding(moduleName, importedFun, importType)
                        )
                    }
                    else ->
                        throw IllegalArgumentException(
                            "Unsupported import type: ${imprt.importType()}"
                        )
                }
            }
        }

        importsFile.line()
        importsFile.line("    fun toImportValues(): ImportValues {")
        importsFile.line("        val imports = ImportValues.builder()")
        for (line in addImportValueLines) {
            importsFile.line(line.prependIndent("        "))
        }
        importsFile.line("        return imports.build()")
        importsFile.line("    }")
        importsFile.line("}")

        result[prefix + typeName + "_ModuleImports"] = importsFile.render()
        return result
    }

    private fun renderImportModuleInterface(
        importClassName: String,
        imports: List<Import>,
    ): String =
        KotlinFile(packageName)
            .apply {
                addImport("uk.shusek.krwa.runtime.GlobalInstance")
                addImport("uk.shusek.krwa.runtime.Memory")
                addImport("uk.shusek.krwa.runtime.TableInstance")
                line("interface $importClassName {")
                for (imprt in imports) {
                    val methodName = CodegenUtils.snakeCaseToCamelCase(imprt.name(), false)
                    when (imprt.importType()) {
                        ExternalType.MEMORY -> line("    fun $methodName(): Memory")
                        ExternalType.GLOBAL -> line("    fun $methodName(): GlobalInstance")
                        ExternalType.TABLE -> line("    fun $methodName(): TableInstance")
                        ExternalType.FUNCTION -> {
                            val importType =
                                module.typeSection().getType((imprt as FunctionImport).typeIndex())
                            val params =
                                importType.params().mapIndexed { idx, param ->
                                    "arg$idx: ${kotlinTypeFromValueType(param)}"
                                }
                            val returnType =
                                when (importType.returns().size) {
                                    0 -> ""
                                    1 -> ": ${kotlinTypeFromValueType(importType.returns()[0])}"
                                    else -> ": LongArray"
                                }
                            line("    fun $methodName(${params.joinToString(", ")})$returnType")
                        }
                        else ->
                            throw IllegalArgumentException(
                                "Unsupported import type: ${imprt.importType()}"
                            )
                    }
                }
                line("}")
            }
            .render()

    private fun renderHostFunctionBinding(
        moduleName: String,
        importedFun: FunctionImport,
        importType: uk.shusek.krwa.wasm.types.FunctionType,
    ): String {
        val moduleMethodName = CodegenUtils.snakeCaseToCamelCase(moduleName, false)
        val importMethodName = CodegenUtils.snakeCaseToCamelCase(importedFun.name(), false)
        val arguments = importType.params().map { param -> fromLong(param, "args[argIdx++]") }
        val params = valueTypes(importType.params())
        val returns = valueTypes(importType.returns())
        return buildString {
            appendLine("imports.addFunction(")
            appendLine("    HostFunction(")
            appendLine("        ${moduleName.kotlinLiteral()},")
            appendLine("        ${importedFun.name().kotlinLiteral()},")
            appendLine("        FunctionType.of($params, $returns),")
            appendLine("    ) { _, args ->")
            appendLine("        var argIdx = 0")
            val invocation =
                "$moduleMethodName().$importMethodName(${arguments.joinToString(", ")})"
            when (importType.returns().size) {
                0 -> {
                    appendLine("        $invocation")
                    appendLine("        null")
                }
                1 -> {
                    appendLine("        val result = $invocation")
                    appendLine("        longArrayOf(${toLong(importType.returns()[0], "result")})")
                }
                else -> appendLine("        $invocation")
            }
            appendLine("    }")
            append(")")
        }
    }

    class Builder internal constructor(internal val module: WasmModule?) {
        internal var packageName: String? = null
        internal var typeName: String? = null
        internal var generatorName: String? = null

        fun withPackageName(packageName: String): Builder {
            this.packageName = packageName
            return this
        }

        fun withTypeName(typeName: String): Builder {
            this.typeName = typeName
            return this
        }

        fun withGeneratorName(generatorName: String): Builder {
            this.generatorName = generatorName
            return this
        }

        fun build(): ModuleInterfaceCodegen = ModuleInterfaceCodegen(this)
    }

    companion object {
        @JvmStatic fun builder(module: WasmModule): Builder = Builder(module)

        private fun kotlinTypeFromValueType(type: ValType): String =
            when (type.opcode()) {
                ValType.ID.I32 -> "Int"
                ValType.ID.I64 -> "Long"
                ValType.ID.F32 -> "Float"
                ValType.ID.F64 -> "Double"
                else -> {
                    if (ValType.TypeIdxCode.EXTERN.code() == type.typeIdx()) {
                        "Long"
                    } else {
                        throw IllegalArgumentException(
                            "kotlinTypeFromValueType - Unsupported WASM type: $type"
                        )
                    }
                }
            }

        private fun valueTypes(valTypes: List<ValType>): String =
            if (valTypes.isEmpty()) {
                "emptyList()"
            } else {
                valTypes.joinToString(prefix = "listOf(", postfix = ")") { valueType(it) }
            }

        private fun valueType(type: ValType): String =
            when (type.opcode()) {
                ValType.ID.I32 -> "ValType.I32"
                ValType.ID.I64 -> "ValType.I64"
                ValType.ID.F32 -> "ValType.F32"
                ValType.ID.F64 -> "ValType.F64"
                else -> {
                    if (ValType.TypeIdxCode.EXTERN.code() == type.typeIdx()) {
                        "ValType.ExternRef"
                    } else {
                        throw IllegalArgumentException("valueType - Unsupported WASM type: $type")
                    }
                }
            }

        private fun toLong(type: ValType, expression: String): String =
            when (type.opcode()) {
                ValType.ID.I32 -> "$expression.toLong()"
                ValType.ID.I64 -> expression
                ValType.ID.F32 -> "Value.floatToLong($expression)"
                ValType.ID.F64 -> "Value.doubleToLong($expression)"
                else -> {
                    if (ValType.TypeIdxCode.EXTERN.code() == type.typeIdx()) {
                        expression
                    } else {
                        throw IllegalArgumentException("toLong - Unsupported WASM type: $type")
                    }
                }
            }

        private fun fromLong(type: ValType, expression: String): String =
            when (type.opcode()) {
                ValType.ID.I32 -> "$expression.toInt()"
                ValType.ID.I64 -> expression
                ValType.ID.F32 -> "Value.longToFloat($expression)"
                ValType.ID.F64 -> "Value.longToDouble($expression)"
                else -> {
                    if (ValType.TypeIdxCode.EXTERN.code() == type.typeIdx()) {
                        expression
                    } else {
                        throw IllegalArgumentException("fromLong - Unsupported WASM type: $type")
                    }
                }
            }

        private fun deduplicatedMethodName(name: String, names: List<String>): String =
            if (!names.contains(name)) {
                name
            } else {
                deduplicatedMethodName("_$name", names)
            }
    }
}

private class KotlinFile(private val packageName: String) {
    private val imports = linkedSetOf<String>()
    private val body = StringBuilder()

    fun addImport(importName: String) {
        imports.add(importName)
    }

    fun line(value: String = "") {
        body.appendLine(value)
    }

    fun render(): String = buildString {
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName")
            appendLine()
        }
        for (importName in imports.sorted()) {
            appendLine("import $importName")
        }
        if (imports.isNotEmpty()) {
            appendLine()
        }
        append(body)
    }
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
