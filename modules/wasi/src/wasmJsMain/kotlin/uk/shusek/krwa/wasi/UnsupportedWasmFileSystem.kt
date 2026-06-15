package uk.shusek.krwa.wasi

import okio.FileHandle
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.Source

internal object UnsupportedWasmFileSystem : FileSystem() {
    override fun canonicalize(path: Path): Path = unsupported()

    override fun metadataOrNull(path: Path): FileMetadata? = null

    override fun list(dir: Path): List<Path> = unsupported()

    override fun listOrNull(dir: Path): List<Path>? = null

    override fun openReadOnly(file: Path): FileHandle = unsupported()

    override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle =
        unsupported()

    override fun source(file: Path): Source = unsupported()

    override fun sink(file: Path, mustCreate: Boolean): Sink = unsupported()

    override fun appendingSink(file: Path, mustExist: Boolean): Sink = unsupported()

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        unsupported()
    }

    override fun atomicMove(source: Path, target: Path) {
        unsupported()
    }

    override fun delete(path: Path, mustExist: Boolean) {
        unsupported()
    }

    override fun createSymlink(source: Path, target: Path) {
        unsupported()
    }

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Default WASI filesystem is not available on web/wasm")
}
