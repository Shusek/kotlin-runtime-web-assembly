module uk.shusek.krwa.runtime {
    requires chasm.jvm;
    requires host.jvm;
    requires kotlin.stdlib;
    requires transitive uk.shusek.krwa.wasm;
    requires value.jvm;

    exports uk.shusek.krwa.runtime;
    exports uk.shusek.krwa.runtime.alloc;
    exports uk.shusek.krwa.runtime.internal;
}
