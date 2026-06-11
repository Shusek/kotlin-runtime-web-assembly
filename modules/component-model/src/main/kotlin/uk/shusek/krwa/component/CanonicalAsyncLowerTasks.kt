package uk.shusek.krwa.component

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import uk.shusek.krwa.runtime.Instance

internal class CanonicalAsyncLowerTasks {
    private val subtasks = WitResourceTable<AsyncSubtask>()
    private val waitableSets = WitResourceTable<WaitableSet>()
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
            subtasks.size(),
            1,
            maxInFlightHostTasks,
        )
        val handle = subtasks.insertResource(subtask).handle()
        subtask.handle = handle
        return packSubtaskStatus(handle, SUBTASK_STARTED)
    }

    fun futureNew(futures: CanonicalFutureIntrinsics): Long {
        requireWasiPreview3Capacity("waitable", activeWaitableCount(), 2, maxWaitables)
        val handles = futures.futureNew()
        registerKnownWaitable(handles and 0xffff_ffffL, "future-readable")
        registerKnownWaitable((handles ushr 32) and 0xffff_ffffL, "future-writable")
        return handles
    }

    fun streamNew(payloadType: WitPackage.TypeRef, streams: CanonicalStreamIntrinsics): Long {
        requireWasiPreview3Capacity("waitable", activeWaitableCount(), 2, maxWaitables)
        val handles = streams.streamNew(payloadType)
        registerKnownWaitable(handles and 0xffff_ffffL, "stream-readable")
        registerKnownWaitable((handles ushr 32) and 0xffff_ffffL, "stream-writable")
        return handles
    }

    fun futureRead(
        instance: Instance,
        futureHandle: Long,
        ptr: Int,
        payloadType: WitPackage.TypeRef,
        futures: CanonicalFutureIntrinsics,
        abi: CanonicalAbi,
    ): Long {
        val status = futures.futureRead(instance, futureHandle, ptr, abi, payloadType)
        if (status == BLOCKED) {
            registerPending(
                pendingFutureReads,
                futureHandle,
                FutureOperation(
                    instance,
                    futureHandle,
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
        val status = futures.futureWrite(instance, futureHandle, ptr, abi, payloadType)
        if (status == BLOCKED) {
            registerPending(
                pendingFutureWrites,
                futureHandle,
                FutureOperation(
                    instance,
                    futureHandle,
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
        val status = futures.futureCancelRead(futureHandle)
        if (status != BLOCKED) {
            clearPendingFutureRead(futureHandle)
        }
        return status
    }

    fun futureCancelWrite(futureHandle: Long, futures: CanonicalFutureIntrinsics): Long {
        val status = futures.futureCancelWrite(futureHandle)
        if (status != BLOCKED) {
            clearPendingFutureWrite(futureHandle)
        }
        return status
    }

    fun futureDropReadable(futureHandle: Long, futures: CanonicalFutureIntrinsics) {
        clearPendingFutureRead(futureHandle)
        futures.futureDropReadable(futureHandle)
    }

    fun futureDropWritable(futureHandle: Long, futures: CanonicalFutureIntrinsics) {
        clearPendingFutureWrite(futureHandle)
        futures.futureDropWritable(futureHandle)
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
        val status = streams.streamRead(instance, streamHandle, ptr, len, abi, payloadType)
        if (status == BLOCKED) {
            registerPending(
                pendingStreamReads,
                streamHandle,
                StreamOperation(
                    instance,
                    streamHandle,
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
        val status = streams.streamWrite(instance, streamHandle, ptr, len, abi, payloadType)
        if (status == BLOCKED) {
            registerPending(
                pendingStreamWrites,
                streamHandle,
                StreamOperation(
                    instance,
                    streamHandle,
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
        val status = streams.streamCancelRead(streamHandle)
        if (status != BLOCKED) {
            clearPendingStreamRead(streamHandle)
        }
        return status
    }

    fun streamCancelWrite(streamHandle: Long, streams: CanonicalStreamIntrinsics): Long {
        val status = streams.streamCancelWrite(streamHandle)
        if (status != BLOCKED) {
            clearPendingStreamWrite(streamHandle)
        }
        return status
    }

    fun streamDropReadable(streamHandle: Long, streams: CanonicalStreamIntrinsics) {
        clearPendingStreamRead(streamHandle)
        streams.streamDropReadable(streamHandle)
    }

    fun streamDropWritable(streamHandle: Long, streams: CanonicalStreamIntrinsics) {
        clearPendingStreamWrite(streamHandle)
        streams.streamDropWritable(streamHandle)
    }

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
        val subtask = subtasks.remove(subtaskHandle)
        if (!subtask.resolveDelivered) {
            throw ComponentModelException(
                "canonical subtask ${subtaskHandle.toULong()} was dropped before resolve delivery"
            )
        }
        subtask.waitableSet?.waitables?.remove(subtask)
        subtask.waitableSet = null
    }

    fun subtaskCancel(subtaskHandle: Long): Long {
        val subtask = subtasks.get(subtaskHandle)
        return subtask.cancel()
    }

    private fun waitable(waitableHandle: Long): AsyncWaitable =
        pendingFutureReads[waitableHandle]
            ?: pendingFutureWrites[waitableHandle]
            ?: pendingStreamReads[waitableHandle]
            ?: pendingStreamWrites[waitableHandle]
            ?: passiveWaitables[waitableHandle]
            ?: passiveWaitableOrNull(waitableHandle)
            ?: subtasks.get(waitableHandle)

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

    private fun activeWaitableCount(): Int =
        knownWaitableHandles.size + subtasks.size() + waitableSets.size()

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
        private val futureHandle: Long,
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
            futureAwaiter?.awaitFutureValue(futureHandle)
        }

        override fun pendingEvent(): WaitableEvent? {
            val status =
                when (kind) {
                    Kind.READ -> futures.futureRead(instance, futureHandle, ptr, abi, payloadType)
                    Kind.WRITE -> futures.futureWrite(instance, futureHandle, ptr, abi, payloadType)
                }
            if (status == BLOCKED) {
                return null
            }
            unregister(futureHandle)
            return WaitableEvent(kind.eventCode, futureHandle.toInt(), status.toInt())
        }

        override fun debugName(): String = "future-${kind.name.lowercase()}:$futureHandle"

        enum class Kind(val eventCode: Int) {
            READ(EVENT_FUTURE_READ),
            WRITE(EVENT_FUTURE_WRITE),
        }
    }

    private class StreamOperation(
        private val instance: Instance,
        private val streamHandle: Long,
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
                Kind.READ -> streamAwaiter?.awaitStreamReadable(streamHandle)
                Kind.WRITE -> streamAwaiter?.awaitStreamWritable(streamHandle)
            }
        }

        override fun pendingEvent(): WaitableEvent? {
            val status =
                when (kind) {
                    Kind.READ ->
                        streams.streamRead(instance, streamHandle, ptr, len, abi, payloadType)
                    Kind.WRITE ->
                        streams.streamWrite(instance, streamHandle, ptr, len, abi, payloadType)
                }
            if (status == BLOCKED) {
                return null
            }
            unregister(streamHandle)
            return WaitableEvent(kind.eventCode, streamHandle.toInt(), status.toInt())
        }

        override fun debugName(): String = "stream-${kind.name.lowercase()}:$streamHandle"

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
    }
}
