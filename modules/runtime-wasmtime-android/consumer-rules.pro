# Native code resolves these classes and members by their JVM names.
-keep class uk.shusek.krwa.runtime.wasmtime.android.AndroidWasmtimePulleyNative { *; }
-keep class uk.shusek.krwa.runtime.wasmtime.android.AndroidWasmtimeModuleCompilerNative { *; }
-keep class uk.shusek.krwa.runtime.wasmtime.android.AndroidWasmtimePreview3Native { *; }
-keep class uk.shusek.krwa.wasm.WasmEngineException {
    public <init>(java.lang.String);
}
-keep class uk.shusek.krwa.wasm.UninstantiableException {
    public <init>(java.lang.String);
}
-keep class uk.shusek.krwa.runtime.WasmRuntimeException {
    public <init>(java.lang.String);
}

# Keep canonical Component Model bindings that are inspected reflectively.
-keepclassmembers,allowoptimization class ** {
    @uk.shusek.krwa.annotations.* <fields>;
    @uk.shusek.krwa.annotations.* <methods>;
}
