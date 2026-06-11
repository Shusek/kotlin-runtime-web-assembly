package uk.shusek.krwa.component

import uk.shusek.krwa.runtime.Instance

interface CanonicalFutureIntrinsics {
    fun completedFutureHandle(value: Any?): Long =
        throw ComponentModelException("canonical future intrinsics cannot create completed futures")

    fun futureNew(): Long

    fun futureRead(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long

    fun futureWrite(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long

    fun futureCancelRead(futureHandle: Long): Long

    fun futureCancelWrite(futureHandle: Long): Long

    fun futureDropReadable(futureHandle: Long)

    fun futureDropWritable(futureHandle: Long)
}

internal interface CanonicalFutureAwaitIntrinsics {
    suspend fun awaitFutureValue(futureHandle: Long): Any?
}
