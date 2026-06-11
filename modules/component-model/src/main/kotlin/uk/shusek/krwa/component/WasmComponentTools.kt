package uk.shusek.krwa.component

import java.io.IOException
import java.io.UncheckedIOException
import java.util.Collections
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import uk.shusek.krwa.wasm.Parser
import uk.shusek.krwa.wasm.WasmWriter
import uk.shusek.krwa.wasm.types.CustomSection
import uk.shusek.krwa.wasm.types.UnknownCustomSection

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
        withTempDirectory("embed") { root ->
            val stagedWit = stage(root, "wit", witFileOrDirectory)
            val stagedModule = stage(root, "module", coreModule)

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
                val dummyModule =
                    WasmToolsInvoker.run(args, WasmToolsInvoker.directory(".", root)).stdout()
                val module = fileSystem.read(stagedModule) { readByteArray() }
                return@withTempDirectory appendCustomSections(module, dummyModule)
            }
            args.add(relative(root, stagedWit))
            args.add("--world")
            args.add(world)
            args.add(relative(root, stagedModule))
            WasmToolsInvoker.run(args, WasmToolsInvoker.directory(".", root)).stdout()
        }

    @JvmStatic
    fun componentNew(embeddedCoreModule: Path, vararg adapters: Path): ByteArray =
        withTempDirectory("new") { root ->
            val stagedModule = stage(root, "module", embeddedCoreModule)

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

            WasmToolsInvoker.run(args, WasmToolsInvoker.directory(".", root)).stdout()
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
            val embedded = root.resolve("embedded.wasm")
            fileSystem.write(embedded) {
                write(embedWit(witFileOrDirectory, world, coreModule, asyncCallback))
            }
            val resolvedAdapters = ArrayList<Path>()
            resolvedAdapters.addAll(adapters)
            if (WasiPreview1Adapter.shouldInstall(coreModule, adapters.asList())) {
                resolvedAdapters.add(WasiPreview1Adapter.writeBundledReactor(root))
            }
            componentNew(embedded, *resolvedAdapters.toTypedArray())
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
    ): Path {
        try {
            val parent = outputComponent.normalized().parent
            if (parent != null) {
                fileSystem.createDirectories(parent)
            }
            fileSystem.write(outputComponent) {
                write(componentFromCore(witFileOrDirectory, world, coreModule, asyncCallback, *adapters))
            }
            if (validate) {
                validateComponent(outputComponent, asyncCallback)
            }
            return outputComponent
        } catch (e: IOException) {
            throw UncheckedIOException(e)
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

            val directories = LinkedHashMap<String, Path>()
            directories["."] = root
            directories["modules"] = modules
            val result = WasmToolsInvoker.run(args, directories)

            UnbundledComponent(result.stdout(), readModules(modules))
        }

    private fun stage(root: Path, label: String, source: Path): Path {
        val target = root.resolve("$label-${source.name}")
        copyRecursively(source.normalized(), target)
        return target
    }

    private fun relative(root: Path, path: Path): String = "./${path.relativeTo(root)}"

    private fun appendCustomSections(module: ByteArray, customSectionSourceModule: ByteArray): ByteArray {
        val out = Buffer()
        out.write(module)
        for (section in Parser.parse(customSectionSourceModule).customSections()) {
            if (section.name() == "name") {
                continue
            }
            val contents = encodeCustomSectionContents(section)
            out.writeByte(0)
            WasmWriter.writeVarUInt32(out, contents.size)
            out.write(contents)
        }
        return out.readByteArray()
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

    private fun <T> withTempDirectory(prefix: String, operation: (Path) -> T): T {
        var dir: Path? = null
        try {
            dir = createTempDirectory("krwa-component-$prefix-")
            return operation(dir)
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
