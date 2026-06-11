package uk.shusek.krwa.runtime

import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.Table
import uk.shusek.krwa.wasm.types.TableLimits
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasm.types.Value

open class TableInstance(private val table: Table, initialValue: Int) {
    private var instances = arrayOfNulls<Instance>(table.limits().min().toInt())
    private var refs = IntArray(table.limits().min().toInt()) { initialValue }

    fun size(): Int = refs.size

    fun elementType(): ValType = table.elementType()

    fun limits(): TableLimits = table.limits()

    fun grow(size: Int, value: Int, instance: Instance?): Int {
        val oldSize = refs.size
        val targetSize = oldSize + size
        if (size < 0 || targetSize > limits().max()) {
            return -1
        }
        val newRefs = refs.copyOf(targetSize)
        newRefs.fill(value, oldSize, targetSize)
        val newInstances = instances.copyOf(targetSize)
        newInstances.fill(instance, oldSize, targetSize)
        refs = newRefs
        instances = newInstances
        table.limits().grow(size)
        return oldSize
    }

    fun ref(index: Int): Int {
        if (index < 0 || index >= refs.size) {
            throw WasmEngineException("undefined element")
        }
        return refs[index]
    }

    fun requiredRef(index: Int): Int {
        val ref = ref(index)
        if (ref == Value.REF_NULL_VALUE) {
            throw WasmEngineException("uninitialized element $index")
        }
        return ref
    }

    fun setRef(index: Int, value: Int, instance: Instance?) {
        if (index < 0 || index >= refs.size || index >= instances.size) {
            throw UninstantiableException("out of bounds table access")
        }
        refs[index] = value
        instances[index] = instance
    }

    fun instance(index: Int): Instance? = instances[index]

    fun reset() {
        refs.fill(Value.REF_NULL_VALUE)
    }
}
