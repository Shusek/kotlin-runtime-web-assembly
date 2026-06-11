package uk.shusek.krwa.annotations

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class WasmExport(val value: String = "")
