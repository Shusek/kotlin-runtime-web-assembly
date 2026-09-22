package uk.shusek.krwa.component

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class WasiPreview3FutureConcurrencyTest {
    @Test
    fun cancellationCannotBeOverwrittenByLateHostCompletion() {
        WasiPreview3.builder().build().use { wasi ->
            val future = wasi.pendingFuture<Long>()
            wasi.futureCancelWrite(future.handle())
            wasi.completeFuture(future, 42L)
            assertEquals(2L, wasi.futureRead(FutureContext(), future.handle(), 0, WitPackage.TypeRef.primitive("u32")))
            assertThrows(ComponentModelException::class.java) { runBlocking { wasi.awaitFuture(future) } }
        }
    }

    @Test
    fun repeatedCompletionKeepsCanonicalReadAndAwaitConsistent() {
        WasiPreview3.builder().build().use { wasi ->
            val future = wasi.pendingFuture<Long>()
            wasi.completeFuture(future, 42L)
            wasi.completeFuture(future, 99L)
            val context = FutureContext()
            assertEquals(0L, wasi.futureRead(context, future.handle(), 0, WitPackage.TypeRef.primitive("u32")))
            assertEquals(42L, context.value)
            assertEquals(42L, runBlocking { wasi.awaitFuture(future) })
        }
    }

    @Test
    @Timeout(60)
    fun completingOnAnotherThreadNeverLooksLikeCancellation() {
        WasiPreview3.builder().build().use { wasi ->
            val queue = ArrayBlockingQueue<Pair<WitFuture<Long>, Long>>(1)
            val failure = AtomicReference<Throwable?>()
            val producer = thread(name = "future-completion", isDaemon = true) {
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val (future, value) = queue.take()
                        wasi.completeFuture(future, value)
                    }
                } catch (_: InterruptedException) {
                    // Test cleanup interrupts the idle queue consumer.
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            try {
                val context = FutureContext()
                val type = WitPackage.TypeRef.primitive("u32")
                repeat(100_000) { index ->
                    val future = wasi.pendingFuture<Long>()
                    context.value = null
                    queue.put(future to index.toLong())
                    var status: Long
                    do {
                        failure.get()?.let { throw it }
                        assertTrue(!Thread.currentThread().isInterrupted, "Future read timed out")
                        status = wasi.futureRead(context, future.handle(), 0, type)
                        assertTrue(status == 0L || status == 0xffff_ffffL,
                            "Completion was observed as status $status at iteration $index")
                        if (status != 0L) Thread.onSpinWait()
                    } while (status != 0L)
                    assertEquals(index.toLong(), context.value)
                    wasi.futureDropReadable(future.handle())
                }
            } finally {
                producer.interrupt()
                producer.join(5_000)
                assertTrue(!producer.isAlive, "Completion thread did not stop")
            }
        }
    }

    private class FutureContext : WasiPreview3CanonicalContext {
        var value: Any? = null
        override fun storeFutureValue(ptr: Int, payloadType: WitPackage.TypeRef, value: Any?) {
            this.value = value
        }
        override fun loadFutureValue(ptr: Int, payloadType: WitPackage.TypeRef): Any? = error("not used")
        override fun readMemory(ptr: Int, len: Int): ByteArray = error("not used")
        override fun writeMemory(ptr: Int, bytes: ByteArray) = error("not used")
        override fun storeListElements(ptr: Int, payloadType: WitPackage.TypeRef, values: List<Any?>) = error("not used")
        override fun loadListElements(ptr: Int, len: Int, payloadType: WitPackage.TypeRef): List<Any?> = error("not used")
    }
}
