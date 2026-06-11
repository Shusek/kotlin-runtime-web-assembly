package uk.shusek.krwa.component

import kotlinx.coroutines.delay
import uk.shusek.krwa.runtime.ExportFunction
import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.Memory
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

class CanonicalAbi private constructor(private val witPackage: WitPackage) {
    private val declarations: Map<String, WitPackage.TypeDeclaration> = indexTypes(witPackage)

    fun witPackage(): WitPackage = witPackage

    fun bind(
        instance: Instance,
        exportName: String,
        function: WitPackage.Function,
        asyncTaskReturn: AsyncTaskReturn? = null,
        asyncCallbackExportName: String? = null,
    ): BoundFunction =
        bindInternal(instance, exportName, function, asyncTaskReturn, asyncCallbackExportName, null)

    internal fun bindAsyncCallback(
        instance: Instance,
        exportName: String,
        function: WitPackage.Function,
        asyncTaskReturn: AsyncTaskReturn?,
        asyncCallbackExportName: String?,
        asyncTasks: CanonicalAsyncLowerTasks?,
    ): BoundFunction =
        bindInternal(instance, exportName, function, asyncTaskReturn, asyncCallbackExportName, asyncTasks)

    private fun bindInternal(
        instance: Instance,
        exportName: String,
        function: WitPackage.Function,
        asyncTaskReturn: AsyncTaskReturn?,
        asyncCallbackExportName: String?,
        asyncTasks: CanonicalAsyncLowerTasks?,
    ): BoundFunction =
        BoundFunction(
            this,
            instance,
            exportName,
            function,
            asyncTaskReturn,
            asyncCallbackExportName,
            asyncTasks,
        )

    fun call(
        instance: Instance,
        exportName: String,
        function: WitPackage.Function,
        vararg args: Any?,
    ): Any? = bind(instance, exportName, function).call(*args)

    fun hostFunction(
        moduleName: String,
        symbolName: String,
        function: WitPackage.Function,
        handler: HostHandler,
    ): HostFunction = hostFunction(moduleName, symbolName, function, handler, null)

    fun hostFunction(
        moduleName: String,
        symbolName: String,
        function: WitPackage.Function,
        handler: HostHandler,
        asyncFutures: CanonicalFutureIntrinsics?,
    ): HostFunction =
        HostFunction(
            moduleName,
            symbolName,
            coreFunctionType(function, Direction.LOWERED_IMPORT),
        ) { instance, args ->
            callHostFunction(instance, function, handler, asyncFutures, args)
        }

    fun asyncLoweredHostFunction(
        moduleName: String,
        symbolName: String,
        coreFunctionType: FunctionType,
        function: WitPackage.Function,
        handler: HostHandler,
    ): HostFunction =
        asyncLoweredHostFunction(moduleName, symbolName, coreFunctionType, function, handler, null, null, null)

    fun asyncLoweredHostFunction(
        moduleName: String,
        symbolName: String,
        coreFunctionType: FunctionType,
        function: WitPackage.Function,
        handler: HostHandler,
        asyncFutures: CanonicalFutureIntrinsics?,
    ): HostFunction =
        asyncLoweredHostFunction(moduleName, symbolName, coreFunctionType, function, handler, asyncFutures, null, null)

    internal fun asyncLoweredHostFunction(
        moduleName: String,
        symbolName: String,
        coreFunctionType: FunctionType,
        function: WitPackage.Function,
        handler: HostHandler,
        asyncFutures: CanonicalFutureIntrinsics?,
        asyncTasks: CanonicalAsyncLowerTasks?,
        asyncResultPayloadType: WitPackage.TypeRef?,
    ): HostFunction =
        HostFunction(moduleName, symbolName, coreFunctionType) { instance, args ->
            callAsyncLoweredHostFunction(
                instance,
                symbolName,
                function,
                handler,
                asyncFutures,
                asyncTasks,
                asyncResultPayloadType,
                args,
            )
        }

    fun coreFunctionType(function: WitPackage.Function, direction: Direction): FunctionType {
        val params = flattenFields(function.parameters())
        val results =
            if (function.isAsync && direction == Direction.LOWERED_IMPORT) {
                listOf(CoreValType.I32)
            } else {
                flattenFields(function.results())
            }

        val coreParams = ArrayList<CoreValType>()
        if (params.size > MAX_FLAT_PARAMS) {
            coreParams.add(CoreValType.I32)
        } else {
            coreParams.addAll(params)
        }

        val coreResults = ArrayList<CoreValType>()
        if (results.size > MAX_FLAT_RESULTS) {
            if (direction == Direction.LOWERED_IMPORT) {
                coreParams.add(CoreValType.I32)
            } else {
                coreResults.add(CoreValType.I32)
            }
        } else {
            coreResults.addAll(results)
        }

        return FunctionType.of(toValTypes(coreParams), toValTypes(coreResults))
    }

    fun asyncLiftedExportFunctionType(function: WitPackage.Function): FunctionType {
        val params = flattenFields(function.parameters())
        val coreParams = ArrayList<CoreValType>()
        if (params.size > MAX_FLAT_PARAMS) {
            coreParams.add(CoreValType.I32)
        } else {
            coreParams.addAll(params)
        }
        return FunctionType.of(toValTypes(coreParams), listOf(ValType.I32))
    }

    fun asyncTaskReturnFunctionType(function: WitPackage.Function): FunctionType {
        val results = flattenFields(function.results())
        val coreParams = ArrayList<CoreValType>()
        if (results.size > MAX_FLAT_PARAMS) {
            coreParams.add(CoreValType.I32)
        } else {
            coreParams.addAll(results)
        }
        return FunctionType.of(toValTypes(coreParams), emptyList())
    }

    fun flattenType(type: WitPackage.TypeRef): List<CoreValType> {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE) {
            return flattenPrimitive(resolved.name()!!)
        }
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            return when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.RECORD -> flattenFields(declaration.fields())
                WitPackage.TypeDeclaration.Kind.FLAGS -> flattenFlags(declaration.cases().size)
                WitPackage.TypeDeclaration.Kind.ENUM,
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    flattenVariant(casesFromDeclaration(declaration))
                WitPackage.TypeDeclaration.Kind.RESOURCE -> listOf(CoreValType.I32)
                WitPackage.TypeDeclaration.Kind.ALIAS -> flattenType(declaration.target()!!)
            }
        }

        return when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.LIST -> listOf(CoreValType.I32, CoreValType.I32)
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT -> flattenVariant(casesFromConstructed(resolved))
            WitPackage.TypeRef.TypeKind.TUPLE -> flattenTypes(resolved.arguments())
            WitPackage.TypeRef.TypeKind.FUTURE,
            WitPackage.TypeRef.TypeKind.STREAM,
            WitPackage.TypeRef.TypeKind.BORROW,
            WitPackage.TypeRef.TypeKind.OWN -> listOf(CoreValType.I32)
            else -> throw unsupported(resolved)
        }
    }

    fun alignment(type: WitPackage.TypeRef): Int {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE) {
            return primitiveAlignment(resolved.name()!!)
        }
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            return when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.RECORD -> alignmentOfFields(declaration.fields())
                WitPackage.TypeDeclaration.Kind.FLAGS -> flagsAlignment(declaration.cases().size)
                WitPackage.TypeDeclaration.Kind.ENUM,
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    alignmentOfVariant(casesFromDeclaration(declaration))
                WitPackage.TypeDeclaration.Kind.RESOURCE -> 4
                WitPackage.TypeDeclaration.Kind.ALIAS -> alignment(declaration.target()!!)
            }
        }

        return when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.LIST -> 4
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT -> alignmentOfVariant(casesFromConstructed(resolved))
            WitPackage.TypeRef.TypeKind.TUPLE -> alignmentOfTypes(resolved.arguments())
            WitPackage.TypeRef.TypeKind.FUTURE,
            WitPackage.TypeRef.TypeKind.STREAM,
            WitPackage.TypeRef.TypeKind.BORROW,
            WitPackage.TypeRef.TypeKind.OWN -> 4
            else -> throw unsupported(resolved)
        }
    }

    fun elementSize(type: WitPackage.TypeRef): Int {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE) {
            return primitiveSize(resolved.name()!!)
        }
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            return when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.RECORD -> sizeOfFields(declaration.fields())
                WitPackage.TypeDeclaration.Kind.FLAGS -> flagsSize(declaration.cases().size)
                WitPackage.TypeDeclaration.Kind.ENUM,
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    sizeOfVariant(casesFromDeclaration(declaration))
                WitPackage.TypeDeclaration.Kind.RESOURCE -> 4
                WitPackage.TypeDeclaration.Kind.ALIAS -> elementSize(declaration.target()!!)
            }
        }

        return when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.LIST -> 8
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT -> sizeOfVariant(casesFromConstructed(resolved))
            WitPackage.TypeRef.TypeKind.TUPLE -> sizeOfTypes(resolved.arguments())
            WitPackage.TypeRef.TypeKind.FUTURE,
            WitPackage.TypeRef.TypeKind.STREAM,
            WitPackage.TypeRef.TypeKind.BORROW,
            WitPackage.TypeRef.TypeKind.OWN -> 4
            else -> throw unsupported(resolved)
        }
    }

    fun lowerFlatValues(
        context: Context,
        fields: List<WitPackage.Field>,
        values: List<*>,
        maxFlat: Int,
    ): LongArray {
        if (fields.size != values.size) {
            throw ComponentModelException("expected ${fields.size} values, got ${values.size}")
        }
        val flatTypes = flattenFields(fields)
        if (flatTypes.size > maxFlat) {
            val alignment = alignmentOfFields(fields)
            val size = sizeOfFields(fields)
            val ptr = context.reallocate(0, 0, alignment, size)
            storeFields(context, ptr, fields, values)
            return longArrayOf(ptr.toLong())
        }
        val out = ArrayList<Long>()
        for (i in fields.indices) {
            for (value in lowerFlat(context, values[i], fields[i].type())) {
                out.add(value)
            }
        }
        return toLongArray(out)
    }

    fun liftFlatValues(
        context: Context,
        fields: List<WitPackage.Field>,
        values: LongArray,
        maxFlat: Int,
    ): List<Any?> {
        val flatTypes = flattenFields(fields)
        if (flatTypes.size > maxFlat) {
            if (values.size != 1) {
                throw ComponentModelException("expected one result pointer")
            }
            return loadFields(context, values[0].toInt(), fields)
        }
        val iter = FlatIter(flatTypes, values)
        val result = ArrayList<Any?>()
        for (field in fields) {
            result.add(liftFlat(context, iter, field.type()))
        }
        iter.requireDone()
        return result
    }

    fun storeValues(context: Context, ptr: Int, fields: List<WitPackage.Field>, values: List<*>) {
        if (fields.size != values.size) {
            throw ComponentModelException("expected ${fields.size} values, got ${values.size}")
        }
        storeFields(context, ptr, fields, values)
    }

    fun loadValues(context: Context, ptr: Int, fields: List<WitPackage.Field>): List<Any?> {
        return loadFields(context, ptr, fields)
    }

    fun storeListElements(
        context: Context,
        ptr: Int,
        elementType: WitPackage.TypeRef,
        values: List<*>,
    ) {
        val stride = listElementStride(elementType)
        for (i in values.indices) {
            store(context, values[i], elementType, ptr + (i * stride))
        }
    }

    fun loadListElements(
        context: Context,
        ptr: Int,
        length: Int,
        elementType: WitPackage.TypeRef,
    ): List<Any?> {
        val stride = listElementStride(elementType)
        val result = ArrayList<Any?>()
        for (i in 0 until length) {
            result.add(load(context, ptr + (i * stride), elementType))
        }
        return result
    }

    fun listElementStride(elementType: WitPackage.TypeRef): Int = listStride(elementType)

    private fun callHostFunction(
        instance: Instance,
        function: WitPackage.Function,
        handler: HostHandler,
        asyncFutures: CanonicalFutureIntrinsics?,
        args: LongArray,
    ): LongArray {
        val context = Context.forInstance(instance)
        val flatParamCount = flattenFields(function.parameters()).size
        val paramValues: LongArray
        val resultPointerIndex: Int
        if (flatParamCount > MAX_FLAT_PARAMS) {
            if (args.isEmpty()) {
                throw ComponentModelException("lowered import is missing parameter pointer")
            }
            paramValues = longArrayOf(args[0])
            resultPointerIndex = 1
        } else {
            if (args.size < flatParamCount) {
                throw ComponentModelException("lowered import received too few parameters")
            }
            paramValues = args.copyOfRange(0, flatParamCount)
            resultPointerIndex = flatParamCount
        }

        val hostCallContext = if (function.isAsync) HostCallContext.ASYNC_FUNCTION else HostCallContext.SYNC
        val flatResultCount = flattenFields(function.results()).size
        val resultPointer =
            if (flatResultCount > MAX_FLAT_RESULTS && args.size > resultPointerIndex) {
                args[resultPointerIndex].toInt()
            } else {
                null
            }
        val directResult =
            if (handler is DirectHostHandler) {
                handler.applyDirect(context, function, paramValues, resultPointer, hostCallContext)
            } else {
                null
            }
        if (directResult?.resultsStored == true) {
            return LongArray(0)
        }
        val result =
            if (directResult != null) {
                directResult.value
            } else {
                val params = liftFlatValues(context, function.parameters(), paramValues, MAX_FLAT_PARAMS)
                applyHostHandler(handler, params, hostCallContext)
            }
        if (function.isAsync) {
            val futureHandle =
                if (result is WitFuture<*>) {
                    result.handle()
                } else {
                    asyncFutures?.completedFutureHandle(result)
                        ?: throw ComponentModelException(
                            "lowered async import ${function.name()} requires canonical future intrinsics"
                        )
                }
            return longArrayOf(futureHandle and 0xffff_ffffL)
        }
        val resultValues = resultValues(function.results(), result, asyncFutures)
        if (flatResultCount > MAX_FLAT_RESULTS) {
            if (args.size <= resultPointerIndex) {
                throw ComponentModelException("lowered import is missing result pointer")
            }
            try {
                storeValues(context, args[resultPointerIndex].toInt(), function.results(), resultValues)
            } catch (error: ComponentModelException) {
                throw ComponentModelException(
                    "lowered import ${function.name()} failed to store result: ${error.message} " +
                        "resultPointerIndex=$resultPointerIndex flatResultCount=$flatResultCount args=${args.toList()}",
                    error,
                )
            }
            return LongArray(0)
        }
        return lowerFlatValues(context, function.results(), resultValues, MAX_FLAT_RESULTS)
    }

    private fun callAsyncLoweredHostFunction(
        instance: Instance,
        symbolName: String,
        function: WitPackage.Function,
        handler: HostHandler,
        asyncFutures: CanonicalFutureIntrinsics?,
        asyncTasks: CanonicalAsyncLowerTasks?,
        asyncResultPayloadType: WitPackage.TypeRef?,
        args: LongArray,
    ): LongArray {
        val context = Context.forInstance(instance)
        val hasResults = function.results().isNotEmpty()
        val paramArgCount = if (hasResults) args.size - 1 else args.size
        if (paramArgCount < 0) {
            throw ComponentModelException(
                "async-lower import $symbolName is missing result pointer"
            )
        }

        val flatParamCount = flattenFields(function.parameters()).size
        val params =
            when {
                paramArgCount == flatParamCount ->
                    liftFlatValues(
                        context,
                        function.parameters(),
                        args.copyOfRange(0, paramArgCount),
                        paramArgCount,
                    )
                paramArgCount == 1 ->
                    loadValues(
                        context,
                        memoryIndex(args[0], "async-lower parameter pointer"),
                        function.parameters(),
                    )
                else ->
                    throw ComponentModelException(
                        "async-lower import $symbolName expected $flatParamCount flat " +
                            "parameters or one parameter pointer, got $paramArgCount"
                    )
            }

        val result = applyHostHandler(handler, params, HostCallContext.ASYNC_LOWER)
        if (result is WitFuture<*> && !asyncLoweredResultCanContainFuture(function.results())) {
            val resultPtr =
                if (hasResults) {
                    memoryIndex(args[args.size - 1], "async-lower result pointer")
                } else {
                    0
                }
            val futureTasks =
                asyncTasks
                    ?: throw ComponentModelException(
                        "async-lower import $symbolName returned a pending future; " +
                            "async subtasks are not available"
                    )
            val futures =
                asyncFutures
                    ?: throw ComponentModelException(
                        "async-lower import $symbolName returned a pending future; " +
                            "canonical future intrinsics are not available"
                    )
            val payloadType =
                asyncResultPayloadType
                    ?: throw ComponentModelException(
                        "async-lower import $symbolName returned a pending future without a payload type"
                    )
            return longArrayOf(
                futureTasks.startFutureSubtask(
                    instance,
                    result.handle(),
                    resultPtr,
                    payloadType,
                    futures,
                    this,
                )
            )
        }
        if (hasResults) {
            val resultValues = resultValues(function.results(), result, asyncFutures)
            val resultPtr = memoryIndex(args[args.size - 1], "async-lower result pointer")
            try {
                storeValues(
                    context,
                    resultPtr,
                    function.results(),
                    resultValues,
                )
            } catch (error: ComponentModelException) {
                throw ComponentModelException(
                    "async-lower import $symbolName (${function.name()}) failed to store result: " +
                        "${error.message} resultPtr=$resultPtr args=${args.toList()}",
                    error,
                )
            }
        }
        return longArrayOf(CanonicalAsyncLowerTasks.ASYNC_LOWER_RETURNED)
    }

    private fun asyncLoweredResultCanContainFuture(results: List<WitPackage.Field>): Boolean {
        for (field in results) {
            if (typeCanContainFutureHandle(field.type())) {
                return true
            }
        }
        return false
    }

    private fun typeCanContainFutureHandle(type: WitPackage.TypeRef): Boolean {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            return when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.ALIAS ->
                    typeCanContainFutureHandle(declaration.target()!!)
                WitPackage.TypeDeclaration.Kind.RECORD ->
                    fieldsCanContainFutureHandle(declaration.fields())
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    casesFromDeclaration(declaration).any { case ->
                        case.type?.let(::typeCanContainFutureHandle) == true
                    }
                else -> false
            }
        }
        return when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.FUTURE -> true
            WitPackage.TypeRef.TypeKind.LIST,
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT,
            WitPackage.TypeRef.TypeKind.TUPLE ->
                resolved.arguments().any(::typeCanContainFutureHandle)
            else -> false
        }
    }

    private fun fieldsCanContainFutureHandle(fields: List<WitPackage.Field>): Boolean {
        for (field in fields) {
            if (typeCanContainFutureHandle(field.type())) {
                return true
            }
        }
        return false
    }

    private fun resultValues(
        results: List<WitPackage.Field>,
        result: Any?,
        asyncFutures: CanonicalFutureIntrinsics? = null,
    ): List<Any?> {
        val values: List<Any?>
        if (results.isEmpty()) {
            return ArrayList()
        }
        if (results.size == 1) {
            values = arrayListOf(result)
        } else if (result is List<*>) {
            values = ArrayList(result)
        } else {
            val positional = positionalValuesOrNull(result, results.size)
            values = positional ?: valuesForFields(result, results)
        }
        if (values.size != results.size) {
            return values
        }
        val normalized = ArrayList<Any?>(values.size)
        for (i in results.indices) {
            normalized.add(normalizeHostResultValue(results[i].type(), values[i], asyncFutures))
        }
        return normalized
    }

    private fun normalizeHostResultValue(
        type: WitPackage.TypeRef,
        value: Any?,
        asyncFutures: CanonicalFutureIntrinsics?,
    ): Any? {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            return when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.RECORD ->
                    normalizeHostRecordResultValue(declaration.fields(), value, asyncFutures)
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    normalizeHostVariantResultValue(
                        casesFromDeclaration(declaration),
                        value,
                        asyncFutures,
                    )
                else -> value
            }
        }
        return when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.FUTURE ->
                normalizeHostFutureResultValue(value, asyncFutures)
            WitPackage.TypeRef.TypeKind.LIST ->
                normalizeHostListResultValue(firstArgument(resolved), value, asyncFutures)
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT ->
                normalizeHostVariantResultValue(casesFromConstructed(resolved), value, asyncFutures)
            WitPackage.TypeRef.TypeKind.TUPLE ->
                normalizeHostTupleResultValue(resolved.arguments(), value, asyncFutures)
            else -> value
        }
    }

    private fun normalizeHostFutureResultValue(
        value: Any?,
        asyncFutures: CanonicalFutureIntrinsics?,
    ): Any? {
        if (value is Number || value is WitFuture<*>) {
            return value
        }
        return asyncFutures?.completedFutureHandle(value)
            ?: throw ComponentModelException(
                "lowered import returned a completed future payload without canonical future intrinsics"
            )
    }

    private fun normalizeHostListResultValue(
        elementType: WitPackage.TypeRef,
        value: Any?,
        asyncFutures: CanonicalFutureIntrinsics?,
    ): Any? {
        if (isByteList(elementType)) {
            return value
        }
        val elements = listElements(value)
        val normalized = ArrayList<Any?>(elements.size)
        for (element in elements) {
            normalized.add(normalizeHostResultValue(elementType, element, asyncFutures))
        }
        return normalized
    }

    private fun normalizeHostRecordResultValue(
        fields: List<WitPackage.Field>,
        value: Any?,
        asyncFutures: CanonicalFutureIntrinsics?,
    ): Map<String, Any?> {
        val normalized = LinkedHashMap<String, Any?>()
        for (field in fields) {
            normalized[field.name()] =
                normalizeHostResultValue(field.type(), fieldValue(value, field.name()), asyncFutures)
        }
        return normalized
    }

    private fun normalizeHostTupleResultValue(
        types: List<WitPackage.TypeRef>,
        value: Any?,
        asyncFutures: CanonicalFutureIntrinsics?,
    ): List<Any?> {
        val values = positionalValues(value, types.size)
        val normalized = ArrayList<Any?>(types.size)
        for (i in types.indices) {
            normalized.add(normalizeHostResultValue(types[i], values[i], asyncFutures))
        }
        return normalized
    }

    private fun normalizeHostVariantResultValue(
        cases: List<CaseLayout>,
        value: Any?,
        asyncFutures: CanonicalFutureIntrinsics?,
    ): Any? {
        val selected = selectCase(value, cases)
        val payloadType = selected.type ?: return value
        return WitValue.variant(
            cases[selected.index].label,
            normalizeHostResultValue(payloadType, selected.value, asyncFutures),
        )
    }

    private fun memoryIndex(value: Long, name: String): Int {
        val unsigned = value and 0xffff_ffffL
        if (unsigned > Int.MAX_VALUE) {
            throw ComponentModelException("$name exceeds supported JVM memory index: $unsigned")
        }
        return unsigned.toInt()
    }

    private fun lowerFlat(context: Context, value: Any?, type: WitPackage.TypeRef): LongArray {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE) {
            return lowerFlatPrimitive(context, value, resolved.name()!!)
        }
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            return when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.RECORD ->
                    lowerFlatRecord(context, value, declaration.fields())
                WitPackage.TypeDeclaration.Kind.FLAGS -> packFlags(value, declaration.cases())
                WitPackage.TypeDeclaration.Kind.ENUM,
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    lowerFlatVariant(context, value, casesFromDeclaration(declaration))
                WitPackage.TypeDeclaration.Kind.RESOURCE -> longArrayOf(asLong(value))
                WitPackage.TypeDeclaration.Kind.ALIAS ->
                    lowerFlat(context, value, declaration.target()!!)
            }
        }

        return when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.LIST ->
                lowerFlatList(context, value, firstArgument(resolved))
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT ->
                lowerFlatVariant(context, value, casesFromConstructed(resolved))
            WitPackage.TypeRef.TypeKind.TUPLE ->
                lowerFlatTuple(context, value, resolved.arguments())
            WitPackage.TypeRef.TypeKind.FUTURE,
            WitPackage.TypeRef.TypeKind.STREAM,
            WitPackage.TypeRef.TypeKind.BORROW,
            WitPackage.TypeRef.TypeKind.OWN -> longArrayOf(asLong(value))
            else -> throw unsupported(resolved)
        }
    }

    private fun liftFlat(context: Context, iter: FlatIter, type: WitPackage.TypeRef): Any? {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE) {
            return liftFlatPrimitive(context, iter, resolved.name()!!)
        }
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            return when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.RECORD ->
                    liftFlatRecord(context, iter, declaration.fields())
                WitPackage.TypeDeclaration.Kind.FLAGS ->
                    unpackFlags(liftFlatFlags(iter, declaration.cases().size), declaration.cases())
                WitPackage.TypeDeclaration.Kind.ENUM,
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    liftFlatVariant(context, iter, casesFromDeclaration(declaration))
                WitPackage.TypeDeclaration.Kind.RESOURCE -> iter.next(CoreValType.I32)
                WitPackage.TypeDeclaration.Kind.ALIAS ->
                    liftFlat(context, iter, declaration.target()!!)
            }
        }

        return when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.LIST -> liftFlatList(context, iter, firstArgument(resolved))
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT ->
                liftFlatVariant(context, iter, casesFromConstructed(resolved))
            WitPackage.TypeRef.TypeKind.TUPLE -> liftFlatTuple(context, iter, resolved.arguments())
            WitPackage.TypeRef.TypeKind.FUTURE -> WitFuture.of<Any?>(iter.next(CoreValType.I32))
            WitPackage.TypeRef.TypeKind.STREAM -> WitStream.of<Any?>(iter.next(CoreValType.I32))
            WitPackage.TypeRef.TypeKind.BORROW,
            WitPackage.TypeRef.TypeKind.OWN -> iter.next(CoreValType.I32)
            else -> throw unsupported(resolved)
        }
    }

    private fun lowerFlatPrimitive(context: Context, value: Any?, typeName: String): LongArray =
        when (typeName) {
            "unit" -> LongArray(0)
            "bool" -> longArrayOf(if (asBoolean(value)) 1 else 0)
            "s8",
            "s16",
            "s32" -> longArrayOf(asLong(value) and 0xFFFFFFFFL)
            "u8",
            "u16",
            "u32" -> longArrayOf(asLong(value))
            "char" -> longArrayOf(asCodePoint(value))
            "s64",
            "u64" -> longArrayOf(asLong(value))
            "f32" ->
                longArrayOf(
                    asFloat(value).toRawBits().toLong() and 0xFFFFFFFFL
                )
            "f64" -> longArrayOf(asDouble(value).toRawBits())
            "string" -> lowerFlatString(context, value?.toString() ?: "")
            else -> throw ComponentModelException("unsupported primitive WIT type $typeName")
        }

    private fun liftFlatPrimitive(context: Context, iter: FlatIter, typeName: String): Any? =
        when (typeName) {
            "unit" -> null
            "bool" -> iter.next(CoreValType.I32) != 0L
            "s8" -> iter.next(CoreValType.I32).toByte()
            "s16" -> iter.next(CoreValType.I32).toShort()
            "s32" -> iter.next(CoreValType.I32).toInt()
            "u8" -> (iter.next(CoreValType.I32) and 0xFFL).toInt()
            "u16" -> (iter.next(CoreValType.I32) and 0xFFFFL).toInt()
            "u32" -> iter.next(CoreValType.I32) and 0xFFFFFFFFL
            "char" -> checkedCodePoint(iter.next(CoreValType.I32)).toInt()
            "s64",
            "u64" -> iter.next(CoreValType.I64)
            "f32" -> Float.fromBits(iter.next(CoreValType.F32).toInt())
            "f64" -> Double.fromBits(iter.next(CoreValType.F64))
            "string" -> liftFlatString(context, iter)
            else -> throw ComponentModelException("unsupported primitive WIT type $typeName")
        }

    private fun lowerFlatString(context: Context, value: String): LongArray {
        val bytes = value.encodeToByteArray()
        val ptr = allocateBytes(context, bytes, 1)
        return longArrayOf(ptr.toLong(), bytes.size.toLong())
    }

    private fun liftFlatString(context: Context, iter: FlatIter): String {
        val ptr = iter.next(CoreValType.I32).toInt()
        val len = iter.next(CoreValType.I32).toInt()
        return context.memory().readUtf8String(ptr, len)
    }

    private fun lowerFlatList(
        context: Context,
        value: Any?,
        elementType: WitPackage.TypeRef,
    ): LongArray {
        val stored = storeListIntoRange(context, value, elementType)
        return longArrayOf(stored.ptr.toLong(), stored.length.toLong())
    }

    private fun liftFlatList(
        context: Context,
        iter: FlatIter,
        elementType: WitPackage.TypeRef,
    ): Any {
        val ptr = iter.next(CoreValType.I32).toInt()
        val len = iter.next(CoreValType.I32).toInt()
        return loadListFromRange(context, ptr, len, elementType)
    }

    private fun lowerFlatRecord(
        context: Context,
        value: Any?,
        fields: List<WitPackage.Field>,
    ): LongArray {
        val flat = ArrayList<Long>()
        for (field in fields) {
            for (lowered in lowerFlat(context, fieldValue(value, field.name()), field.type())) {
                flat.add(lowered)
            }
        }
        return toLongArray(flat)
    }

    private fun liftFlatRecord(
        context: Context,
        iter: FlatIter,
        fields: List<WitPackage.Field>,
    ): Any {
        val result = LinkedHashMap<String, Any?>()
        for (field in fields) {
            result[field.name()] = liftFlat(context, iter, field.type())
        }
        return result
    }

    private fun lowerFlatTuple(
        context: Context,
        value: Any?,
        types: List<WitPackage.TypeRef>,
    ): LongArray {
        val flat = ArrayList<Long>()
        val values = positionalValues(value, types.size)
        for (i in types.indices) {
            for (lowered in lowerFlat(context, values[i], types[i])) {
                flat.add(lowered)
            }
        }
        return toLongArray(flat)
    }

    private fun liftFlatTuple(
        context: Context,
        iter: FlatIter,
        types: List<WitPackage.TypeRef>,
    ): Any {
        val result = ArrayList<Any?>()
        for (type in types) {
            result.add(liftFlat(context, iter, type))
        }
        return result
    }

    private fun lowerFlatVariant(
        context: Context,
        value: Any?,
        cases: List<CaseLayout>,
    ): LongArray {
        val selected = selectCase(value, cases)
        val payload = ArrayList<Long>()
        if (selected.type != null) {
            for (lowered in lowerFlat(context, selected.value, selected.type)) {
                payload.add(lowered)
            }
        }

        val flatTypes = ArrayList(flattenVariant(cases))
        flatTypes.removeAt(0)
        val coerced = ArrayList<Long>()
        for (i in flatTypes.indices) {
            if (i < payload.size) {
                coerced.add(payload[i])
            } else {
                coerced.add(0L)
            }
        }

        val result = LongArray(1 + coerced.size)
        result[0] = selected.index.toLong()
        for (i in coerced.indices) {
            result[i + 1] = coerced[i]
        }
        return result
    }

    private fun liftFlatVariant(
        context: Context,
        iter: FlatIter,
        cases: List<CaseLayout>,
    ): WitValue.Variant {
        val flatTypes = ArrayList(flattenVariant(cases))
        flatTypes.removeAt(0)
        val index = iter.next(CoreValType.I32).toInt()
        if (index < 0 || index >= cases.size) {
            throw ComponentModelException("variant case index out of range: $index")
        }
        val selected = cases[index]
        val payloadValues = LongArray(flatTypes.size)
        for (i in flatTypes.indices) {
            payloadValues[i] = iter.next(flatTypes[i])
        }
        if (selected.type == null) {
            return WitValue.variant(selected.label)
        }
        val payloadIter = FlatIter(flattenType(selected.type), payloadValues)
        return WitValue.variant(selected.label, liftFlat(context, payloadIter, selected.type))
    }

    private fun store(context: Context, value: Any?, type: WitPackage.TypeRef, ptr: Int) {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE) {
            checkRange(context, ptr, elementSize(resolved), alignment(resolved))
            storePrimitive(context, value, resolved.name()!!, ptr)
            return
        }
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.RECORD ->
                    storeFields(
                        context,
                        ptr,
                        declaration.fields(),
                        valuesForFields(value, declaration.fields()),
                    )
                WitPackage.TypeDeclaration.Kind.FLAGS ->
                    {
                        checkRange(context, ptr, flagsSize(declaration.cases().size), flagsAlignment(declaration.cases().size))
                        storeFlags(
                            context,
                            ptr,
                            packFlags(value, declaration.cases()),
                            declaration.cases().size,
                        )
                    }
                WitPackage.TypeDeclaration.Kind.ENUM,
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    storeVariant(context, ptr, value, casesFromDeclaration(declaration))
                WitPackage.TypeDeclaration.Kind.RESOURCE ->
                    {
                        checkRange(context, ptr, 4, 4)
                        context.memory().writeI32(ptr, asLong(value).toInt())
                    }
                WitPackage.TypeDeclaration.Kind.ALIAS ->
                    store(context, value, declaration.target()!!, ptr)
            }
            return
        }

        when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.LIST -> {
                checkRange(context, ptr, 8, 4)
                storeList(context, ptr, value, firstArgument(resolved))
            }
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT ->
                storeVariant(context, ptr, value, casesFromConstructed(resolved))
            WitPackage.TypeRef.TypeKind.TUPLE ->
                storeTuple(context, ptr, value, resolved.arguments())
            WitPackage.TypeRef.TypeKind.FUTURE,
            WitPackage.TypeRef.TypeKind.STREAM,
            WitPackage.TypeRef.TypeKind.BORROW,
            WitPackage.TypeRef.TypeKind.OWN -> {
                checkRange(context, ptr, 4, 4)
                context.memory().writeI32(ptr, asLong(value).toInt())
            }
            else -> throw unsupported(resolved)
        }
    }

    private fun load(context: Context, ptr: Int, type: WitPackage.TypeRef): Any? {
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE) {
            checkRange(context, ptr, elementSize(resolved), alignment(resolved))
            return loadPrimitive(context, resolved.name()!!, ptr)
        }
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = requireDeclaration(resolved.name()!!)
            return when (declaration.kind()) {
                WitPackage.TypeDeclaration.Kind.RECORD ->
                    loadFieldsAsRecord(context, ptr, declaration.fields())
                WitPackage.TypeDeclaration.Kind.FLAGS ->
                    {
                        checkRange(context, ptr, flagsSize(declaration.cases().size), flagsAlignment(declaration.cases().size))
                        unpackFlags(
                            loadFlags(context, ptr, declaration.cases().size),
                            declaration.cases(),
                        )
                    }
                WitPackage.TypeDeclaration.Kind.ENUM,
                WitPackage.TypeDeclaration.Kind.VARIANT ->
                    loadVariant(context, ptr, casesFromDeclaration(declaration))
                WitPackage.TypeDeclaration.Kind.RESOURCE ->
                    {
                        checkRange(context, ptr, 4, 4)
                        unsignedIntToLong(context.memory().readInt(ptr))
                    }
                WitPackage.TypeDeclaration.Kind.ALIAS -> load(context, ptr, declaration.target()!!)
            }
        }

        return when (resolved.kind()) {
            WitPackage.TypeRef.TypeKind.LIST -> {
                checkRange(context, ptr, 8, 4)
                loadList(context, ptr, firstArgument(resolved))
            }
            WitPackage.TypeRef.TypeKind.OPTION,
            WitPackage.TypeRef.TypeKind.RESULT ->
                loadVariant(context, ptr, casesFromConstructed(resolved))
            WitPackage.TypeRef.TypeKind.TUPLE -> loadTuple(context, ptr, resolved.arguments())
            WitPackage.TypeRef.TypeKind.FUTURE -> {
                checkRange(context, ptr, 4, 4)
                WitFuture.of<Any?>(unsignedIntToLong(context.memory().readInt(ptr)))
            }
            WitPackage.TypeRef.TypeKind.STREAM -> {
                checkRange(context, ptr, 4, 4)
                WitStream.of<Any?>(unsignedIntToLong(context.memory().readInt(ptr)))
            }
            WitPackage.TypeRef.TypeKind.BORROW,
            WitPackage.TypeRef.TypeKind.OWN -> {
                checkRange(context, ptr, 4, 4)
                unsignedIntToLong(context.memory().readInt(ptr))
            }
            else -> throw unsupported(resolved)
        }
    }

    private fun storePrimitive(context: Context, value: Any?, typeName: String, ptr: Int) {
        when (typeName) {
            "unit" -> return
            "bool" -> context.memory().writeByte(ptr, (if (asBoolean(value)) 1 else 0).toByte())
            "s8",
            "u8" -> context.memory().writeByte(ptr, asLong(value).toByte())
            "s16",
            "u16" -> context.memory().writeShort(ptr, asLong(value).toShort())
            "s32",
            "u32" -> context.memory().writeI32(ptr, asLong(value).toInt())
            "char" -> context.memory().writeI32(ptr, asCodePoint(value).toInt())
            "s64",
            "u64" -> context.memory().writeLong(ptr, asLong(value))
            "f32" -> context.memory().writeF32(ptr, asFloat(value))
            "f64" -> context.memory().writeF64(ptr, asDouble(value))
            "string" -> {
                val lowered = lowerFlatString(context, value?.toString() ?: "")
                context.memory().writeI32(ptr, lowered[0].toInt())
                context.memory().writeI32(ptr + 4, lowered[1].toInt())
            }
            else -> throw ComponentModelException("unsupported primitive WIT type $typeName")
        }
    }

    private fun loadPrimitive(context: Context, typeName: String, ptr: Int): Any? =
        when (typeName) {
            "unit" -> null
            "bool" -> context.memory().readU8(ptr).toInt() != 0
            "s8" -> context.memory().read(ptr)
            "u8" -> context.memory().readU8(ptr).toInt()
            "s16" -> context.memory().readShort(ptr)
            "u16" -> context.memory().readU16(ptr).toInt()
            "s32" -> context.memory().readInt(ptr)
            "u32" -> context.memory().readU32(ptr)
            "char" -> checkedCodePoint(context.memory().readU32(ptr)).toInt()
            "s64",
            "u64" -> context.memory().readLong(ptr)
            "f32" -> context.memory().readFloat(ptr)
            "f64" -> context.memory().readDouble(ptr)
            "string" ->
                context
                    .memory()
                    .readUtf8String(
                        context.memory().readInt(ptr),
                        context.memory().readInt(ptr + 4),
                    )
            else -> throw ComponentModelException("unsupported primitive WIT type $typeName")
        }

    private fun storeFields(
        context: Context,
        ptr: Int,
        fields: List<WitPackage.Field>,
        values: List<*>,
    ) {
        var offset = ptr
        for (i in fields.indices) {
            val type = fields[i].type()
            offset = alignTo(offset, alignment(type))
            store(context, values[i], type, offset)
            offset += elementSize(type)
        }
    }

    private fun loadFields(context: Context, ptr: Int, fields: List<WitPackage.Field>): List<Any?> {
        val result = ArrayList<Any?>()
        var offset = ptr
        for (field in fields) {
            offset = alignTo(offset, alignment(field.type()))
            result.add(load(context, offset, field.type()))
            offset += elementSize(field.type())
        }
        return result
    }

    private fun loadFieldsAsRecord(
        context: Context,
        ptr: Int,
        fields: List<WitPackage.Field>,
    ): Map<String, Any?> {
        val values = loadFields(context, ptr, fields)
        val result = LinkedHashMap<String, Any?>()
        for (i in fields.indices) {
            result[fields[i].name()] = values[i]
        }
        return result
    }

    private fun valuesForFields(value: Any?, fields: List<WitPackage.Field>): List<Any?> {
        val result = ArrayList<Any?>()
        for (field in fields) {
            result.add(fieldValue(value, field.name()))
        }
        return result
    }

    private fun storeListIntoRange(
        context: Context,
        value: Any?,
        elementType: WitPackage.TypeRef,
    ): StoredList {
        if (isByteList(elementType) && (value == null || value is ByteArray)) {
            val bytes = value ?: ByteArray(0)
            val ptr = allocateBytes(context, bytes, 1)
            return StoredList(ptr, bytes.size)
        }
        val elements = listElements(value)
        val length = elements.size
        val elementAlignment = alignment(elementType)
        val stride = listStride(elementType)
        val ptr = context.reallocate(0, 0, elementAlignment, listAllocationSize(stride, length))
        for (i in 0 until length) {
            store(context, elements[i], elementType, ptr + (i * stride))
        }
        return StoredList(ptr, length)
    }

    private fun storeList(
        context: Context,
        ptr: Int,
        value: Any?,
        elementType: WitPackage.TypeRef,
    ) {
        val stored = storeListIntoRange(context, value, elementType)
        context.memory().writeI32(ptr, stored.ptr)
        context.memory().writeI32(ptr + 4, stored.length)
    }

    private fun loadList(context: Context, ptr: Int, elementType: WitPackage.TypeRef): Any =
        loadListFromRange(
            context,
            context.memory().readInt(ptr),
            context.memory().readInt(ptr + 4),
            elementType,
        )

    private fun loadListFromRange(
        context: Context,
        ptr: Int,
        length: Int,
        elementType: WitPackage.TypeRef,
    ): Any {
        if (isByteList(elementType)) {
            return context.memory().readBytes(ptr, length)
        }
        val stride = listStride(elementType)
        val result = ArrayList<Any?>()
        for (i in 0 until length) {
            result.add(load(context, ptr + (i * stride), elementType))
        }
        return result
    }

    private fun storeTuple(
        context: Context,
        ptr: Int,
        value: Any?,
        types: List<WitPackage.TypeRef>,
    ) {
        val values = positionalValues(value, types.size)
        var offset = ptr
        for (i in types.indices) {
            offset = alignTo(offset, alignment(types[i]))
            store(context, values[i], types[i], offset)
            offset += elementSize(types[i])
        }
    }

    private fun loadTuple(context: Context, ptr: Int, types: List<WitPackage.TypeRef>): Any {
        val result = ArrayList<Any?>()
        var offset = ptr
        for (type in types) {
            offset = alignTo(offset, alignment(type))
            result.add(load(context, offset, type))
            offset += elementSize(type)
        }
        return result
    }

    private fun storeVariant(context: Context, ptr: Int, value: Any?, cases: List<CaseLayout>) {
        val selected = selectCase(value, cases)
        storeDiscriminant(context, ptr, selected.index, cases.size)
        if (selected.type == null) {
            return
        }
        val payloadPtr = variantPayloadPtr(ptr, cases)
        store(context, selected.value, selected.type, payloadPtr)
    }

    private fun loadVariant(context: Context, ptr: Int, cases: List<CaseLayout>): WitValue.Variant {
        val index = loadDiscriminant(context, ptr, cases.size)
        if (index < 0 || index >= cases.size) {
            throw ComponentModelException("variant case index out of range: $index")
        }
        val selected = cases[index]
        if (selected.type == null) {
            return WitValue.variant(selected.label)
        }
        return WitValue.variant(
            selected.label,
            load(context, variantPayloadPtr(ptr, cases), selected.type),
        )
    }

    private fun selectCase(value: Any?, cases: List<CaseLayout>): SelectedCase {
        val label: String
        var payload: Any? = null
        var hasPayload = false

        if (
            value is WitValue.Variant &&
                isOptionCases(cases) &&
                value.label() != "none" &&
                value.label() != "some"
        ) {
            label = "some"
            payload = value
            hasPayload = true
        } else if (value is WitValue.Variant) {
            label = value.label()
            payload = value.value()
            hasPayload = value.hasValue()
        } else if (value is WitResult.Ok<*, *>) {
            label = "ok"
            payload = value.value()
            hasPayload = true
        } else if (value is WitResult.Err<*, *>) {
            label = "err"
            payload = value.value()
            hasPayload = true
        } else if (value is Map<*, *>) {
            if (value.size != 1) {
                throw ComponentModelException("variant map must contain exactly one case")
            }
            val entry = value.entries.iterator().next()
            label = entry.key.toString()
            payload = entry.value
            hasPayload = true
        } else if (isOptionCases(cases)) {
            if (value == null) {
                label = "none"
            } else {
                label = "some"
                payload = value
                hasPayload = true
            }
        } else {
            val reflected = reflectedCase(value, cases)
            if (reflected != null) {
                return reflected
            }
            val enumCase = enumCase(value, cases)
            if (enumCase != null) {
                return enumCase
            }
            label = value.toString()
        }

        for (i in cases.indices) {
            val c = cases[i]
            if (c.label == label) {
                return SelectedCase(i, c.type, if (hasPayload) payload else null)
            }
        }
        throw ComponentModelException(
            "unknown variant case $label; expected ${cases.joinToString { it.label }}"
        )
    }

    private fun reflectedCase(value: Any?, cases: List<CaseLayout>): SelectedCase? {
        if (value == null) {
            return null
        }
        val reflected =
            canonicalAbiReflectedCase(
                value,
                cases.map { it.label },
                cases.map { it.type != null },
            ) ?: return null
        val selected = cases[reflected.index]
        return SelectedCase(reflected.index, selected.type, reflected.payload)
    }

    private fun enumCase(value: Any?, cases: List<CaseLayout>): SelectedCase? {
        if (value !is Enum<*> || cases.any { it.type != null }) {
            return null
        }
        val name = value.name
        for (i in cases.indices) {
            val c = cases[i]
            if (
                name == c.label ||
                    name == WitNames.enumName(c.label) ||
                    name == WitNames.typeName(c.label)
            ) {
                return SelectedCase(i, null, null)
            }
        }
        return null
    }

    private fun isOptionCases(cases: List<CaseLayout>): Boolean =
        cases.size == 2 && cases[0].label == "none" && cases[1].label == "some"

    private fun packFlags(value: Any?, flags: List<WitPackage.Case>): LongArray {
        val enabled = LinkedHashSet<String>()
        if (value is Map<*, *>) {
            for (flag in flags) {
                if (
                    value[flag.name()] == true ||
                        value[memberName(flag.name())] == true
                ) {
                    enabled.add(flag.name())
                }
            }
        } else if (value is Iterable<*>) {
            for (label in value) {
                enabled.add(label.toString())
            }
        } else if (value != null) {
            val arrayElements = canonicalAbiArrayElements(value)
            if (arrayElements != null) {
                for (label in arrayElements) {
                    enabled.add(label.toString())
                }
            } else {
                for (flag in flags) {
                    if (fieldValue(value, flag.name()) == true) {
                        enabled.add(flag.name())
                    }
                }
            }
        }

        val result = LongArray(flagsWordCount(flags.size))
        for (i in flags.indices) {
            if (enabled.contains(flags[i].name())) {
                result[i / 32] = result[i / 32] or (1L shl (i % 32))
            }
        }
        return result
    }

    private fun unpackFlags(packed: LongArray, flags: List<WitPackage.Case>): Map<String, Boolean> {
        val result = LinkedHashMap<String, Boolean>()
        for (i in flags.indices) {
            result[flags[i].name()] = (packed[i / 32] and (1L shl (i % 32))) != 0L
        }
        return result
    }

    private fun storeFlags(context: Context, ptr: Int, packed: LongArray, count: Int) {
        when (flagsSize(count)) {
            1 -> context.memory().writeByte(ptr, packed[0].toByte())
            2 -> context.memory().writeShort(ptr, packed[0].toShort())
            4 -> context.memory().writeI32(ptr, packed[0].toInt())
            else -> {
                for (i in packed.indices) {
                    context.memory().writeI32(ptr + (i * 4), packed[i].toInt())
                }
            }
        }
    }

    private fun loadFlags(context: Context, ptr: Int, count: Int): LongArray =
        when (flagsSize(count)) {
            1 -> longArrayOf(context.memory().readU8(ptr))
            2 -> longArrayOf(context.memory().readU16(ptr))
            4 -> longArrayOf(unsignedIntToLong(context.memory().readInt(ptr)))
            else -> {
                val result = LongArray(flagsWordCount(count))
                for (i in result.indices) {
                    result[i] = unsignedIntToLong(context.memory().readInt(ptr + (i * 4)))
                }
                result
            }
        }

    private fun liftFlatFlags(iter: FlatIter, count: Int): LongArray {
        val result = LongArray(flagsWordCount(count))
        for (i in result.indices) {
            result[i] = unsignedIntToLong(iter.next(CoreValType.I32).toInt())
        }
        return result
    }

    private fun allocateBytes(context: Context, bytes: ByteArray, alignment: Int): Int {
        val ptr = context.reallocate(0, 0, alignment, bytes.size.coerceAtLeast(1))
        if (bytes.isNotEmpty()) {
            context.memory().write(ptr, bytes)
        }
        return ptr
    }

    private fun listAllocationSize(stride: Int, length: Int): Int =
        if (length == 0) stride.coerceAtLeast(1) else stride * length

    private fun flattenFields(fields: List<WitPackage.Field>): List<CoreValType> {
        val result = ArrayList<CoreValType>()
        for (field in fields) {
            result.addAll(flattenType(field.type()))
        }
        return result
    }

    private fun flattenTypes(types: List<WitPackage.TypeRef>): List<CoreValType> {
        val result = ArrayList<CoreValType>()
        for (type in types) {
            result.addAll(flattenType(type))
        }
        return result
    }

    private fun flattenPrimitive(name: String): List<CoreValType> =
        when (name) {
            "unit" -> listOf()
            "bool",
            "s8",
            "s16",
            "s32",
            "u8",
            "u16",
            "u32",
            "char" -> listOf(CoreValType.I32)
            "s64",
            "u64" -> listOf(CoreValType.I64)
            "f32" -> listOf(CoreValType.F32)
            "f64" -> listOf(CoreValType.F64)
            "string" -> listOf(CoreValType.I32, CoreValType.I32)
            else -> throw ComponentModelException("unsupported primitive WIT type $name")
        }

    private fun flattenFlags(count: Int): List<CoreValType> {
        val result = ArrayList<CoreValType>()
        for (i in 0 until flagsWordCount(count)) {
            result.add(CoreValType.I32)
        }
        return result
    }

    private fun flattenVariant(cases: List<CaseLayout>): MutableList<CoreValType> {
        var payload: List<CoreValType> = ArrayList()
        for (c in cases) {
            if (c.type != null) {
                payload = joinFlat(payload, flattenType(c.type))
            }
        }
        val result = ArrayList<CoreValType>()
        result.add(CoreValType.I32)
        result.addAll(payload)
        return result
    }

    private fun joinFlat(left: List<CoreValType>, right: List<CoreValType>): List<CoreValType> {
        val result = ArrayList<CoreValType>()
        val size = maxOf(left.size, right.size)
        for (i in 0 until size) {
            val l = if (i < left.size) left[i] else null
            val r = if (i < right.size) right[i] else null
            result.add(join(l, r))
        }
        return result
    }

    private fun join(left: CoreValType?, right: CoreValType?): CoreValType {
        if (left == null) {
            return right!!
        }
        if (right == null || left == right) {
            return left
        }
        if (left == CoreValType.I64 || right == CoreValType.I64) {
            return CoreValType.I64
        }
        if (left == CoreValType.F64 || right == CoreValType.F64) {
            return CoreValType.I64
        }
        return CoreValType.I32
    }

    private fun alignmentOfFields(fields: List<WitPackage.Field>): Int {
        var result = 1
        for (field in fields) {
            result = maxOf(result, alignment(field.type()))
        }
        return result
    }

    private fun alignmentOfTypes(types: List<WitPackage.TypeRef>): Int {
        var result = 1
        for (type in types) {
            result = maxOf(result, alignment(type))
        }
        return result
    }

    private fun sizeOfFields(fields: List<WitPackage.Field>): Int {
        var size = 0
        val alignment = alignmentOfFields(fields)
        for (field in fields) {
            size = alignTo(size, alignment(field.type()))
            size += elementSize(field.type())
        }
        return alignTo(size, alignment)
    }

    private fun sizeOfTypes(types: List<WitPackage.TypeRef>): Int {
        var size = 0
        val alignment = alignmentOfTypes(types)
        for (type in types) {
            size = alignTo(size, alignment(type))
            size += elementSize(type)
        }
        return alignTo(size, alignment)
    }

    private fun alignmentOfVariant(cases: List<CaseLayout>): Int {
        var alignment = discriminantSize(cases.size)
        for (c in cases) {
            if (c.type != null) {
                alignment = maxOf(alignment, alignment(c.type))
            }
        }
        return alignment
    }

    private fun sizeOfVariant(cases: List<CaseLayout>): Int {
        var payloadAlignment = 1
        var payloadSize = 0
        for (c in cases) {
            if (c.type != null) {
                payloadAlignment = maxOf(payloadAlignment, alignment(c.type))
                payloadSize = maxOf(payloadSize, elementSize(c.type))
            }
        }
        val payload = alignTo(discriminantSize(cases.size), payloadAlignment) + payloadSize
        return alignTo(payload, alignmentOfVariant(cases))
    }

    private fun variantPayloadPtr(ptr: Int, cases: List<CaseLayout>): Int {
        var payloadAlignment = 1
        for (c in cases) {
            if (c.type != null) {
                payloadAlignment = maxOf(payloadAlignment, alignment(c.type))
            }
        }
        return alignTo(ptr + discriminantSize(cases.size), payloadAlignment)
    }

    private fun storeDiscriminant(context: Context, ptr: Int, index: Int, caseCount: Int) {
        checkRange(context, ptr, discriminantSize(caseCount), discriminantSize(caseCount))
        when (discriminantSize(caseCount)) {
            1 -> context.memory().writeByte(ptr, index.toByte())
            2 -> context.memory().writeShort(ptr, index.toShort())
            else -> context.memory().writeI32(ptr, index)
        }
    }

    private fun loadDiscriminant(context: Context, ptr: Int, caseCount: Int): Int {
        val size = discriminantSize(caseCount)
        checkRange(context, ptr, size, size)
        return when (size) {
            1 -> context.memory().readU8(ptr).toInt()
            2 -> context.memory().readU16(ptr).toInt()
            else -> context.memory().readInt(ptr)
        }
    }

    private fun primitiveAlignment(name: String): Int =
        when (name) {
            "unit",
            "bool",
            "s8",
            "u8" -> 1
            "s16",
            "u16" -> 2
            "s32",
            "u32",
            "f32",
            "char",
            "string" -> 4
            "s64",
            "u64",
            "f64" -> 8
            else -> throw ComponentModelException("unsupported primitive WIT type $name")
        }

    private fun primitiveSize(name: String): Int =
        when (name) {
            "unit" -> 0
            "bool",
            "s8",
            "u8" -> 1
            "s16",
            "u16" -> 2
            "s32",
            "u32",
            "f32",
            "char" -> 4
            "s64",
            "u64",
            "f64" -> 8
            "string" -> 8
            else -> throw ComponentModelException("unsupported primitive WIT type $name")
        }

    private fun flagsAlignment(count: Int): Int = minOf(flagsSize(count), 4)

    private fun flagsSize(count: Int): Int {
        if (count <= 8) {
            return 1
        }
        if (count <= 16) {
            return 2
        }
        if (count <= 32) {
            return 4
        }
        return flagsWordCount(count) * 4
    }

    private fun flagsWordCount(count: Int): Int = maxOf(1, (count + 31) / 32)

    private fun discriminantSize(cases: Int): Int {
        if (cases <= 256) {
            return 1
        }
        if (cases <= 65536) {
            return 2
        }
        return 4
    }

    private fun listStride(elementType: WitPackage.TypeRef): Int =
        alignTo(elementSize(elementType), alignment(elementType))

    private fun isByteList(type: WitPackage.TypeRef): Boolean {
        val resolved = resolveAlias(type)
        return resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE &&
            (resolved.name() == "u8" || resolved.name() == "s8")
    }

    private fun firstArgument(type: WitPackage.TypeRef): WitPackage.TypeRef =
        if (type.arguments().isEmpty()) {
            WitPackage.TypeRef.primitive("unit")
        } else {
            type.arguments()[0]
        }

    private fun casesFromDeclaration(declaration: WitPackage.TypeDeclaration): List<CaseLayout> {
        val cases = ArrayList<CaseLayout>()
        for (c in declaration.cases()) {
            cases.add(CaseLayout(c.name(), unitToNull(c.type())))
        }
        return cases
    }

    private fun casesFromConstructed(type: WitPackage.TypeRef): List<CaseLayout> {
        val args = type.arguments()
        val cases = ArrayList<CaseLayout>()
        when (type.kind()) {
            WitPackage.TypeRef.TypeKind.OPTION -> {
                cases.add(CaseLayout("none", null))
                cases.add(CaseLayout("some", if (args.isEmpty()) null else unitToNull(args[0])))
                return cases
            }
            WitPackage.TypeRef.TypeKind.RESULT -> {
                cases.add(CaseLayout("ok", if (args.isNotEmpty()) unitToNull(args[0]) else null))
                cases.add(CaseLayout("err", if (args.size > 1) unitToNull(args[1]) else null))
                return cases
            }
            else -> throw unsupported(type)
        }
    }

    private fun unitToNull(type: WitPackage.TypeRef?): WitPackage.TypeRef? {
        if (type == null) {
            return null
        }
        val resolved = resolveAlias(type)
        if (resolved.kind() == WitPackage.TypeRef.TypeKind.PRIMITIVE && resolved.name() == "unit") {
            return null
        }
        return type
    }

    private fun resolveAlias(type: WitPackage.TypeRef): WitPackage.TypeRef {
        if (type.kind() == WitPackage.TypeRef.TypeKind.NAMED) {
            val declaration = declaration(type.name()!!)
            if (
                declaration != null && declaration.kind() == WitPackage.TypeDeclaration.Kind.ALIAS
            ) {
                return resolveAlias(declaration.target()!!)
            }
        }
        return type
    }

    private fun requireDeclaration(name: String): WitPackage.TypeDeclaration =
        declaration(name) ?: throw ComponentModelException("unknown WIT type $name")

    private fun declaration(name: String): WitPackage.TypeDeclaration? {
        val normalized = normalizeTypeName(name)
        val direct = declarations[normalized]
        if (direct != null) {
            return direct
        }
        return declarations[lastSegment(normalized)]
    }

    private fun indexTypes(witPackage: WitPackage): Map<String, WitPackage.TypeDeclaration> {
        val result = LinkedHashMap<String, WitPackage.TypeDeclaration>()
        for (declaration in witPackage.declarations()) {
            if (declaration is WitPackage.TypeDeclaration) {
                indexType(result, declaration)
            } else if (declaration is WitPackage.InterfaceDeclaration) {
                for (member in declaration.members()) {
                    if (member is WitPackage.TypeDeclaration) {
                        indexType(result, member, declaration.qualifiedName())
                    }
                }
            } else if (declaration is WitPackage.WorldDeclaration) {
                for (member in declaration.declarations()) {
                    if (member is WitPackage.TypeDeclaration) {
                        indexType(result, member)
                    }
                }
            }
        }
        for (declaration in witPackage.declarations()) {
            if (declaration is WitPackage.UseDeclaration) {
                indexUse(result, declaration)
            } else if (declaration is WitPackage.InterfaceDeclaration) {
                for (member in declaration.members()) {
                    if (member is WitPackage.UseDeclaration) {
                        indexUse(result, member)
                    }
                }
            } else if (declaration is WitPackage.WorldDeclaration) {
                for (member in declaration.declarations()) {
                    if (member is WitPackage.UseDeclaration) {
                        indexUse(result, member)
                    }
                }
            }
        }
        return result
    }

    private fun indexType(
        result: MutableMap<String, WitPackage.TypeDeclaration>,
        type: WitPackage.TypeDeclaration,
        ownerQualifiedName: String? = null,
    ) {
        if (ownerQualifiedName != null) {
            putIfAbsent(result, normalizeTypeName("$ownerQualifiedName/${type.name()}"), type)
        }
        putIfAbsent(result, normalizeTypeName(type.name()), type)
        putIfAbsent(result, lastSegment(normalizeTypeName(type.name())), type)
    }

    private fun indexUse(
        result: MutableMap<String, WitPackage.TypeDeclaration>,
        use: WitPackage.UseDeclaration,
    ) {
        for (item in use.items()) {
            val resource =
                WitPackage.TypeDeclaration(
                    WitPackage.TypeDeclaration.Kind.RESOURCE,
                    item.localName(),
                    listOf(),
                    listOf(),
                    null,
            )
            putIfAbsent(result, normalizeTypeName(item.localName()), resource)
            putIfAbsent(result, lastSegment(normalizeTypeName(item.localName())), resource)
            putIfAbsent(result, normalizeTypeName(use.path() + "/" + item.name()), resource)
        }
    }

    private fun <K, V> putIfAbsent(map: MutableMap<K, V>, key: K, value: V) {
        if (!map.containsKey(key)) {
            map[key] = value
        }
    }

    private fun toValTypes(types: List<CoreValType>): List<ValType> {
        val result = ArrayList<ValType>()
        for (type in types) {
            result.add(type.valType())
        }
        return result
    }

    private fun checkRange(context: Context, ptr: Int, size: Int, alignment: Int) {
        if (alignment > 1 && ptr != alignTo(ptr, alignment)) {
            throw ComponentModelException("misaligned canonical ABI pointer $ptr")
        }
        val end = ptr.toLong() + size.toLong()
        val max = context.memory().pages().toLong() * Memory.PAGE_SIZE.toLong()
        if (ptr < 0 || end > max) {
            throw ComponentModelException(
                "canonical ABI memory access out of bounds: ptr=$ptr size=$size alignment=$alignment " +
                    "end=$end max=$max pages=${context.memory().pages()}"
            )
        }
    }

    private fun alignTo(value: Int, alignment: Int): Int {
        if (alignment <= 1) {
            return value
        }
        val remainder = value % alignment
        return if (remainder == 0) value else value + alignment - remainder
    }

    private fun fieldValue(value: Any?, name: String): Any? {
        if (value is Map<*, *>) {
            if (value.containsKey(name)) {
                return value[name]
            }
            val memberName = memberName(name)
            if (value.containsKey(memberName)) {
                return value[memberName]
            }
        }
        if (value == null) {
            throw ComponentModelException("missing field $name")
        }
        val memberName = memberName(name)
        for (methodName in
            listOf(memberName, "get" + capitalize(memberName), "is" + capitalize(memberName))) {
            val reflected = canonicalAbiFieldValue(value, methodName)
            if (reflected != null) {
                return reflected.value
            }
        }
        for (fieldName in listOf(name, memberName)) {
            val reflected = canonicalAbiFieldValue(value, fieldName)
            if (reflected != null) {
                return reflected.value
            }
        }
        throw ComponentModelException("missing field $name on ${canonicalAbiTypeName(value)}")
    }

    private fun positionalValues(value: Any?, size: Int): List<Any?> {
        val positional = positionalValuesOrNull(value, size)
        if (positional != null) {
            return positional
        }
        if (size == 1) {
            return listOf(value)
        }
        throw ComponentModelException("tuple value must be a list, array, or componentN value")
    }

    private fun positionalValuesOrNull(value: Any?, size: Int): List<Any?>? {
        if (value is List<*>) {
            return ArrayList(value)
        }
        if (value != null) {
            canonicalAbiArrayElements(value)?.let { return it }
        }
        return componentValues(value, size)
    }

    private fun componentValues(value: Any?, size: Int): List<Any?>? {
        if (value == null) {
            return null
        }
        return canonicalAbiTupleComponents(value, size)
    }

    private fun listElements(value: Any?): List<Any?> {
        if (value == null) {
            return listOf()
        }
        if (value is ByteArray) {
            val result = ArrayList<Any?>(value.size)
            for (b in value) {
                result.add(b)
            }
            return result
        }
        if (value is Iterable<*>) {
            val result = ArrayList<Any?>()
            for (item in value) {
                result.add(item)
            }
            return result
        }
        val arrayElements = canonicalAbiArrayElements(value)
        if (arrayElements != null) {
            return arrayElements
        }
        throw ComponentModelException("list value must be iterable or array")
    }

    private fun asBoolean(value: Any?): Boolean {
        if (value is Boolean) {
            return value
        }
        return asLong(value) != 0L
    }

    private fun asCodePoint(value: Any?): Long {
        if (value is Number) {
            return checkedCodePoint(value.toLong())
        }
        val unsigned = kotlinUnsignedLong(value)
        if (unsigned != null) {
            return checkedCodePoint(unsigned)
        }
        if (value is Char) {
            return checkedCodePoint(value.code.toLong())
        }
        if (value is CharSequence) {
            val text = value.toString()
            val codePoint = singleCodePointOrNull(text)
            if (codePoint != null) {
                return checkedCodePoint(codePoint)
            }
        }
        throw ComponentModelException("expected one Unicode scalar value, got $value")
    }

    private fun checkedCodePoint(value: Long): Long {
        if (
            value < 0 ||
                value > MAX_UNICODE_CODE_POINT ||
                (value >= MIN_SURROGATE && value <= MAX_SURROGATE)
        ) {
            throw ComponentModelException(
                "invalid Unicode scalar value " + unsignedLongToString(value)
            )
        }
        return value
    }

    private fun asLong(value: Any?): Long {
        if (value is Number) {
            return value.toLong()
        }
        val unsigned = kotlinUnsignedLong(value)
        if (unsigned != null) {
            return unsigned
        }
        if (value is Char) {
            return value.code.toLong()
        }
        if (value is Boolean) {
            return if (value) 1 else 0
        }
        val handle = resourceHandle(value)
        if (handle != null) {
            return handle
        }
        throw ComponentModelException("expected numeric canonical ABI value, got $value")
    }

    private fun singleCodePointOrNull(value: String): Long? {
        if (value.isEmpty()) {
            return null
        }
        val first = value[0].code
        if (first in MIN_HIGH_SURROGATE..MAX_HIGH_SURROGATE) {
            if (value.length != 2) {
                return null
            }
            val second = value[1].code
            if (second !in MIN_LOW_SURROGATE..MAX_LOW_SURROGATE) {
                return null
            }
            return (((first - MIN_HIGH_SURROGATE) shl 10) + (second - MIN_LOW_SURROGATE) + 0x10000)
                .toLong()
        }
        if (first in MIN_LOW_SURROGATE..MAX_LOW_SURROGATE || value.length != 1) {
            return null
        }
        return first.toLong()
    }

    private fun kotlinUnsignedLong(value: Any?): Long? {
        if (value == null) {
            return null
        }
        return when (value) {
            is UByte -> value.toLong()
            is UShort -> value.toLong()
            is UInt -> value.toLong()
            is ULong -> value.toLong()
            else -> null
        }
    }

    private fun resourceHandle(value: Any?): Long? {
        if (value == null) {
            return null
        }
        if (value is WitResource<*>) {
            return value.handle()
        }
        return canonicalAbiResourceHandle(value)
    }

    private fun asFloat(value: Any?): Float {
        if (value is Number) {
            return value.toFloat()
        }
        throw ComponentModelException("expected f32 canonical ABI value, got $value")
    }

    private fun asDouble(value: Any?): Double {
        if (value is Number) {
            return value.toDouble()
        }
        throw ComponentModelException("expected f64 canonical ABI value, got $value")
    }

    private fun toLongArray(values: List<Long>): LongArray {
        val result = LongArray(values.size)
        for (i in values.indices) {
            result[i] = values[i]
        }
        return result
    }

    private fun unsignedIntToLong(value: Int): Long = value.toLong() and 0xffff_ffffL

    private fun unsignedLongToString(value: Long): String = value.toULong().toString()

    private fun normalizeTypeName(name: String): String =
        WitNames.withoutVersion(WitNames.stripIdentifierEscape(name))

    private fun lastSegment(name: String): String = WitNames.lastSegment(name)

    private fun memberName(name: String): String {
        val out = StringBuilder()
        var upper = false
        for (ch in name) {
            if (ch.isLetterOrDigit()) {
                out.append(if (upper) ch.uppercaseChar() else ch)
                upper = false
            } else {
                upper = true
            }
        }
        return out.toString()
    }

    private fun capitalize(value: String): String =
        if (value.isEmpty()) value else value[0].uppercaseChar() + value.substring(1)

    private fun unsupported(type: WitPackage.TypeRef): ComponentModelException =
        ComponentModelException("unsupported canonical ABI type $type")

    enum class Direction {
        LIFTED_EXPORT,
        LOWERED_IMPORT,
    }

    enum class CoreValType {
        I32,
        I64,
        F32,
        F64;

        fun valType(): ValType =
            when (this) {
                I32 -> ValType.I32
                I64 -> ValType.I64
                F32 -> ValType.F32
                F64 -> ValType.F64
            }
    }

    fun interface Reallocator {
        fun realloc(oldPtr: Int, oldSize: Int, alignment: Int, newSize: Int): Int
    }

    class Context
    private constructor(private val memory: Memory, private val reallocator: Reallocator?) {
        fun memory(): Memory = memory

        fun reallocate(oldPtr: Int, oldSize: Int, alignment: Int, newSize: Int): Int {
            if (newSize == 0) {
                return 0
            }
            if (reallocator == null) {
                throw ComponentModelException("canonical ABI lowering requires $REALLOC_EXPORT")
            }
            return reallocator.realloc(oldPtr, oldSize, alignment, newSize)
        }

        companion object {
            @ComponentModelJvmStatic
            fun of(memory: Memory, reallocator: Reallocator): Context = Context(memory, reallocator)

            @ComponentModelJvmStatic
            fun forInstance(instance: Instance): Context {
                val memory =
                    instance.memory()
                        ?: throw ComponentModelException(
                            "canonical ABI requires an exported or imported memory"
                        )
                val realloc = findReallocator(instance)
                val reallocator =
                    if (realloc == null) {
                        null
                    } else {
                        Reallocator { oldPtr, oldSize, alignment, newSize ->
                            val result =
                                realloc.apply(
                                    oldPtr.toLong(),
                                    oldSize.toLong(),
                                    alignment.toLong(),
                                    newSize.toLong(),
                                )
                            if (result == null || result.isEmpty()) {
                                throw ComponentModelException("$REALLOC_EXPORT returned no pointer")
                            }
                            result[0].toInt()
                        }
                    }
                return Context(memory, reallocator)
            }

            private fun findReallocator(instance: Instance): ExportFunction? {
                for (name in REALLOC_EXPORTS) {
                    try {
                        return instance.export(name)
                    } catch (_: InvalidException) {
                        // Some calls only lift values and do not need guest allocation.
                    } catch (_: NullPointerException) {
                        // Instance.export currently throws NPE when an export is absent.
                    }
                }
                return null
            }
        }
    }

    class BoundFunction
    internal constructor(
        private val abi: CanonicalAbi,
        private val instance: Instance,
        private val exportName: String,
        private val function: WitPackage.Function,
        private val asyncTaskReturn: AsyncTaskReturn? = null,
        private val asyncCallbackExportName: String? = null,
        private val asyncTasks: CanonicalAsyncLowerTasks? = null,
    ) {
        fun coreFunctionType(): FunctionType =
            abi.coreFunctionType(function, Direction.LIFTED_EXPORT)

        fun call(vararg args: Any?): Any? {
            val context = Context.forInstance(instance)
            val lowered =
                abi.lowerFlatValues(
                    context,
                    function.parameters(),
                    args.toList(),
                    MAX_FLAT_PARAMS,
                )
            asyncTaskReturn?.reset()
            val rawResults = instance.export(exportName).apply(*lowered) ?: LongArray(0)
            val canonicalResults =
                if (asyncTaskReturn != null) {
                    asyncTaskReturnResults(rawResults)
                } else {
                    rawResults
                }
            val lifted =
                abi.liftFlatValues(
                    context,
                    function.results(),
                    canonicalResults,
                    if (asyncTaskReturn != null) MAX_FLAT_PARAMS else MAX_FLAT_RESULTS,
                )
            callPostReturn(canonicalResults)
            if (lifted.isEmpty()) {
                return null
            }
            return if (lifted.size == 1) lifted[0] else lifted
        }

        private fun asyncTaskReturnResults(rawResults: LongArray): LongArray {
            if (rawResults.size != 1) {
                throw ComponentModelException(
                    "async WIT export $exportName returned ${rawResults.size} status values"
                )
            }
            val status = rawResults[0] and 0xffff_ffffL
            return when (callbackStatusCode(status)) {
                CALLBACK_EXIT -> asyncTaskReturn!!.takeRawResults() ?: LongArray(0)
                CALLBACK_YIELD,
                CALLBACK_WAIT -> resumeAsyncTaskReturn(status)
                else ->
                    throw ComponentModelException(
                        "async WIT export $exportName returned unknown status ${rawResults[0]}"
                    )
            }
        }

        private fun resumeAsyncTaskReturn(initialStatus: Long): LongArray {
            val callbackName =
                asyncCallbackExportName
                    ?: throw ComponentModelException(
                        "async WIT export $exportName yielded without callback export"
                    )
            var status = initialStatus and 0xffff_ffffL
            var event = CanonicalAsyncLowerTasks.WaitableEvent(EVENT_NONE, 0, 0)
            while (true) {
                val code = callbackStatusCode(status)
                when (code) {
                    CALLBACK_EXIT ->
                        return asyncTaskReturn!!.takeRawResults() ?: LongArray(0)
                    CALLBACK_YIELD ->
                        event = CanonicalAsyncLowerTasks.WaitableEvent(EVENT_NONE, 0, 0)
                    CALLBACK_WAIT -> {
                        val waitableSet = status ushr CALLBACK_STATUS_BITS
                        event =
                            asyncTasks?.waitableSetWaitEvent(waitableSet)
                                ?: throw ComponentModelException(
                                    "async WIT export $exportName waited on waitable set " +
                                        "${waitableSet.toULong()} but no waitable event is available" +
                                        asyncTasks?.waitableSetDebugDescription(waitableSet).orEmpty()
                                )
                    }
                    else ->
                        throw ComponentModelException(
                            "async WIT export callback $callbackName returned unknown status $status"
                        )
                }
                val callbackResults =
                    instance.export(callbackName).apply(
                        event.code.toLong(),
                        event.payload1.toLong(),
                        event.payload2.toLong(),
                    )
                if (callbackResults.size != 1) {
                    throw ComponentModelException(
                        "async WIT export callback $callbackName returned ${callbackResults.size} status values"
                    )
                }
                status = callbackResults[0] and 0xffff_ffffL
                if (callbackStatusCode(status) == CALLBACK_YIELD) {
                    val waited =
                        wasiRunBlockingOrNull {
                            delay(1)
                        } != null
                    if (!waited) {
                        throw ComponentModelException(
                            "async WIT export $exportName yielded again after callback"
                        )
                    }
                }
            }
        }

        private fun callbackStatusCode(status: Long): Int =
            (status and CALLBACK_STATUS_MASK).toInt()

        private fun callPostReturn(rawResults: LongArray) {
            val postReturnName = "cabi_post_$exportName"
            val actual: FunctionType =
                try {
                    instance.exportType(postReturnName)
                } catch (_: InvalidException) {
                    return
                } catch (_: NullPointerException) {
                    return
                }
            val expected = FunctionType.of(coreFunctionType().returns(), listOf())
            if (actual != expected) {
                throw ComponentModelException(
                    "post-return export $postReturnName has core type $actual, expected $expected"
                )
            }
            instance.export(postReturnName).apply(*rawResults)
        }
    }

    interface AsyncTaskReturn {
        fun reset()

        fun takeRawResults(): LongArray?
    }

    private class FlatIter(types: List<CoreValType>, values: LongArray) {
        private val types: List<CoreValType> = ArrayList(types)
        private val values: LongArray = values.copyOf()
        private var index = 0

        fun next(expected: CoreValType): Long {
            if (index >= values.size) {
                throw ComponentModelException("not enough canonical ABI flat values")
            }
            if (index < types.size) {
                val actual = types[index]
                if (actual != expected) {
                    if (!(actual == CoreValType.I64 && expected == CoreValType.I32)) {
                        throw ComponentModelException("expected flat value $expected, got $actual")
                    }
                }
            }
            return values[index++]
        }

        fun requireDone() {
            if (index != values.size) {
                throw ComponentModelException("too many canonical ABI flat values")
            }
        }
    }

    private data class CaseLayout(val label: String, val type: WitPackage.TypeRef?)

    private data class SelectedCase(val index: Int, val type: WitPackage.TypeRef?, val value: Any?)

    private data class StoredList(val ptr: Int, val length: Int)

    companion object {
        const val MAX_FLAT_PARAMS: Int = 16
        const val MAX_FLAT_RESULTS: Int = 1
        const val REALLOC_EXPORT: String = "canonical_abi_realloc"
        private val REALLOC_EXPORTS: Array<String> = arrayOf(REALLOC_EXPORT, "cabi_realloc")
        private const val CALLBACK_EXIT: Int = 0
        private const val CALLBACK_YIELD: Int = 1
        private const val CALLBACK_WAIT: Int = 2
        private const val CALLBACK_STATUS_BITS: Int = 4
        private const val CALLBACK_STATUS_MASK: Long = 0xfL
        private const val EVENT_NONE: Int = 0
        private const val MAX_UNICODE_CODE_POINT: Long = 0x10ffffL
        private const val MIN_SURROGATE: Long = 0xd800L
        private const val MAX_SURROGATE: Long = 0xdfffL
        private const val MIN_HIGH_SURROGATE: Int = 0xd800
        private const val MAX_HIGH_SURROGATE: Int = 0xdbff
        private const val MIN_LOW_SURROGATE: Int = 0xdc00
        private const val MAX_LOW_SURROGATE: Int = 0xdfff

        @ComponentModelJvmStatic fun of(witPackage: WitPackage): CanonicalAbi = CanonicalAbi(witPackage)
    }
}
