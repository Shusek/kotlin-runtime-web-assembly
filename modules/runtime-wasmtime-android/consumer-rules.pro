# Native code resolves these classes and members by their JVM names.
-keep class uk.shusek.krwa.runtime.wasmtime.android.AndroidWasmtimePulleyNative { *; }
-keep class uk.shusek.krwa.runtime.wasmtime.android.AndroidWasmtimeModuleCompilerNative { *; }

# Keep canonical Component Model bindings that are inspected reflectively.
-keepclassmembers,allowoptimization class ** {
    @uk.shusek.krwa.annotations.* <fields>;
    @uk.shusek.krwa.annotations.* <methods>;
}
