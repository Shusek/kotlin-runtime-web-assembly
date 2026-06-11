module uk.shusek.krwa.wasm {
    requires transitive kotlinx.io.core;
    requires kotlin.stdlib;
    requires okio;

    exports uk.shusek.krwa.wasm;
    exports uk.shusek.krwa.wasm.io;
    exports uk.shusek.krwa.wasm.types;
}
