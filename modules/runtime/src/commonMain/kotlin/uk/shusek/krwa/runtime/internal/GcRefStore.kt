package uk.shusek.krwa.runtime.internal

import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.MStack
import uk.shusek.krwa.runtime.StackFrame
import uk.shusek.krwa.runtime.WasmArray
import uk.shusek.krwa.runtime.WasmGcRef
import uk.shusek.krwa.runtime.WasmStruct
import uk.shusek.krwa.wasm.types.Value

/**
 * Store for GC-managed references keyed by auto-assigned integers.
 *
 * Uses epoch-based deferred collection: refs are never swept during wasm execution. Collection only
 * happens at safe points between top-level calls when the wasm operand stack and all call frames
 * are empty. At that point the only roots are globals and tables.
 */
open class GcRefStore(private val instance: Instance) {
    private val refs = ArrayList<WasmGcRef?>()
    private var allocsSinceLastSweep = 0
    private var sweepRequested = false

    /** Inserts a value with an automatically assigned key. */
    fun put(value: WasmGcRef): Int {
        val id = ID_OFFSET + refs.size
        refs.add(value)
        allocsSinceLastSweep++
        if (allocsSinceLastSweep >= SWEEP_INTERVAL) {
            sweepRequested = true
        }
        return id
    }

    /** Retrieves a value by key, or null if missing. */
    operator fun get(key: Int): WasmGcRef? {
        val index = key - ID_OFFSET
        if (index < 0 || index >= refs.size) {
            return null
        }
        return refs[index]
    }

    fun getUnchecked(key: Int): WasmGcRef = refs[key - ID_OFFSET]!!

    /** Called at safe points (between top-level calls). */
    fun safePoint() {
        // Async component-model calls can suspend and keep GC references alive outside globals and
        // tables. Until those continuation roots are visible to the runtime, sweeping here can
        // remove live refs and make resumed code crash on struct/array access.
    }

    fun safePoint(stack: MStack, callStack: ArrayDeque<StackFrame>) {
        if (!sweepRequested) {
            return
        }
        sweep(stack, callStack)
        allocsSinceLastSweep = 0
        sweepRequested = false
    }

    private fun sweep(stack: MStack, callStack: ArrayDeque<StackFrame>) {
        val reachable = mutableSetOf<Int>()

        // 1. Scan operand stack and locals.
        val stackValues = stack.array()
        for (i in 0 until stack.size()) {
            markIfGcRef(stackValues[i], reachable)
        }
        for (frame in callStack) {
            for (i in 0 until frame.localSlotCount()) {
                markIfGcRef(frame.localSlot(i), reachable)
            }
        }

        // 2. Scan globals.
        val globalCount = instance.globalCount()
        for (i in 0 until globalCount) {
            val global = instance.global(i)
            markIfGcRef(global.valueLow, reachable)
        }

        // 3. Scan tables.
        val tableCount = instance.tableCount()
        for (i in 0 until tableCount) {
            val table = instance.table(i)
            for (j in 0 until table.size()) {
                markIfGcRef(table.ref(j).toLong(), reachable)
            }
        }

        // 4. Remove unreachable entries.
        for (index in refs.indices) {
            if (!reachable.contains(ID_OFFSET + index)) {
                refs[index] = null
            }
        }
    }

    private fun markIfGcRef(value: Long, reachable: MutableSet<Int>) {
        if (!isGcRefId(value)) {
            return
        }
        val id = value.toInt()
        if (!reachable.add(id)) {
            return
        }
        when (val ref = get(id)) {
            is WasmStruct -> {
                for (i in 0 until ref.fieldCount()) {
                    markIfGcRef(ref.field(i), reachable)
                }
            }
            is WasmArray -> {
                for (i in 0 until ref.length()) {
                    markIfGcRef(ref.get(i), reachable)
                }
            }
        }
    }

    companion object {
        /**
         * GC ref IDs start at this offset to avoid collisions with externref values that get
         * internalized via any.convert_extern. Since internalized externrefs and GC refs both live
         * in the ANY hierarchy, they share the same integer representation space.
         */
        const val ID_OFFSET: Int = 0x10000

        private const val SWEEP_INTERVAL: Int = 4096

        /** Checks whether a raw reference value is a GC ref ID. */
        fun isGcRefId(value: Long): Boolean =
            value >= ID_OFFSET && value != Value.REF_NULL_VALUE.toLong() && !Value.isI31(value)
    }
}
