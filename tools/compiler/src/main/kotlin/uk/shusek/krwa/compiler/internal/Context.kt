package uk.shusek.krwa.compiler.internal

import java.util.function.IntFunction
import uk.shusek.krwa.compiler.internal.CompilerUtil.hasTooManyParameters
import uk.shusek.krwa.compiler.internal.CompilerUtil.slotCount
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionBody
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.TagImport
import uk.shusek.krwa.wasm.types.TypeSection
import uk.shusek.krwa.wasm.types.ValType

/** Class for tracking context relevant to compiling a single function. */
class Context(
    private val module: WasmModule,
    private val internalClassName: String,
    private val maxFunctionsPerClass: Int,
    private val globalTypes: List<ValType>,
    private val functionTypes: List<FunctionType>,
    private val funcId: Int,
    private val functionType: FunctionType,
    private val body: FunctionBody,
    private val tailCallFunctions: BooleanArray,
    private val tailCallTypes: BooleanArray,
    private val callIndirectClassResolver: IntFunction<String>,
    maxTempSlots: Int,
) {
    private val slots: List<Int>
    private val memorySlot: Int
    private val instanceSlot: Int
    private val tempSlot: Int
    private val trySaveBaseSlot: Int
    private val tagImports: List<TagImport>

    init {
        // Compute JVM slot indices for WASM locals.
        val slots = ArrayList<Int>(functionType.params().size + body.localTypes().size)
        var slot = 0

        // WASM arguments
        if (hasTooManyParameters(functionType)) {
            slot += 1 // long[]
        } else {
            for (param in functionType.params()) {
                slots.add(slot)
                slot += slotCount(param)
            }
        }

        // Extra arguments
        memorySlot = slot
        slot++
        instanceSlot = slot
        slot++

        // The long[] gets unboxed.
        if (hasTooManyParameters(functionType)) {
            for (param in functionType.params()) {
                slots.add(slot)
                slot += slotCount(param)
            }
        }

        // WASM locals
        for (local in body.localTypes()) {
            slots.add(slot)
            slot += slotCount(local)
        }

        this.slots = java.util.List.copyOf(slots)
        tempSlot = slot
        trySaveBaseSlot = slot + maxTempSlots

        tagImports =
            module
                .importSection()
                .imports()
                .asSequence()
                .filter { it.importType() == ExternalType.TAG }
                .map { it as TagImport }
                .toList()
    }

    fun internalClassName(): String = internalClassName

    fun globalTypes(): List<ValType> = globalTypes

    fun functionTypes(): List<FunctionType> = functionTypes

    fun type(idx: Int): FunctionType = module.typeSection().getType(idx)

    fun types(): Array<FunctionType> = module.typeSection().types()

    fun typeSection(): TypeSection = module.typeSection()

    fun getId(): Int = funcId

    fun getType(): FunctionType = functionType

    fun getBody(): FunctionBody = body

    fun needsTailCallCheck(funcId: Int): Boolean =
        funcId >= 0 && funcId < tailCallFunctions.size && tailCallFunctions[funcId]

    fun needsTailCallCheckForType(typeId: Int): Boolean =
        typeId >= 0 && typeId < tailCallTypes.size && tailCallTypes[typeId]

    fun localSlotIndex(localIndex: Int): Int = slots[localIndex]

    fun memorySlot(): Int = memorySlot

    fun instanceSlot(): Int = instanceSlot

    fun tempSlot(): Int = tempSlot

    fun trySaveBaseSlot(): Int = trySaveBaseSlot

    fun callIndirectClassName(typeId: Int): String = callIndirectClassResolver.apply(typeId)

    fun classNameForFuncGroup(prefix: String, funcId: Int): String =
        prefix + "FuncGroup_" + (funcId / maxFunctionsPerClass)

    fun tagFunctionType(tagId: Int): FunctionType {
        if (tagId < 0) {
            throw IllegalArgumentException("Tag ID must be non-negative")
        }

        val idx =
            if (tagId < tagImports.size) {
                tagImports[tagId].tagType().typeIdx()
            } else {
                val tagSection = module.tagSection()
                if (tagSection == null) {
                    throw IllegalStateException("No tag section available")
                }
                tagSection.getTag(tagId - tagImports.size).typeIdx()
            }

        return type(idx)
    }
}
