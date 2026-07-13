package uk.shusek.krwa.component

import uk.shusek.krwa.runtime.HostFunction
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.wasm.InvalidException
import uk.shusek.krwa.wasm.types.FunctionType
import uk.shusek.krwa.wasm.types.ValType

// Internal canonical ABI bindings used while constructing a WasmPlugin.
internal class CanonicalResourceHandles {
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

internal class AsyncTaskReturnSlot : CanonicalAbi.AsyncTaskReturn {
    private var rawResults: LongArray? = null

    fun putRawResults(results: LongArray) {
        rawResults = results.copyOf()
    }

    override fun reset() {
        rawResults = null
    }

    override fun takeRawResults(): LongArray? {
        val result = rawResults?.copyOf()
        rawResults = null
        return result
    }
}

internal class ResourceIntrinsic
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

internal data class FunctionBinding(
    val publicName: String,
    val symbolName: String,
    val function: WitPackage.Function,
)

internal data class ModuleImportBinding(
    val handlerInterfaceName: String,
    val publicName: String,
    val symbolName: String,
    val function: WitPackage.Function,
    val additionalHandlerInterfaceNames: List<String>,
)

internal class AsyncTypeCursor(val target: Int) {
    private var nextIndex = 0

    fun next(): Int = nextIndex++
}

internal class ContextIntrinsic private constructor(
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

internal class WaitableIntrinsic private constructor(private val kind: Kind) {
    fun hostFunction(
        moduleName: String,
        symbolName: String,
        tasks: CanonicalAsyncLowerTasks,
    ): HostFunction =
        HostFunction(moduleName, symbolName, functionType()) { instance, args ->
            apply(instance, tasks, args)
        }

    private fun functionType(): FunctionType =
        when (kind) {
            Kind.SET_NEW -> FunctionType.of(emptyList(), listOf(ValType.I32))
            Kind.SET_WAIT,
            Kind.SET_POLL ->
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32))
            Kind.SET_DROP -> FunctionType.of(listOf(ValType.I32), emptyList())
            Kind.JOIN -> FunctionType.of(listOf(ValType.I32, ValType.I32), emptyList())
        }

    private fun apply(
        instance: Instance,
        tasks: CanonicalAsyncLowerTasks,
        args: LongArray,
    ): LongArray =
        when (kind) {
            Kind.SET_NEW -> {
                requireArity(args, 0)
                longArrayOf(tasks.waitableSetNew())
            }
            Kind.SET_WAIT -> {
                requireArity(args, 2)
                longArrayOf(
                    tasks.waitableSetWait(
                        instance,
                        toU32(args[0]),
                        toU32Int(args[1], "payload pointer"),
                    )
                )
            }
            Kind.SET_POLL -> {
                requireArity(args, 2)
                longArrayOf(
                    tasks.waitableSetPoll(
                        instance,
                        toU32(args[0]),
                        toU32Int(args[1], "payload pointer"),
                    )
                )
            }
            Kind.SET_DROP -> {
                requireArity(args, 1)
                tasks.waitableSetDrop(toU32(args[0]))
                LongArray(0)
            }
            Kind.JOIN -> {
                requireArity(args, 2)
                tasks.waitableJoin(toU32(args[0]), toU32(args[1]))
                LongArray(0)
            }
        }

    private fun requireArity(args: LongArray, expected: Int) {
        if (args.size != expected) {
            throw ComponentModelException(
                "canonical waitable intrinsic expected $expected arguments, got ${args.size}"
            )
        }
    }

    private enum class Kind {
        SET_NEW,
        SET_WAIT,
        SET_POLL,
        SET_DROP,
        JOIN,
    }

    companion object {
        fun parse(symbolName: String): WaitableIntrinsic? =
            when (symbolName) {
                "[waitable-set-new]", "waitable-set.new" ->
                    WaitableIntrinsic(Kind.SET_NEW)
                "[waitable-set-wait]", "waitable-set.wait" ->
                    WaitableIntrinsic(Kind.SET_WAIT)
                "[waitable-set-poll]", "waitable-set.poll" ->
                    WaitableIntrinsic(Kind.SET_POLL)
                "[waitable-set-drop]", "waitable-set.drop" ->
                    WaitableIntrinsic(Kind.SET_DROP)
                "[waitable-join]", "waitable.join" ->
                    WaitableIntrinsic(Kind.JOIN)
                else -> null
            }
    }
}

internal class SubtaskIntrinsic private constructor(private val kind: Kind) {
    fun hostFunction(
        moduleName: String,
        symbolName: String,
        tasks: CanonicalAsyncLowerTasks,
    ): HostFunction =
        HostFunction(moduleName, symbolName, functionType()) { _, args ->
            apply(tasks, args)
        }

    private fun functionType(): FunctionType =
        when (kind) {
            Kind.CANCEL -> FunctionType.of(listOf(ValType.I32), listOf(ValType.I32))
            Kind.DROP -> FunctionType.of(listOf(ValType.I32), emptyList())
        }

    private fun apply(tasks: CanonicalAsyncLowerTasks, args: LongArray): LongArray =
        when (kind) {
            Kind.CANCEL -> {
                requireArity(args, 1)
                longArrayOf(tasks.subtaskCancel(toU32(args[0])))
            }
            Kind.DROP -> {
                requireArity(args, 1)
                tasks.subtaskDrop(toU32(args[0]))
                LongArray(0)
            }
        }

    private fun requireArity(args: LongArray, expected: Int) {
        if (args.size != expected) {
            throw ComponentModelException(
                "canonical subtask intrinsic expected $expected arguments, got ${args.size}"
            )
        }
    }

    private enum class Kind {
        CANCEL,
        DROP,
    }

    companion object {
        fun parse(symbolName: String): SubtaskIntrinsic? =
            when (symbolName) {
                "[subtask-cancel]", "subtask.cancel" ->
                    SubtaskIntrinsic(Kind.CANCEL)
                "[subtask-drop]", "subtask.drop" ->
                    SubtaskIntrinsic(Kind.DROP)
                else -> null
            }
    }
}

internal class ThreadIntrinsic private constructor(private val kind: Kind) {
    fun hostFunction(
        moduleName: String,
        symbolName: String,
    ): HostFunction =
        HostFunction(moduleName, symbolName, functionType()) { _, args ->
            apply(args)
        }

    private fun functionType(): FunctionType =
        when (kind) {
            Kind.INDEX -> FunctionType.of(emptyList(), listOf(ValType.I32))
            Kind.NEW_INDIRECT,
            Kind.SPAWN_INDIRECT ->
                FunctionType.of(listOf(ValType.I32, ValType.I32), listOf(ValType.I32))
            Kind.RESUME_LATER -> FunctionType.of(listOf(ValType.I32), emptyList())
            Kind.SUSPEND,
            Kind.YIELD -> FunctionType.of(emptyList(), listOf(ValType.I32))
            Kind.SUSPEND_THEN_RESUME,
            Kind.YIELD_THEN_RESUME,
            Kind.SUSPEND_THEN_PROMOTE,
            Kind.YIELD_THEN_PROMOTE ->
                FunctionType.of(listOf(ValType.I32), listOf(ValType.I32))
        }

    private fun apply(
        args: LongArray,
    ): LongArray =
        when (kind) {
            Kind.INDEX -> {
                requireArity(args, 0)
                longArrayOf(ROOT_THREAD_INDEX)
            }
            Kind.YIELD -> {
                requireArity(args, 0)
                longArrayOf(NOT_CANCELLED)
            }
            Kind.NEW_INDIRECT,
            Kind.SPAWN_INDIRECT,
            Kind.RESUME_LATER,
            Kind.SUSPEND,
            Kind.SUSPEND_THEN_RESUME,
            Kind.YIELD_THEN_RESUME,
            Kind.SUSPEND_THEN_PROMOTE,
            Kind.YIELD_THEN_PROMOTE ->
                throw ComponentModelException(
                    "canonical thread intrinsics require resumable execution, which is not supported by platform execution"
                )
        }

    private fun requireArity(args: LongArray, expected: Int) {
        if (args.size != expected) {
            throw ComponentModelException(
                "canonical thread intrinsic expected $expected arguments, got ${args.size}"
            )
        }
    }

    private enum class Kind {
        INDEX,
        NEW_INDIRECT,
        SPAWN_INDIRECT,
        RESUME_LATER,
        SUSPEND,
        YIELD,
        SUSPEND_THEN_RESUME,
        YIELD_THEN_RESUME,
        SUSPEND_THEN_PROMOTE,
        YIELD_THEN_PROMOTE,
    }

    companion object {
        private const val ROOT_THREAD_INDEX: Long = 0L
        private const val NOT_CANCELLED: Long = 0L

        fun parse(symbolName: String): ThreadIntrinsic? =
            when (symbolName) {
                "[thread-index]", "thread.index" ->
                    ThreadIntrinsic(Kind.INDEX)
                "[thread-new-indirect]", "thread.new-indirect" ->
                    ThreadIntrinsic(Kind.NEW_INDIRECT)
                "[thread-spawn-indirect]", "thread.spawn-indirect" ->
                    ThreadIntrinsic(Kind.SPAWN_INDIRECT)
                "[thread-resume-later]", "thread.resume-later" ->
                    ThreadIntrinsic(Kind.RESUME_LATER)
                "[thread-suspend]", "thread.suspend" ->
                    ThreadIntrinsic(Kind.SUSPEND)
                "[thread-yield]", "thread.yield" ->
                    ThreadIntrinsic(Kind.YIELD)
                "[thread-suspend-then-resume]", "thread.suspend-then-resume" ->
                    ThreadIntrinsic(Kind.SUSPEND_THEN_RESUME)
                "[thread-yield-then-resume]", "thread.yield-then-resume" ->
                    ThreadIntrinsic(Kind.YIELD_THEN_RESUME)
                "[thread-suspend-then-promote]", "thread.suspend-then-promote" ->
                    ThreadIntrinsic(Kind.SUSPEND_THEN_PROMOTE)
                "[thread-yield-then-promote]", "thread.yield-then-promote" ->
                    ThreadIntrinsic(Kind.YIELD_THEN_PROMOTE)
                else -> null
            }
    }
}

internal class AsyncLowerIntrinsic private constructor(val targetSymbolName: String) {
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

internal class FutureIntrinsic
private constructor(private val kind: Kind, val index: Int, val targetSymbolName: String) {
    fun hostFunction(
        moduleName: String,
        symbolName: String,
        intrinsics: CanonicalFutureIntrinsics,
        tasks: CanonicalAsyncLowerTasks,
        payloadType: WitPackage.TypeRef,
        abi: CanonicalAbi,
    ): HostFunction =
        HostFunction(moduleName, symbolName, functionType()) { instance, args ->
            apply(instance, intrinsics, tasks, payloadType, abi, args)
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
        tasks: CanonicalAsyncLowerTasks,
        payloadType: WitPackage.TypeRef,
        abi: CanonicalAbi,
        args: LongArray,
    ): LongArray {
        val result =
            when (kind) {
                Kind.NEW -> {
                    requireArity(args, 0)
                    tasks.futureNew(intrinsics)
                }
                Kind.CANCEL_READ -> {
                    requireArity(args, 1)
                    tasks.futureCancelRead(toU32(args[0]), intrinsics)
                }
                Kind.CANCEL_WRITE -> {
                    requireArity(args, 1)
                    tasks.futureCancelWrite(toU32(args[0]), intrinsics)
                }
                Kind.DROP_READABLE -> {
                    requireArity(args, 1)
                    tasks.futureDropReadable(toU32(args[0]), intrinsics)
                    return LongArray(0)
                }
                Kind.DROP_WRITABLE -> {
                    requireArity(args, 1)
                    tasks.futureDropWritable(toU32(args[0]), intrinsics)
                    return LongArray(0)
                }
                Kind.READ -> {
                    requireArity(args, 2)
                    tasks.futureRead(
                        instance,
                        toU32(args[0]),
                        toU32Int(args[1], "pointer"),
                        payloadType,
                        intrinsics,
                        abi,
                    )
                }
                Kind.WRITE -> {
                    requireArity(args, 2)
                    tasks.futureWrite(
                        instance,
                        toU32(args[0]),
                        toU32Int(args[1], "pointer"),
                        payloadType,
                        intrinsics,
                        abi,
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

internal class StreamIntrinsic
private constructor(private val kind: Kind, val index: Int, val targetSymbolName: String) {
    fun hostFunction(
        moduleName: String,
        symbolName: String,
        intrinsics: CanonicalStreamIntrinsics,
        tasks: CanonicalAsyncLowerTasks,
        payloadType: WitPackage.TypeRef,
        abi: CanonicalAbi,
    ): HostFunction =
        HostFunction(moduleName, symbolName, functionType()) { instance, args ->
            apply(instance, intrinsics, tasks, payloadType, abi, args)
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
        tasks: CanonicalAsyncLowerTasks,
        payloadType: WitPackage.TypeRef,
        abi: CanonicalAbi,
        args: LongArray,
    ): LongArray {
        val result =
            when (kind) {
                Kind.NEW -> {
                    requireArity(args, 0)
                    tasks.streamNew(payloadType, intrinsics)
                }
                Kind.CANCEL_READ -> {
                    requireArity(args, 1)
                    tasks.streamCancelRead(toU32(args[0]), intrinsics)
                }
                Kind.CANCEL_WRITE -> {
                    requireArity(args, 1)
                    tasks.streamCancelWrite(toU32(args[0]), intrinsics)
                }
                Kind.DROP_READABLE -> {
                    requireArity(args, 1)
                    tasks.streamDropReadable(toU32(args[0]), intrinsics)
                    return LongArray(0)
                }
                Kind.DROP_WRITABLE -> {
                    requireArity(args, 1)
                    tasks.streamDropWritable(toU32(args[0]), intrinsics)
                    return LongArray(0)
                }
                Kind.READ -> {
                    requireArity(args, 3)
                    tasks.streamRead(
                        instance,
                        toU32(args[0]),
                        toU32Int(args[1], "pointer"),
                        toU32Int(args[2], "length"),
                        payloadType,
                        intrinsics,
                        abi,
                    )
                }
                Kind.WRITE -> {
                    requireArity(args, 3)
                    tasks.streamWrite(
                        instance,
                        toU32(args[0]),
                        toU32Int(args[1], "pointer"),
                        toU32Int(args[2], "length"),
                        payloadType,
                        intrinsics,
                        abi,
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


private fun toU32(value: Long): Long = value and 0xffff_ffffL

private fun toU32Int(value: Long, name: String): Int {
    val unsigned = toU32(value)
    if (unsigned > Int.MAX_VALUE) {
        throw ComponentModelException(
            "canonical stream intrinsic $name exceeds supported memory index: $unsigned"
        )
    }
    return unsigned.toInt()
}
