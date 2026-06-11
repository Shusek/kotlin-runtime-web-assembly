module uk.shusek.krwa.compiler {
    requires kotlin.stdlib;
    requires transitive uk.shusek.krwa.runtime;
    requires transitive uk.shusek.krwa.wasm;
    requires org.objectweb.asm;
    requires org.objectweb.asm.commons;
    requires org.objectweb.asm.util;

    exports uk.shusek.krwa.compiler;
    exports uk.shusek.krwa.compiler.internal;
    exports uk.shusek.krwa.experimental.aot;
}
