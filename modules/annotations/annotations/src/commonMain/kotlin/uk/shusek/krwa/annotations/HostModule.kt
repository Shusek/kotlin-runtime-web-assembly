package uk.shusek.krwa.annotations

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class HostModule(val value: String)
