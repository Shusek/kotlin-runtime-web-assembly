package uk.shusek.krwa.component

import okio.Path
import uk.shusek.krwa.runtime.ExecutionListener
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.ImportFunction
import uk.shusek.krwa.runtime.ImportValues
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Machine
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.WasmModule
import uk.shusek.krwa.wasm.WasmParser
import uk.shusek.krwa.wasm.types.ExternalType
import uk.shusek.krwa.wasm.types.FunctionImport
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType
import uk.shusek.krwa.wasi.WasiPreview1
import uk.shusek.krwa.wasi.WasiOptions

class WasmPlugin
private constructor(
    private val witPackage: WitPackage,
    private val world: WitPackage.WorldDeclaration,
    private val instance: Instance,
    exports: Map<String, CanonicalAbi.BoundFunction>,
) : WasiComponentInvoker {
    private val exportsByName: Map<String, CanonicalAbi.BoundFunction> =
        LinkedHashMap(exports).toMap()

    fun witPackage(): WitPackage = witPackage

    fun world(): WitPackage.WorldDeclaration = world

    fun instance(): Instance = instance

    override fun call(exportName: String, vararg args: Any?): Any? {
        val export =
            exportsByName[exportName]
                ?: throw ComponentModelException("unknown plugin export $exportName")
        return export.call(*args)
    }

    fun exports(): Map<String, CanonicalAbi.BoundFunction> = exportsByName

    fun <T : Any> exports(contractType: ComponentModelJvmClass<T>): T =
        wasmPluginReflectExports(this, contractType)

    class Builder internal constructor(witPackage: WitPackage) : WasiHostImportBuilder {
        private val witPackage: WitPackage = witPackage
        private val abi: CanonicalAbi = CanonicalAbi.of(witPackage)
        private val hostImports = LinkedHashMap<String, HostHandler>()
        private val hostObjects = ArrayList<Any>()
        private val resourceHandles = CanonicalResourceHandles()
        private val asyncContexts = LinkedHashMap<Int, Int>()
        private val asyncTaskReturns = LinkedHashMap<String, AsyncTaskReturnSlot>()
        private var canonicalFutureIntrinsics: CanonicalFutureIntrinsics? = null
        private var canonicalStreamIntrinsics: CanonicalStreamIntrinsics? = null
        private var preview1HostConfigured: Boolean = false
        private var preview3Host: WasiPreview3? = null
        private var worldName: String? = null
        private var module: WasmModule? = null
        private var component: WasmPluginUnbundledComponent? = null
        private var executionListener: ExecutionListener? = null
        private val rawHostFunctions = ArrayList<ImportFunction>()

        fun withWorld(worldName: String?): Builder {
            this.worldName = worldName
            return this
        }

        fun withModule(module: WasmModule?): Builder {
            this.module = module
            component = null
            return this
        }

        fun withModule(wasmBytes: ByteArray): Builder {
            module = WasmParser.parse(wasmBytes)
            return this
        }

        fun withModule(wasmPath: Path): Builder {
            return withModule(wasmPluginReadPathBytes(wasmPath))
        }

        fun withComponent(componentBytes: ByteArray): Builder {
            module = null
            component = wasmPluginUnbundleComponent(componentBytes)
            return this
        }

        fun withComponent(componentPath: Path): Builder {
            module = null
            component = wasmPluginUnbundleComponent(componentPath)
            return this
        }

        fun withComponentModule(componentBytes: ByteArray, moduleName: String): Builder {
            module =
                WasmParser.parse(
                    wasmPluginUnbundleComponent(componentBytes).module(moduleName)
                )
            component = null
            return this
        }

        fun withComponentModule(componentPath: Path, moduleName: String): Builder {
            module =
                WasmParser.parse(wasmPluginUnbundleComponent(componentPath).module(moduleName))
            component = null
            return this
        }

        override fun withHostImport(
            interfaceName: String?,
            functionName: String?,
            handler: HostHandler,
        ): Builder {
            hostImports[importKey(interfaceName, functionName)] = handler
            return this
        }

        override fun withHostImport(qualifiedName: String, handler: HostHandler): Builder {
            hostImports[qualifiedName] = handler
            return this
        }

        fun withHost(host: Any): Builder {
            hostObjects.add(host)
            return this
        }

        fun withCanonicalFutureIntrinsics(intrinsics: CanonicalFutureIntrinsics?): Builder {
            canonicalFutureIntrinsics = intrinsics
            return this
        }

        fun withCanonicalStreamIntrinsics(intrinsics: CanonicalStreamIntrinsics?): Builder {
            canonicalStreamIntrinsics = intrinsics
            return this
        }

        override fun withWasiPreview3CanonicalIntrinsics(
            intrinsics: WasiPreview3CanonicalIntrinsics
        ): Builder {
            val adapter = WasiPreview3CanonicalIntrinsicsAdapter(intrinsics)
            canonicalFutureIntrinsics = adapter
            canonicalStreamIntrinsics = adapter
            return this
        }

        fun withWasiPreview2(wasi: WasiPreview2): Builder {
            wasi.install(this)
            return this
        }

        fun withWasiPreview1(wasi: WasiPreview1): Builder {
            preview1HostConfigured = true
            rawHostFunctions.addAll(wasi.toHostFunctions())
            return this
        }

        fun withWasiPreview3(wasi: WasiPreview3): Builder {
            wasi.install(this)
            preview3Host = wasi
            return this
        }

        /*
         * This method is experimental and might be dropped without notice in future releases.
         */
        fun withUnsafeExecutionListener(listener: ExecutionListener): Builder {
            executionListener = listener
            return this
        }

        fun build(): WasmPlugin {
            val world = selectWorld()
            val selectedComponent = component
            if (module == null && selectedComponent != null) {
                module = selectComponentModule(selectedComponent, world)
            }
            val selectedModule =
                module ?: throw ComponentModelException("plugin module is required")
            val imports = buildImports(world, selectedModule)
            val missingPreview1Imports = missingPreview1Imports(selectedModule, imports)
            if (missingPreview1Imports.isNotEmpty()) {
                throw ComponentModelException(
                    "selected core module imports WASI Preview 1 functions " +
                        missingPreview1Imports.joinToString(prefix = "[", postfix = "]") +
                        "; Kotlin wasmWasi components must be loaded with " +
                        "WasmPlugin.Builder.withWasiPreview1(...)"
                )
            }
            val instanceBuilder = Instance.builder(selectedModule).withImportValues(imports)
            executionListener?.let { instanceBuilder.withUnsafeExecutionListener(it) }
            compiledComponentMachineFactory(selectedModule)?.let {
                instanceBuilder.withMachineFactory(it)
            }
            val instance = instanceBuilder.build()
            runGuestInitializers(selectedModule, instance)
            val exports = bindExports(world, instance)
            return WasmPlugin(witPackage, world, instance, exports)
        }

        private fun compiledComponentMachineFactory(module: WasmModule): ((Instance) -> Machine)? {
            return wasmPluginCompiledMachineFactory(module)
        }

        private fun selectWorld(): WitPackage.WorldDeclaration {
            val selectedWorldName = worldName
            if (selectedWorldName != null) {
                for (world in witPackage.worlds()) {
                    if (world.name() == selectedWorldName) {
                        return world
                    }
                }
                throw ComponentModelException("unknown WIT world $selectedWorldName")
            }
            val worlds = witPackage.worlds()
            if (worlds.size != 1) {
                val applicationWorld = selectSingleApplicationWorld(worlds)
                if (applicationWorld != null) {
                    return applicationWorld
                }
                throw ComponentModelException(
                    "WIT world must be selected explicitly; available worlds: " + worldNames(worlds)
                )
            }
            return worlds[0]
        }

        private fun selectSingleApplicationWorld(
            worlds: List<WitPackage.WorldDeclaration>
        ): WitPackage.WorldDeclaration? {
            var selected: WitPackage.WorldDeclaration? = null
            for (world in worlds) {
                if (isWasiPackage(world.packageName())) {
                    continue
                }
                if (selected != null) {
                    return null
                }
                selected = world
            }
            return selected
        }

        private fun isWasiPackage(packageName: String?): Boolean =
            packageName != null && packageName.startsWith("wasi:")

        private fun worldNames(worlds: List<WitPackage.WorldDeclaration>): List<String> {
            val result = ArrayList<String>()
            for (world in worlds) {
                result.add(world.qualifiedName())
            }
            return result
        }

        private fun buildImports(
            world: WitPackage.WorldDeclaration,
            module: WasmModule,
        ): ImportValues {
            val functions = ArrayList<ImportFunction>()
            val moduleFunctionImports = moduleFunctionImports(module)
            for (item in world.imports()) {
                if (item.isFunction) {
                    if (moduleFunctionImports.contains(importKey(world.name(), item.name()))) {
                        functions.add(importFunction(world.name(), item.name(), item.function()!!))
                    }
                    continue
                }
                val localInterfaceName = interfaceName(item)
                val importedInterface = requireInterface(item)
                for (binding in interfaceFunctionBindings(importedInterface)) {
                    for (moduleName in interfaceModuleNames(item, importedInterface)) {
                        if (
                            !moduleFunctionImports.contains(
                                importKey(moduleName, binding.symbolName)
                            )
                        ) {
                            continue
                        }
                        functions.add(
                            importFunction(
                                moduleName,
                                localInterfaceName,
                                binding.publicName,
                                binding.symbolName,
                                binding.function,
                                item.name(),
                            )
                        )
                    }
                }
                addImportedResourceIntrinsics(functions, item, importedInterface)
            }
            for (item in world.exports()) {
                if (item.isFunction) {
                    if (item.function()!!.isAsync) {
                        addAsyncTaskReturnImport(
                            functions,
                            rootAsyncTaskReturnModuleName(),
                            item.function()!!,
                        )
                    }
                    continue
                }
                val exportedInterface = requireInterface(item)
                for (binding in interfaceFunctionBindings(exportedInterface)) {
                    if (binding.function.isAsync) {
                        addAsyncTaskReturnImport(
                            functions,
                            interfaceAsyncTaskReturnModuleName(exportedInterface),
                            binding.function,
                        )
                    }
                }
                addExportedResourceIntrinsics(functions, item, exportedInterface)
            }
            functions.addAll(rawHostFunctions)
            addPreview1BridgeHostImports(functions, module)
            addModuleDeclaredHostImports(functions, module)
            return ImportValues.builder().withFunctions(deduplicateFunctions(functions)).build()
        }

        private fun moduleFunctionImports(module: WasmModule): Set<String> {
            val result = LinkedHashSet<String>()
            for (imported in module.importSection().imports()) {
                if (imported.importType() == ExternalType.FUNCTION) {
                    result.add(importKey(imported.module(), imported.name()))
                }
            }
            return result
        }

        private fun addPreview1BridgeHostImports(
            functions: MutableList<ImportFunction>,
            module: WasmModule,
        ) {
            val preview3 = preview3Host ?: return
            if (preview1HostConfigured || !hasPreview1FunctionImports(module)) {
                return
            }
            for (function in preview1HostFunctions(preview3)) {
                functions.add(function)
            }
        }

        private fun hasPreview1FunctionImports(module: WasmModule): Boolean {
            for (imported in module.importSection().imports()) {
                if (
                    imported.importType() == ExternalType.FUNCTION &&
                        imported.module() == PREVIEW1_MODULE
                ) {
                    return true
                }
            }
            return false
        }

        private fun preview1HostFunctions(preview3: WasiPreview3): Array<HostFunction> {
            val options = WasiOptions.builder()
            options.withRandom(preview3.preview1SecureRandom())
            options.withClock(preview3.preview1WallClock())
            options.withArguments(preview3.preview1Arguments())
            for ((name, value) in preview3.preview1Environment()) {
                options.withEnvironment(name, value)
            }
            for (preopen in preview3.preview1Preopens()) {
                options.withDirectory(
                    preopen.guestPath,
                    preopen.hostPath,
                    preview3.preview1FileSystem(),
                )
            }
            return WasiPreview1.builder().withOptions(options.build()).build().toHostFunctions()
        }

        private fun missingPreview1Imports(
            module: WasmModule,
            imports: ImportValues,
        ): List<String> {
            val provided = LinkedHashSet<String>()
            for (function in imports.functions()) {
                provided.add(importKey(function.module(), function.name()))
            }
            val missing = ArrayList<String>()
            for (imported in module.importSection().imports()) {
                val baseFunctionName = preview1BaseFunctionName(imported.name())
                if (
                    imported.importType() == ExternalType.FUNCTION &&
                        imported.module() == PREVIEW1_MODULE &&
                        !provided.contains(
                            importKey(
                                imported.module(),
                                baseFunctionName,
                            )
                        )
                ) {
                    missing.add(imported.name())
                }
            }
            return missing
        }

        private fun preview1BaseFunctionName(functionName: String): String =
            functionName.substringBefore(" ")

        private fun importFunction(
            interfaceName: String,
            functionName: String,
            function: WitPackage.Function,
        ): ImportFunction = importFunction(interfaceName, interfaceName, functionName, function)

        private fun importFunction(
            moduleName: String,
            handlerInterfaceName: String,
            functionName: String,
            function: WitPackage.Function,
        ): ImportFunction =
            importFunction(moduleName, handlerInterfaceName, functionName, functionName, function)

        private fun importFunction(
            moduleName: String,
            handlerInterfaceName: String,
            publicName: String,
            symbolName: String,
            function: WitPackage.Function,
            vararg additionalHandlerInterfaceNames: String,
        ): ImportFunction {
            val handler =
                requireHostHandler(
                    moduleName,
                    handlerInterfaceName,
                    publicName,
                    symbolName,
                    *additionalHandlerInterfaceNames,
                )
            return abi.hostFunction(
                moduleName,
                symbolName,
                function,
                handler,
                hostResultFutures(function),
            )
        }

        private fun hostResultFutures(function: WitPackage.Function): CanonicalFutureIntrinsics? {
            if (!function.isAsync && !fieldsContainFuture(function.results())) {
                return null
            }
            return canonicalFutureIntrinsics
        }

        private fun requireHostHandler(
            moduleName: String,
            handlerInterfaceName: String,
            publicName: String,
            symbolName: String,
            vararg additionalHandlerInterfaceNames: String,
        ): HostHandler {
            var handler = hostImports[importKey(moduleName, publicName)]
            if (handler == null) {
                handler = hostImports[importKey(moduleName, symbolName)]
            }
            val handlerInterfaceNames =
                handlerInterfaceNames(handlerInterfaceName, *additionalHandlerInterfaceNames)
            for (candidateInterfaceName in handlerInterfaceNames) {
                if (handler == null) {
                    handler = hostImports[importKey(candidateInterfaceName, publicName)]
                }
                if (handler == null) {
                    handler = hostImports[importKey(candidateInterfaceName, symbolName)]
                }
            }
            if (handler == null) {
                handler = hostImports[publicName]
            }
            if (handler == null) {
                handler = hostImports[symbolName]
            }
            for (candidateInterfaceName in handlerInterfaceNames) {
                if (handler == null) {
                    handler =
                        wasmPluginHostHandler(hostObjects, candidateInterfaceName, publicName)
                }
                if (handler == null) {
                    handler =
                        wasmPluginHostHandler(hostObjects, candidateInterfaceName, symbolName)
                }
            }
            if (handler == null) {
                throw ComponentModelException(
                    "missing host import handler for ${importKey(handlerInterfaceName, publicName)}"
                )
            }
            return handler
        }

        private fun addModuleDeclaredHostImports(
            functions: MutableList<ImportFunction>,
            module: WasmModule,
        ) {
            val existing = LinkedHashSet<String>()
            for (function in functions) {
                existing.add(importKey(function.module(), function.name()))
            }
            for (index in 0 until module.importSection().importCount()) {
                val imported = module.importSection().getImport(index)
                if (imported.importType() != ExternalType.FUNCTION) {
                    continue
                }
                val key = importKey(imported.module(), imported.name())
                if (existing.contains(key)) {
                    continue
                }
                if (imported.module() == PREVIEW1_MODULE) {
                    val baseKey =
                        importKey(imported.module(), preview1BaseFunctionName(imported.name()))
                    val baseFunction =
                        functions.firstOrNull { function ->
                            importKey(function.module(), function.name()) == baseKey
                        }
                    if (baseFunction != null) {
                        functions.add(
                            ImportFunction(
                                imported.module(),
                                imported.name(),
                                baseFunction.functionType(),
                                baseFunction.handle(),
                                baseFunction.sourceInstance(),
                            )
                        )
                        existing.add(key)
                        continue
                    }
                }
                val contextIntrinsic = ContextIntrinsic.parse(imported.name())
                if (contextIntrinsic != null) {
                    val functionType =
                        module.typeSection().getType((imported as FunctionImport).typeIndex())
                    functions.add(
                        contextIntrinsic.hostFunction(
                            imported.module(),
                            imported.name(),
                            functionType,
                            asyncContexts,
                        )
                    )
                    existing.add(key)
                    continue
                }
                val futureIntrinsic = FutureIntrinsic.parse(imported.name())
                if (futureIntrinsic != null && canonicalFutureIntrinsics != null) {
                    val function =
                        findDeclaredHostFunction(
                            imported.module(),
                            futureIntrinsic.targetSymbolName,
                        ) ?: continue
                    val payloadType = futurePayloadType(function, futureIntrinsic.index)
                    functions.add(
                        futureIntrinsic.hostFunction(
                            imported.module(),
                            imported.name(),
                            canonicalFutureIntrinsics!!,
                            payloadType,
                            abi,
                        )
                    )
                    existing.add(key)
                    continue
                }
                val streamIntrinsic = StreamIntrinsic.parse(imported.name())
                if (streamIntrinsic != null && canonicalStreamIntrinsics != null) {
                    val function =
                        findDeclaredHostFunction(
                            imported.module(),
                            streamIntrinsic.targetSymbolName,
                        ) ?: continue
                    val payloadType = streamPayloadType(function, streamIntrinsic.index)
                    functions.add(
                        streamIntrinsic.hostFunction(
                            imported.module(),
                            imported.name(),
                            canonicalStreamIntrinsics!!,
                            payloadType,
                            abi,
                        )
                    )
                    existing.add(key)
                    continue
                }
                val asyncLowerIntrinsic = AsyncLowerIntrinsic.parse(imported.name())
                if (asyncLowerIntrinsic != null) {
                    val binding =
                        findDeclaredHostBinding(
                            imported.module(),
                            asyncLowerIntrinsic.targetSymbolName,
                        ) ?: continue
                    val handler =
                        requireHostHandler(
                            imported.module(),
                            binding.handlerInterfaceName,
                            binding.publicName,
                            binding.symbolName,
                            *binding.additionalHandlerInterfaceNames.toTypedArray(),
                        )
                    val functionType =
                        module.typeSection().getType((imported as FunctionImport).typeIndex())
                    functions.add(
                        abi.asyncLoweredHostFunction(
                            imported.module(),
                            imported.name(),
                            functionType,
                            binding.function,
                            handler,
                            hostResultFutures(binding.function),
                        )
                    )
                    existing.add(key)
                    continue
                }
                val binding = findDeclaredHostImport(imported.module(), imported.name()) ?: continue
                val handler =
                    requireHostHandler(
                        imported.module(),
                        binding.handlerInterfaceName,
                        binding.publicName,
                        binding.symbolName,
                        *binding.additionalHandlerInterfaceNames.toTypedArray(),
                    )
                functions.add(
                    abi.hostFunction(
                        imported.module(),
                        imported.name(),
                        binding.function,
                        handler,
                        hostResultFutures(binding.function),
                    )
                )
                existing.add(key)
            }
        }

        private fun findDeclaredHostFunction(
            moduleName: String,
            functionName: String,
        ): WitPackage.Function? = findDeclaredHostBinding(moduleName, functionName)?.function

        private fun findDeclaredHostBinding(
            moduleName: String,
            functionName: String,
        ): ModuleImportBinding? =
            findDeclaredHostImport(moduleName, functionName)
                ?: findWorldHostImport(moduleName, functionName)

        private fun findWorldHostImport(
            moduleName: String,
            functionName: String,
        ): ModuleImportBinding? {
            for (world in witPackage.worlds()) {
                if (world.name() != moduleName && world.qualifiedName() != moduleName) {
                    continue
                }
                for (item in world.imports()) {
                    if (item.isFunction && item.name() == functionName) {
                        return ModuleImportBinding(
                            moduleName,
                            item.name(),
                            item.name(),
                            item.function()!!,
                            emptyList(),
                        )
                    }
                }
            }
            return null
        }

        private fun fieldsContainFuture(fields: List<WitPackage.Field>): Boolean {
            for (field in fields) {
                if (typeContainsFuture(field.type())) {
                    return true
                }
            }
            return false
        }

        private fun typeContainsFuture(type: WitPackage.TypeRef): Boolean {
            return when (type.kind()) {
                WitPackage.TypeRef.TypeKind.FUTURE -> true
                WitPackage.TypeRef.TypeKind.LIST,
                WitPackage.TypeRef.TypeKind.OPTION,
                WitPackage.TypeRef.TypeKind.RESULT,
                WitPackage.TypeRef.TypeKind.TUPLE -> {
                    for (argument in type.arguments()) {
                        if (typeContainsFuture(argument)) {
                            return true
                        }
                    }
                    false
                }
                WitPackage.TypeRef.TypeKind.NAMED -> {
                    val declaration = findTypeDeclaration(type.name()!!) ?: return false
                    when (declaration.kind()) {
                        WitPackage.TypeDeclaration.Kind.ALIAS ->
                            typeContainsFuture(declaration.target()!!)
                        WitPackage.TypeDeclaration.Kind.RECORD -> fieldsContainFuture(declaration.fields())
                        WitPackage.TypeDeclaration.Kind.VARIANT -> {
                            for (case in declaration.cases()) {
                                val caseType = case.type() ?: continue
                                if (typeContainsFuture(caseType)) {
                                    return true
                                }
                            }
                            false
                        }
                        else -> false
                    }
                }
                else -> false
            }
        }

        private fun futurePayloadType(
            function: WitPackage.Function,
            index: Int,
        ): WitPackage.TypeRef {
            val cursor = AsyncTypeCursor(index)
            for (parameter in function.parameters()) {
                val found = futurePayloadType(parameter.type(), cursor)
                if (found != null) {
                    return found
                }
            }
            if (function.isAsync) {
                val current = cursor.next()
                if (current == cursor.target) {
                    return asyncResultPayloadType(function)
                }
                throw ComponentModelException(
                    "future intrinsic index $index does not match ${function.name()}"
                )
            }
            for (result in function.results()) {
                val found = futurePayloadType(result.type(), cursor)
                if (found != null) {
                    return found
                }
            }
            throw ComponentModelException(
                "future intrinsic index $index does not match ${function.name()}"
            )
        }

        private fun futurePayloadType(
            type: WitPackage.TypeRef,
            cursor: AsyncTypeCursor,
        ): WitPackage.TypeRef? {
            return when (type.kind()) {
                WitPackage.TypeRef.TypeKind.FUTURE -> {
                    val current = cursor.next()
                    if (current == cursor.target) firstTypeArgument(type) else null
                }
                WitPackage.TypeRef.TypeKind.STREAM -> {
                    cursor.next()
                    null
                }
                WitPackage.TypeRef.TypeKind.LIST,
                WitPackage.TypeRef.TypeKind.OPTION,
                WitPackage.TypeRef.TypeKind.RESULT,
                WitPackage.TypeRef.TypeKind.TUPLE -> {
                    for (argument in type.arguments()) {
                        val found = futurePayloadType(argument, cursor)
                        if (found != null) {
                            return found
                        }
                    }
                    null
                }
                WitPackage.TypeRef.TypeKind.NAMED -> {
                    val declaration = findTypeDeclaration(type.name()!!) ?: return null
                    when (declaration.kind()) {
                        WitPackage.TypeDeclaration.Kind.ALIAS ->
                            futurePayloadType(declaration.target()!!, cursor)
                        WitPackage.TypeDeclaration.Kind.RECORD -> {
                            for (field in declaration.fields()) {
                                val found = futurePayloadType(field.type(), cursor)
                                if (found != null) {
                                    return found
                                }
                            }
                            null
                        }
                        WitPackage.TypeDeclaration.Kind.VARIANT -> {
                            for (case in declaration.cases()) {
                                val caseType = case.type() ?: continue
                                val found = futurePayloadType(caseType, cursor)
                                if (found != null) {
                                    return found
                                }
                            }
                            null
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }

        private fun firstTypeArgument(type: WitPackage.TypeRef): WitPackage.TypeRef =
            if (type.arguments().isEmpty()) WitPackage.TypeRef.primitive("unit")
            else resolvePayloadAlias(type.arguments()[0])

        private fun resolvePayloadAlias(type: WitPackage.TypeRef): WitPackage.TypeRef {
            if (type.kind() != WitPackage.TypeRef.TypeKind.NAMED) {
                return type
            }
            val declaration = findTypeDeclaration(type.name()!!) ?: return type
            if (declaration.kind() != WitPackage.TypeDeclaration.Kind.ALIAS) {
                return type
            }
            return resolvePayloadAlias(declaration.target()!!)
        }

        private fun streamPayloadType(
            function: WitPackage.Function,
            index: Int,
        ): WitPackage.TypeRef {
            val cursor = AsyncTypeCursor(index)
            for (parameter in function.parameters()) {
                val found = streamPayloadType(parameter.type(), cursor)
                if (found != null) {
                    return found
                }
            }
            if (function.isAsync) {
                cursor.next()
                throw ComponentModelException(
                    "stream intrinsic index $index does not match ${function.name()}"
                )
            }
            for (result in function.results()) {
                val found = streamPayloadType(result.type(), cursor)
                if (found != null) {
                    return found
                }
            }
            throw ComponentModelException(
                "stream intrinsic index $index does not match ${function.name()}"
            )
        }

        private fun streamPayloadType(
            type: WitPackage.TypeRef,
            cursor: AsyncTypeCursor,
        ): WitPackage.TypeRef? {
            return when (type.kind()) {
                WitPackage.TypeRef.TypeKind.STREAM -> {
                    val current = cursor.next()
                    if (current == cursor.target) firstTypeArgument(type) else null
                }
                WitPackage.TypeRef.TypeKind.FUTURE -> {
                    cursor.next()
                    null
                }
                WitPackage.TypeRef.TypeKind.LIST,
                WitPackage.TypeRef.TypeKind.OPTION,
                WitPackage.TypeRef.TypeKind.RESULT,
                WitPackage.TypeRef.TypeKind.TUPLE -> {
                    for (argument in type.arguments()) {
                        val found = streamPayloadType(argument, cursor)
                        if (found != null) {
                            return found
                        }
                    }
                    null
                }
                WitPackage.TypeRef.TypeKind.NAMED -> {
                    val declaration = findTypeDeclaration(type.name()!!) ?: return null
                    when (declaration.kind()) {
                        WitPackage.TypeDeclaration.Kind.ALIAS ->
                            streamPayloadType(declaration.target()!!, cursor)
                        WitPackage.TypeDeclaration.Kind.RECORD -> {
                            for (field in declaration.fields()) {
                                val found = streamPayloadType(field.type(), cursor)
                                if (found != null) {
                                    return found
                                }
                            }
                            null
                        }
                        WitPackage.TypeDeclaration.Kind.VARIANT -> {
                            for (case in declaration.cases()) {
                                val caseType = case.type() ?: continue
                                val found = streamPayloadType(caseType, cursor)
                                if (found != null) {
                                    return found
                                }
                            }
                            null
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }

        private fun asyncResultPayloadType(function: WitPackage.Function): WitPackage.TypeRef {
            if (function.results().isEmpty()) {
                return WitPackage.TypeRef.primitive("unit")
            }
            if (function.results().size == 1) {
                return function.results()[0].type()
            }
            val types = ArrayList<WitPackage.TypeRef>()
            for (result in function.results()) {
                types.add(result.type())
            }
            return WitPackage.TypeRef.constructed(WitPackage.TypeRef.TypeKind.TUPLE, types)
        }

        private fun findTypeDeclaration(name: String): WitPackage.TypeDeclaration? {
            val normalized = normalizeTypeName(name)
            for (declaration in witPackage.interfaces()) {
                for (member in declaration.members()) {
                    if (
                        member is WitPackage.TypeDeclaration && matchesTypeName(member, normalized)
                    ) {
                        return member
                    }
                }
            }
            for (declaration in witPackage.declarations()) {
                if (
                    declaration is WitPackage.TypeDeclaration &&
                        matchesTypeName(declaration, normalized)
                ) {
                    return declaration
                }
                if (declaration is WitPackage.WorldDeclaration) {
                    for (member in declaration.declarations()) {
                        if (
                            member is WitPackage.TypeDeclaration &&
                                matchesTypeName(member, normalized)
                        ) {
                            return member
                        }
                    }
                }
            }
            return null
        }

        private fun matchesTypeName(
            declaration: WitPackage.TypeDeclaration,
            normalizedName: String,
        ): Boolean {
            val declarationName = normalizeTypeName(declaration.name())
            return declarationName == normalizedName ||
                WitNames.lastSegment(declarationName) == WitNames.lastSegment(normalizedName)
        }

        private fun normalizeTypeName(name: String): String =
            WitNames.withoutVersion(WitNames.stripIdentifierEscape(name))

        private fun findDeclaredHostImport(
            moduleName: String,
            functionName: String,
        ): ModuleImportBinding? {
            for (declaration in witPackage.interfaces()) {
                val interfaceNames = interfaceModuleNames(declaration)
                if (!matchesInterfaceModuleName(moduleName, interfaceNames)) {
                    continue
                }
                for (binding in interfaceFunctionBindings(declaration)) {
                    if (functionName == binding.publicName || functionName == binding.symbolName) {
                        val localName = WitNames.lastSegment(declaration.name())
                        val additional = ArrayList<String>()
                        for (interfaceName in interfaceNames) {
                            if (interfaceName != localName) {
                                additional.add(interfaceName)
                            }
                        }
                        return ModuleImportBinding(
                            localName,
                            binding.publicName,
                            binding.symbolName,
                            binding.function,
                            additional,
                        )
                    }
                }
            }
            return null
        }

        private fun interfaceModuleNames(
            declaration: WitPackage.InterfaceDeclaration
        ): Set<String> {
            val result = LinkedHashSet<String>()
            result.add(WitNames.lastSegment(declaration.name()))
            result.add(declaration.name())
            result.add(WitNames.lastSegment(qualifiedInterfaceName(declaration)))
            result.add(qualifiedInterfaceName(declaration))
            return result
        }

        private fun matchesInterfaceModuleName(
            moduleName: String,
            interfaceNames: Set<String>,
        ): Boolean {
            if (interfaceNames.contains(moduleName)) {
                return true
            }
            val unversioned = WitNames.withoutVersion(moduleName)
            for (interfaceName in interfaceNames) {
                if (WitNames.withoutVersion(interfaceName) == unversioned) {
                    return true
                }
            }
            return false
        }

        private fun handlerInterfaceNames(
            handlerInterfaceName: String,
            vararg additionalHandlerInterfaceNames: String,
        ): List<String> {
            val result = ArrayList<String>()
            val seen = LinkedHashSet<String>()
            addHandlerInterfaceName(result, seen, handlerInterfaceName)
            for (additionalHandlerInterfaceName in additionalHandlerInterfaceNames) {
                addHandlerInterfaceName(result, seen, additionalHandlerInterfaceName)
            }
            return result
        }

        private fun addHandlerInterfaceName(
            result: MutableList<String>,
            seen: MutableSet<String>,
            handlerInterfaceName: String,
        ) {
            if (seen.add(handlerInterfaceName)) {
                result.add(handlerInterfaceName)
            }
        }

        private fun addImportedResourceIntrinsics(
            functions: MutableList<ImportFunction>,
            item: WitPackage.WorldItem,
            importedInterface: WitPackage.InterfaceDeclaration,
        ) {
            for (resource in resourceDeclarations(importedInterface)) {
                for (moduleName in interfaceModuleNames(item, importedInterface)) {
                    addResourceIntrinsic(
                        functions,
                        moduleName,
                        resource.name(),
                        ResourceIntrinsic.DROP,
                    )
                }
            }
        }

        private fun addExportedResourceIntrinsics(
            functions: MutableList<ImportFunction>,
            item: WitPackage.WorldItem,
            exportedInterface: WitPackage.InterfaceDeclaration,
        ) {
            for (resource in resourceDeclarations(exportedInterface)) {
                val resourceKey = qualifiedInterfaceName(exportedInterface) + "/" + resource.name()
                for (moduleName in exportedResourceModuleNames(item, exportedInterface)) {
                    addResourceIntrinsic(
                        functions,
                        moduleName,
                        resource.name(),
                        ResourceIntrinsic.newHandle(resourceKey),
                    )
                    addResourceIntrinsic(
                        functions,
                        moduleName,
                        resource.name(),
                        ResourceIntrinsic.rep(resourceKey),
                    )
                    addResourceIntrinsic(
                        functions,
                        moduleName,
                        resource.name(),
                        ResourceIntrinsic.drop(
                            resourceKey,
                            exportNameCandidates(
                                interfaceName(item),
                                qualifiedInterfaceName(exportedInterface),
                                "[dtor]" + resource.name(),
                                false,
                                item.name(),
                            ),
                        ),
                    )
                }
            }
        }

        private fun addResourceIntrinsic(
            functions: MutableList<ImportFunction>,
            moduleName: String,
            resourceName: String,
            intrinsic: ResourceIntrinsic,
        ) {
            val symbolName = intrinsic.symbolName(resourceName)
            var handler = hostImports[importKey(moduleName, symbolName)]
            if (handler == null) {
                handler = hostImports[symbolName]
            }
            if (handler != null) {
                val explicitHandler = handler
                functions.add(
                    HostFunction(moduleName, symbolName, intrinsic.functionType()) { _, args ->
                        intrinsic.callHandler(explicitHandler, args)
                    }
                )
                return
            }
            functions.add(
                HostFunction(moduleName, symbolName, intrinsic.functionType()) { instance, args ->
                    intrinsic.apply(instance, resourceHandles, args)
                }
            )
        }

        private fun addAsyncTaskReturnImport(
            functions: MutableList<ImportFunction>,
            moduleName: String,
            function: WitPackage.Function,
        ) {
            val importName = asyncTaskReturnName(function)
            val slot = asyncTaskReturnSlot(moduleName, importName)
            functions.add(
                HostFunction(moduleName, importName, abi.asyncTaskReturnFunctionType(function)) {
                    _,
                    args ->
                    slot.putRawResults(args)
                    LongArray(0)
                }
            )
        }

        private fun asyncTaskReturnSlot(
            moduleName: String,
            importName: String,
        ): AsyncTaskReturnSlot {
            val key = importKey(moduleName, importName)
            val existing = asyncTaskReturns[key]
            if (existing != null) {
                return existing
            }
            val created = AsyncTaskReturnSlot()
            asyncTaskReturns[key] = created
            return created
        }

        private fun rootAsyncTaskReturnModuleName(): String = "[export]\$root"

        private fun interfaceAsyncTaskReturnModuleName(
            declaration: WitPackage.InterfaceDeclaration
        ): String = "[export]${qualifiedInterfaceName(declaration)}"

        private fun asyncTaskReturnName(function: WitPackage.Function): String =
            "[task-return][async]${function.name()}"

        private fun resourceDeclarations(
            declaration: WitPackage.InterfaceDeclaration
        ): List<WitPackage.TypeDeclaration> {
            val result = ArrayList<WitPackage.TypeDeclaration>()
            for (member in declaration.members()) {
                if (
                    member is WitPackage.TypeDeclaration &&
                        member.kind() == WitPackage.TypeDeclaration.Kind.RESOURCE
                ) {
                    result.add(member)
                }
            }
            return result
        }

        private fun exportedResourceModuleNames(
            item: WitPackage.WorldItem,
            declaration: WitPackage.InterfaceDeclaration,
        ): Set<String> {
            val result = LinkedHashSet<String>()
            for (moduleName in interfaceModuleNames(item, declaration)) {
                result.add("[export]$moduleName")
            }
            return result
        }

        private fun deduplicateFunctions(functions: List<ImportFunction>): List<ImportFunction> {
            val result = LinkedHashMap<String, ImportFunction>()
            for (function in functions) {
                putIfAbsent(result, function.module() + "\u0000" + function.name(), function)
            }
            return ArrayList(result.values)
        }

        private fun bindExports(
            world: WitPackage.WorldDeclaration,
            instance: Instance,
        ): Map<String, CanonicalAbi.BoundFunction> {
            val result = LinkedHashMap<String, CanonicalAbi.BoundFunction>()
            for (item in world.exports()) {
                if (item.isFunction) {
                    bindExport(
                        result,
                        instance,
                        world.name(),
                        world.name(),
                        item.name(),
                        item.name(),
                        item.function()!!,
                        if (item.function()!!.isAsync) rootAsyncTaskReturnModuleName() else null,
                    )
                    continue
                }
                val localInterfaceName = interfaceName(item)
                val exportedInterface = requireInterface(item)
                for (binding in interfaceFunctionBindings(exportedInterface)) {
                    bindExport(
                        result,
                        instance,
                        localInterfaceName,
                        qualifiedInterfaceName(exportedInterface),
                        binding.publicName,
                        binding.symbolName,
                        binding.function,
                        if (binding.function.isAsync)
                            interfaceAsyncTaskReturnModuleName(exportedInterface)
                        else null,
                        item.name(),
                    )
                }
            }
            return result
        }

        private fun bindExport(
            exports: MutableMap<String, CanonicalAbi.BoundFunction>,
            instance: Instance,
            interfaceName: String,
            qualifiedInterfaceName: String,
            publicName: String,
            symbolName: String,
            function: WitPackage.Function,
            asyncTaskReturnModuleName: String?,
            vararg additionalInterfaceNames: String,
        ) {
            val coreName =
                findCoreExportName(
                    instance,
                    interfaceName,
                    qualifiedInterfaceName,
                    symbolName,
                    function,
                    *additionalInterfaceNames,
                )
            val asyncTaskReturn =
                if (function.isAsync &&
                    asyncTaskReturnModuleName != null &&
                    coreName.startsWith(ASYNC_LIFT_EXPORT_PREFIX)
                ) {
                    asyncTaskReturnSlot(asyncTaskReturnModuleName, asyncTaskReturnName(function))
                } else {
                    null
                }
            val bound = abi.bind(instance, coreName, function, asyncTaskReturn)
            exports[publicName] = bound
            exports[symbolName] = bound
            exports[importKey(interfaceName, publicName)] = bound
            exports[importKey(interfaceName, symbolName)] = bound
            exports["$interfaceName#$publicName"] = bound
            exports["$interfaceName#$symbolName"] = bound
            exports["$interfaceName/$publicName"] = bound
            exports["$interfaceName/$symbolName"] = bound
            if (qualifiedInterfaceName != interfaceName) {
                exports[importKey(qualifiedInterfaceName, publicName)] = bound
                exports[importKey(qualifiedInterfaceName, symbolName)] = bound
                exports["$qualifiedInterfaceName#$publicName"] = bound
                exports["$qualifiedInterfaceName#$symbolName"] = bound
                exports["$qualifiedInterfaceName/$publicName"] = bound
                exports["$qualifiedInterfaceName/$symbolName"] = bound
            }
            for (additionalInterfaceName in additionalInterfaceNames) {
                if (
                    additionalInterfaceName == interfaceName ||
                        additionalInterfaceName == qualifiedInterfaceName
                ) {
                    continue
                }
                exports[importKey(additionalInterfaceName, publicName)] = bound
                exports[importKey(additionalInterfaceName, symbolName)] = bound
                exports["$additionalInterfaceName#$publicName"] = bound
                exports["$additionalInterfaceName#$symbolName"] = bound
                exports["$additionalInterfaceName/$publicName"] = bound
                exports["$additionalInterfaceName/$symbolName"] = bound
            }
        }

        private fun findCoreExportName(
            instance: Instance,
            interfaceName: String,
            qualifiedInterfaceName: String,
            functionName: String,
            function: WitPackage.Function,
            vararg additionalInterfaceNames: String,
        ): String {
            val candidates =
                exportNameCandidates(
                    interfaceName,
                    qualifiedInterfaceName,
                    functionName,
                    function.isAsync,
                    *additionalInterfaceNames,
                )
            var mismatchedType: FunctionType? = null
            var mismatchedName: String? = null
            var mismatchedExpected: FunctionType? = null
            for (candidate in candidates) {
                try {
                    val actual = instance.exportType(candidate)
                    val expected = expectedExportType(function, candidate)
                    if (actual == expected) {
                        return candidate
                    }
                    mismatchedName = candidate
                    mismatchedType = actual
                    mismatchedExpected = expected
                } catch (ignored: InvalidException) {
                    // Try the next common lowering convention.
                } catch (ignored: NullPointerException) {
                    // Instance.exportType currently throws NPE when an export is absent.
                    // Try the next common lowering convention.
                }
            }
            if (mismatchedName != null) {
                throw ComponentModelException(
                    "plugin export $mismatchedName has core type $mismatchedType, expected $mismatchedExpected"
                )
            }
            throw ComponentModelException(
                "plugin module does not export WIT function ${importKey(interfaceName, functionName)}"
            )
        }

        private fun expectedExportType(
            function: WitPackage.Function,
            candidate: String,
        ): FunctionType =
            if (function.isAsync && candidate.startsWith(ASYNC_LIFT_EXPORT_PREFIX)) {
                abi.asyncLiftedExportFunctionType(function)
            } else {
                abi.coreFunctionType(function, CanonicalAbi.Direction.LIFTED_EXPORT)
            }

        private fun requireInterface(item: WitPackage.WorldItem): WitPackage.InterfaceDeclaration {
            val names = ArrayList<String>()
            val type = item.type()
            val typeName = type?.name()
            if (
                type != null && type.kind() == WitPackage.TypeRef.TypeKind.NAMED && typeName != null
            ) {
                names.add(typeName)
            }
            names.add(item.name())
            names.add(interfaceName(item))
            return requireInterface(names)
        }

        private fun requireInterface(names: List<String>): WitPackage.InterfaceDeclaration {
            val qualifiedNames = ArrayList<String>()
            val localNames = ArrayList<String>()
            for (name in names) {
                if (name.contains("/") || name.contains(":")) {
                    qualifiedNames.add(name)
                } else {
                    localNames.add(name)
                }
            }
            for (declaration in witPackage.interfaces()) {
                for (name in qualifiedNames) {
                    if (qualifiedInterfaceName(declaration) == name || declaration.name() == name) {
                        return declaration
                    }
                }
            }
            for (declaration in witPackage.interfaces()) {
                for (name in localNames) {
                    if (
                        declaration.name() == name ||
                            WitNames.lastSegment(declaration.name()) == name ||
                            WitNames.lastSegment(qualifiedInterfaceName(declaration)) == name
                    ) {
                        return declaration
                    }
                }
            }
            val available = ArrayList<String>()
            for (declaration in witPackage.interfaces()) {
                available.add(declaration.name())
            }
            val worlds = ArrayList<String>()
            for (world in witPackage.worlds()) {
                for (item in world.imports()) {
                    worlds.add(world.name() + " import " + describeWorldItem(item))
                }
                for (item in world.exports()) {
                    worlds.add(world.name() + " export " + describeWorldItem(item))
                }
            }
            throw ComponentModelException(
                "unknown WIT interface $names, available interfaces: $available, world items: $worlds"
            )
        }

        private fun describeWorldItem(item: WitPackage.WorldItem): String {
            if (item.isFunction) {
                return item.name() + ": func"
            }
            if (item.type() != null) {
                return item.name() + ": " + item.type()
            }
            return item.name()
        }

        private fun selectComponentModule(
            component: WasmPluginUnbundledComponent,
            world: WitPackage.WorldDeclaration,
        ): WasmModule {
            val matches = LinkedHashMap<String, WasmModule>()
            val exportsByModule = LinkedHashMap<String, List<String>>()
            for ((name, bytes) in component.modules()) {
                val candidate = WasmParser.parse(bytes)
                val exportNames = functionExports(candidate)
                exportsByModule[name] = exportNames
                if (exportsWorld(candidate, world)) {
                    matches[name] = candidate
                }
            }
            if (matches.size == 1) {
                return matches.values.iterator().next()
            }
            if (matches.isEmpty()) {
                throw ComponentModelException(
                    "component does not contain a core module matching WIT world " +
                        world.name() +
                        "; module function exports: " +
                        exportsByModule
                )
            }
            throw ComponentModelException(
                "component has multiple core modules matching WIT world " +
                    world.name() +
                    ": " +
                    matches.keys
            )
        }

        private fun exportsWorld(module: WasmModule, world: WitPackage.WorldDeclaration): Boolean {
            val exports = LinkedHashMap<String, Boolean>()
            for (name in functionExports(module)) {
                exports[name] = true
            }
            for (item in world.exports()) {
                if (item.isFunction) {
                    if (
                        exportNameCandidates(
                                world.name(),
                                world.name(),
                                item.name(),
                                item.function()!!.isAsync,
                            )
                            .none { exports.containsKey(it) }
                    ) {
                        return false
                    }
                    continue
                }
                val localInterfaceName = interfaceName(item)
                val exportedInterface = requireInterface(item)
                for (binding in interfaceFunctionBindings(exportedInterface)) {
                    var found = false
                    for (candidate in
                        exportNameCandidates(
                            localInterfaceName,
                            qualifiedInterfaceName(exportedInterface),
                            binding.symbolName,
                            binding.function.isAsync,
                            item.name(),
                        )) {
                        if (exports.containsKey(candidate)) {
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        return false
                    }
                }
            }
            return true
        }

        private fun functionExports(module: WasmModule): List<String> {
            val result = ArrayList<String>()
            val exports = module.exportSection()
            for (index in 0 until exports.exportCount()) {
                val export = exports.getExport(index)
                if (export.exportType() == ExternalType.FUNCTION) {
                    result.add(export.name())
                }
            }
            return result
        }

        private fun runGuestInitializers(module: WasmModule, instance: Instance) {
            val exports = functionExports(module).toSet()
            for (name in GUEST_INIT_EXPORTS) {
                if (name in exports) {
                    instance.export(name).apply()
                }
            }
        }

        private fun exportNameCandidates(
            interfaceName: String,
            qualifiedInterfaceName: String,
            functionName: String,
            isAsync: Boolean,
            vararg additionalInterfaceNames: String,
        ): List<String> {
            val candidates = ArrayList<String>()
            val seen = LinkedHashSet<String>()
            addCandidate(candidates, seen, functionName)
            if (isAsync) {
                addCandidate(candidates, seen, "[async]$functionName")
                addCandidate(
                    candidates,
                    seen,
                    "$ASYNC_LIFT_EXPORT_PREFIX[async]$functionName",
                )
            }
            addInterfaceExportCandidates(candidates, seen, interfaceName, functionName, isAsync)
            if (qualifiedInterfaceName != interfaceName) {
                addInterfaceExportCandidates(
                    candidates,
                    seen,
                    qualifiedInterfaceName,
                    functionName,
                    isAsync,
                )
            }
            for (additionalInterfaceName in additionalInterfaceNames) {
                if (
                    additionalInterfaceName == interfaceName ||
                        additionalInterfaceName == qualifiedInterfaceName
                ) {
                    continue
                }
                addInterfaceExportCandidates(
                    candidates,
                    seen,
                    additionalInterfaceName,
                    functionName,
                    isAsync,
                )
            }
            return candidates
        }

        private fun addInterfaceExportCandidates(
            candidates: MutableList<String>,
            seen: MutableSet<String>,
            interfaceName: String,
            functionName: String,
            isAsync: Boolean,
        ) {
            addCandidate(candidates, seen, importKey(interfaceName, functionName))
            addCandidate(candidates, seen, "$interfaceName#$functionName")
            addCandidate(candidates, seen, "$interfaceName/$functionName")
            if (isAsync) {
                val asyncFunctionName = "[async]$functionName"
                addCandidate(candidates, seen, importKey(interfaceName, asyncFunctionName))
                addCandidate(candidates, seen, "$interfaceName#$asyncFunctionName")
                addCandidate(candidates, seen, "$interfaceName/$asyncFunctionName")
                addCandidate(
                    candidates,
                    seen,
                    "$ASYNC_LIFT_EXPORT_PREFIX$interfaceName.$asyncFunctionName",
                )
                addCandidate(
                    candidates,
                    seen,
                    "$ASYNC_LIFT_EXPORT_PREFIX$interfaceName#$asyncFunctionName",
                )
                addCandidate(
                    candidates,
                    seen,
                    "$ASYNC_LIFT_EXPORT_PREFIX$interfaceName/$asyncFunctionName",
                )
            }
        }

        private fun addCandidate(
            candidates: MutableList<String>,
            seen: MutableSet<String>,
            candidate: String,
        ) {
            if (seen.add(candidate)) {
                candidates.add(candidate)
            }
        }

        private fun interfaceFunctionBindings(
            declaration: WitPackage.InterfaceDeclaration
        ): List<FunctionBinding> {
            val result = ArrayList<FunctionBinding>()
            for (function in declaration.functions()) {
                result.add(FunctionBinding(function.name(), function.name(), function))
            }
            for (member in declaration.members()) {
                if (
                    member is WitPackage.TypeDeclaration &&
                        member.kind() == WitPackage.TypeDeclaration.Kind.RESOURCE
                ) {
                    for (function in member.functions()) {
                        result.add(resourceFunctionBinding(member.name(), function))
                    }
                }
            }
            return result
        }

        private fun resourceFunctionBinding(
            resourceName: String,
            function: WitPackage.Function,
        ): FunctionBinding {
            var publicName = "$resourceName.${function.name()}"
            val symbolName: String
            var effectiveFunction = function
            if (function.isConstructor) {
                publicName = "$resourceName.constructor"
                symbolName = "[constructor]$resourceName"
                effectiveFunction =
                    WitPackage.Function(
                        function.name(),
                        function.parameters(),
                        listOf(
                            WitPackage.Field(
                                "result",
                                WitPackage.TypeRef.constructed(
                                    WitPackage.TypeRef.TypeKind.OWN,
                                    listOf(WitPackage.TypeRef.named(resourceName)),
                                ),
                            )
                        ),
                        function.isAsync,
                        function.isStatic,
                        function.isConstructor,
                    )
            } else if (function.isStatic) {
                symbolName = "[static]$resourceName.${function.name()}"
            } else {
                symbolName = "[method]$resourceName.${function.name()}"
                val parameters = ArrayList<WitPackage.Field>()
                parameters.add(
                    WitPackage.Field(
                        "self",
                        WitPackage.TypeRef.constructed(
                            WitPackage.TypeRef.TypeKind.BORROW,
                            listOf(WitPackage.TypeRef.named(resourceName)),
                        ),
                    )
                )
                parameters.addAll(function.parameters())
                effectiveFunction =
                    WitPackage.Function(
                        function.name(),
                        parameters,
                        function.results(),
                        function.isAsync,
                        function.isStatic,
                        function.isConstructor,
                    )
            }
            return FunctionBinding(publicName, symbolName, effectiveFunction)
        }

        private fun interfaceModuleNames(
            item: WitPackage.WorldItem,
            declaration: WitPackage.InterfaceDeclaration,
        ): Set<String> {
            val result = LinkedHashSet<String>()
            result.add(WitNames.lastSegment(declaration.name()))
            result.add(declaration.name())
            result.add(WitNames.lastSegment(qualifiedInterfaceName(declaration)))
            result.add(qualifiedInterfaceName(declaration))
            result.add(WitNames.lastSegment(item.name()))
            result.add(item.name())
            val typeName = item.type()?.name()
            if (typeName != null) {
                result.add(WitNames.lastSegment(typeName))
                result.add(typeName)
            }
            return result
        }

        private fun interfaceName(item: WitPackage.WorldItem): String {
            val type = item.type()
            val typeName = type?.name()
            if (
                type != null && type.kind() == WitPackage.TypeRef.TypeKind.NAMED && typeName != null
            ) {
                return WitNames.lastSegment(typeName)
            }
            return WitNames.lastSegment(item.name())
        }

        private fun qualifiedInterfaceName(declaration: WitPackage.InterfaceDeclaration): String =
            declaration.qualifiedName()

        private fun importKey(interfaceName: String?, functionName: String?): String =
            "$interfaceName.$functionName"

        private class CanonicalResourceHandles {
            private val handles = LinkedHashMap<String?, MutableMap<Long, Long>>()
            private var nextHandle = 1L

            fun newHandle(resourceKey: String?, rep: Long): Long {
                if (nextHandle == 0L || nextHandle > 0xffff_ffffL) {
                    throw ComponentModelException("WIT canonical resource table exhausted")
                }
                val handle = nextHandle++
                val resources = handles[resourceKey] ?: LinkedHashMap<Long, Long>().also {
                    handles[resourceKey] = it
                }
                resources[handle] = toU32(rep)
                return handle
            }

            fun rep(resourceKey: String?, handle: Long): Long {
                val resources = handles[resourceKey]
                val rep = resources?.get(toU32(handle))
                if (rep == null) {
                    throw ComponentModelException(
                        "unknown WIT resource handle ${toU32(handle).toULong()} for $resourceKey"
                    )
                }
                return rep
            }

            fun drop(resourceKey: String?, handle: Long): Long {
                val resources = handles[resourceKey]
                val rep = resources?.remove(toU32(handle))
                if (rep == null) {
                    throw ComponentModelException(
                        "unknown WIT resource handle ${toU32(handle).toULong()} for $resourceKey"
                    )
                }
                return rep
            }
        }

        private class AsyncTaskReturnSlot : CanonicalAbi.AsyncTaskReturn {
            private var rawResults: LongArray? = null

            fun putRawResults(results: LongArray) {
                rawResults = results.copyOf()
            }

            override fun reset() {
                rawResults = null
            }

            override fun takeRawResults(): LongArray? = rawResults?.copyOf()
        }

        private class ResourceIntrinsic
        private constructor(
            private val kind: Kind,
            private val resourceKey: String?,
            dtorExportNames: List<String> = emptyList(),
        ) {
            private val dtorExportNames: List<String> = dtorExportNames.toList()

            fun symbolName(resourceName: String): String =
                when (kind) {
                    Kind.NEW -> "[resource-new]$resourceName"
                    Kind.REP -> "[resource-rep]$resourceName"
                    Kind.DROP -> "[resource-drop]$resourceName"
                }

            fun functionType(): FunctionType =
                when (kind) {
                    Kind.NEW,
                    Kind.REP -> FunctionType.of(listOf(ValType.I32), listOf(ValType.I32))
                    Kind.DROP -> FunctionType.of(listOf(ValType.I32), emptyList())
                }

            fun apply(
                instance: Instance,
                handles: CanonicalResourceHandles,
                args: LongArray,
            ): LongArray {
                requireArity(args)
                return when (kind) {
                    Kind.NEW -> longArrayOf(handles.newHandle(resourceKey, args[0]))
                    Kind.REP -> longArrayOf(handles.rep(resourceKey, args[0]))
                    Kind.DROP -> {
                        if (resourceKey != null) {
                            val rep = handles.drop(resourceKey, args[0])
                            callDtorIfPresent(instance, rep)
                        }
                        LongArray(0)
                    }
                }
            }

            private fun callDtorIfPresent(instance: Instance, rep: Long) {
                if (dtorExportNames.isEmpty()) {
                    return
                }
                val expected = FunctionType.of(listOf(ValType.I32), emptyList())
                var mismatchedName: String? = null
                var mismatchedType: FunctionType? = null
                for (name in dtorExportNames) {
                    try {
                        val actual = instance.exportType(name)
                        if (actual == expected) {
                            instance.export(name).apply(rep)
                            return
                        }
                        mismatchedName = name
                        mismatchedType = actual
                    } catch (ignored: InvalidException) {
                        // Optional destructor export; try the next common lowering convention.
                    } catch (ignored: NullPointerException) {
                        // Optional destructor export; try the next common lowering convention.
                    }
                }
                if (mismatchedName != null) {
                    throw ComponentModelException(
                        "WIT resource destructor $mismatchedName has core type $mismatchedType, expected $expected"
                    )
                }
            }

            fun callHandler(handler: HostHandler, args: LongArray): LongArray {
                requireArity(args)
                val result = handler.apply(listOf(toU32(args[0])))
                return when (kind) {
                    Kind.NEW,
                    Kind.REP -> longArrayOf(resourceHandle(result))
                    Kind.DROP -> LongArray(0)
                }
            }

            private fun requireArity(args: LongArray) {
                if (args.size != 1) {
                    throw ComponentModelException(
                        "canonical resource intrinsic expected one i32 argument, got ${args.size}"
                    )
                }
            }

            private enum class Kind {
                NEW,
                REP,
                DROP,
            }

            companion object {
                val DROP = ResourceIntrinsic(Kind.DROP, null)

                fun newHandle(resourceKey: String): ResourceIntrinsic =
                    ResourceIntrinsic(Kind.NEW, resourceKey)

                fun rep(resourceKey: String): ResourceIntrinsic =
                    ResourceIntrinsic(Kind.REP, resourceKey)

                fun drop(resourceKey: String, dtorExportNames: List<String>): ResourceIntrinsic =
                    ResourceIntrinsic(Kind.DROP, resourceKey, dtorExportNames)

                private fun resourceHandle(value: Any?): Long {
                    if (value is WitResource<*>) {
                        return value.handle()
                    }
                    if (value is Number) {
                        return value.toLong()
                    }
                    throw ComponentModelException(
                        "canonical resource intrinsic expected a numeric handle, got $value"
                    )
                }
            }
        }

        private data class FunctionBinding(
            val publicName: String,
            val symbolName: String,
            val function: WitPackage.Function,
        )

        private data class ModuleImportBinding(
            val handlerInterfaceName: String,
            val publicName: String,
            val symbolName: String,
            val function: WitPackage.Function,
            val additionalHandlerInterfaceNames: List<String>,
        )

        private class AsyncTypeCursor(val target: Int) {
            private var nextIndex = 0

            fun next(): Int = nextIndex++
        }

        private class ContextIntrinsic private constructor(
            private val kind: Kind,
            private val index: Int,
        ) {
            fun hostFunction(
                moduleName: String,
                symbolName: String,
                functionType: FunctionType,
                contexts: MutableMap<Int, Int>,
            ): HostFunction =
                HostFunction(moduleName, symbolName, functionType) { _, args ->
                    when (kind) {
                        Kind.GET -> {
                            requireArity(symbolName, args, 0)
                            val value = contexts[index] ?: 0
                            longArrayOf(value.toLong())
                        }
                        Kind.SET -> {
                            requireArity(symbolName, args, 1)
                            contexts[index] = args[0].toInt()
                            LongArray(0)
                        }
                    }
                }

            private enum class Kind {
                GET,
                SET,
            }

            companion object {
                private const val GET_PREFIX = "[context-get-"
                private const val SET_PREFIX = "[context-set-"

                fun parse(symbolName: String): ContextIntrinsic? =
                    parse(symbolName, GET_PREFIX, Kind.GET)
                        ?: parse(symbolName, SET_PREFIX, Kind.SET)

                private fun parse(
                    symbolName: String,
                    prefix: String,
                    kind: Kind,
                ): ContextIntrinsic? {
                    if (!symbolName.startsWith(prefix) || !symbolName.endsWith("]")) {
                        return null
                    }
                    val index =
                        symbolName.substring(prefix.length, symbolName.length - 1).toIntOrNull()
                            ?: return null
                    return ContextIntrinsic(kind, index)
                }

                private fun requireArity(
                    symbolName: String,
                    args: LongArray,
                    expected: Int,
                ) {
                    if (args.size != expected) {
                        throw ComponentModelException(
                            "canonical context intrinsic $symbolName expected $expected arguments, got ${args.size}"
                        )
                    }
                }
            }
        }

        private class AsyncLowerIntrinsic private constructor(val targetSymbolName: String) {
            companion object {
                fun parse(symbolName: String): AsyncLowerIntrinsic? {
                    if (!symbolName.startsWith(PREFIX)) {
                        return null
                    }
                    val target = symbolName.substring(PREFIX.length)
                    if (target.startsWith("[future-") || target.startsWith("[stream-")) {
                        return null
                    }
                    if (target.isEmpty()) {
                        return null
                    }
                    return AsyncLowerIntrinsic(target)
                }

                private const val PREFIX = "[async-lower]"
            }
        }

        private class FutureIntrinsic
        private constructor(private val kind: Kind, val index: Int, val targetSymbolName: String) {
            fun hostFunction(
                moduleName: String,
                symbolName: String,
                intrinsics: CanonicalFutureIntrinsics,
                payloadType: WitPackage.TypeRef,
                abi: CanonicalAbi,
            ): HostFunction =
                HostFunction(moduleName, symbolName, functionType()) { instance, args ->
                    apply(instance, intrinsics, payloadType, abi, args)
                }

            private fun functionType(): FunctionType =
                when (kind) {
                    Kind.NEW -> FunctionType.of(emptyList(), listOf(ValType.I64))
                    Kind.CANCEL_READ,
                    Kind.CANCEL_WRITE -> FunctionType.of(listOf(ValType.I32), listOf(ValType.I32))
                    Kind.DROP_READABLE,
                    Kind.DROP_WRITABLE -> FunctionType.of(listOf(ValType.I32), emptyList())
                    Kind.READ,
                    Kind.WRITE ->
                        FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32))
                }

            private fun apply(
                instance: Instance,
                intrinsics: CanonicalFutureIntrinsics,
                payloadType: WitPackage.TypeRef,
                abi: CanonicalAbi,
                args: LongArray,
            ): LongArray {
                val result =
                    when (kind) {
                        Kind.NEW -> {
                            requireArity(args, 0)
                            intrinsics.futureNew()
                        }
                        Kind.CANCEL_READ -> {
                            requireArity(args, 1)
                            intrinsics.futureCancelRead(toU32(args[0]))
                        }
                        Kind.CANCEL_WRITE -> {
                            requireArity(args, 1)
                            intrinsics.futureCancelWrite(toU32(args[0]))
                        }
                        Kind.DROP_READABLE -> {
                            requireArity(args, 1)
                            intrinsics.futureDropReadable(toU32(args[0]))
                            return LongArray(0)
                        }
                        Kind.DROP_WRITABLE -> {
                            requireArity(args, 1)
                            intrinsics.futureDropWritable(toU32(args[0]))
                            return LongArray(0)
                        }
                        Kind.READ -> {
                            requireArity(args, 2)
                            intrinsics.futureRead(
                                instance,
                                toU32(args[0]),
                                toU32Int(args[1], "pointer"),
                                abi,
                                payloadType,
                            )
                        }
                        Kind.WRITE -> {
                            requireArity(args, 2)
                            intrinsics.futureWrite(
                                instance,
                                toU32(args[0]),
                                toU32Int(args[1], "pointer"),
                                abi,
                                payloadType,
                            )
                        }
                    }
                return longArrayOf(result)
            }

            private fun requireArity(args: LongArray, expected: Int) {
                if (args.size != expected) {
                    throw ComponentModelException(
                        "canonical future intrinsic expected $expected arguments, got ${args.size}"
                    )
                }
            }

            private enum class Kind {
                NEW,
                CANCEL_READ,
                CANCEL_WRITE,
                DROP_READABLE,
                DROP_WRITABLE,
                READ,
                WRITE,
            }

            companion object {
                fun parse(symbolName: String): FutureIntrinsic? =
                    parse(symbolName, "[future-new-", Kind.NEW)
                        ?: parse(symbolName, "[future-cancel-read-", Kind.CANCEL_READ)
                        ?: parse(symbolName, "[future-cancel-write-", Kind.CANCEL_WRITE)
                        ?: parse(symbolName, "[future-drop-readable-", Kind.DROP_READABLE)
                        ?: parse(symbolName, "[future-drop-writable-", Kind.DROP_WRITABLE)
                        ?: parse(symbolName, "[async-lower][future-read-", Kind.READ)
                        ?: parse(symbolName, "[async-lower][future-write-", Kind.WRITE)

                private fun parse(
                    symbolName: String,
                    prefix: String,
                    kind: Kind,
                ): FutureIntrinsic? {
                    if (!symbolName.startsWith(prefix)) {
                        return null
                    }
                    val end = symbolName.indexOf(']', prefix.length)
                    if (end < 0) {
                        return null
                    }
                    val index =
                        symbolName.substring(prefix.length, end).toIntOrNull() ?: return null
                    return FutureIntrinsic(kind, index, symbolName.substring(end + 1))
                }
            }
        }

        private class StreamIntrinsic
        private constructor(private val kind: Kind, val index: Int, val targetSymbolName: String) {
            fun hostFunction(
                moduleName: String,
                symbolName: String,
                intrinsics: CanonicalStreamIntrinsics,
                payloadType: WitPackage.TypeRef,
                abi: CanonicalAbi,
            ): HostFunction =
                HostFunction(moduleName, symbolName, functionType()) { instance, args ->
                    apply(instance, intrinsics, payloadType, abi, args)
                }

            private fun functionType(): FunctionType =
                when (kind) {
                    Kind.NEW -> FunctionType.of(emptyList(), listOf(ValType.I64))
                    Kind.CANCEL_READ,
                    Kind.CANCEL_WRITE -> FunctionType.of(listOf(ValType.I32), listOf(ValType.I32))
                    Kind.DROP_READABLE,
                    Kind.DROP_WRITABLE -> FunctionType.of(listOf(ValType.I32), emptyList())
                    Kind.READ,
                    Kind.WRITE ->
                        FunctionType.of(
                            listOf(ValType.I32, ValType.I32, ValType.I32),
                            listOf(ValType.I32),
                        )
                }

            private fun apply(
                instance: Instance,
                intrinsics: CanonicalStreamIntrinsics,
                payloadType: WitPackage.TypeRef,
                abi: CanonicalAbi,
                args: LongArray,
            ): LongArray {
                val result =
                    when (kind) {
                        Kind.NEW -> {
                            requireArity(args, 0)
                            intrinsics.streamNew(payloadType)
                        }
                        Kind.CANCEL_READ -> {
                            requireArity(args, 1)
                            intrinsics.streamCancelRead(toU32(args[0]))
                        }
                        Kind.CANCEL_WRITE -> {
                            requireArity(args, 1)
                            intrinsics.streamCancelWrite(toU32(args[0]))
                        }
                        Kind.DROP_READABLE -> {
                            requireArity(args, 1)
                            intrinsics.streamDropReadable(toU32(args[0]))
                            return LongArray(0)
                        }
                        Kind.DROP_WRITABLE -> {
                            requireArity(args, 1)
                            intrinsics.streamDropWritable(toU32(args[0]))
                            return LongArray(0)
                        }
                        Kind.READ -> {
                            requireArity(args, 3)
                            intrinsics.streamRead(
                                instance,
                                toU32(args[0]),
                                toU32Int(args[1], "pointer"),
                                toU32Int(args[2], "length"),
                                abi,
                                payloadType,
                            )
                        }
                        Kind.WRITE -> {
                            requireArity(args, 3)
                            intrinsics.streamWrite(
                                instance,
                                toU32(args[0]),
                                toU32Int(args[1], "pointer"),
                                toU32Int(args[2], "length"),
                                abi,
                                payloadType,
                            )
                        }
                    }
                return longArrayOf(result)
            }

            private fun requireArity(args: LongArray, expected: Int) {
                if (args.size != expected) {
                    throw ComponentModelException(
                        "canonical stream intrinsic expected $expected arguments, got ${args.size}"
                    )
                }
            }

            private enum class Kind {
                NEW,
                CANCEL_READ,
                CANCEL_WRITE,
                DROP_READABLE,
                DROP_WRITABLE,
                READ,
                WRITE,
            }

            companion object {
                fun parse(symbolName: String): StreamIntrinsic? =
                    parse(symbolName, "[stream-new-", Kind.NEW)
                        ?: parse(symbolName, "[stream-cancel-read-", Kind.CANCEL_READ)
                        ?: parse(symbolName, "[stream-cancel-write-", Kind.CANCEL_WRITE)
                        ?: parse(symbolName, "[stream-drop-readable-", Kind.DROP_READABLE)
                        ?: parse(symbolName, "[stream-drop-writable-", Kind.DROP_WRITABLE)
                        ?: parse(symbolName, "[async-lower][stream-read-", Kind.READ)
                        ?: parse(symbolName, "[async-lower][stream-write-", Kind.WRITE)

                private fun parse(
                    symbolName: String,
                    prefix: String,
                    kind: Kind,
                ): StreamIntrinsic? {
                    if (!symbolName.startsWith(prefix)) {
                        return null
                    }
                    val end = symbolName.indexOf(']', prefix.length)
                    if (end < 0) {
                        return null
                    }
                    val index =
                        symbolName.substring(prefix.length, end).toIntOrNull() ?: return null
                    return StreamIntrinsic(kind, index, symbolName.substring(end + 1))
                }
            }
        }

        companion object {
            private const val ASYNC_LIFT_EXPORT_PREFIX = "[async-lift]"
            private const val PREVIEW1_MODULE = "wasi_snapshot_preview1"
            private val GUEST_INIT_EXPORTS = arrayOf("krwa_guest_init")

            private fun toU32(value: Long): Long = value and 0xffff_ffffL

            private fun toU32Int(value: Long, name: String): Int {
                val unsigned = toU32(value)
                if (unsigned > Int.MAX_VALUE) {
                    throw ComponentModelException(
                        "canonical stream intrinsic $name exceeds supported JVM memory index: $unsigned"
                    )
                }
                return unsigned.toInt()
            }

            private fun <K, V> putIfAbsent(map: MutableMap<K, V>, key: K, value: V) {
                if (!map.containsKey(key)) {
                    map[key] = value
                }
            }
        }
    }

    companion object {
        @ComponentModelJvmStatic fun builder(witPackage: WitPackage): Builder = Builder(witPackage)

        @ComponentModelJvmStatic
        fun builderFromComponent(componentBytes: ByteArray): Builder =
            builder(wasmPluginParseWit(componentBytes)).withComponent(componentBytes)

        @ComponentModelJvmStatic
        fun builderFromComponent(componentPath: Path): Builder =
            builder(wasmPluginParseWit(componentPath)).withComponent(componentPath)
    }
}
