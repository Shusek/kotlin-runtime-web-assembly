package uk.shusek.krwa.component

import uk.shusek.krwa.wasm.Parser

object ComponentModelLeanRuntimeSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        check(ComponentModelLeanRuntimeSmoke::class.java.getResource("/wasm-tools.wasm") == null) {
            "The lean component-model runtime unexpectedly contains wasm-tools.wasm"
        }

        val witPackage =
            WitPackage.parse(
                """
                package krwa:lean-runtime;

                world plugin {}
                """.trimIndent(),
            )
        val module =
            Parser.parse(byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00))
        WasmPlugin.builder(witPackage).withModule(module)

        val missingTooling =
            runCatching {
                WasmToolsInvoker.run(listOf("wasm-tools", "--version"), emptyMap())
            }.exceptionOrNull()
        check(missingTooling is ComponentModelException)
        check(missingTooling.message?.contains("component-model-tooling") == true)
    }
}
