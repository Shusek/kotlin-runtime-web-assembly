package uk.shusek.krwa.testgen

import uk.shusek.krwa.testgen.StringUtils.Companion.capitalize
import uk.shusek.krwa.testgen.StringUtils.Companion.escapedCamelCase
import uk.shusek.krwa.testgen.wast.ActionType
import uk.shusek.krwa.testgen.wast.Command
import uk.shusek.krwa.testgen.wast.CommandType
import uk.shusek.krwa.testgen.wast.LaneType
import uk.shusek.krwa.testgen.wast.WasmValueType
import uk.shusek.krwa.testgen.wast.Wast

data class GeneratedKotlinSource(val packageName: String, val typeName: String, val source: String)

class KotlinTestGen(
    private val excludedTests: List<String>,
    private val excludedMalformedWasts: List<String>,
    private val excludedInvalidWasts: List<String>,
    private val excludedUninstantiableWasts: List<String>,
    private val excludedUnlinkableWasts: List<String>,
) {
    fun generate(name: String, wast: Wast, wasmClasspath: String): GeneratedKotlinSource {
        val packageName = "uk.shusek.krwa.test.gen"
        val testName = "SpecV1" + capitalize(escapedCamelCase(name)) + "Test"
        val fields =
            mutableListOf("private val store = Store().addImportValues(Spectest.toImportValues())")
        val methods = mutableListOf<TestMethod>()

        var testNumber = 0
        var moduleInstantiationNumber = 0
        var lastModuleVarName: String? = null
        var fallbackVarNumber = 0

        val excludedMethods =
            excludedTests
                .filter { test -> test.startsWith(testName) }
                .map { test -> test.substring(testName.length + 1) }

        var currentWasmFile: String?
        for (cmd in wast.commands()!!) {
            when (cmd.type()) {
                CommandType.MODULE -> {
                    currentWasmFile = getWasmFile(cmd, wasmClasspath)
                    lastModuleVarName = TEST_MODULE_NAME + moduleInstantiationNumber
                    var lastInstanceVarName = lastModuleVarName + "Instance"
                    moduleInstantiationNumber++
                    if (cmd.name() != null) {
                        lastModuleVarName = cmd.name()!!.replace("$", "")
                        lastInstanceVarName = cmd.name()!!.replace("$", "") + "Instance"
                    }

                    fields += "private lateinit var $lastInstanceVarName: Instance"

                    methods +=
                        TestMethod(
                            name = "instantiate_$lastInstanceVarName",
                            order = testNumber++,
                            displayName =
                                formatWastFileCoordinates(
                                    wast.sourceFilename()!!.name,
                                    cmd.line(),
                                    cmd.filename(),
                                ),
                            statements =
                                mutableListOf(
                                    "$lastInstanceVarName = " +
                                        generateModuleInstantiation(
                                            currentWasmFile,
                                            getExcluded(CommandType.ASSERT_INVALID, name),
                                        )
                                ),
                        )
                }
                CommandType.ACTION,
                CommandType.ASSERT_RETURN,
                CommandType.ASSERT_TRAP,
                CommandType.ASSERT_EXHAUSTION,
                CommandType.ASSERT_EXCEPTION -> {
                    val method =
                        createTestMethod(
                            wast.sourceFilename()!!.name,
                            cmd,
                            testNumber++,
                            excludedMethods,
                        )

                    val baseVarName = escapedCamelCase(cmd.action()!!.field()!!)
                    val varNum = fallbackVarNumber++
                    val varName = "var" + if (baseVarName.isEmpty()) varNum else baseVarName
                    val moduleName =
                        cmd.action()!!.module()?.replace("$", "")
                            ?: requireNotNull(lastModuleVarName)
                    val fieldExport = generateFieldExport(varName, cmd, moduleName)
                    if (fieldExport != null) {
                        method.statements += fieldExport
                    }

                    if (cmd.type() == CommandType.ACTION) {
                        method.statements += generateInvoke(varName, cmd)
                    } else {
                        method.statements += generateAssert(varName, cmd, moduleName + "Instance")
                    }
                    methods += method
                }
                CommandType.REGISTER -> {
                    val lastInstanceVarName = requireNotNull(lastModuleVarName) + "Instance"
                    methods +=
                        TestMethod(
                            name = "register_$lastInstanceVarName",
                            order = testNumber++,
                            statements =
                                mutableListOf(
                                    generateRegisterInstance(cmd.`as`(), lastInstanceVarName)
                                ),
                        )
                }
                CommandType.ASSERT_MALFORMED,
                CommandType.ASSERT_INVALID,
                CommandType.ASSERT_UNINSTANTIABLE,
                CommandType.ASSERT_UNLINKABLE -> {
                    val method =
                        createTestMethod(
                            wast.sourceFilename()!!.name,
                            cmd,
                            testNumber++,
                            excludedMethods,
                        )
                    generateAssertThrows(
                        wasmClasspath,
                        cmd,
                        method,
                        getExcluded(cmd.type()!!, name),
                        getExceptionType(cmd.type()!!),
                    )
                    methods += method
                }
                else ->
                    throw IllegalArgumentException("command type not yet supported ${cmd.type()}")
            }
        }

        return GeneratedKotlinSource(
            packageName,
            testName,
            renderSource(packageName, testName, fields, methods),
        )
    }

    private fun getExcluded(typ: CommandType, name: String): Boolean =
        when (typ) {
            CommandType.ASSERT_MALFORMED -> excludedMalformedWasts.contains("$name.wast")
            CommandType.ASSERT_INVALID -> excludedInvalidWasts.contains("$name.wast")
            CommandType.ASSERT_UNINSTANTIABLE -> excludedUninstantiableWasts.contains("$name.wast")
            CommandType.ASSERT_UNLINKABLE -> excludedUnlinkableWasts.contains("$name.wast")
            CommandType.ASSERT_EXHAUSTION,
            CommandType.ASSERT_TRAP,
            CommandType.ASSERT_EXCEPTION -> false
            else -> throw IllegalArgumentException(typ.toString() + "not implemented")
        }

    private fun getExceptionType(typ: CommandType): String =
        when (typ) {
            CommandType.ASSERT_MALFORMED -> "MalformedException"
            CommandType.ASSERT_INVALID -> "InvalidException"
            CommandType.ASSERT_UNINSTANTIABLE -> "UninstantiableException"
            CommandType.ASSERT_UNLINKABLE -> "UnlinkableException"
            CommandType.ASSERT_TRAP,
            CommandType.ASSERT_EXHAUSTION -> "WasmEngineException"
            CommandType.ASSERT_EXCEPTION -> "WasmException"
            else -> throw IllegalArgumentException(typ.toString() + "not implemented")
        }

    private fun createTestMethod(
        wastName: String,
        cmd: Command,
        testNumber: Int,
        excludedTests: List<String>,
    ): TestMethod {
        val methodName = "test$testNumber"
        return TestMethod(
            name = methodName,
            order = testNumber,
            displayName = formatWastFileCoordinates(wastName, cmd.line(), cmd.filename()),
            disabled = excludedTests.contains(methodName),
        )
    }

    private fun generateFieldExport(varName: String, cmd: Command, moduleName: String): String? {
        if (cmd.action() != null && cmd.action()!!.field() != null) {
            val accessor = if (cmd.action()!!.type() == ActionType.INVOKE) "function" else "global"
            return "val $varName = ${moduleName}Instance.exports().$accessor(" +
                cmd.action()!!.field()!!.kotlinLiteral() +
                ")"
        }
        return null
    }

    private fun generateAssert(varName: String, cmd: Command, moduleName: String): List<String> {
        assert(
            cmd.type() == CommandType.ASSERT_RETURN ||
                cmd.type() == CommandType.ASSERT_TRAP ||
                cmd.type() == CommandType.ASSERT_EXCEPTION ||
                cmd.type() == CommandType.ASSERT_EXHAUSTION
        )

        if (
            cmd.type() == CommandType.ASSERT_TRAP ||
                cmd.type() == CommandType.ASSERT_EXHAUSTION ||
                cmd.type() == CommandType.ASSERT_EXCEPTION
        ) {
            val statements = mutableListOf<String>()
            val invocationExpression = generateInvocationExpression(statements, varName, cmd)
            statements +=
                "val exception = assertThrows(${getExceptionType(cmd.type()!!)}::class.java) {\n" +
                    "    $invocationExpression\n" +
                    "}"
            if (cmd.text() != null) {
                statements += exceptionMessageMatch(cmd.text()!!)
            }
            return statements
        } else if (cmd.type() == CommandType.ASSERT_RETURN) {
            assert(cmd.expected() != null)

            val exprs = ArrayList<String>()
            val invocationExpression = generateInvocationExpression(exprs, varName, cmd)
            val resVarName = if (cmd.action()!!.type() == ActionType.INVOKE) "results" else "result"
            exprs.add("val $resVarName = $invocationExpression")

            for (i in cmd.expected()!!.indices) {
                val expected = cmd.expected()!![i]
                val resultVar =
                    if (cmd.action()!!.type() == ActionType.INVOKE) {
                        expected.toResultValue("$resVarName[$i]")
                    } else {
                        expected.toResultValue(resVarName)
                    }

                if (expected.type() == WasmValueType.V128) {
                    exprs.add("val expected = $resultVar")
                    when (expected.laneType()) {
                        LaneType.I8 ->
                            exprs.add("assertArrayEquals(expected, Value.vecTo8(results))")
                        LaneType.I16 ->
                            exprs.add("assertArrayEquals(expected, Value.vecTo16(results))")
                        LaneType.I32 ->
                            exprs.add("assertArrayEquals(expected, Value.vecTo32(results))")
                        LaneType.F32 ->
                            exprs.add("assertArrayEquals(expected, Value.vecToF32(results))")
                        else -> Unit
                    }
                } else {
                    exprs.add(expected.toAssertion(resultVar, moduleName))
                }
            }

            return exprs
        } else {
            throw IllegalArgumentException("Unhandled command type ${cmd.type()}")
        }
    }

    private fun generateInvoke(varName: String, cmd: Command): List<String> {
        assert(cmd.type() == CommandType.ACTION)

        val statement =
            if (cmd.action()!!.type() == ActionType.INVOKE) {
                val statements = mutableListOf<String>()
                val invocationExpression = generateInvocationExpression(statements, varName, cmd)
                statements += "assertDoesNotThrow { $invocationExpression }"
                return statements
            } else {
                "assertDoesNotThrow { $varName.value }"
            }

        return listOf(statement)
    }

    private fun generateInvocationExpression(
        statements: MutableList<String>,
        varName: String,
        cmd: Command,
    ): String {
        if (cmd.action()!!.type() != ActionType.INVOKE) {
            return "$varName.value"
        }

        val args = cmd.action()!!.args()?.map { value -> value.toArgsValue() } ?: emptyList()
        if (args.isEmpty()) {
            return "$varName.apply()"
        }

        statements += "val args = ArgsAdapter.builder()"
        for (arg in args) {
            statements += "args.add($arg)"
        }
        return "$varName.apply(*args.build())"
    }

    private fun getWasmFile(cmd: Command, wasmClasspath: String): String =
        "$wasmClasspath/${cmd.filename()}"

    private fun generateAssertThrows(
        wasmClasspath: String,
        cmd: Command,
        method: TestMethod,
        excluded: Boolean,
        exceptionType: String,
    ) {
        val wasmFile = getWasmFile(cmd, wasmClasspath)
        val invocation = generateModuleInstantiation(wasmFile, false)

        val assertThrows =
            (if (cmd.text() != null) "val exception = " else "") +
                "assertThrows($exceptionType::class.java) {\n" +
                invocation.prependIndent("    ") +
                "\n}"

        method.statements += assertThrows
        if (cmd.text() != null) {
            method.statements += exceptionMessageMatch(cmd.text()!!)
        }

        if (excluded) {
            method.disabled = true
        }
    }

    private fun exceptionMessageMatch(text: String): String =
        "assertTrue((exception.message ?: \"\").contains(${text.kotlinLiteral()}), " +
            "\"'\" + exception.message + \"' doesn't contain: '\" + ${text.kotlinLiteral()} + \"'\")"

    private fun renderSource(
        packageName: String,
        testName: String,
        fields: List<String>,
        methods: List<TestMethod>,
    ): String = buildString {
        appendLine("package $packageName")
        appendLine()
        appendLine("import org.junit.jupiter.api.Assertions.assertArrayEquals")
        appendLine("import org.junit.jupiter.api.Assertions.assertDoesNotThrow")
        appendLine("import org.junit.jupiter.api.Assertions.assertEquals")
        appendLine("import org.junit.jupiter.api.Assertions.assertNotEquals")
        appendLine("import org.junit.jupiter.api.Assertions.assertNotNull")
        appendLine("import org.junit.jupiter.api.Assertions.assertThrows")
        appendLine("import org.junit.jupiter.api.Assertions.assertTrue")
        appendLine("import org.junit.jupiter.api.Disabled")
        appendLine("import org.junit.jupiter.api.DisplayName")
        appendLine("import org.junit.jupiter.api.MethodOrderer")
        appendLine("import org.junit.jupiter.api.Order")
        appendLine("import org.junit.jupiter.api.Test")
        appendLine("import org.junit.jupiter.api.TestInstance")
        appendLine("import org.junit.jupiter.api.TestMethodOrder")
        appendLine("import uk.shusek.krwa.runtime.Instance")
        appendLine("import uk.shusek.krwa.runtime.Store")
        appendLine("import uk.shusek.krwa.runtime.WasmException")
        appendLine("import uk.shusek.krwa.testing.ArgsAdapter")
        appendLine("import uk.shusek.krwa.testing.Spectest")
        appendLine("import uk.shusek.krwa.testing.TestModule")
        appendLine("import uk.shusek.krwa.wasm.InvalidException")
        appendLine("import uk.shusek.krwa.wasm.MalformedException")
        appendLine("import uk.shusek.krwa.wasm.UninstantiableException")
        appendLine("import uk.shusek.krwa.wasm.UnlinkableException")
        appendLine("import uk.shusek.krwa.wasm.WasmEngineException")
        appendLine("import uk.shusek.krwa.wasm.types.Value")
        appendLine()
        appendLine("@TestMethodOrder(MethodOrderer.OrderAnnotation::class)")
        appendLine("@TestInstance(TestInstance.Lifecycle.PER_CLASS)")
        appendLine("class $testName {")
        for (field in fields) {
            appendLine(field.prependIndent(TAB))
        }
        appendLine()
        methods.forEachIndexed { index, method ->
            if (index > 0) {
                appendLine()
            }
            append(method.render().prependIndent(TAB))
        }
        appendLine()
        appendLine("}")
    }

    private data class TestMethod(
        val name: String,
        val order: Int,
        val displayName: String? = null,
        var disabled: Boolean = false,
        val statements: MutableList<String> = mutableListOf(),
    ) {
        fun render(): String = buildString {
            if (disabled) {
                appendLine("@Disabled(\"Test excluded\")")
            }
            appendLine("@Test")
            appendLine("@Order($order)")
            if (displayName != null) {
                appendLine("@DisplayName(${displayName.kotlinLiteral()})")
            }
            appendLine("fun $name() {")
            for (statement in statements) {
                appendLine(statement.prependIndent(TAB))
            }
            appendLine("}")
        }
    }

    companion object {
        private const val TEST_MODULE_NAME = "testModule"
        private const val TAB = "    "

        private fun formatWastFileCoordinates(
            wastName: String,
            line: Int,
            wasmName: String?,
        ): String =
            if (wasmName != null) {
                "$wastName:$line @ $wasmName"
            } else {
                "$wastName:$line"
            }

        private fun generateModuleInstantiation(
            wasmFile: String?,
            excludeInvalid: Boolean,
        ): String = buildString {
            append("TestModule.of(")
            append(wasmFile?.kotlinLiteral() ?: "null")
            append(")")
            if (excludeInvalid) {
                appendLine()
                append("    .withTypeValidation(false)")
            }
            appendLine()
            append("    .instantiate(store)")
        }

        private fun generateRegisterInstance(name: String?, instance: String): String =
            "store.register(${name.kotlinLiteral()}, $instance)"
    }
}

private fun String?.kotlinLiteral(): String {
    if (this == null) {
        return "null"
    }
    if (any { it < ' ' && it !in setOf('\b', '\t', '\n', '\r') }) {
        return "String(charArrayOf(${map { "${it.code}.toChar()" }.joinToString(", ")}))"
    }
    return buildString {
        append('"')
        for (char in this@kotlinLiteral) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> {
                    append('\\')
                    append('$')
                }
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    if (char < ' ') {
                        append("\\u")
                        append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        append(char)
                    }
            }
        }
        append('"')
    }
}
