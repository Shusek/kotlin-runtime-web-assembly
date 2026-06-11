package uk.shusek.krwa.annotations

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class WasmModuleInterface(
    /*
     * for wasmFile we support two kind of values:
     * - "file:/" format with an absolute path
     * - local files included in the current compilation unit resources
     */
    val value: String
)
