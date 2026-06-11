package uk.shusek.krwa.testgen

class StringUtils private constructor() {
    companion object {
        @JvmStatic
        fun capitalize(input: String): String {
            if (input.isEmpty()) {
                return input
            }
            return input[0].uppercaseChar() + input.substring(1)
        }

        @JvmStatic
        fun escapedCamelCase(input: String): String {
            val sb = StringBuilder()
            var capitalize = true
            for (character in input) {
                if (Character.isDigit(character)) {
                    sb.append(character)
                } else if (Character.isLetter(character)) {
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
}
