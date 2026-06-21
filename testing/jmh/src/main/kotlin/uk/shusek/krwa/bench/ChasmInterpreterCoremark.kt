package uk.shusek.krwa.bench

import io.github.charlietap.chasm.embedding.dsl.imports
import io.github.charlietap.chasm.embedding.instance
import io.github.charlietap.chasm.embedding.invoke
import io.github.charlietap.chasm.embedding.module
import io.github.charlietap.chasm.embedding.shapes.ChasmResult
import io.github.charlietap.chasm.embedding.shapes.flatMap
import io.github.charlietap.chasm.embedding.store
import io.github.charlietap.chasm.runtime.value.ExecutionValue
import io.github.charlietap.chasm.runtime.value.NumberValue

internal object ChasmInterpreterCoremark {
    fun run(bytes: ByteArray): CoremarkResult {
        val start = System.nanoTime()
        val store = store()
        val imports =
            imports(store) {
                function {
                    moduleName = "env"
                    entityName = "clock_ms"
                    type {
                        results {
                            i64()
                        }
                    }
                    reference {
                        listOf<ExecutionValue>(NumberValue.I64(System.currentTimeMillis()))
                    }
                }
            }

        val result =
            module(bytes)
                .flatMap { decodedModule ->
                    instance(store, decodedModule, imports)
                }
                .flatMap { instance ->
                    invoke(store, instance, "run")
                }
        val elapsedNanos = System.nanoTime() - start

        val values =
            when (result) {
                is ChasmResult.Success -> result.result
                is ChasmResult.Error -> error("Chasm interpreter failed: ${result.error}")
            }
        val score =
            (values.firstOrNull() as? NumberValue.F32)?.value
                ?: error("Chasm interpreter returned unexpected result: $values")
        return CoremarkResult(score, elapsedNanos)
    }
}
