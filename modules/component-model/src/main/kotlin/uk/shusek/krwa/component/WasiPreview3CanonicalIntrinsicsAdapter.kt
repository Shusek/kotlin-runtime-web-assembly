package uk.shusek.krwa.component

import uk.shusek.krwa.runtime.Instance

internal class WasiPreview3CanonicalIntrinsicsAdapter(
    private val delegate: WasiPreview3CanonicalIntrinsics,
) : CanonicalFutureIntrinsics,
    CanonicalFutureAwaitIntrinsics,
    CanonicalStreamIntrinsics,
    CanonicalStreamAwaitIntrinsics {
    override fun completedFutureHandle(value: Any?): Long =
        delegate.completedFutureHandle(value)

    override suspend fun awaitFutureValue(futureHandle: Long): Any? =
        delegate.awaitFutureValue(futureHandle)

    override fun futureNew(): Long =
        delegate.futureNew()

    override fun futureRead(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long =
        delegate.futureRead(ComponentContext(instance, abi), futureHandle, ptr, payloadType)

    override fun futureWrite(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long =
        delegate.futureWrite(ComponentContext(instance, abi), futureHandle, ptr, payloadType)

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

    override suspend fun awaitStreamReadable(streamHandle: Long) {
        delegate.awaitStreamReadable(streamHandle)
    }

    override suspend fun awaitStreamWritable(streamHandle: Long) {
        delegate.awaitStreamWritable(streamHandle)
    }

    override fun streamRead(
        instance: Instance,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long =
        delegate.streamRead(ComponentContext(instance, abi), streamHandle, ptr, len, payloadType)

    override fun streamWrite(
        instance: Instance,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        abi: CanonicalAbi,
        payloadType: WitPackage.TypeRef,
    ): Long =
        delegate.streamWrite(ComponentContext(instance, abi), streamHandle, ptr, len, payloadType)

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

    private class ComponentContext(
        private val instance: Instance,
        private val abi: CanonicalAbi,
    ) : WasiPreview3CanonicalContext {
        private val context: CanonicalAbi.Context by lazy {
            CanonicalAbi.Context.forInstance(instance)
        }

        override fun writeMemory(ptr: Int, bytes: ByteArray) {
            instance.memory().write(ptr, bytes)
        }

        override fun writeMemory(ptr: Int, bytes: ByteArray, offset: Int, size: Int) {
            instance.memory().write(ptr, bytes, offset, size)
        }

        override fun readMemory(ptr: Int, len: Int): ByteArray =
            instance.memory().readBytes(ptr, len)

        override fun readMemory(ptr: Int, target: ByteArray, offset: Int, size: Int) {
            instance.memory().read(ptr, target, offset, size)
        }

        override fun storeListElements(
            ptr: Int,
            payloadType: WitPackage.TypeRef,
            values: List<Any?>,
        ) {
            try {
                abi.storeListElements(context, ptr, payloadType, values)
            } catch (error: ComponentModelException) {
                throw ComponentModelException(
                    "WASI Preview 3 stream intrinsic failed to store ${values.size} list element(s) " +
                        "of $payloadType at ptr=$ptr: ${error.message}",
                    error,
                )
            }
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
            try {
                abi.storeValues(
                    context,
                    ptr,
                    listOf(WitPackage.Field("value", payloadType)),
                    listOf(value),
                )
            } catch (error: ComponentModelException) {
                throw ComponentModelException(
                    "WASI Preview 3 future intrinsic failed to store $payloadType at ptr=$ptr: ${error.message}",
                    error,
                )
            }
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
