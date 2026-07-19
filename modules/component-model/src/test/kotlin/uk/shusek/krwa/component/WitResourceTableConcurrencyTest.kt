package uk.shusek.krwa.component

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WitResourceTableConcurrencyTest {
    @Test
    fun updateIfPresentDoesNotReviveAReleasedResource() {
        val table = WitResourceTable<AtomicInteger>()
        val resource = table.insert(AtomicInteger(1))

        assertTrue(table.updateIfPresent(resource.handle()) { it.incrementAndGet() })
        assertEquals(2, table.get(resource).get())
        table.remove(resource)

        assertFalse(table.updateIfPresent(resource.handle()) { it.incrementAndGet() })
        assertEquals(0, table.size())
    }

    @Test
    fun concurrentInsertionsNeverExceedTheConfiguredLimit() {
        val limit = 64
        val attempts = 512
        val table = WitResourceTable<Int>(limit)
        val start = CountDownLatch(1)
        val successfulInsertions = AtomicInteger()
        val rejectedInsertions = AtomicInteger()
        val handles = ConcurrentHashMap.newKeySet<Long>()
        val executor = Executors.newFixedThreadPool(16)

        try {
            val tasks =
                (0 until attempts).map { value ->
                    executor.submit {
                        start.await()
                        try {
                            val handle = table.insert(value).handle()
                            assertTrue(handles.add(handle), "duplicate resource handle $handle")
                            successfulInsertions.incrementAndGet()
                        } catch (failure: ComponentModelException) {
                            assertTrue(failure.message.orEmpty().contains("limit exceeded"))
                            rejectedInsertions.incrementAndGet()
                        }
                    }
                }

            start.countDown()
            for (task in tasks) {
                task.get(20, TimeUnit.SECONDS)
            }

            assertEquals(limit, successfulInsertions.get())
            assertEquals(attempts - limit, rejectedInsertions.get())
            assertEquals(limit, handles.size)
            assertEquals(limit, table.size())
            assertEquals(limit, table.snapshot().toSet().size)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun closeAtomicallyDrainsAllSuccessfulConcurrentInsertions() {
        val workers = 8
        val attemptsPerWorker = 2_000
        val table = WitResourceTable<Int>(workers * attemptsPerWorker)
        val start = CountDownLatch(1)
        val firstInsertion = CountDownLatch(1)
        val insertedValues = ConcurrentHashMap.newKeySet<Int>()
        val drainedValues = AtomicReference<List<Int>>()
        val executor = Executors.newFixedThreadPool(workers + 1)

        try {
            val insertionTasks =
                (0 until workers).map { worker ->
                    executor.submit {
                        start.await()
                        repeat(attemptsPerWorker) { attempt ->
                            val value = worker * attemptsPerWorker + attempt
                            try {
                                table.insert(value)
                                insertedValues.add(value)
                                firstInsertion.countDown()
                            } catch (failure: ComponentModelException) {
                                assertTrue(failure.message.orEmpty().contains("closed"))
                            }
                        }
                    }
                }
            val closeTask =
                executor.submit {
                    start.await()
                    firstInsertion.await()
                    drainedValues.set(table.close())
                }

            start.countDown()
            for (task in insertionTasks) {
                task.get(20, TimeUnit.SECONDS)
            }
            closeTask.get(20, TimeUnit.SECONDS)

            val drained = drainedValues.get()
            assertEquals(insertedValues.size, drained.size)
            assertEquals(insertedValues, drained.toSet())
            assertEquals(0, table.size())
            assertEquals(emptyList<Int>(), table.close())
            assertThrows(ComponentModelException::class.java) {
                table.insert(-1)
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
