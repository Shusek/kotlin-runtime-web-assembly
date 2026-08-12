package uk.shusek.krwa.component

import java.io.IOException
import java.io.UncheckedIOException
import java.util.Collections
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmWriter
import uk.shusek.krwa.wasm.types.CustomSection
import uk.shusek.krwa.wasm.types.UnknownCustomSection

/**
 * JVM Component Model packaging and unbundling helpers.
 *
 * Add `uk.shusek.krwa:component-model-tooling` to the runtime classpath before using this facade.
 */
object WasmComponentTools {
    private val fileSystem: FileSystem = FileSystem.SYSTEM

    @JvmStatic
    fun parseWat(wat: String): ByteArray =
        withTempDirectory("parse") { root ->
            val input = root.resolve("module.wat")
            fileSystem.write(input) { writeUtf8(wat) }

            val args = ArrayList<String>()
            args.add("wasm-tools")
            args.add("parse")
            args.add(relative(root, input))
            WasmToolsInvoker.run(args, WasmToolsInvoker.directory(".", root)).stdout()
        }

    @JvmStatic
    fun embedWit(witFileOrDirectory: Path, world: String, coreModule: Path): ByteArray =
        embedWit(witFileOrDirectory, world, coreModule, false)

    @JvmStatic
    fun embedWit(
        witFileOrDirectory: Path,
        world: String,
        coreModule: Path,
        asyncCallback: Boolean,
    ): ByteArray =
        withTempDirectory("embed-result") { root ->
            val output = root.resolve("embedded.wasm")
            embedWitToFile(witFileOrDirectory, world, coreModule, output, asyncCallback)
            fileSystem.read(output) { readByteArray() }
        }

    private fun embedWitToFile(
        witFileOrDirectory: Path,
        world: String,
        coreModule: Path,
        output: Path,
        asyncCallback: Boolean,
    ) {
        withTempDirectory("embed") { root ->
            val stagedWit = stage(root, "wit", witFileOrDirectory)
            val stagedModule = stage(root, "module", coreModule)
            val toolOutput = root.resolve("embedded-output.wasm")

            val args = ArrayList<String>()
            args.add("wasm-tools")
            args.add("component")
            args.add("embed")
            if (asyncCallback) {
                args.add("--dummy-names")
                args.add("legacy")
                args.add("--async-callback")
                args.add(relative(root, stagedWit))
                args.add("--world")
                args.add(world)
                args.add("--output")
                args.add(relative(root, toolOutput))
                WasmToolsInvoker.run(args, WasmToolsInvoker.directory(".", root))
                appendCustomSectionsToFile(stagedModule, toolOutput, output)
                return@withTempDirectory
            }
            args.add(relative(root, stagedWit))
            args.add("--world")
            args.add(world)
            args.add(relative(root, stagedModule))
            args.add("--output")
            args.add(relative(root, toolOutput))
            WasmToolsInvoker.run(args, WasmToolsInvoker.directory(".", root))
            copyFileReplacing(toolOutput, output)
        }
    }

    @JvmStatic
    fun componentNew(embeddedCoreModule: Path, vararg adapters: Path): ByteArray =
        withTempDirectory("new-result") { root ->
            val output = root.resolve("component.wasm")
            componentNewToFile(embeddedCoreModule, output, *adapters)
            fileSystem.read(output) { readByteArray() }
        }

    private fun componentNewToFile(
        embeddedCoreModule: Path,
        output: Path,
        vararg adapters: Path,
    ) {
        withTempDirectory("new") { root ->
            val stagedModule = stage(root, "module", embeddedCoreModule)
            val toolOutput = root.resolve("component-output.wasm")

            val args = ArrayList<String>()
            args.add("wasm-tools")
            args.add("component")
            args.add("new")
            args.add(relative(root, stagedModule))
            for ((index, adapter) in adapters.withIndex()) {
                val stagedAdapter = stage(root, "adapter$index", adapter)
                args.add("--adapt")
                args.add(
                    WasiPreview1Adapter.componentNewArgument(
                        adapter,
                        relative(root, stagedAdapter),
                    )
                )
            }

            args.add("--output")
            args.add(relative(root, toolOutput))
            WasmToolsInvoker.run(args, WasmToolsInvoker.directory(".", root))
            copyFileReplacing(toolOutput, output)
        }
    }

    @JvmStatic
    fun componentFromCore(
        witFileOrDirectory: Path,
        world: String,
        coreModule: Path,
        vararg adapters: Path,
    ): ByteArray =
        componentFromCore(witFileOrDirectory, world, coreModule, false, *adapters)

    @JvmStatic
    fun componentFromCore(
        witFileOrDirectory: Path,
        world: String,
        coreModule: Path,
        asyncCallback: Boolean,
        vararg adapters: Path,
    ): ByteArray =
        withTempDirectory("package") { root ->
            val output = root.resolve("component.wasm")
            packageComponentToFile(
                witFileOrDirectory,
                world,
                coreModule,
                output,
                asyncCallback,
                adapters,
            )
            fileSystem.read(output) { readByteArray() }
        }

    @JvmStatic
    fun writeComponentFromCore(
        witFileOrDirectory: Path,
        world: String,
        coreModule: Path,
        outputComponent: Path,
        validate: Boolean,
        vararg adapters: Path,
    ): Path {
        return writeComponentFromCore(
            witFileOrDirectory,
            world,
            coreModule,
            outputComponent,
            validate,
            false,
            *adapters,
        )
    }

    @JvmStatic
    fun writeComponentFromCore(
        witFileOrDirectory: Path,
        world: String,
        coreModule: Path,
        outputComponent: Path,
        validate: Boolean,
        asyncCallback: Boolean,
        vararg adapters: Path,
    ): Path =
        writeOutputAtomically(outputComponent) { stagedOutput ->
            packageComponentToFile(
                witFileOrDirectory,
                world,
                coreModule,
                stagedOutput,
                asyncCallback,
                adapters,
            )
            if (validate) {
                validateComponent(stagedOutput, asyncCallback)
            }
        }

    /**
     * Runs all output production and validation against a same-filesystem staging path, then
     * atomically installs the result. The target is unchanged if [writeAndValidate] or the atomic
     * move fails.
     */
    @JvmSynthetic
    internal fun writeOutputAtomically(
        outputComponent: Path,
        writeAndValidate: (Path) -> Unit,
    ): Path {
        try {
            val normalizedOutput = outputComponent.normalized()
            val outputDirectory = normalizedOutput.parent ?: ".".toPath(normalize = true)
            fileSystem.createDirectories(outputDirectory)
            withTempDirectory("write-package", outputDirectory) { root ->
                val stagedOutput = root.resolve("component.wasm")
                writeAndValidate(stagedOutput)
                fileSystem.atomicMove(stagedOutput, normalizedOutput)
            }
            return outputComponent
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    private fun packageComponentToFile(
        witFileOrDirectory: Path,
        world: String,
        coreModule: Path,
        outputComponent: Path,
        asyncCallback: Boolean,
        adapters: Array<out Path>,
    ) {
        withTempDirectory("package-files") { root ->
            val embedded = root.resolve("embedded.wasm")
            embedWitToFile(witFileOrDirectory, world, coreModule, embedded, asyncCallback)
            val resolvedAdapters = ArrayList<Path>()
            resolvedAdapters.addAll(adapters)
            if (WasiPreview1Adapter.shouldInstall(coreModule, adapters.asList())) {
                resolvedAdapters.add(WasiPreview1Adapter.writeBundledReactor(root))
            }
            componentNewToFile(embedded, outputComponent, *resolvedAdapters.toTypedArray())
        }
    }

    @JvmStatic
    fun validateComponent(component: Path) {
        validateComponent(component, false)
    }

    @JvmStatic
    fun validateComponent(component: Path, asyncComponentModel: Boolean) {
        withTempDirectory("validate") { root ->
            val stagedComponent = stage(root, "component", component)
            val args = ArrayList<String>()
            args.add("wasm-tools")
            args.add("validate")
            args.add(relative(root, stagedComponent))
            args.add("--features")
            args.add(if (asyncComponentModel) "component-model,cm-async" else "component-model")
            WasmToolsInvoker.run(args, WasmToolsInvoker.directory(".", root))
            null
        }
    }

    @JvmStatic
    fun unbundleComponent(component: ByteArray): UnbundledComponent =
        withTempDirectory("unbundle-input") { root ->
            val input = root.resolve("component.wasm")
            fileSystem.write(input) { write(component) }
            unbundleComponent(input)
        }

    @JvmStatic
    fun unbundleComponent(component: Path): UnbundledComponent =
        withTempDirectory("unbundle") { root ->
            val stagedComponent = stage(root, "component", component)
            val modules = root.resolve("modules")
            fileSystem.createDirectories(modules)

            val args = ArrayList<String>()
            args.add("wasm-tools")
            args.add("component")
            args.add("unbundle")
            args.add("--threshold")
            args.add("0")
            args.add("--module-dir")
            args.add("modules")
            args.add(relative(root, stagedComponent))

            val result =
                WasmToolsInvoker.run(
                    args,
                    WasmToolsInvoker.directory(".", root),
                )

            UnbundledComponent(result.stdout(), readModules(modules))
        }

    private fun stage(root: Path, label: String, source: Path): Path {
        val target = root.resolve("$label-${source.name}")
        copyRecursively(source.normalized(), target)
        return target
    }

    private fun relative(root: Path, path: Path): String = "./${path.relativeTo(root)}"

    private fun appendCustomSectionsToFile(
        module: Path,
        customSectionSourceModule: Path,
        output: Path,
    ) {
        val customSectionBytes = fileSystem.read(customSectionSourceModule) { readByteArray() }
        fileSystem.write(output) {
            fileSystem.source(module).use { source -> writeAll(source) }
            for (section in Parser.parse(customSectionBytes).customSections()) {
                if (section.name() == "name") {
                    continue
                }
                val contents = encodeCustomSectionContents(section)
                val header = Buffer()
                header.writeByte(0)
                WasmWriter.writeVarUInt32(header, contents.size)
                write(header.readByteArray())
                write(contents)
            }
        }
    }

    private fun encodeCustomSectionContents(section: CustomSection): ByteArray {
        val sectionBytes =
            when (section) {
                is UnknownCustomSection -> section.bytes()
                else -> throw ComponentModelException(
                    "cannot copy decoded custom section ${section.name()}"
                )
            }
        val nameBytes = section.name().encodeToByteArray()
        val out = Buffer()
        WasmWriter.writeVarUInt32(out, nameBytes.size)
        out.write(nameBytes)
        out.write(sectionBytes)
        return out.readByteArray()
    }

    private fun copyRecursively(source: Path, target: Path) {
        if (fileSystem.metadata(source).isRegularFile) {
            fileSystem.delete(target, mustExist = false)
            fileSystem.copy(source, target)
            return
        }
        fileSystem.createDirectories(target)
        for (child in fileSystem.list(source)) {
            copyRecursively(child, target.resolve(child.name))
        }
    }

    private fun copyFileReplacing(source: Path, target: Path) {
        target.normalized().parent?.let(fileSystem::createDirectories)
        fileSystem.delete(target, mustExist = false)
        fileSystem.copy(source, target)
    }

    private fun readModules(moduleDirectory: Path): Map<String, ByteArray> {
        val modules = LinkedHashMap<String, ByteArray>()
        val files =
            fileSystem
                .list(moduleDirectory)
                .filter { path -> path.name.endsWith(".wasm") }
                .sortedBy { path -> path.name }
        for (file in files) {
            modules[file.name] = fileSystem.read(file) { readByteArray() }
        }
        return modules
    }

    private fun <T> withTempDirectory(
        prefix: String,
        base: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY,
        operation: (Path) -> T,
    ): T {
        var dir: Path? = null
        try {
            dir = createTempDirectory(base, "krwa-component-$prefix-")
            return operation(dir)
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        } finally {
            dir?.let { path -> fileSystem.deleteRecursively(path, mustExist = false) }
        }
    }

    private fun createTempDirectory(base: Path, prefix: String): Path {
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

    class UnbundledComponent
    internal constructor(component: ByteArray, modules: Map<String, ByteArray>) {
        private val component: ByteArray = component.clone()
        private val modules: Map<String, ByteArray> = cloneModules(modules)

        fun component(): ByteArray = component.clone()

        fun modules(): Map<String, ByteArray> = cloneModules(modules)

        fun module(name: String): ByteArray {
            val module =
                modules[name]
                    ?: throw ComponentModelException("unknown unbundled component module $name")
            return module.clone()
        }

        fun singleModule(): ByteArray {
            if (modules.size != 1) {
                throw ComponentModelException(
                    "expected exactly one unbundled component module, got ${modules.keys}"
                )
            }
            return modules.values.iterator().next().clone()
        }

        companion object {
            private fun cloneModules(source: Map<String, ByteArray>): Map<String, ByteArray> {
                val result = LinkedHashMap<String, ByteArray>()
                for ((key, value) in source) {
                    result[key] = value.clone()
                }
                return Collections.unmodifiableMap(result)
            }
        }
    }
}
