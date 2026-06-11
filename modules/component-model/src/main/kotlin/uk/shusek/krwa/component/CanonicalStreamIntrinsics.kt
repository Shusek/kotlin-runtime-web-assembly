package uk.shusek.krwa.component

import uk.shusek.krwa.runtime.Instance

interface CanonicalStreamIntrinsics {
    fun streamNew(payloadType: WitPackage.TypeRef): Long

    fun streamRead(
        instance: Instance,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long

    fun streamWrite(
        instance: Instance,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long

    fun streamCancelRead(streamHandle: Long): Long

    fun streamCancelWrite(streamHandle: Long): Long

    fun streamDropReadable(streamHandle: Long)

    fun streamDropWritable(streamHandle: Long)
}

internal interface CanonicalStreamAwaitIntrinsics {
    suspend fun awaitStreamReadable(streamHandle: Long)

    suspend fun awaitStreamWritable(streamHandle: Long)
}
