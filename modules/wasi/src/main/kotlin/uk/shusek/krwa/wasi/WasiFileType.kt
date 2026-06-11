package uk.shusek.krwa.wasi

/**
 * WASI [filetype](https://github.com/WebAssembly/WASI/blob/v0.2.1/legacy/preview1/docs.md#filetype)
 */
enum class WasiFileType {
    UNKNOWN,
    BLOCK_DEVICE,
    CHARACTER_DEVICE,
    DIRECTORY,
    REGULAR_FILE,
    SOCKET_DGRAM,
    SOCKET_STREAM,
    SYMBOLIC_LINK;

    @Suppress("EnumOrdinal") fun value(): Int = ordinal
}
