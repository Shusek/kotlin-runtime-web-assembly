package uk.shusek.krwa.annotations.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import uk.shusek.krwa.codegen.CodegenUtils

class KrwaSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        HostModuleSymbolProcessor(environment.codeGenerator, environment.logger)
}

private class HostModuleSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols =
            resolver
                .getSymbolsWithAnnotation(HOST_MODULE_ANNOTATION)
                .filterIsInstance<KSClassDeclaration>()
                .toList()
        val invalid = symbols.filterNot(KSClassDeclaration::validate)
        symbols.filter(KSClassDeclaration::validate).forEach(::processHostModule)
        return invalid
    }

    private fun processHostModule(type: KSClassDeclaration) {
        try {
            HostModuleFactoryGenerator(codeGenerator).generate(type)
        } catch (e: GenerationException) {
            logger.error(e.message ?: "Failed to generate host module factory", e.node)
        }
    }
}

private class HostModuleFactoryGenerator(private val codeGenerator: CodeGenerator) {
    fun generate(type: KSClassDeclaration) {
        val packageName = type.packageName.asString()
        val typeName = type.simpleName.asString()
        val moduleName =
            type.annotationValue(HOST_MODULE_ANNOTATION, "value") as? String
                ?: throw GenerationException("@HostModule requires a module name", type)
        val functions = exportedFunctions(type)
        val file =
            codeGenerator.createNewFile(
                Dependencies(aggregating = false, type.containingFile!!),
                packageName,
                "${typeName}_ModuleFactory",
                "kt",
            )
        OutputStreamWriter(file, StandardCharsets.UTF_8).use { writer ->
            writer.write(renderFactory(packageName, typeName, moduleName, functions))
        }
    }

    private fun exportedFunctions(type: KSClassDeclaration): List<HostFunctionModel> =
        type
            .getAllFunctions()
            .filter { it.hasAnnotation(WASM_EXPORT_ANNOTATION) }
            .filterNot { Modifier.PRIVATE in it.modifiers }
            .map { function -> model(type, function) }
            .sortedBy(HostFunctionModel::wasmName)
            .toList()

    private fun model(
        hostType: KSClassDeclaration,
        function: KSFunctionDeclaration,
    ): HostFunctionModel {
        val wasmName =
            (function.annotationValue(WASM_EXPORT_ANNOTATION, "value") as? String)?.takeIf(
                String::isNotEmpty
            ) ?: CodegenUtils.camelCaseToSnakeCase(function.simpleName.asString())
        val parameters = function.parameters.map { parameter -> parameterModel(parameter) }
        val returnType = returnModel(function)
        return HostFunctionModel(
            hostType.simpleName.asString(),
            function.simpleName.asString(),
            wasmName,
            parameters,
            returnType,
        )
    }

    private fun parameterModel(parameter: KSValueParameter): ParameterModel {
        val typeName = parameter.type.resolve().declaration.qualifiedName()
        return when (typeName) {
            "kotlin.Int" -> ParameterModel.Wasm("I32", "args[argIdx++].toInt()")
            "kotlin.Long" -> ParameterModel.Wasm("I64", "args[argIdx++]")
            "kotlin.Float" -> ParameterModel.Wasm("F32", "Value.longToFloat(args[argIdx++])")
            "kotlin.Double" -> ParameterModel.Wasm("F64", "Value.longToDouble(args[argIdx++])")
            "kotlin.String" -> stringParameterModel(parameter)
            "uk.shusek.krwa.runtime.Instance" -> ParameterModel.Context("instance")
            "uk.shusek.krwa.runtime.Memory" -> ParameterModel.Context("instance.memory()")
            else ->
                throw GenerationException("Unsupported WASM parameter type: $typeName", parameter)
        }
    }

    private fun stringParameterModel(parameter: KSValueParameter): ParameterModel =
        when {
            parameter.hasAnnotation(BUFFER_ANNOTATION) ->
                ParameterModel.Wasm(
                    listOf("I32", "I32"),
                    "instance.memory().readString(args[argIdx++].toInt(), args[argIdx++].toInt())",
                )
            parameter.hasAnnotation(C_STRING_ANNOTATION) ->
                ParameterModel.Wasm("I32", "instance.memory().readCString(args[argIdx++].toInt())")
            else ->
                throw GenerationException(
                    "Missing annotation for WASM type: kotlin.String",
                    parameter,
                )
        }

    private fun returnModel(function: KSFunctionDeclaration): ReturnModel {
        val typeName = function.returnType?.resolve()?.declaration?.qualifiedName() ?: "kotlin.Unit"
        return when (typeName) {
            "kotlin.Unit" -> ReturnModel("emptyList()", null)
            "kotlin.Int" -> ReturnModel("listOf(ValType.I32)", "longArrayOf(result.toLong())")
            "kotlin.Long" -> ReturnModel("listOf(ValType.I64)", "longArrayOf(result)")
            "kotlin.Float" ->
                ReturnModel("listOf(ValType.F32)", "longArrayOf(Value.floatToLong(result))")
            "kotlin.Double" ->
                ReturnModel("listOf(ValType.F64)", "longArrayOf(Value.doubleToLong(result))")
            else -> throw GenerationException("Unsupported WASM return type: $typeName", function)
        }
    }

    private fun renderFactory(
        packageName: String,
        typeName: String,
        moduleName: String,
        functions: List<HostFunctionModel>,
    ): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine("import uk.shusek.krwa.runtime.HostFunction")
        appendLine("import uk.shusek.krwa.wasm.types.FunctionType")
        appendLine("import uk.shusek.krwa.wasm.types.ValType")
        appendLine("import uk.shusek.krwa.wasm.types.Value")
        appendLine()
        appendLine("object ${typeName}_ModuleFactory {")
        appendLine("    @JvmStatic")
        appendLine("    fun toHostFunctions(functions: $typeName): Array<HostFunction> =")
        appendLine("        toHostFunctions(functions, ${moduleName.kotlinLiteral()})")
        appendLine()
        appendLine("    @JvmStatic")
        appendLine(
            "    fun toHostFunctions(functions: $typeName, moduleName: String): Array<HostFunction> ="
        )
        if (functions.isEmpty()) {
            appendLine("        emptyArray()")
        } else {
            appendLine("        arrayOf(")
            functions.forEachIndexed { index, function ->
                append(renderFunction(function))
                appendLine(if (index == functions.lastIndex) "" else ",")
            }
            appendLine("        )")
        }
        appendLine("}")
    }

    private fun renderFunction(function: HostFunctionModel): String = buildString {
        val paramTypes =
            function.parameters
                .flatMap(ParameterModel::valTypes)
                .joinToString(prefix = "listOf(", postfix = ")") { "ValType.$it" }
                .replace("listOf()", "emptyList()")
        appendLine("            HostFunction(")
        appendLine("                moduleName,")
        appendLine("                ${function.wasmName.kotlinLiteral()},")
        appendLine(
            "                FunctionType.of($paramTypes, ${function.returnType.valTypesExpression}),"
        )
        appendLine("            ) { instance, args ->")
        appendLine("                var argIdx = 0")
        val invocationArgs = function.parameters.joinToString(", ") { it.argumentExpression }
        if (function.returnType.resultExpression == null) {
            appendLine("                functions.${function.kotlinName}($invocationArgs)")
            appendLine("                null")
        } else {
            appendLine(
                "                val result = functions.${function.kotlinName}($invocationArgs)"
            )
            appendLine("                ${function.returnType.resultExpression}")
        }
        append("            }")
    }
}

private sealed class ParameterModel {
    abstract val valTypes: List<String>
    abstract val argumentExpression: String

    data class Wasm(override val valTypes: List<String>, override val argumentExpression: String) :
        ParameterModel() {
        constructor(
            valType: String,
            argumentExpression: String,
        ) : this(listOf(valType), argumentExpression)
    }

    data class Context(override val argumentExpression: String) : ParameterModel() {
        override val valTypes: List<String> = emptyList()
    }
}

private data class ReturnModel(val valTypesExpression: String, val resultExpression: String?)

private data class HostFunctionModel(
    val hostTypeName: String,
    val kotlinName: String,
    val wasmName: String,
    val parameters: List<ParameterModel>,
    val returnType: ReturnModel,
)

private class GenerationException(message: String, val node: KSNode) : RuntimeException(message)

private fun KSDeclaration.qualifiedName(): String =
    qualifiedName?.asString() ?: simpleName.asString()

private fun KSAnnotated.hasAnnotation(qualifiedName: String): Boolean =
    annotations.any { it.annotationType.resolve().declaration.qualifiedName() == qualifiedName }

private fun KSAnnotated.annotationValue(qualifiedName: String, argumentName: String): Any? =
    annotations
        .firstOrNull { it.annotationType.resolve().declaration.qualifiedName() == qualifiedName }
        ?.arguments
        ?.firstOrNull { it.name?.asString() == argumentName }
        ?.value

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

private const val HOST_MODULE_ANNOTATION = "uk.shusek.krwa.annotations.HostModule"
private const val WASM_EXPORT_ANNOTATION = "uk.shusek.krwa.annotations.WasmExport"
private const val BUFFER_ANNOTATION = "uk.shusek.krwa.annotations.Buffer"
private const val C_STRING_ANNOTATION = "uk.shusek.krwa.annotations.CString"
