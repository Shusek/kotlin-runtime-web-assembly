package uk.shusek.krwa.component

public interface WasiPreview3CanonicalIntrinsics {
    public fun completedFutureHandle(value: Any?): Long =
        throw ComponentModelException("canonical future intrinsics cannot create completed futures")

    public suspend fun awaitFutureValue(futureHandle: Long): Any? =
        throw ComponentModelException("canonical future intrinsics cannot await futures")

    public fun futureNew(): Long

    public fun futureRead(
        context: WasiPreview3CanonicalContext,
        futureHandle: Long,
        ptr: Int,
        payloadType: WitPackage.TypeRef,
    ): Long

    public fun futureWrite(
        context: WasiPreview3CanonicalContext,
        futureHandle: Long,
        ptr: Int,
        payloadType: WitPackage.TypeRef,
    ): Long

    public fun futureCancelRead(futureHandle: Long): Long

    public fun futureCancelWrite(futureHandle: Long): Long

    public fun futureDropReadable(futureHandle: Long)

    public fun futureDropWritable(futureHandle: Long)

    public fun streamNew(payloadType: WitPackage.TypeRef): Long

    public suspend fun awaitStreamReadable(streamHandle: Long) {
        throw ComponentModelException("canonical stream intrinsics cannot await readable streams")
    }

    public suspend fun awaitStreamWritable(streamHandle: Long) {
        throw ComponentModelException("canonical stream intrinsics cannot await writable streams")
    }

    public fun streamRead(
        context: WasiPreview3CanonicalContext,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): Long

    public fun streamWrite(
        context: WasiPreview3CanonicalContext,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
    ): Long

    public fun streamCancelRead(streamHandle: Long): Long

    public fun streamCancelWrite(streamHandle: Long): Long

    public fun streamDropReadable(streamHandle: Long)

    public fun streamDropWritable(streamHandle: Long)
}

public interface WasiPreview3CanonicalContext {
    public fun writeMemory(ptr: Int, bytes: ByteArray)

    public fun writeMemory(ptr: Int, bytes: ByteArray, offset: Int, size: Int) {
        writeMemory(
            ptr,
            if (offset == 0 && size == bytes.size) bytes else bytes.copyOfRange(offset, offset + size),
        )
    }

    public fun readMemory(ptr: Int, len: Int): ByteArray

    public fun readMemory(ptr: Int, target: ByteArray, offset: Int, size: Int) {
        val bytes = readMemory(ptr, size)
        bytes.copyInto(target, destinationOffset = offset)
    }

    public fun storeListElements(ptr: Int, payloadType: WitPackage.TypeRef, values: List<Any?>)

    public fun loadListElements(ptr: Int, len: Int, payloadType: WitPackage.TypeRef): List<Any?>

    public fun storeFutureValue(ptr: Int, payloadType: WitPackage.TypeRef, value: Any?)

    public fun loadFutureValue(ptr: Int, payloadType: WitPackage.TypeRef): Any?
}
