package uk.shusek.krwa.component

public interface WasiComponentInvoker {
    public fun call(exportName: String, vararg args: Any?): Any?
}
