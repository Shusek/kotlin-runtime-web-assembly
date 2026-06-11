module uk.shusek.krwa.runtime {
    requires kotlin.stdlib;
    requires transitive uk.shusek.krwa.wasm;

    exports uk.shusek.krwa.runtime;
    exports uk.shusek.krwa.runtime.alloc;
    exports uk.shusek.krwa.runtime.internal;
}
