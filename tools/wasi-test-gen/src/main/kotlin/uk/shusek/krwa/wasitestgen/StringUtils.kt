package uk.shusek.krwa.wasitestgen

object StringUtils {
    fun capitalize(input: String): String {
        if (input.isEmpty()) {
            return input
        }
        return input[0].uppercaseChar() + input.substring(1)
    }

    fun escapedCamelCase(input: String): String {
        val sb = StringBuilder()
        var capitalize = true
        for (character in input) {
            if (character.isDigit()) {
                sb.append(character)
            } else if (character.isLetter()) {
                if (capitalize) {
                    sb.append(character.uppercaseChar())
                    capitalize = false
                } else {
                    sb.append(character)
                }
            } else {
                capitalize = true
            }
        }

        return sb.toString()
    }
}
