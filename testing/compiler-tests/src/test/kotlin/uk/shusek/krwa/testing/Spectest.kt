package uk.shusek.krwa.testing

import uk.shusek.krwa.runtime.ByteBufferMemory
import uk.shusek.krwa.runtime.GlobalInstance
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportGlobal
import uk.shusek.krwa.runtime.ImportMemory
import uk.shusek.krwa.runtime.ImportTable
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.TableInstance
import uk.shusek.krwa.runtime.WasmFunctionHandle
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.MemoryLimits
import uk.shusek.krwa.wasm.types.Table
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

// https://github.com/WebAssembly/spec/blob/ee82c8e50c5106e0cedada0a083d4cc4129034a2/interpreter/host/spectest.ml
object Spectest {
    private val noop = WasmFunctionHandle { _: Instance, _: LongArray -> null }

    @JvmStatic
    fun toImportValues(): ImportValues =
        ImportValues.builder()
            .addFunction(HostFunction("spectest", "print", FunctionType.empty(), noop))
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_i32",
                    FunctionType.of(listOf(ValType.I32), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_i32_1",
                    FunctionType.of(listOf(ValType.I32), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_i32_2",
                    FunctionType.of(listOf(ValType.I32), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_f32",
                    FunctionType.of(listOf(ValType.F32), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_i32_f32",
                    FunctionType.of(listOf(ValType.I32, ValType.F32), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_i64",
                    FunctionType.of(listOf(ValType.I64), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_i64_1",
                    FunctionType.of(listOf(ValType.I64), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_i64_2",
                    FunctionType.of(listOf(ValType.I64), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_f64",
                    FunctionType.of(listOf(ValType.F64), listOf()),
                    noop,
                )
            )
            .addFunction(
                HostFunction(
                    "spectest",
                    "print_f64_f64",
                    FunctionType.of(listOf(ValType.F64, ValType.F64), listOf()),
                    noop,
                )
            )
            .addGlobal(ImportGlobal("spectest", "global_i32", GlobalInstance(Value.i32(666))))
            .addGlobal(ImportGlobal("spectest", "global_i64", GlobalInstance(Value.i64(666))))
            .addGlobal(
                ImportGlobal("spectest", "global_f32", GlobalInstance(Value.fromFloat(666.6f)))
            )
            .addGlobal(
                ImportGlobal("spectest", "global_f64", GlobalInstance(Value.fromDouble(666.6)))
            )
            .addMemory(ImportMemory("spectest", "memory", ByteBufferMemory(MemoryLimits(1, 2))))
            .addMemory(
                ImportMemory(
                    "spectest",
                    "shared_memory",
                    ByteBufferMemory(MemoryLimits(1, 2, true)),
                )
            )
            .addTable(
                ImportTable(
                    "spectest",
                    "table",
                    TableInstance(Table(ValType.FuncRef, TableLimits(10, 20)), Value.REF_NULL_VALUE),
                )
            )
            .build()
}
