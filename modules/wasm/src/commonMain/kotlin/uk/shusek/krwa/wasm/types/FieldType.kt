package uk.shusek.krwa.wasm.types

class FieldType
private constructor(private val storageType: StorageType, private val mut: MutabilityType) {

    fun storageType(): StorageType = storageType

    fun mut(): MutabilityType = mut

    override fun equals(other: Any?): Boolean {
        if (other !is FieldType) {
            return false
        }
        return storageType == other.storageType && mut == other.mut
    }

    override fun hashCode(): Int = storageType.hashCode() * 31 + mut.hashCode()

    class Builder {
        private var storageType: StorageType? = null
        private var mut: MutabilityType? = null

        fun withStorageType(storageType: StorageType): Builder {
            this.storageType = storageType
            return this
        }

        fun withMutability(mut: MutabilityType): Builder {
            this.mut = mut
            return this
        }

        fun build(): FieldType = FieldType(storageType!!, mut!!)
    }

    companion object {
        fun builder(): Builder = Builder()
    }
}
