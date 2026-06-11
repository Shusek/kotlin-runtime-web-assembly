package uk.shusek.krwa.testgen.wast

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty

open class WasmValue {
    @field:JsonProperty("type") private var type: WasmValueType? = null

    @field:JsonProperty("value")
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    private var value: Array<String?>? = null

    @field:JsonProperty("lane_type") private var laneType: LaneType? = null

    open fun type(): WasmValueType? = type

    open fun laneType(): LaneType? = laneType

    open fun toResultValue(result: String): String =
        when (type) {
            WasmValueType.I64 -> result
            WasmValueType.I32 -> "$result.toInt()"
            WasmValueType.F32 -> "java.lang.Float.intBitsToFloat($result.toInt()), 0.0f"
            WasmValueType.F64 -> "java.lang.Double.longBitsToDouble($result), 0.0"
            WasmValueType.EXTERN_REF,
            WasmValueType.EXN_REF,
            WasmValueType.FUNC_REF,
            WasmValueType.STRUCT_REF,
            WasmValueType.ANY_REF,
            WasmValueType.NULL_REF,
            WasmValueType.NULL_FUNC_REF,
            WasmValueType.NULL_EXTERN_REF,
            WasmValueType.ARRAY_REF,
            WasmValueType.EQ_REF,
            WasmValueType.I31_REF,
            WasmValueType.REF_NULL -> {
                if (result == "null") {
                    "Value.REF_NULL_VALUE"
                } else {
                    result
                }
            }
            WasmValueType.V128 -> resultVectorValue()
            null -> throw IllegalArgumentException("Type not recognized $type")
        }

    open fun toAssertion(resultVar: String, moduleName: String): String {
        if (value == null) {
            // Mirrors the spec interpreter checks for opaque reference values.
            return when (type) {
                WasmValueType.FUNC_REF -> "assertTrue($resultVar >= 0)"
                WasmValueType.EXTERN_REF ->
                    "assertNotEquals($resultVar, Value.REF_NULL_VALUE.toLong())"
                WasmValueType.REF_NULL -> "assertEquals($resultVar, Value.REF_NULL_VALUE.toLong())"
                WasmValueType.ARRAY_REF -> "assertNotNull($moduleName.array(results[0].toInt()))"
                WasmValueType.EQ_REF -> "assertNotNull($moduleName.array(results[0].toInt()))"
                WasmValueType.NULL_REF,
                WasmValueType.NULL_FUNC_REF,
                WasmValueType.NULL_EXTERN_REF ->
                    "assertEquals($resultVar, Value.REF_NULL_VALUE.toLong())"
                WasmValueType.STRUCT_REF,
                WasmValueType.ANY_REF,
                WasmValueType.I31_REF ->
                    "assertNotEquals($resultVar, Value.REF_NULL_VALUE.toLong())"
                else ->
                    throw IllegalArgumentException("cannot generate assertion for WasmValue: $this")
            }
        }

        val expectedVar = toExpectedValue()
        return "assertEquals($expectedVar, $resultVar)"
    }

    open fun toExpectedValue(): String =
        when (type) {
            WasmValueType.I32 -> "java.lang.Integer.parseInt(\"${value!![0]}\")"
            WasmValueType.I64 -> "java.lang.Long.parseLong(\"${value!![0]}\")"
            WasmValueType.F32 -> {
                if (value!![0] != null) {
                    when (value!![0]) {
                        "nan:canonical",
                        "nan:arithmetic" -> "Float.NaN"
                        else ->
                            "java.lang.Float.intBitsToFloat(java.lang.Integer.parseUnsignedInt(\"${value!![0]}\"))"
                    }
                } else {
                    "null"
                }
            }
            WasmValueType.F64 -> {
                if (value!![0] != null) {
                    when (value!![0]) {
                        "nan:canonical",
                        "nan:arithmetic" -> "Double.NaN"
                        else ->
                            "java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(\"${value!![0]}\"))"
                    }
                } else {
                    "null"
                }
            }
            WasmValueType.EXTERN_REF,
            WasmValueType.EXN_REF,
            WasmValueType.STRUCT_REF,
            WasmValueType.ANY_REF,
            WasmValueType.NULL_REF,
            WasmValueType.NULL_FUNC_REF,
            WasmValueType.NULL_EXTERN_REF,
            WasmValueType.ARRAY_REF,
            WasmValueType.EQ_REF,
            WasmValueType.FUNC_REF -> {
                if (value!![0] == "null") {
                    "Value.REF_NULL_VALUE.toLong()"
                } else {
                    "java.lang.Long.parseLong(\"${value!![0]}\")"
                }
            }
            WasmValueType.V128 -> resultVectorValue()
            WasmValueType.I31_REF,
            WasmValueType.REF_NULL,
            null -> throw IllegalArgumentException("Type not recognized $type")
        }

    open fun shortLaneValue(v: String): String {
        val intValue = v.toInt()
        return (0xFFFF and intValue).toString()
    }

    open fun intLaneValue(v: String): String {
        val longValue = v.toLong()
        return Integer.toUnsignedString((0xFFFFFFFFL and longValue).toInt()) + "L"
    }

    open fun toArgsValue(): String =
        when (type) {
            WasmValueType.I32 -> "java.lang.Integer.parseInt(\"${value!![0]}\").toLong()"
            WasmValueType.F32 -> {
                if (value!![0] != null) {
                    when (value!![0]) {
                        "nan:canonical",
                        "nan:arithmetic" -> "Float.NaN.toInt().toLong()"
                        else -> "java.lang.Integer.parseUnsignedInt(\"${value!![0]}\").toLong()"
                    }
                } else {
                    "null"
                }
            }
            WasmValueType.I64 -> "java.lang.Long.parseLong(\"${value!![0]}\")"
            WasmValueType.F64 -> {
                if (value!![0] != null) {
                    when (value!![0]) {
                        "nan:canonical",
                        "nan:arithmetic" -> "Double.NaN.toLong()"
                        else -> "java.lang.Long.parseUnsignedLong(\"${value!![0]}\")"
                    }
                } else {
                    "null"
                }
            }
            WasmValueType.EXTERN_REF,
            WasmValueType.EXN_REF,
            WasmValueType.STRUCT_REF,
            WasmValueType.ANY_REF,
            WasmValueType.NULL_REF,
            WasmValueType.NULL_FUNC_REF,
            WasmValueType.NULL_EXTERN_REF,
            WasmValueType.ARRAY_REF,
            WasmValueType.EQ_REF,
            WasmValueType.I31_REF,
            WasmValueType.FUNC_REF -> {
                if (value!![0] == "null") {
                    "Value.REF_NULL_VALUE.toLong()"
                } else {
                    "java.lang.Long.parseLong(\"${value!![0]}\")"
                }
            }
            WasmValueType.V128 -> argsVectorValue()
            WasmValueType.REF_NULL,
            null -> throw IllegalArgumentException("Type not recognized $type")
        }

    private fun resultVectorValue(): String {
        val values = value!!
        val sb = StringBuilder()
        when (laneType) {
            LaneType.I8 -> sb.append("byteArrayOf(")
            LaneType.I16 -> sb.append("intArrayOf(")
            LaneType.I32,
            LaneType.I64 -> sb.append("longArrayOf(")
            LaneType.F32 -> sb.append("floatArrayOf(")
            LaneType.F64 -> sb.append("doubleArrayOf(")
            null -> throw IllegalArgumentException("Lane type not recognized $laneType")
        }

        var first = true
        for (v in values) {
            if (first) {
                first = false
            } else {
                sb.append(", ")
            }

            sb.append(resultLaneValue(v!!))
        }
        sb.append(")")
        return sb.toString()
    }

    private fun resultLaneValue(v: String): String =
        when (laneType) {
            LaneType.I8 -> "(java.lang.Integer.parseInt(\"$v\") and 0xFF).toByte()"
            LaneType.I16 -> shortLaneValue(v)
            LaneType.I32 -> intLaneValue(v)
            LaneType.I64 -> "java.lang.Long.parseLong(\"$v\")"
            LaneType.F32 ->
                when (v) {
                    "nan:canonical",
                    "nan:arithmetic" -> "Float.NaN"
                    else ->
                        "java.lang.Float.intBitsToFloat(java.lang.Integer.parseUnsignedInt(\"$v\"))"
                }
            LaneType.F64 ->
                when (v) {
                    "nan:canonical",
                    "nan:arithmetic" -> "Double.NaN"
                    else ->
                        "java.lang.Double.longBitsToDouble(java.lang.Long.parseUnsignedLong(\"$v\"))"
                }
            null -> throw IllegalArgumentException("Lane type not recognized $laneType")
        }

    private fun argsVectorValue(): String {
        val values = value!!
        val sb = StringBuilder()
        when (laneType) {
            LaneType.I8 -> sb.append("Value.i8ToVec(")
            LaneType.I16 -> sb.append("Value.i16ToVec(")
            LaneType.I32 -> sb.append("Value.i32ToVec(")
            LaneType.I64 -> sb.append("Value.i64ToVec(")
            LaneType.F32 -> sb.append("Value.f32ToVec(")
            LaneType.F64 -> sb.append("Value.f64ToVec(")
            null -> throw IllegalArgumentException("Lane type not recognized $laneType")
        }

        sb.append("longArrayOf(")
        var first = true
        for (v in values) {
            if (first) {
                first = false
            } else {
                sb.append(", ")
            }

            sb.append(argsLaneValue(v!!))
        }
        sb.append("))")
        return sb.toString()
    }

    private fun argsLaneValue(v: String): String =
        when (laneType) {
            LaneType.I8 -> "(java.lang.Integer.parseInt(\"$v\").toLong() and 0xFFL)"
            LaneType.I16 -> "${shortLaneValue(v)}.toLong()"
            LaneType.I32 -> intLaneValue(v)
            LaneType.I64 -> "java.lang.Long.parseLong(\"$v\")"
            LaneType.F32 -> "java.lang.Integer.parseUnsignedInt(\"$v\").toLong()"
            LaneType.F64 -> "java.lang.Long.parseUnsignedLong(\"$v\")"
            null -> throw IllegalArgumentException("Lane type not recognized $laneType")
        }
}
