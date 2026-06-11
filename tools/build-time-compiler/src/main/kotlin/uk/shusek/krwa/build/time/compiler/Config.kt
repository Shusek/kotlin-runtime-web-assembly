package uk.shusek.krwa.build.time.compiler

import java.nio.file.Path
import java.util.StringJoiner
import uk.shusek.krwa.compiler.InterpreterFallback

class Config
private constructor(
    private val wasmFileValue: Path?,
    private val nameValue: String?,
    private val targetClassFolderValue: Path?,
    private val targetSourceFolderValue: Path?,
    private val targetWasmFolderValue: Path?,
    @JvmField val interpreterFallback: InterpreterFallback?,
    private val interpretedFunctionsValue: Set<Int>?,
    private val moduleInterfaceValue: String?,
) {
    fun wasmFile(): Path? = wasmFileValue

    fun name(): String? = nameValue

    fun targetClassFolder(): Path? = targetClassFolderValue

    fun targetSourceFolder(): Path? = targetSourceFolderValue

    fun targetWasmFolder(): Path? = targetWasmFolderValue

    fun interpreterFallback(): InterpreterFallback? = interpreterFallback

    fun interpretedFunctions(): Set<Int>? = interpretedFunctionsValue

    fun moduleInterface(): String? = moduleInterfaceValue

    @Suppress("StringSplitter")
    fun getPackageName(): String {
        val split = nameValue!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val packageName = StringJoiner(".")
        for (i in 0 until split.size - 1) {
            packageName.add(split[i])
        }
        return packageName.toString()
    }

    @Suppress("StringSplitter")
    fun getBaseName(): String {
        val split = nameValue!!.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        return split[split.size - 1]
    }

    class Builder private constructor() {
        private var wasmFile: Path? = null
        private var name: String? = null
        private var targetClassFolder: Path? = null
        private var targetSourceFolder: Path? = null
        private var targetWasmFolder: Path? = null
        private var interpreterFallback: InterpreterFallback? = InterpreterFallback.FAIL
        private var interpretedFunctions: Set<Int>? = null
        private var moduleInterface: String? = null

        fun withWasmFile(wasmFile: Path?): Builder {
            this.wasmFile = wasmFile
            return this
        }

        fun withName(name: String?): Builder {
            this.name = name
            return this
        }

        fun withTargetClassFolder(targetClassFolder: Path?): Builder {
            this.targetClassFolder = targetClassFolder
            return this
        }

        fun withTargetSourceFolder(targetSourceFolder: Path?): Builder {
            this.targetSourceFolder = targetSourceFolder
            return this
        }

        fun withTargetWasmFolder(targetWasmFolder: Path?): Builder {
            this.targetWasmFolder = targetWasmFolder
            return this
        }

        fun withInterpreterFallback(interpreterFallback: InterpreterFallback?): Builder {
            this.interpreterFallback = interpreterFallback
            return this
        }

        fun withInterpretedFunctions(interpretedFunctions: Set<Int>?): Builder {
            this.interpretedFunctions = interpretedFunctions
            return this
        }

        fun withModuleInterface(moduleInterface: String?): Builder {
            this.moduleInterface = moduleInterface
            return this
        }

        fun build(): Config =
            Config(
                wasmFile,
                name,
                targetClassFolder,
                targetSourceFolder,
                targetWasmFolder,
                interpreterFallback,
                interpretedFunctions,
                moduleInterface,
            )

        companion object {
            internal fun create(): Builder = Builder()
        }
    }

    companion object {
        @JvmStatic fun builder(): Builder = Builder.create()
    }
}
