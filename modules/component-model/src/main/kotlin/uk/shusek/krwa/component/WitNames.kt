package uk.shusek.krwa.component

object WitNames {
    @ComponentModelJvmStatic
    fun stripIdentifierEscape(name: String): String =
        if (name.startsWith("%")) name.substring(1) else name

    @ComponentModelJvmStatic
    fun withoutVersion(name: String): String {
        val at = name.indexOf('@')
        if (at < 0 || at + 1 >= name.length || !name[at + 1].isDigit()) {
            return name
        }

        var pos = at + 1
        pos = readDigits(name, pos)
        if (pos <= at + 1 || pos >= name.length || name[pos] != '.') {
            return name
        }
        val minorStart = pos + 1
        pos = readDigits(name, pos + 1)
        if (pos <= minorStart || pos >= name.length || name[pos] != '.') {
            return name
        }
        val patchStart = pos + 1
        pos = readDigits(name, pos + 1)
        if (pos <= patchStart) {
            return name
        }
        if (pos < name.length && (name[pos] == '-' || name[pos] == '+')) {
            pos++
            while (pos < name.length && isVersionSuffixChar(name[pos])) {
                pos++
            }
        }
        return name.substring(0, at) + name.substring(pos)
    }

    @ComponentModelJvmStatic
    fun lastSegment(name: String): String {
        val normalized = withoutVersion(stripIdentifierEscape(name))
        val slash = normalized.lastIndexOf('/')
        val dot = normalized.lastIndexOf('.')
        val colon = normalized.lastIndexOf(':')
        val index = maxOf(slash, dot, colon)
        return if (index >= 0) normalized.substring(index + 1) else normalized
    }

    @ComponentModelJvmStatic
    fun qualifiedInterfaceName(packageName: String?, interfaceName: String): String {
        if (
            packageName == null ||
                packageName.isBlank() ||
                interfaceName.contains("/") ||
                interfaceName.contains(":")
        ) {
            return interfaceName
        }
        val at = versionIndex(packageName)
        return if (at >= 0) {
            packageName.substring(0, at) + "/" + interfaceName + packageName.substring(at)
        } else {
            "$packageName/$interfaceName"
        }
    }

    @ComponentModelJvmStatic fun typeName(name: String): String = capitalizeWords(name)

    @ComponentModelJvmStatic
    fun memberName(name: String): String = uncapitalize(capitalizeWords(name))

    @ComponentModelJvmStatic
    fun enumName(name: String): String {
        val result =
            name
                .replace('-', '_')
                .replace('.', '_')
                .replace(':', '_')
                .replace('/', '_')
                .uppercase()
        if (result.isEmpty() || !isIdentifierStart(result[0])) {
            return "_$result"
        }
        return result
    }

    @ComponentModelJvmStatic
    fun matchesMemberName(javaName: String, witName: String): Boolean {
        val memberName = memberName(witName)
        return javaName == witName || javaName == memberName || javaName.startsWith("$memberName-")
    }

    private fun capitalizeWords(name: String): String {
        val out = StringBuilder()
        var upper = true
        for (ch in name) {
            if (ch.isLetterOrDigit()) {
                out.append(if (upper) ch.uppercaseChar() else ch)
                upper = false
            } else {
                upper = true
            }
        }
        if (out.isEmpty() || !isIdentifierStart(out[0])) {
            out.insert(0, '_')
        }
        return out.toString()
    }

    private fun isIdentifierStart(ch: Char): Boolean = ch == '_' || ch.isLetter()

    private fun uncapitalize(value: String): String {
        if (value.isEmpty()) {
            return value
        }
        return value[0].lowercaseChar() + value.substring(1)
    }

    private fun readDigits(value: String, start: Int): Int {
        var pos = start
        while (pos < value.length && value[pos].isDigit()) {
            pos++
        }
        return pos
    }

    private fun versionIndex(value: String): Int {
        val at = value.indexOf('@')
        if (at < 0 || at + 1 >= value.length || !value[at + 1].isDigit()) {
            return -1
        }

        var pos = at + 1
        pos = readDigits(value, pos)
        if (pos <= at + 1 || pos >= value.length || value[pos] != '.') {
            return -1
        }
        val minorStart = pos + 1
        pos = readDigits(value, pos + 1)
        if (pos <= minorStart || pos >= value.length || value[pos] != '.') {
            return -1
        }
        val patchStart = pos + 1
        pos = readDigits(value, pos + 1)
        return if (pos <= patchStart) -1 else at
    }

    private fun isVersionSuffixChar(ch: Char): Boolean =
        ch.isLetterOrDigit() || ch == '.' || ch == '-'
}
