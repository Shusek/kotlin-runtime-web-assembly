package uk.shusek.krwa.testing

import java.util.ArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import java.util.function.Supplier
import java.util.stream.Stream
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.corpus.CorpusResources
import uk.shusek.krwa.runtime.ByteArrayMemory
import uk.shusek.krwa.runtime.ByteBufferMemory
import uk.shusek.krwa.runtime.ImportMemory
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.InterpreterMachine
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.types.MemoryLimits

class ThreadsProposalTest {
    fun interface LockWithTimeout {
        fun lock(instance: Instance, mutexAddr: Int, expected: Long): Int
    }

    @ParameterizedTest
    @MethodSource("memoryAndMachinesImplementations")
    fun threadsExample(
        memory: Memory,
        machineInject: Function<Instance.Builder, Instance.Builder>,
    ) {
        val mutexAddr = 0
        val mainInstance = newInstance(memory, machineInject)
        val workerInstance = newInstance(memory, machineInject)

        var mainLocked = tryLockMutex(mainInstance, mutexAddr)
        assertEquals(1, mainLocked)

        var workerLocked = tryLockMutex(workerInstance, mutexAddr)
        assertEquals(0, workerLocked)

        unlockMutex(mainInstance, mutexAddr)

        workerLocked = tryLockMutex(workerInstance, mutexAddr)
        assertEquals(1, workerLocked)

        mainLocked = tryLockMutex(mainInstance, mutexAddr)
        assertEquals(0, mainLocked)

        workerInstance.exports().function("unlockMutex").apply(mutexAddr.toLong())

        mainLocked = tryLockMutex(mainInstance, mutexAddr)
        assertEquals(1, mainLocked)

        val workerAcquiredLock = AtomicBoolean(false)
        val thread = Thread {
            lockMutex(workerInstance, mutexAddr)
            workerAcquiredLock.set(true)
            unlockMutex(workerInstance, mutexAddr)
        }
        thread.start()

        unlockMutex(mainInstance, mutexAddr)

        thread.join()

        assertTrue(workerAcquiredLock.get())
    }

    @ParameterizedTest
    @MethodSource("memoryMachinesAndLocksImplementations")
    fun threadsExampleWake(
        memory: Memory,
        machineInject: Function<Instance.Builder, Instance.Builder>,
        lockWithTimeout: LockWithTimeout,
    ) {
        val mutexAddr = 0
        val mainInstance = newInstance(memory, machineInject)
        val workerInstance = newInstance(memory, machineInject)

        val mainLocked = tryLockMutex(mainInstance, mutexAddr)
        assertEquals(1, mainLocked)

        val workerAcquireLock = AtomicInteger(-1)
        val workerT = Thread {
            val result = lockWithTimeout.lock(workerInstance, mutexAddr, 1L)
            workerAcquireLock.set(result)
        }
        workerT.start()
        Thread.sleep(200)

        unlockMutex(mainInstance, mutexAddr)
        workerT.join()

        assertEquals(0, workerAcquireLock.get())
    }

    @ParameterizedTest
    @MethodSource("memoryMachinesAndLocksImplementations")
    fun threadsExampleNotEqual(
        memory: Memory,
        machineInject: Function<Instance.Builder, Instance.Builder>,
        lockWithTimeout: LockWithTimeout,
    ) {
        val mutexAddr = 0
        val mainInstance = newInstance(memory, machineInject)
        val workerInstance = newInstance(memory, machineInject)

        val mainLocked = tryLockMutex(mainInstance, mutexAddr)
        assertEquals(1, mainLocked)

        val workerAcquireLock = AtomicInteger(-1)
        val workerT = Thread {
            val result = lockWithTimeout.lock(workerInstance, mutexAddr, 2L)
            workerAcquireLock.set(result)
        }
        val mainT = Thread {
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
            unlockMutex(mainInstance, mutexAddr)
        }
        workerT.start()
        mainT.start()

        mainT.join()
        workerT.join()

        assertEquals(1, workerAcquireLock.get())
    }

    @ParameterizedTest
    @MethodSource("memoryMachinesAndLocksImplementations")
    fun threadsExampleTimeout(
        memory: Memory,
        machineInject: Function<Instance.Builder, Instance.Builder>,
        lockWithTimeout: LockWithTimeout,
    ) {
        val mutexAddr = 0
        val mainInstance = newInstance(memory, machineInject)
        val workerInstance = newInstance(memory, machineInject)

        val mainLocked = tryLockMutex(mainInstance, mutexAddr)
        assertEquals(1, mainLocked)

        val workerAcquireLock = AtomicInteger(-1)
        val workerT = Thread {
            val result = lockWithTimeout.lock(workerInstance, mutexAddr, 1L)
            workerAcquireLock.set(result)
        }
        workerT.start()
        workerT.join()

        assertEquals(2, workerAcquireLock.get())
    }

    @Timeout(30)
    @ParameterizedTest
    @MethodSource("memoryAndMachinesImplementations")
    fun atomicFenceOrder(
        memory: Memory,
        machineInject: Function<Instance.Builder, Instance.Builder>,
    ) {
        val mainInstance = newInstance(memory, machineInject)
        val workerInstance = newInstance(memory, machineInject)

        val fencedReadAndVerify = mainInstance.exports().function("fenced_read_and_verify")
        val fencedWrite = workerInstance.exports().function("fenced_write")

        memory.writeI32(0, 0)
        memory.writeI32(4, 0)

        val done = AtomicBoolean(false)
        val minIterations = 10000L

        val writerT = Thread {
            while (!done.get()) {
                fencedWrite.apply()
            }
        }

        writerT.start()
        assertDoesNotThrow {
            var a: Long
            do {
                a = fencedReadAndVerify.apply()[0]
            } while (a < minIterations)
        }
        done.set(true)
        writerT.join()
    }

    @ParameterizedTest
    @MethodSource("memoryAndMachinesImplementations")
    fun concurrentMutexStressTest(
        memory: Memory,
        machineInject: Function<Instance.Builder, Instance.Builder>,
    ) {
        val numThreads = 4
        val iterationsPerThread = 1000
        val mutexAddr = 0
        val counterAddr = 4

        memory.writeI32(mutexAddr, 0)
        memory.writeI32(counterAddr, 0)

        val threads = ArrayList<Thread>()

        for (i in 0..<numThreads) {
            val instance = newInstance(memory, machineInject)
            val thread = Thread {
                for (j in 0..<iterationsPerThread) {
                    lockMutex(instance, mutexAddr)

                    val value = memory.readI32(counterAddr)
                    memory.writeI32(counterAddr, (value + 1).toInt())

                    unlockMutex(instance, mutexAddr)
                }
            }
            threads.add(thread)
        }

        for (thread in threads) {
            thread.start()
        }

        for (thread in threads) {
            thread.join(5_000)
            if (thread.isAlive) {
                thread.interrupt()
                throw AssertionError("Thread deadlocked - wait/notify bug suspected")
            }
        }

        val finalCount = memory.readI32(counterAddr)
        val expectedCount = numThreads.toLong() * iterationsPerThread
        assertEquals(expectedCount, finalCount)
    }

    companion object {
        private val memoryLimits = MemoryLimits(1, 1, true)
        private val memories: List<Supplier<Memory>> =
            listOf(
                Supplier { ByteArrayMemory(memoryLimits) },
                Supplier { ByteBufferMemory(memoryLimits) },
            )
        private val machines: List<Function<Instance.Builder, Instance.Builder>> =
            listOf(
                Function { builder ->
                    builder.withMachineFactory(
                        Function<Instance, Machine> { instance -> InterpreterMachine(instance) }
                    )
                },
                Function { builder ->
                    builder.withMachineFactory(
                        Function<Instance, Machine> { instance ->
                            MachineFactoryCompiler.compile(instance)
                        }
                    )
                },
                Function { builder ->
                    builder.withMachineFactory(
                        Function<Instance, Machine> { instance ->
                            ThreadsExampleModule.create(instance)
                        }
                    )
                },
            )
        private val locks: List<LockWithTimeout> =
            listOf(
                LockWithTimeout { instance, mutexAddr, expected ->
                    lockMutexWithTimeout(instance, mutexAddr, expected)
                },
                LockWithTimeout { instance, mutexAddr, expected ->
                    lock64MutexWithTimeout(instance, mutexAddr, expected)
                },
            )
        private val module: WasmModule = loadModule("compiled/threads-example.wat.wasm")

        @JvmStatic
        fun memoryAndMachinesImplementations(): Stream<Arguments> {
            val args = ArrayList<Arguments>()
            for (mem in memories) {
                for (machine in machines) {
                    args.add(Arguments.of(mem.get(), machine))
                }
            }
            return args.stream()
        }

        @JvmStatic
        fun memoryMachinesAndLocksImplementations(): Stream<Arguments> {
            val args = ArrayList<Arguments>()
            for (mem in memories) {
                for (machine in machines) {
                    for (lock in locks) {
                        args.add(Arguments.of(mem.get(), machine, lock))
                    }
                }
            }
            return args.stream()
        }

        private fun loadModule(fileName: String): WasmModule =
            Parser.parse(CorpusResources.getResource(fileName))

        private fun newInstance(
            memory: Memory,
            machineInject: Function<Instance.Builder, Instance.Builder>,
        ): Instance {
            val builder =
                Instance.builder(module)
                    .withImportValues(
                        ImportValues.builder()
                            .addMemory(ImportMemory("env", "memory", memory))
                            .build()
                    )

            return machineInject.apply(builder).build()
        }

        private fun tryLockMutex(instance: Instance, mutexAddr: Int): Int =
            instance.exports().function("tryLockMutex").apply(mutexAddr.toLong())[0].toInt()

        private fun lockMutexWithTimeout(instance: Instance, mutexAddr: Int, expected: Long): Int =
            instance
                .exports()
                .function("lockMutexWithTimeout")
                .apply(mutexAddr.toLong(), expected)[0]
                .toInt()

        private fun lock64MutexWithTimeout(
            instance: Instance,
            mutexAddr: Int,
            expected: Long,
        ): Int =
            instance
                .exports()
                .function("lock64MutexWithTimeout")
                .apply(mutexAddr.toLong(), expected)[0]
                .toInt()

        private fun lockMutex(instance: Instance, mutexAddr: Int) {
            instance.exports().function("lockMutex").apply(mutexAddr.toLong())
        }

        private fun unlockMutex(instance: Instance, mutexAddr: Int) {
            instance.exports().function("unlockMutex").apply(mutexAddr.toLong())
        }
    }
}
