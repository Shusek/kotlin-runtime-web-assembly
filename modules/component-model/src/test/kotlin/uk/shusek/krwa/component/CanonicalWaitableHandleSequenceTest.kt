package uk.shusek.krwa.component

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import uk.shusek.krwa.runtime.Instance

class CanonicalWaitableHandleSequenceTest {
    @Test
    fun reservesFourStatusBitsForPackedSubtaskHandles() {
        val handles =
            CanonicalWaitableHandleSequence(MAX_CANONICAL_ASYNC_WAITABLE_HANDLE)

        assertEquals(MAX_CANONICAL_ASYNC_WAITABLE_HANDLE, handles.allocate())
        assertThrows(ComponentModelException::class.java) {
            handles.allocate()
        }
    }

    @Test
    fun preservesZeroFutureSentinelWithoutAllocatingADelegate() {
        val tasks = CanonicalAsyncLowerTasks()

        assertEquals(
            0L,
            tasks.internalizeFutureReadableHandle(0L, UnusedFutureIntrinsics),
        )
    }

    private object UnusedFutureIntrinsics : CanonicalFutureIntrinsics {
        override fun futureNew(): Long = error("not used")

        override fun futureRead(
            instance: Instance,
            futureHandle: Long,
            ptr: Int,
            abi: CanonicalAbi,
            payloadType: WitPackage.TypeRef,
        ): Long = error("not used")

        override fun futureWrite(
            instance: Instance,
            futureHandle: Long,
            ptr: Int,
            abi: CanonicalAbi,
            payloadType: WitPackage.TypeRef,
        ): Long = error("not used")

        override fun futureCancelRead(futureHandle: Long): Long = error("not used")

        override fun futureCancelWrite(futureHandle: Long): Long = error("not used")

        override fun futureDropReadable(futureHandle: Long) = error("not used")

        override fun futureDropWritable(futureHandle: Long) = error("not used")
    }
}
