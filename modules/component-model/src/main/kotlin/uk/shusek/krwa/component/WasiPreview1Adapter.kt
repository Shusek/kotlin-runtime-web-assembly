package uk.shusek.krwa.component

import okio.FileSystem
import okio.Path

internal object WasiPreview1Adapter {
    private const val PREVIEW1_MODULE = "wasi_snapshot_preview1"
    private const val RESOURCE_DIR = "/uk/shusek/krwa/component/wasi-preview1"
    private const val REACTOR_ADAPTER = "wasi_snapshot_preview1.reactor.wasm"
    private const val COMMAND_ADAPTER = "wasi_snapshot_preview1.command.wasm"
    private val fileSystem: FileSystem = FileSystem.SYSTEM

    fun shouldInstall(coreModule: Path, explicitAdapters: List<Path>): Boolean {
        val preview1Imports = WasmCoreModule.inspect(coreModule).preview1FunctionImports()
        if (preview1Imports.isEmpty()) {
            return false
        }
        val explicitExports =
            explicitAdapters.flatMapTo(LinkedHashSet()) { adapter ->
                WasmCoreModule.inspect(adapter).preview1FunctionExports()
            }
        return !explicitExports.containsAll(preview1Imports)
    }

    fun componentNewArgument(adapter: Path, relativePath: String): String =
        if (WasmCoreModule.inspect(adapter).exportsPreview1Function()) {
            "$PREVIEW1_MODULE=$relativePath"
        } else {
            relativePath
        }

    fun writeBundledReactor(root: Path): Path = writeBundled(root, REACTOR_ADAPTER)

    fun writeBundledCommand(root: Path): Path = writeBundled(root, COMMAND_ADAPTER)

    private fun writeBundled(root: Path, name: String): Path {
        val target = root.resolve(name)
        val resource = "$RESOURCE_DIR/$name"
        val bytes =
            WasiPreview1Adapter::class.java.getResourceAsStream(resource)?.use { input ->
                input.readBytes()
            }
                ?: throw ComponentModelException(
                    "missing bundled WASI Preview 1 adapter resource $resource; " +
                        "run the Gradle processJvmMainResources task to package adapters"
                )
        fileSystem.write(target) { write(bytes) }
        return target
    }

    private class WasmCoreModule(
        private val imports: Set<ExternalName>,
        private val exports: Set<String>,
    ) {
        fun preview1FunctionImports(): Set<String> =
            imports
                .filter { imported -> imported.module == PREVIEW1_MODULE }
                .mapTo(LinkedHashSet()) { imported -> imported.name }

        fun exportsPreview1Function(): Boolean =
            exports.any { exported -> exported in PREVIEW1_FUNCTIONS }

        fun preview1FunctionExports(): Set<String> =
            exports.filterTo(LinkedHashSet()) { exported -> exported in PREVIEW1_FUNCTIONS }

        companion object {
            fun inspect(path: Path): WasmCoreModule {
                val bytes = fileSystem.read(path) { readByteArray() }
                return WasmCoreModuleReader(bytes).read()
            }
        }
    }

    private data class ExternalName(val module: String, val name: String)

    private class WasmCoreModuleReader(private val bytes: ByteArray) {
        private var position: Int = 0

        fun read(): WasmCoreModule {
            require(bytes.size >= 8) { "invalid wasm module" }
            position = 8
            val imports = LinkedHashSet<ExternalName>()
            val exports = LinkedHashSet<String>()
            while (position < bytes.size) {
                val id = readByte()
                val sectionSize = readU32()
                val sectionEnd = position + sectionSize
                when (id) {
                    2 -> readImports(imports)
                    7 -> readExports(exports)
                }
                position = sectionEnd
            }
            return WasmCoreModule(imports, exports)
        }

        private fun readImports(imports: MutableSet<ExternalName>) {
            repeat(readU32()) {
                val module = readString()
                val name = readString()
                when (readByte()) {
                    0 -> {
                        readU32()
                        imports.add(ExternalName(module, name))
                    }
                    1 -> {
                        readByte()
                        skipLimits()
                    }
                    2 -> skipLimits()
                    3 -> {
                        readByte()
                        readByte()
                    }
                    4 -> readU32()
                    else -> error("unsupported wasm import kind")
                }
            }
        }

        private fun readExports(exports: MutableSet<String>) {
            repeat(readU32()) {
                val name = readString()
                val kind = readByte()
                readU32()
                if (kind == 0) {
                    exports.add(name)
                }
            }
        }

        private fun skipLimits() {
            val flags = readU32()
            readU32()
            if ((flags and 1) != 0) {
                readU32()
            }
        }

        private fun readString(): String {
            val length = readU32()
            val start = position
            position += length
            return bytes.decodeToString(start, position)
        }

        private fun readByte(): Int = bytes[position++].toInt() and 0xff

        private fun readU32(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val byte = readByte()
                result = result or ((byte and 0x7f) shl shift)
                if ((byte and 0x80) == 0) {
                    return result
                }
                shift += 7
            }
        }
    }

    private val PREVIEW1_FUNCTIONS =
        setOf(
            "adapter_close_badfd",
            "adapter_open_badfd",
            "args_get",
            "args_sizes_get",
            "clock_res_get",
            "clock_time_get",
            "environ_get",
            "environ_sizes_get",
            "fd_advise",
            "fd_allocate",
            "fd_close",
            "fd_datasync",
            "fd_fdstat_get",
            "fd_fdstat_set_flags",
            "fd_fdstat_set_rights",
            "fd_filestat_get",
            "fd_filestat_set_size",
            "fd_filestat_set_times",
            "fd_pread",
            "fd_prestat_dir_name",
            "fd_prestat_get",
            "fd_pwrite",
            "fd_read",
            "fd_readdir",
            "fd_renumber",
            "fd_seek",
            "fd_sync",
            "fd_tell",
            "fd_write",
            "path_create_directory",
            "path_filestat_get",
            "path_filestat_set_times",
            "path_link",
            "path_open",
            "path_readlink",
            "path_remove_directory",
            "path_rename",
            "path_symlink",
            "path_unlink_file",
            "poll_oneoff",
            "proc_exit",
            "proc_raise",
            "random_get",
            "sched_yield",
            "sock_accept",
            "sock_recv",
            "sock_send",
            "sock_shutdown",
        )
}
