package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.types.Table
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

open class ImportTable(
    private val module: String,
    private val name: String,
    private val table: TableInstance,
) : ImportValue {
    constructor(
        module: String,
        name: String,
        funcRefs: Map<Int, Int>,
    ) : this(module, name, createFuncRefTable(funcRefs))

    override fun module(): String = module

    override fun name(): String = name

    override fun type(): ImportValue.Type = ImportValue.Type.TABLE

    fun table(): TableInstance = table

    private companion object {
        fun createFuncRefTable(funcRefs: Map<Int, Int>): TableInstance {
            var maxFuncRef = 0L
            for (key in funcRefs.keys) {
                if (key > maxFuncRef) {
                    maxFuncRef = key.toLong()
                }
            }

            val table =
                TableInstance(
                    Table(ValType.FuncRef, TableLimits(maxFuncRef, maxFuncRef)),
                    Value.REF_NULL_VALUE,
                )
            table.reset()
            return table
        }
    }
}
