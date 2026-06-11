module uk.shusek.krwa.wasi {
    requires transitive kotlinx.io.core;
    requires transitive okio;
    requires transitive org.kotlincrypto.random;
    requires kotlin.stdlib;
    requires static uk.shusek.krwa.annotations;
    requires uk.shusek.krwa.log;
    requires transitive uk.shusek.krwa.runtime;

    exports uk.shusek.krwa.wasi;
}
