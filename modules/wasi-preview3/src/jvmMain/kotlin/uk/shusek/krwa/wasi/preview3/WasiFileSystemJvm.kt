package uk.shusek.krwa.wasi.preview3

import uk.shusek.krwa.component.WasiPreview3
import uk.shusek.krwa.component.WitStream

@OptIn(ExperimentalUnsignedTypes::class)
public fun WasiFileSystem.readWitByteStream(
    path: String,
    wasi: WasiPreview3,
    chunkSize: Int = WasiFileSystem.DEFAULT_CHUNK_SIZE,
): WitStream<UByte> = readByteChunks(path, chunkSize).toWitByteStream(wasi)

@OptIn(ExperimentalUnsignedTypes::class)
public suspend fun WasiFileSystem.writeWitByteStream(
    path: String,
    stream: WitStream<UByte>,
    wasi: WasiPreview3,
    createParentDirectories: Boolean = true,
    append: Boolean = false,
) {
    writeByteChunks(path, stream.asByteChunks(wasi), append, createParentDirectories)
}
