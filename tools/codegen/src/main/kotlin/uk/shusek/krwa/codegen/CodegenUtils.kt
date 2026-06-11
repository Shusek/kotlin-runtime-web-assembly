package uk.shusek.krwa.codegen

import java.util.Locale

class CodegenUtils private constructor() {
    companion object {
        @JvmStatic
        fun camelCaseToSnakeCase(name: String): String =
            name.replace("([a-z])([A-Z]+)".toRegex(), "$1_$2").lowercase(Locale.ROOT)

        @JvmStatic
        fun snakeCaseToCamelCase(name: String, className: Boolean): String {
            val sb = StringBuilder()
            var toUppercase = className
            for (i in name.indices) {
                val c = name[i]
                if ((c == '_' || c == '-' || !Character.isJavaIdentifierPart(c)) && i != 0) {
                    toUppercase = true
                } else if (toUppercase) {
                    sb.append(c.uppercaseChar())
                    toUppercase = false
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
