module uk.shusek.krwa.build.time.compiler {
    requires kotlin.stdlib;
    requires transitive uk.shusek.krwa.compiler;
    requires uk.shusek.krwa.codegen;
    requires uk.shusek.krwa.runtime;
    requires uk.shusek.krwa.wasm;

    exports uk.shusek.krwa.build.time.compiler;
}
