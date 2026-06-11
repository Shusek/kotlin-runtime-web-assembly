package uk.shusek.krwa.runtime

//
// This class is used by compiler generated classes. It MUST remain backwards compatible
// so that older generated code can run on newer versions of the library.
//
// This is an ugly hack to work around a bug on some JVMs (Temurin 17-)
object MemCopyWorkaround {
    private fun interface MemoryCopyFunc {
        fun apply(destination: Int, offset: Int, size: Int, memory: Memory?)
    }

    private fun interface I32GEUFunc {
        fun apply(a: Int, b: Int): Int
    }

    private var memoryCopyFunc: MemoryCopyFunc? = null
    private var i32geuFunc: I32GEUFunc? = null

    init {
        if (shouldUseMemWorkaround()) {
            val noop1 = MemoryCopyFunc { _, _, _, _ -> }
            val noop2 = MemoryCopyFunc { _, _, _, _ -> }

            val noop3 = I32GEUFunc { a, _ -> a }
            val noop4 = I32GEUFunc { _, b -> b }

            // Warm up the JIT... to make it see memoryCopyFunc.apply is megamorphic
            for (i in 0 until 10_000) {
                memoryCopyFunc = noop1
                memoryCopy(0, 0, 0, null)
                memoryCopyFunc = noop2
                memoryCopy(0, 0, 0, null)

                i32geuFunc = noop3
                i32_ge_u(0, 0)
                i32geuFunc = noop4
                i32_ge_u(0, 0)
            }

            memoryCopyFunc = MemoryCopyFunc { destination, offset, size, memory ->
                memory!!.copy(destination, offset, size)
            }
            i32geuFunc = I32GEUFunc { a, b -> OpcodeImpl.I32_GE_U(a, b) }
        }
    }

    @JvmStatic
    fun shouldUseMemWorkaround(): Boolean =
        shouldUseMemWorkaround(System.getProperty("java.version"))

    @JvmStatic
    fun shouldUseMemWorkaround(version: String?): Boolean {
        if (version == null || version == "0") {
            // Android https://developer.android.com/reference/java/lang/System#getProperties()
            return false
        }
        if (version.startsWith("1.")) {
            // Java 8 or earlier: "1.8.0_231" -> 8
            return true
        }

        // Java 9 or later: "11.0.9" or "17.0.1"
        val dotIndex = version.indexOf(".")
        var majorStr = if (dotIndex != -1) version.substring(0, dotIndex) else version
        val dashIndex = majorStr.indexOf("-")
        majorStr = if (dashIndex != -1) majorStr.substring(0, dashIndex) else majorStr
        return try {
            majorStr.toInt() <= 17
        } catch (_: NumberFormatException) {
            false
        }
    }

    @JvmStatic
    fun memoryCopy(destination: Int, offset: Int, size: Int, memory: Memory?) {
        memoryCopyFunc!!.apply(destination, offset, size, memory)
    }

    @JvmStatic fun i32_ge_u(a: Int, b: Int): Int = i32geuFunc!!.apply(a, b)
}
