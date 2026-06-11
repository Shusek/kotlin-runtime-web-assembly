package uk.shusek.krwa.testing

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.objectweb.asm.ClassTooLargeException
import uk.shusek.krwa.compiler.MachineFactoryCompiler
import uk.shusek.krwa.compiler.internal.ClassCollector
import uk.shusek.krwa.compiler.internal.ClassLoadingCollector
import uk.shusek.krwa.compiler.internal.Compiler
import uk.shusek.krwa.corpus.WatGenerator.bigWat
import uk.shusek.krwa.runtime.ExportFunction
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wabt.Wat2Wasm
import uk.shusek.krwa.wasm.Parser

class ClassTooLargeTest {
    @Test
    fun testFunc50k() {
        var funcCount = 50_000
        val wasm = Wat2Wasm.parse(bigWat(funcCount, 0))
        val instance =
            Instance.builder(Parser.parse(wasm))
                .withMachineFactory { instance -> MachineFactoryCompiler.compile(instance) }
                .withStart(false)
                .build()

        funcCount = 1000
        var expected = 0
        for (i in 1..funcCount) {
            expected += i
        }
        val func1: ExportFunction = instance.export("func_$funcCount")
        assertEquals(expected.toLong(), func1.apply(0L)[0])
    }

    @Test
    fun testManyBigFuncs() {
        val funcCount = 10
        val wasm = Wat2Wasm.parse(bigWat(funcCount, 15_000))
        val instance =
            Instance.builder(Parser.parse(wasm))
                .withMachineFactory { instance -> MachineFactoryCompiler.compile(instance) }
                .withStart(false)
                .build()

        var expected = 0
        for (i in 1..funcCount) {
            expected += i
        }
        val func1: ExportFunction = instance.export("func_$funcCount")
        assertEquals(expected.toLong(), func1.apply(0L)[0])
    }

    @Test
    fun testCallIndirectBridgeRetryAfterClassTooLarge() {
        val wasm = Wat2Wasm.parse(manyTypesWat(4))
        val module = Parser.parse(wasm)
        val failedOnce = AtomicBoolean(false)

        val result =
            Compiler.builder(module)
                .withClassCollectorFactory { ClassTooLargeOnceCollector(failedOnce) }
                .build()
                .compile()

        val classNames = result.classBytes().keys
        assertTrue(
            classNames.any { it.contains("CallIndirectBridge") },
            "Expected CallIndirectBridge classes to be generated, got: $classNames",
        )
        assertTrue(failedOnce.get(), "Expected compiler to retry after ClassTooLargeException")

        val factory = (result.collector() as ClassTooLargeOnceCollector).machineFactory()
        val instance = Instance.builder(module).withMachineFactory(factory).withStart(false).build()
        assertEquals(42L, instance.export("main").apply()[0])
    }

    // Takes ~30-50s: generates 33k types to exceed the 65535 constant pool limit,
    // triggering ClassTooLargeException and verifying the call_indirect bridge splitting.
    @Test
    @Timeout(120)
    fun testCallIndirectBridge() {
        val wasm = Wat2Wasm.parse(manyTypesWat(33_000))
        val module = Parser.parse(wasm)

        val result = Compiler.builder(module).build().compile()
        val classNames = result.classBytes().keys
        assertTrue(
            classNames.stream().anyMatch { k -> k.contains("CallIndirectBridge") },
            "Expected CallIndirectBridge classes to be generated, got: $classNames",
        )

        val instance =
            Instance.builder(module)
                .withMachineFactory { instance -> MachineFactoryCompiler.compile(instance) }
                .withStart(false)
                .build()
        assertEquals(42L, instance.export("main").apply()[0])
    }

    companion object {
        fun manyTypesWat(typeCount: Int): String {
            val valTypes = arrayOf("i32", "i64", "f32", "f64")
            val sb = StringBuilder()
            sb.append("(module\n")

            sb.append("  (type (func (result i32)))\n")

            for (i in 0..<typeCount) {
                sb.append("  (type (func (param")
                var n = i
                for (j in 0..7) {
                    sb.append(" ").append(valTypes[n % 4])
                    n /= 4
                }
                sb.append(")))\n")
            }

            sb.append("  (table 1 funcref)\n")
            sb.append("  (elem (i32.const 0) \$f0)\n")

            sb.append("  (func \$f0 (type 0) i32.const 42)\n")

            sb.append("  (func (export \"main\") (result i32)\n")
            sb.append("    i32.const 0\n")
            sb.append("    call_indirect (type 0)\n")
            sb.append("  )\n")

            sb.append(")\n")
            return sb.toString()
        }
    }

    private class ClassTooLargeOnceCollector(
        private val failedOnce: AtomicBoolean,
    ) : ClassCollector {
        private val delegate = ClassLoadingCollector()

        override fun mainClassName(): String = delegate.mainClassName()

        override fun putMainClass(className: String, bytes: ByteArray) {
            if (failedOnce.compareAndSet(false, true)) {
                throw ClassTooLargeException(className, 65_536)
            }
            delegate.putMainClass(className, bytes)
        }

        override fun put(name: String, data: ByteArray) {
            delegate.put(name, data)
        }

        override fun putAll(collector: ClassCollector) {
            delegate.putAll(collector)
        }

        override fun classBytes(): Map<String, ByteArray> = delegate.classBytes()

        override fun resolve(clazz: Class<*>): ByteArray = delegate.resolve(clazz)

        fun machineFactory(): (Instance) -> Machine = delegate.machineFactory()
    }
}
