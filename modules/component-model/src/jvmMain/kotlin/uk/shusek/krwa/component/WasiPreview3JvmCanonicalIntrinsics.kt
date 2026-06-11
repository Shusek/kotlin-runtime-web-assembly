package uk.shusek.krwa.component

import uk.shusek.krwa.runtime.Instance

internal class WasiPreview3JvmCanonicalIntrinsics(
    private val delegate: WasiPreview3CanonicalIntrinsics,
) : CanonicalFutureIntrinsics,
    CanonicalStreamIntrinsics {
    override fun completedFutureHandle(value: Any?): Long =
        delegate.completedFutureHandle(value)

    override fun futureNew(): Long =
        delegate.futureNew()

    override fun futureRead(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long =
        delegate.futureRead(JvmCanonicalContext(instance, abi), futureHandle, ptr, payloadType)

    override fun futureWrite(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long =
        delegate.futureWrite(JvmCanonicalContext(instance, abi), futureHandle, ptr, payloadType)

    override fun futureCancelRead(futureHandle: Long): Long =
        delegate.futureCancelRead(futureHandle)

    override fun futureCancelWrite(futureHandle: Long): Long =
        delegate.futureCancelWrite(futureHandle)

    override fun futureDropReadable(futureHandle: Long) {
        delegate.futureDropReadable(futureHandle)
    }

    override fun futureDropWritable(futureHandle: Long) {
        delegate.futureDropWritable(futureHandle)
    }

    override fun streamNew(payloadType: WitPackage.TypeRef): Long =
        delegate.streamNew(payloadType)

    override fun streamRead(
        instance: Instance,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long =
        delegate.streamRead(JvmCanonicalContext(instance, abi), streamHandle, ptr, len, payloadType)

    override fun streamWrite(
        instance: Instance,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long =
        delegate.streamWrite(JvmCanonicalContext(instance, abi), streamHandle, ptr, len, payloadType)

    override fun streamCancelRead(streamHandle: Long): Long =
        delegate.streamCancelRead(streamHandle)

    override fun streamCancelWrite(streamHandle: Long): Long =
        delegate.streamCancelWrite(streamHandle)

    override fun streamDropReadable(streamHandle: Long) {
        delegate.streamDropReadable(streamHandle)
    }

    override fun streamDropWritable(streamHandle: Long) {
        delegate.streamDropWritable(streamHandle)
    }

    private class JvmCanonicalContext(
        private val instance: Instance,
        private val abi: CanonicalAbi,
    ) : WasiPreview3CanonicalContext {
        private val context: CanonicalAbi.Context by lazy {
            CanonicalAbi.Context.forInstance(instance)
        }

        override fun writeMemory(ptr: Int, bytes: ByteArray) {
            instance.memory().write(ptr, bytes)
        }

        override fun readMemory(ptr: Int, len: Int): ByteArray =
            instance.memory().readBytes(ptr, len)

        override fun storeListElements(
            ptr: Int,
            payloadType: WitPackage.TypeRef,
            values: List<Any?>,
        ) {
            abi.storeListElements(context, ptr, payloadType, values)
        }

        override fun loadListElements(
            ptr: Int,
            len: Int,
            payloadType: WitPackage.TypeRef,
        ): List<Any?> =
            abi.loadListElements(context, ptr, len, payloadType)

        override fun storeFutureValue(
            ptr: Int,
            payloadType: WitPackage.TypeRef,
            value: Any?,
        ) {
            abi.storeValues(
                context,
                ptr,
                listOf(WitPackage.Field("value", payloadType)),
                listOf(value),
            )
        }

        override fun loadFutureValue(
            ptr: Int,
            payloadType: WitPackage.TypeRef,
        ): Any? =
            abi.loadValues(
                context,
                ptr,
                listOf(WitPackage.Field("value", payloadType)),
            )[0]
    }
}
