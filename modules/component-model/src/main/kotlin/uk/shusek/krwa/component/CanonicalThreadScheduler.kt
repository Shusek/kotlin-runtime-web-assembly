package uk.shusek.krwa.component

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.ResumableMachine
import uk.shusek.krwa.runtime.WasmContinuation
import uk.shusek.krwa.runtime.WasmExecutionSuspended
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.types.ValType

private fun defaultCanonicalThreadScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)

internal class CanonicalThreadScheduler(
    private var scope: CoroutineScope = defaultCanonicalThreadScope(),
    private var maxCanonicalThreads: Int = WASI_PREVIEW3_UNLIMITED_RESOURCES,
) {
    private val lock = WasiPreviewLock()
    private val threads = WitResourceTable<ThreadState>()
    private var currentThread: Long = ROOT_THREAD

    fun withCoroutineScope(scope: CoroutineScope) {
        this.scope = requireNotNull(scope) { "scope" }
    }

    fun withCoroutineDispatcher(dispatcher: CoroutineDispatcher) {
        withCoroutineScope(
            CoroutineScope(SupervisorJob() + requireNotNull(dispatcher) { "dispatcher" })
        )
    }

    fun withMaxCanonicalThreads(limit: Int) {
        maxCanonicalThreads = requireWasiPreview3Limit("maxCanonicalThreads", limit)
    }

    fun threadIndex(): Long =
        withWasiPreviewLock(lock) {
            currentThread
        }

    fun newIndirect(
        instance: Instance,
        functionIndex: Long,
        closure: Long,
        tableIndex: Int = 0,
    ): Long {
        val target = resolveTableFunction(instance, tableIndex, functionIndex)
        requireWasiPreview3Capacity("canonical thread", threads.size(), 1, maxCanonicalThreads)
        val handle = threads.insertResource(ThreadState(target.instance, target.functionId, closure)).handle()
        return handle
    }

    fun spawnIndirect(
        instance: Instance,
        functionIndex: Long,
        closure: Long,
        tableIndex: Int = 0,
    ): Long {
        val handle = newIndirect(instance, functionIndex, closure, tableIndex)
        resumeLater(handle)
        return handle
    }

    fun resumeLater(threadHandle: Long) {
        val thread =
            withWasiPreviewLock(lock) {
                val thread = threads.get(threadHandle)
                thread.requireState(ThreadState.Status.SUSPENDED, "thread.resume-later")
                thread.status = ThreadState.Status.READY
                thread
            }
        scope.launch {
            runThread(threadHandle, thread, allowReady = true)
        }
    }

    fun yield(): Long {
        wasiRunBlockingOrNull {
            delay(1)
        }
        return NOT_CANCELLED
    }

    fun suspendCurrent(): Long {
        throw WasmExecutionSuspended(longArrayOf(NOT_CANCELLED))
    }

    fun yieldThenResume(threadHandle: Long): Long {
        val thread =
            withWasiPreviewLock(lock) {
                val thread = threads.get(threadHandle)
                thread.requireState(ThreadState.Status.SUSPENDED, "thread.yield-then-resume")
                thread.status = ThreadState.Status.RUNNING
                thread
            }
        runThread(threadHandle, thread, allowReady = false, stateAlreadyRunning = true)
        return NOT_CANCELLED
    }

    fun suspendThenResume(threadHandle: Long): Long {
        val thread =
            withWasiPreviewLock(lock) {
                val thread = threads.get(threadHandle)
                thread.requireState(ThreadState.Status.SUSPENDED, "thread.suspend-then-resume")
                thread.status = ThreadState.Status.RUNNING
                thread
            }
        runThread(threadHandle, thread, allowReady = false, stateAlreadyRunning = true)
        return NOT_CANCELLED
    }

    fun yieldThenPromote(threadHandle: Long): Long {
        val thread =
            withWasiPreviewLock(lock) {
                val thread = threads.get(threadHandle)
                if (thread.status != ThreadState.Status.READY) {
                    return@withWasiPreviewLock null
                }
                thread.status = ThreadState.Status.RUNNING
                thread
            }
        if (thread != null) {
            runThread(threadHandle, thread, allowReady = false, stateAlreadyRunning = true)
        } else {
            yield()
        }
        return NOT_CANCELLED
    }

    fun suspendThenPromote(threadHandle: Long): Long {
        val thread =
            withWasiPreviewLock(lock) {
                val thread = threads.get(threadHandle)
                if (thread.status != ThreadState.Status.READY) {
                    return@withWasiPreviewLock null
                }
                thread.status = ThreadState.Status.RUNNING
                thread
            }
        if (thread == null) {
            return suspendCurrent()
        }
        runThread(threadHandle, thread, allowReady = false, stateAlreadyRunning = true)
        return NOT_CANCELLED
    }

    private fun runThread(
        threadHandle: Long,
        thread: ThreadState,
        allowReady: Boolean,
        stateAlreadyRunning: Boolean = false,
    ) {
        withWasiPreviewLock(lock) {
            if (!stateAlreadyRunning) {
                if (thread.status == ThreadState.Status.DONE) {
                    return
                }
                if (allowReady) {
                    thread.requireState(ThreadState.Status.READY, "thread runner")
                } else {
                    thread.requireState(ThreadState.Status.SUSPENDED, "thread runner")
                }
                thread.status = ThreadState.Status.RUNNING
            }
            val previousThread = currentThread
            currentThread = threadHandle
            try {
                val machine = thread.instance.getMachine()
                val continuation = thread.continuation
                thread.continuation = null
                val rawResults: LongArray? =
                    if (continuation != null) {
                        if (machine !is ResumableMachine) {
                            throw ComponentModelException(
                                "canonical thread $threadHandle cannot resume on ${machine::class}"
                            )
                        }
                        machine.resume(continuation)
                    } else {
                        machine.call(
                            thread.functionId,
                            longArrayOf(thread.closure),
                        )
                    }
                val results = rawResults ?: LongArray(0)
                if (results.isNotEmpty()) {
                    throw ComponentModelException(
                        "canonical thread $threadHandle returned ${results.size} value(s)"
                    )
                }
                thread.status = ThreadState.Status.DONE
            } catch (suspended: WasmExecutionSuspended) {
                thread.continuation =
                    suspended.continuation
                        ?: throw ComponentModelException(
                            "canonical thread $threadHandle suspended without a captured continuation"
                        )
                thread.status = ThreadState.Status.SUSPENDED
            } catch (error: Throwable) {
                thread.status = ThreadState.Status.DONE
                throw error
            } finally {
                currentThread = previousThread
            }
        }
    }

    private fun resolveTableFunction(
        instance: Instance,
        tableIndex: Int,
        functionIndex: Long,
    ): ThreadTarget {
        if (functionIndex !in 0..Int.MAX_VALUE) {
            throw ComponentModelException("canonical thread function index is out of range: $functionIndex")
        }
        val table = instance.table(tableIndex)
        val elementIndex = functionIndex.toInt()
        val functionId = table.requiredRef(elementIndex)
        val targetInstance = table.instance(elementIndex) ?: instance
        val type = targetInstance.type(targetInstance.functionType(functionId))
        if (type.params().size != 1 ||
            type.params()[0] !in listOf(ValType.I32, ValType.I64) ||
            type.returns().isNotEmpty()
        ) {
            throw WasmEngineException(
                "canonical thread target must have core type (func (param i32|i64))"
            )
        }
        return ThreadTarget(targetInstance, functionId)
    }

    private data class ThreadTarget(val instance: Instance, val functionId: Int)

    private class ThreadState(
        val instance: Instance,
        val functionId: Int,
        val closure: Long,
    ) {
        var status: Status = Status.SUSPENDED
        var continuation: WasmContinuation? = null

        fun requireState(expected: Status, operation: String) {
            if (status != expected) {
                throw ComponentModelException(
                    "$operation expected thread in $expected state, found $status"
                )
            }
        }

        enum class Status {
            SUSPENDED,
            READY,
            RUNNING,
            DONE,
        }
    }

    companion object {
        private const val ROOT_THREAD: Long = 0L
        private const val NOT_CANCELLED: Long = 0L
    }
}
