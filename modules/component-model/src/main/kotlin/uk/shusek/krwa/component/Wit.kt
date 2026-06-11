package uk.shusek.krwa.component

import java.io.IOException
import java.io.UncheckedIOException
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source

object Wit {
    private val fileSystem: FileSystem = FileSystem.SYSTEM

    @JvmStatic fun normalize(wit: String): String = normalize(Buffer().writeUtf8(wit))

    @JvmStatic
    fun normalize(input: Source): String =
        withTempInput(input, "input.wit") { path -> normalize(path) }

    @JvmStatic fun normalize(path: Path): String = runComponentWit(path, emptyList()).stdoutText()

    @JvmStatic fun json(wit: String): String = json(Buffer().writeUtf8(wit))

    @JvmStatic
    fun json(input: Source): String = withTempInput(input, "input.wit") { path -> json(path) }

    @JvmStatic fun json(path: Path): String = runComponentWit(path, listOf("--json")).stdoutText()

    @JvmStatic
    fun encodePackage(witFileOrDirectory: Path): ByteArray =
        runComponentWit(witFileOrDirectory, listOf("--wasm")).stdout()

    @JvmStatic fun parse(wit: String): WitPackage = WitPackage.parse(wit)

    @JvmStatic
    fun parse(componentOrWitBytes: ByteArray): WitPackage =
        withTempInput(Buffer().write(componentOrWitBytes), "input.wasm") { path -> parse(path) }

    @JvmStatic
    fun parse(componentOrWitPath: Path): WitPackage =
        WitPackage.parse(normalizeGraph(componentOrWitPath))

    @JvmStatic fun parseNormalized(wit: String): WitPackage = WitPackage.parse(normalize(wit))

    private fun runComponentWit(path: Path, options: List<String>): WasmToolsInvoker.Result {
        val absolute = path.normalized()
        val parent = absolute.parent ?: ".".toPath()
        val guestPath = "input/${absolute.relativeTo(parent)}"

        val args = ArrayList<String>()
        args.add("wasm-tools")
        args.add("component")
        args.add("wit")
        args.add(guestPath)
        args.addAll(options)

        return try {
            WasmToolsInvoker.run(args, WasmToolsInvoker.directory("input", parent))
        } catch (e: ComponentModelException) {
            throw WitParseException(e.message, e)
        }
    }

    private fun normalizeGraph(path: Path): String =
        withTempDirectory("graph") { root ->
            val absolute = path.normalized()
            val parent = absolute.parent ?: ".".toPath()
            val output = root.resolve("out")
            fileSystem.createDirectories(output)
            val guestPath = "input/${absolute.relativeTo(parent)}"

            val args = ArrayList<String>()
            args.add("wasm-tools")
            args.add("component")
            args.add("wit")
            args.add(guestPath)
            args.add("--out-dir")
            args.add("out")

            val directories = LinkedHashMap<String, Path>()
            directories["input"] = parent
            directories["out"] = output
            WasmToolsInvoker.run(args, directories)
            readWitGraph(output)
        }

    private fun readWitGraph(directory: Path): String {
        val files =
            fileSystem
                .listRecursively(directory)
                .filter { path -> path.name.endsWith(".wit") }
                .sortedWith(
                    compareBy<Path>(
                        { file -> directory != file.parent },
                        { file -> file.name != "component.wit" },
                        { file -> file.toString() },
                    )
                )
                .toList()
        val result = StringBuilder()
        for (file in files) {
            result.append(fileSystem.read(file) { readUtf8() }).append('\n')
        }
        return result.toString()
    }

    private fun <T> withTempInput(input: Source, fileName: String, op: (Path) -> T): T {
        var dir: Path? = null
        try {
            dir = createTempDirectory("krwa-wit")
            val file = dir.resolve(fileName)
            fileSystem.write(file) { writeAll(input) }
            return op(file)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        } finally {
            dir?.let { path -> fileSystem.deleteRecursively(path, mustExist = false) }
        }
    }

    private fun <T> withTempDirectory(prefix: String, op: (Path) -> T): T {
        var dir: Path? = null
        try {
            dir = createTempDirectory("krwa-wit-$prefix")
            return op(dir)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        } finally {
            dir?.let { path -> fileSystem.deleteRecursively(path, mustExist = false) }
        }
    }

    private fun createTempDirectory(prefix: String): Path {
        val base = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        for (attempt in 0 until 100) {
            val candidate =
                base.resolve("$prefix-${java.lang.System.nanoTime()}-$attempt", normalize = true)
            try {
                fileSystem.createDirectory(candidate, mustCreate = true)
                return candidate
            } catch (e: IOException) {
                if (attempt == 99) {
                    throw e
                }
            }
        }
        throw IOException("could not create temporary directory for $prefix")
    }
}
