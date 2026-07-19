package uk.shusek.krwa.component

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import uk.shusek.krwa.runtime.Instance

internal const val MAX_CANONICAL_ASYNC_WAITABLE_HANDLE: Long = 0x0fff_ffffL

internal class CanonicalWaitableHandleSequence(
    private var nextHandle: Long = 1L,
) {
    init {
        require(nextHandle in 1L..(MAX_CANONICAL_ASYNC_WAITABLE_HANDLE + 1L)) {
            "nextHandle must fit the canonical async subtask status encoding"
        }
    }

    fun allocate(): Long {
        if (nextHandle > MAX_CANONICAL_ASYNC_WAITABLE_HANDLE) {
            throw ComponentModelException("canonical waitable handle space exhausted")
        }
        return nextHandle++
    }
}

internal class CanonicalAsyncLowerTasks(
    private val waitableHandles: CanonicalWaitableHandleSequence =
        CanonicalWaitableHandleSequence(),
) {
    private val subtasks = LinkedHashMap<Long, AsyncSubtask>()
    private val waitableSets = WitResourceTable<WaitableSet>()
    private val futureHandles = LinkedHashMap<Long, FutureDelegate>()
    private val streamHandles = LinkedHashMap<Long, StreamDelegate>()
    private val futureReadableHandles = LinkedHashMap<Long, Long>()
    private val futureWritableHandles = LinkedHashMap<Long, Long>()
    private val streamReadableHandles = LinkedHashMap<Long, Long>()
    private val streamWritableHandles = LinkedHashMap<Long, Long>()
    private val pendingFutureReads = LinkedHashMap<Long, FutureOperation>()
    private val pendingFutureWrites = LinkedHashMap<Long, FutureOperation>()
    private val pendingStreamReads = LinkedHashMap<Long, StreamOperation>()
    private val pendingStreamWrites = LinkedHashMap<Long, StreamOperation>()
    private val passiveWaitables = LinkedHashMap<Long, PassiveWaitable>()
    private val knownWaitableHandles = LinkedHashSet<Long>()
    private val knownWaitableKinds = LinkedHashMap<Long, String>()
    private var maxWaitables: Int = WASI_PREVIEW3_UNLIMITED_RESOURCES
    private var maxInFlightHostTasks: Int = WASI_PREVIEW3_UNLIMITED_RESOURCES

    fun withMaxWaitables(limit: Int) {
        maxWaitables = requireWasiPreview3Limit("maxWaitables", limit)
    }

    fun withMaxInFlightHostTasks(limit: Int) {
        maxInFlightHostTasks = requireWasiPreview3Limit("maxInFlightHostTasks", limit)
    }

    fun startFutureSubtask(
        instance: Instance,
        futureHandle: Long,
        resultPtr: Int,
        payloadType: WitPackage.TypeRef,
        futures: CanonicalFutureIntrinsics,
        abi: CanonicalAbi,
    ): Long {
        val immediate = futures.futureRead(instance, futureHandle, resultPtr, abi, payloadType)
        if (immediate == FUTURE_COMPLETED) {
            return ASYNC_LOWER_RETURNED
        }
        if (immediate != BLOCKED) {
            throw ComponentModelException(
                "async-lower future $futureHandle completed with unsupported status $immediate"
            )
        }

        val subtask =
            AsyncSubtask(
                instance,
                futureHandle,
                resultPtr,
                payloadType,
                futures,
                abi,
            )
        requireWasiPreview3Capacity("waitable", activeWaitableCount(), 1, maxWaitables)
        requireWasiPreview3Capacity(
            "async-lower subtask",
            subtasks.size,
            1,
            maxInFlightHostTasks,
        )
        val handle = allocateWaitableHandle()
        subtasks[handle] = subtask
        subtask.handle = handle
        return packSubtaskStatus(handle, SUBTASK_STARTED)
    }

    fun futureNew(futures: CanonicalFutureIntrinsics): Long {
        requireWasiPreview3Capacity("waitable", activeWaitableCount(), 2, maxWaitables)
        val delegateHandles = futures.futureNew()
        val reader =
            externalizeFutureHandle(
                delegateHandles and U32_MASK,
                FutureHandleKind.READABLE,
                futures,
            )
        val writer =
            externalizeFutureHandle(
                (delegateHandles ushr 32) and U32_MASK,
                FutureHandleKind.WRITABLE,
                futures,
            )
        return (writer shl 32) or reader
    }

    fun streamNew(payloadType: WitPackage.TypeRef, streams: CanonicalStreamIntrinsics): Long {
        requireWasiPreview3Capacity("waitable", activeWaitableCount(), 2, maxWaitables)
        val delegateHandles = streams.streamNew(payloadType)
        val reader =
            externalizeStreamHandle(
                delegateHandles and U32_MASK,
                StreamHandleKind.READABLE,
                streams,
            )
        val writer =
            externalizeStreamHandle(
                (delegateHandles ushr 32) and U32_MASK,
                StreamHandleKind.WRITABLE,
                streams,
            )
        return (writer shl 32) or reader
    }

    fun futureRead(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        payloadType: WitPackage.TypeRef,
        futures: CanonicalFutureIntrinsics,
        abi: CanonicalAbi,
    ): Long {
        val delegate =
            requireFutureDelegate(futureHandle, FutureHandleKind.READABLE, futures)
        val status =
            futures.futureRead(instance, delegate.handle, ptr, abi, payloadType)
        if (status == BLOCKED) {
            registerPending(
                pendingFutureReads,
                futureHandle,
                FutureOperation(
                    instance,
                    futureHandle,
                    delegate.handle,
                    ptr,
                    payloadType,
                    futures,
                    abi,
                    FutureOperation.Kind.READ,
                    ::clearPendingFutureRead,
                ),
            )
        } else {
            clearPendingFutureRead(futureHandle)
        }
        return status
    }

    fun futureWrite(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        payloadType: WitPackage.TypeRef,
        futures: CanonicalFutureIntrinsics,
        abi: CanonicalAbi,
    ): Long {
        val delegate =
            requireFutureDelegate(futureHandle, FutureHandleKind.WRITABLE, futures)
        val status =
            futures.futureWrite(instance, delegate.handle, ptr, abi, payloadType)
        if (status == BLOCKED) {
            registerPending(
                pendingFutureWrites,
                futureHandle,
                FutureOperation(
                    instance,
                    futureHandle,
                    delegate.handle,
                    ptr,
                    payloadType,
                    futures,
                    abi,
                    FutureOperation.Kind.WRITE,
                    ::clearPendingFutureWrite,
                ),
            )
        } else {
            clearPendingFutureWrite(futureHandle)
        }
        return status
    }

    fun futureCancelRead(futureHandle: Long, futures: CanonicalFutureIntrinsics): Long {
        val delegate =
            requireFutureDelegate(futureHandle, FutureHandleKind.READABLE, futures)
        val status = futures.futureCancelRead(delegate.handle)
        if (status != BLOCKED) {
            clearPendingFutureRead(futureHandle)
        }
        return status
    }

    fun futureCancelWrite(futureHandle: Long, futures: CanonicalFutureIntrinsics): Long {
        val delegate =
            requireFutureDelegate(futureHandle, FutureHandleKind.WRITABLE, futures)
        val status = futures.futureCancelWrite(delegate.handle)
        if (status != BLOCKED) {
            clearPendingFutureWrite(futureHandle)
        }
        return status
    }

    fun futureDropReadable(futureHandle: Long, futures: CanonicalFutureIntrinsics) {
        clearPendingFutureRead(futureHandle)
        val delegate =
            requireFutureDelegate(futureHandle, FutureHandleKind.READABLE, futures)
        futures.futureDropReadable(delegate.handle)
        removeFutureHandle(futureHandle, delegate)
    }

    fun futureDropWritable(futureHandle: Long, futures: CanonicalFutureIntrinsics) {
        clearPendingFutureWrite(futureHandle)
        val delegate =
            requireFutureDelegate(futureHandle, FutureHandleKind.WRITABLE, futures)
        futures.futureDropWritable(delegate.handle)
        removeFutureHandle(futureHandle, delegate)
    }

    fun streamRead(
        instance: Instance,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
        streams: CanonicalStreamIntrinsics,
        abi: CanonicalAbi,
    ): Long {
        val delegate =
            requireStreamDelegate(streamHandle, StreamHandleKind.READABLE, streams)
        val status =
            streams.streamRead(instance, delegate.handle, ptr, len, abi, payloadType)
        if (status == BLOCKED) {
            registerPending(
                pendingStreamReads,
                streamHandle,
                StreamOperation(
                    instance,
                    streamHandle,
                    delegate.handle,
                    ptr,
                    len,
                    payloadType,
                    streams,
                    abi,
                    StreamOperation.Kind.READ,
                    ::clearPendingStreamRead,
                ),
            )
        } else {
            clearPendingStreamRead(streamHandle)
        }
        return status
    }

    fun streamWrite(
        instance: Instance,
        streamHandle: Long,
        ptr: Int,
        len: Int,
        payloadType: WitPackage.TypeRef,
        streams: CanonicalStreamIntrinsics,
        abi: CanonicalAbi,
    ): Long {
        val delegate =
            requireStreamDelegate(streamHandle, StreamHandleKind.WRITABLE, streams)
        val status =
            streams.streamWrite(instance, delegate.handle, ptr, len, abi, payloadType)
        if (status == BLOCKED) {
            registerPending(
                pendingStreamWrites,
                streamHandle,
                StreamOperation(
                    instance,
                    streamHandle,
                    delegate.handle,
                    ptr,
                    len,
                    payloadType,
                    streams,
                    abi,
                    StreamOperation.Kind.WRITE,
                    ::clearPendingStreamWrite,
                ),
            )
        } else {
            clearPendingStreamWrite(streamHandle)
        }
        return status
    }

    fun streamCancelRead(streamHandle: Long, streams: CanonicalStreamIntrinsics): Long {
        val delegate =
            requireStreamDelegate(streamHandle, StreamHandleKind.READABLE, streams)
        val status = streams.streamCancelRead(delegate.handle)
        if (status != BLOCKED) {
            clearPendingStreamRead(streamHandle)
        }
        return status
    }

    fun streamCancelWrite(streamHandle: Long, streams: CanonicalStreamIntrinsics): Long {
        val delegate =
            requireStreamDelegate(streamHandle, StreamHandleKind.WRITABLE, streams)
        val status = streams.streamCancelWrite(delegate.handle)
        if (status != BLOCKED) {
            clearPendingStreamWrite(streamHandle)
        }
        return status
    }

    fun streamDropReadable(streamHandle: Long, streams: CanonicalStreamIntrinsics) {
        clearPendingStreamRead(streamHandle)
        val delegate =
            requireStreamDelegate(streamHandle, StreamHandleKind.READABLE, streams)
        streams.streamDropReadable(delegate.handle)
        removeStreamHandle(streamHandle, delegate)
    }

    fun streamDropWritable(streamHandle: Long, streams: CanonicalStreamIntrinsics) {
        clearPendingStreamWrite(streamHandle)
        val delegate =
            requireStreamDelegate(streamHandle, StreamHandleKind.WRITABLE, streams)
        streams.streamDropWritable(delegate.handle)
        removeStreamHandle(streamHandle, delegate)
    }

    internal fun externalizeFutureReadableHandle(
        delegateHandle: Long,
        futures: CanonicalFutureIntrinsics,
    ): Long =
        externalizeFutureHandle(
            delegateHandle,
            FutureHandleKind.READABLE,
            futures,
        )

    internal fun externalizeStreamReadableHandle(
        delegateHandle: Long,
        streams: CanonicalStreamIntrinsics,
    ): Long =
        externalizeStreamHandle(
            delegateHandle,
            StreamHandleKind.READABLE,
            streams,
        )

    internal fun internalizeFutureReadableHandle(
        externalHandle: Long,
        futures: CanonicalFutureIntrinsics,
    ): Long {
        // WASI HTTP uses zero as the legacy "no trailers future" sentinel. It can arrive
        // directly from guest code, without first being returned by a host import and
        // therefore without an entry in the external-handle namespace.
        if (externalHandle == 0L) {
            return 0L
        }
        return requireFutureDelegate(
            externalHandle,
            FutureHandleKind.READABLE,
            futures,
        ).handle
    }

    internal fun internalizeStreamReadableHandle(
        externalHandle: Long,
        streams: CanonicalStreamIntrinsics,
    ): Long =
        requireStreamDelegate(
            externalHandle,
            StreamHandleKind.READABLE,
            streams,
        ).handle

    fun waitableSetNew(): Long {
        requireWasiPreview3Capacity("waitable", activeWaitableCount(), 1, maxWaitables)
        return waitableSets.insertResource(WaitableSet()).handle()
    }

    fun waitableSetPoll(
        instance: Instance,
        waitableSetHandle: Long,
        payloadPtr: Int,
    ): Long {
        val event =
            waitableSets.get(waitableSetHandle).pendingEvent()
                ?: WaitableEvent(EVENT_NONE, 0, 0)
        storeEvent(instance, payloadPtr, event)
        return event.code.toLong()
    }

    fun waitableSetWait(
        instance: Instance,
        waitableSetHandle: Long,
        payloadPtr: Int,
    ): Long {
        val event =
            waitableSetWaitEvent(waitableSetHandle)
                ?: WaitableEvent(EVENT_NONE, 0, 0)
        storeEvent(instance, payloadPtr, event)
        return event.code.toLong()
    }

    fun waitableSetWaitEvent(waitableSetHandle: Long): WaitableEvent? {
        val waitableSet = waitableSets.get(waitableSetHandle)
        return waitableSet.pendingEvent()
            ?: wasiRunBlockingOrNull {
                waitableSet.awaitPendingEvent()
            }
    }

    fun waitableSetDebugDescription(waitableSetHandle: Long): String =
        try {
            val waitableSet = waitableSets.get(waitableSetHandle)
            waitableSet.debugDescription()
        } catch (_: Exception) {
            " (waitable set is not registered)"
        }

    fun waitableSetDrop(waitableSetHandle: Long) {
        val waitableSet = waitableSets.remove(waitableSetHandle)
        if (waitableSet.waitables.isNotEmpty()) {
            throw ComponentModelException(
                "canonical waitable set ${waitableSetHandle.toULong()} is not empty"
            )
        }
    }

    fun waitableJoin(waitableHandle: Long, waitableSetHandle: Long) {
        val waitable = waitable(waitableHandle)
        waitable.waitableSet?.waitables?.remove(waitable)
        waitable.waitableSet =
            if (waitableSetHandle == 0L) {
                null
            } else {
                waitableSets.get(waitableSetHandle).also { waitableSet ->
                    waitableSet.waitables.add(waitable)
                }
            }
    }

    fun subtaskDrop(subtaskHandle: Long) {
        val subtask =
            subtasks.remove(subtaskHandle)
                ?: throw ComponentModelException(
                    "unknown canonical subtask handle ${subtaskHandle.toULong()}"
                )
        if (!subtask.resolveDelivered) {
            throw ComponentModelException(
                "canonical subtask ${subtaskHandle.toULong()} was dropped before resolve delivery"
            )
        }
        subtask.waitableSet?.waitables?.remove(subtask)
        subtask.waitableSet = null
    }

    fun subtaskCancel(subtaskHandle: Long): Long {
        val subtask =
            subtasks[subtaskHandle]
                ?: throw ComponentModelException(
                    "unknown canonical subtask handle ${subtaskHandle.toULong()}"
                )
        return subtask.cancel()
    }

    private fun waitable(waitableHandle: Long): AsyncWaitable =
        pendingFutureReads[waitableHandle]
            ?: pendingFutureWrites[waitableHandle]
            ?: pendingStreamReads[waitableHandle]
            ?: pendingStreamWrites[waitableHandle]
            ?: passiveWaitables[waitableHandle]
            ?: passiveWaitableOrNull(waitableHandle)
            ?: subtasks[waitableHandle]
            ?: throw ComponentModelException(
                "unknown canonical waitable handle ${waitableHandle.toULong()}"
            )

    private fun externalizeFutureHandle(
        delegateHandle: Long,
        kind: FutureHandleKind,
        futures: CanonicalFutureIntrinsics,
    ): Long {
        val checkedDelegateHandle = requireU32Handle("future", delegateHandle)
        val reverse =
            when (kind) {
                FutureHandleKind.READABLE -> futureReadableHandles
                FutureHandleKind.WRITABLE -> futureWritableHandles
            }
        val existing = reverse[checkedDelegateHandle]
        if (existing != null) {
            requireFutureDelegate(existing, kind, futures)
            return existing
        }
        requireWasiPreview3Capacity("waitable", activeWaitableCount(), 1, maxWaitables)
        val externalHandle = allocateWaitableHandle()
        futureHandles[externalHandle] =
            FutureDelegate(
                checkedDelegateHandle,
                kind,
                futures,
            )
        reverse[checkedDelegateHandle] = externalHandle
        registerKnownWaitable(externalHandle, kind.debugName)
        return externalHandle
    }

    private fun externalizeStreamHandle(
        delegateHandle: Long,
        kind: StreamHandleKind,
        streams: CanonicalStreamIntrinsics,
    ): Long {
        val checkedDelegateHandle = requireU32Handle("stream", delegateHandle)
        val reverse =
            when (kind) {
                StreamHandleKind.READABLE -> streamReadableHandles
                StreamHandleKind.WRITABLE -> streamWritableHandles
            }
        val existing = reverse[checkedDelegateHandle]
        if (existing != null) {
            requireStreamDelegate(existing, kind, streams)
            return existing
        }
        requireWasiPreview3Capacity("waitable", activeWaitableCount(), 1, maxWaitables)
        val externalHandle = allocateWaitableHandle()
        streamHandles[externalHandle] =
            StreamDelegate(
                checkedDelegateHandle,
                kind,
                streams,
            )
        reverse[checkedDelegateHandle] = externalHandle
        registerKnownWaitable(externalHandle, kind.debugName)
        return externalHandle
    }

    private fun requireFutureDelegate(
        externalHandle: Long,
        expectedKind: FutureHandleKind,
        futures: CanonicalFutureIntrinsics,
    ): FutureDelegate {
        val delegate =
            futureHandles[externalHandle]
                ?: streamHandles[externalHandle]?.let {
                    throw ComponentModelException(
                        "canonical waitable handle ${externalHandle.toULong()} is a " +
                            "${it.kind.debugName}, not a ${expectedKind.debugName}"
                    )
                }
                ?: throw ComponentModelException(
                    "unknown canonical future handle ${externalHandle.toULong()}"
                )
        if (delegate.kind != expectedKind) {
            throw ComponentModelException(
                "canonical future handle ${externalHandle.toULong()} is " +
                    "${delegate.kind.debugName}, not ${expectedKind.debugName}"
            )
        }
        if (delegate.intrinsics !== futures) {
            throw ComponentModelException(
                "canonical future handle ${externalHandle.toULong()} belongs to a different " +
                    "CanonicalFutureIntrinsics provider"
            )
        }
        return delegate
    }

    private fun requireStreamDelegate(
        externalHandle: Long,
        expectedKind: StreamHandleKind,
        streams: CanonicalStreamIntrinsics,
    ): StreamDelegate {
        val delegate =
            streamHandles[externalHandle]
                ?: futureHandles[externalHandle]?.let {
                    throw ComponentModelException(
                        "canonical waitable handle ${externalHandle.toULong()} is a " +
                            "${it.kind.debugName}, not a ${expectedKind.debugName}"
                    )
                }
                ?: throw ComponentModelException(
                    "unknown canonical stream handle ${externalHandle.toULong()}"
                )
        if (delegate.kind != expectedKind) {
            throw ComponentModelException(
                "canonical stream handle ${externalHandle.toULong()} is " +
                    "${delegate.kind.debugName}, not ${expectedKind.debugName}"
            )
        }
        if (delegate.intrinsics !== streams) {
            throw ComponentModelException(
                "canonical stream handle ${externalHandle.toULong()} belongs to a different " +
                    "CanonicalStreamIntrinsics provider"
            )
        }
        return delegate
    }

    private fun removeFutureHandle(externalHandle: Long, delegate: FutureDelegate) {
        futureHandles.remove(externalHandle)
        val reverse =
            when (delegate.kind) {
                FutureHandleKind.READABLE -> futureReadableHandles
                FutureHandleKind.WRITABLE -> futureWritableHandles
            }
        if (reverse[delegate.handle] == externalHandle) {
            reverse.remove(delegate.handle)
        }
        unregisterKnownWaitable(externalHandle)
    }

    private fun removeStreamHandle(externalHandle: Long, delegate: StreamDelegate) {
        streamHandles.remove(externalHandle)
        val reverse =
            when (delegate.kind) {
                StreamHandleKind.READABLE -> streamReadableHandles
                StreamHandleKind.WRITABLE -> streamWritableHandles
            }
        if (reverse[delegate.handle] == externalHandle) {
            reverse.remove(delegate.handle)
        }
        unregisterKnownWaitable(externalHandle)
    }

    private fun allocateWaitableHandle(): Long {
        return waitableHandles.allocate()
    }

    private fun requireU32Handle(kind: String, handle: Long): Long {
        if (handle < 0L || handle > U32_MASK) {
            throw ComponentModelException(
                "canonical $kind provider returned a handle outside u32: ${handle.toULong()}"
            )
        }
        return handle
    }

    private fun clearPendingFutureRead(futureHandle: Long) {
        clearPending(pendingFutureReads, futureHandle)
    }

    private fun clearPendingFutureWrite(futureHandle: Long) {
        clearPending(pendingFutureWrites, futureHandle)
    }

    private fun clearPendingStreamRead(streamHandle: Long) {
        clearPending(pendingStreamReads, streamHandle)
    }

    private fun clearPendingStreamWrite(streamHandle: Long) {
        clearPending(pendingStreamWrites, streamHandle)
    }

    private fun <T : AsyncWaitable> clearPending(pending: MutableMap<Long, T>, handle: Long) {
        val removed = pending.remove(handle) ?: return
        removed.detachFromWaitableSet()
        if (knownWaitableHandles.contains(handle)) {
            passiveWaitables[handle] =
                PassiveWaitable(handle, knownWaitableKinds[handle] ?: "passive")
        }
    }

    private fun <T : AsyncWaitable> registerPending(
        pending: MutableMap<Long, T>,
        handle: Long,
        waitable: T,
    ) {
        registerKnownWaitable(handle, waitable.debugName())
        val previous = pending.put(handle, waitable)
            ?: passiveWaitables.remove(handle)
        val previousSet = previous?.waitableSet
        previous?.waitableSet = null
        if (previousSet != null) {
            previousSet.waitables.remove(previous)
            waitable.waitableSet = previousSet
            previousSet.waitables.add(waitable)
        }
    }

    private fun registerKnownWaitable(handle: Long, kind: String) {
        if (!knownWaitableHandles.contains(handle)) {
            requireWasiPreview3Capacity(
                "waitable",
                activeWaitableCount(),
                1,
                maxWaitables,
            )
        }
        knownWaitableKinds[handle] = kind
        if (knownWaitableHandles.add(handle)) {
            passiveWaitables[handle] = PassiveWaitable(handle, kind)
        }
    }

    private fun unregisterKnownWaitable(handle: Long) {
        val passive = passiveWaitables.remove(handle)
        passive?.detachFromWaitableSet()
        knownWaitableHandles.remove(handle)
        knownWaitableKinds.remove(handle)
    }

    private fun activeWaitableCount(): Int =
        knownWaitableHandles.size + subtasks.size + waitableSets.size()

    private fun passiveWaitableOrNull(handle: Long): PassiveWaitable? {
        if (!knownWaitableHandles.contains(handle)) {
            return null
        }
        val kind = knownWaitableKinds[handle] ?: "passive"
        return PassiveWaitable(handle, kind).also { passiveWaitables[handle] = it }
    }

    private fun storeEvent(instance: Instance, ptr: Int, event: WaitableEvent) {
        val memory = instance.memory()
        memory.writeI32(ptr, event.payload1)
        memory.writeI32(ptr + 4, event.payload2)
    }

    private fun packSubtaskStatus(handle: Long, status: Int): Long =
        ((handle shl 4) or status.toLong()) and 0xffff_ffffL

    private data class FutureDelegate(
        val handle: Long,
        val kind: FutureHandleKind,
        val intrinsics: CanonicalFutureIntrinsics,
    )

    private enum class FutureHandleKind(val debugName: String) {
        READABLE("future-readable"),
        WRITABLE("future-writable"),
    }

    private data class StreamDelegate(
        val handle: Long,
        val kind: StreamHandleKind,
        val intrinsics: CanonicalStreamIntrinsics,
    )

    private enum class StreamHandleKind(val debugName: String) {
        READABLE("stream-readable"),
        WRITABLE("stream-writable"),
    }

    private class WaitableSet {
        val waitables = ArrayList<AsyncWaitable>()

        fun pendingEvent(): WaitableEvent? {
            for (waitable in waitables.toList()) {
                val event = waitable.pendingEvent()
                if (event != null) {
                    return event
                }
            }
            return null
        }

        suspend fun awaitPendingEvent(): WaitableEvent? {
            while (true) {
                pendingEvent()?.let { return it }
                val awaitable = waitables.toList().filter { it.canAwait() }
                if (awaitable.isEmpty()) {
                    return null
                }
                awaitAny(awaitable)
            }
        }

        fun debugDescription(): String {
            val current = waitables.toList()
            val kinds = current.joinToString(prefix = "[", postfix = "]") { it.debugName() }
            return " (waitables=${current.size}, awaitable=${current.count { it.canAwait() }}, kinds=$kinds)"
        }

        private suspend fun awaitAny(awaitable: List<AsyncWaitable>) {
            coroutineScope {
                val deferred =
                    awaitable.map { waitable ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            waitable.awaitReady()
                        }
                    }
                try {
                    select<Unit> {
                        for (candidate in deferred) {
                            candidate.onAwait {}
                        }
                    }
                } finally {
                    for (candidate in deferred) {
                        if (!candidate.isCompleted) {
                            candidate.cancel()
                        }
                    }
                }
            }
        }
    }

    private interface AsyncWaitable {
        var waitableSet: WaitableSet?

        fun canAwait(): Boolean

        suspend fun awaitReady()

        fun pendingEvent(): WaitableEvent?

        fun debugName(): String

        fun detachFromWaitableSet() {
            waitableSet?.waitables?.remove(this)
            waitableSet = null
        }
    }

    private class PassiveWaitable(
        private val handle: Long,
        private val kind: String,
    ) : AsyncWaitable {
        override var waitableSet: WaitableSet? = null

        override fun canAwait(): Boolean = false

        override suspend fun awaitReady() = Unit

        override fun pendingEvent(): WaitableEvent? = null

        override fun debugName(): String = "passive:$kind:$handle"
    }

    private class AsyncSubtask(
        private val instance: Instance,
        private val futureHandle: Long,
        private val resultPtr: Int,
        private val payloadType: WitPackage.TypeRef,
        private val futures: CanonicalFutureIntrinsics,
        private val abi: CanonicalAbi,
    ) : AsyncWaitable {
        private val futureAwaiter = futures as? CanonicalFutureAwaitIntrinsics
        var handle: Long = 0
        override var waitableSet: WaitableSet? = null
        var resolveDelivered: Boolean = false

        override fun canAwait(): Boolean = futureAwaiter != null

        override suspend fun awaitReady() {
            futureAwaiter?.awaitFutureValue(futureHandle)
        }

        override fun pendingEvent(): WaitableEvent? {
            if (resolveDelivered) {
                return null
            }
            val status = futures.futureRead(instance, futureHandle, resultPtr, abi, payloadType)
            if (status == BLOCKED) {
                return null
            }
            if (status != FUTURE_COMPLETED) {
                throw ComponentModelException(
                    "async-lower future $futureHandle completed with unsupported status $status"
                )
            }
            resolveDelivered = true
            waitableSet?.waitables?.remove(this)
            waitableSet = null
            return WaitableEvent(EVENT_SUBTASK, handle.toInt(), SUBTASK_RETURNED)
        }

        override fun debugName(): String = "subtask:$handle"

        fun cancel(): Long {
            pendingEvent()?.let { event ->
                return event.payload2.toLong()
            }
            val cancelStatus = futures.futureCancelRead(futureHandle)
            if (cancelStatus == BLOCKED) {
                return BLOCKED
            }
            resolveDelivered = true
            detachFromWaitableSet()
            return SUBTASK_CANCELLED_BEFORE_RETURNED.toLong()
        }
    }

    private class FutureOperation(
        private val instance: Instance,
        private val externalHandle: Long,
        private val delegateHandle: Long,
        private val ptr: Int,
        private val payloadType: WitPackage.TypeRef,
        private val futures: CanonicalFutureIntrinsics,
        private val abi: CanonicalAbi,
        private val kind: Kind,
        private val unregister: (Long) -> Unit,
    ) : AsyncWaitable {
        private val futureAwaiter = futures as? CanonicalFutureAwaitIntrinsics
        override var waitableSet: WaitableSet? = null

        override fun canAwait(): Boolean = futureAwaiter != null

        override suspend fun awaitReady() {
            futureAwaiter?.awaitFutureValue(delegateHandle)
        }

        override fun pendingEvent(): WaitableEvent? {
            val status =
                when (kind) {
                    Kind.READ ->
                        futures.futureRead(instance, delegateHandle, ptr, abi, payloadType)
                    Kind.WRITE ->
                        futures.futureWrite(instance, delegateHandle, ptr, abi, payloadType)
                }
            if (status == BLOCKED) {
                return null
            }
            unregister(externalHandle)
            return WaitableEvent(kind.eventCode, externalHandle.toInt(), status.toInt())
        }

        override fun debugName(): String =
            "future-${kind.name.lowercase()}:$externalHandle:$delegateHandle"

        enum class Kind(val eventCode: Int) {
            READ(EVENT_FUTURE_READ),
            WRITE(EVENT_FUTURE_WRITE),
        }
    }

    private class StreamOperation(
        private val instance: Instance,
        private val externalHandle: Long,
        private val delegateHandle: Long,
        private val ptr: Int,
        private val len: Int,
        private val payloadType: WitPackage.TypeRef,
        private val streams: CanonicalStreamIntrinsics,
        private val abi: CanonicalAbi,
        private val kind: Kind,
        private val unregister: (Long) -> Unit,
    ) : AsyncWaitable {
        private val streamAwaiter = streams as? CanonicalStreamAwaitIntrinsics
        override var waitableSet: WaitableSet? = null

        override fun canAwait(): Boolean = streamAwaiter != null

        override suspend fun awaitReady() {
            when (kind) {
                Kind.READ -> streamAwaiter?.awaitStreamReadable(delegateHandle)
                Kind.WRITE -> streamAwaiter?.awaitStreamWritable(delegateHandle)
            }
        }

        override fun pendingEvent(): WaitableEvent? {
            val status =
                when (kind) {
                    Kind.READ ->
                        streams.streamRead(instance, delegateHandle, ptr, len, abi, payloadType)
                    Kind.WRITE ->
                        streams.streamWrite(instance, delegateHandle, ptr, len, abi, payloadType)
                }
            if (status == BLOCKED) {
                return null
            }
            unregister(externalHandle)
            return WaitableEvent(kind.eventCode, externalHandle.toInt(), status.toInt())
        }

        override fun debugName(): String =
            "stream-${kind.name.lowercase()}:$externalHandle:$delegateHandle"

        enum class Kind(val eventCode: Int) {
            READ(EVENT_STREAM_READ),
            WRITE(EVENT_STREAM_WRITE),
        }
    }

    data class WaitableEvent(val code: Int, val payload1: Int, val payload2: Int)

    companion object {
        const val ASYNC_LOWER_RETURNED: Long = 2L
        private const val SUBTASK_STARTED: Int = 1
        private const val SUBTASK_RETURNED: Int = 2
        private const val SUBTASK_CANCELLED_BEFORE_RETURNED: Int = 4
        private const val EVENT_NONE: Int = 0
        private const val EVENT_SUBTASK: Int = 1
        private const val EVENT_STREAM_READ: Int = 2
        private const val EVENT_STREAM_WRITE: Int = 3
        private const val EVENT_FUTURE_READ: Int = 4
        private const val EVENT_FUTURE_WRITE: Int = 5
        private const val FUTURE_COMPLETED: Long = 0L
        private const val BLOCKED: Long = 0xffff_ffffL
        private const val U32_MASK: Long = 0xffff_ffffL
    }
}
