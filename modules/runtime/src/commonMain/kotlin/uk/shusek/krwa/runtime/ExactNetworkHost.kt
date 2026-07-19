package uk.shusek.krwa.runtime

/**
 * Validates and canonicalizes one exact ASCII DNS name, IPv4 literal, or IPv6 literal.
 *
 * URL-shaped values, wildcards, user info, paths, zones, and ambiguous numeric IPv4 forms are
 * rejected. DNS names are lowercased and a single trailing root dot is removed. IPv6 brackets are
 * accepted at the API boundary and removed from the canonical value.
 */
fun canonicalizeExactNetworkHost(host: String): String {
    require(host.isNotBlank()) { "exact network host must not be blank" }
    require(host == host.trim()) {
        "exact network host must not contain surrounding whitespace"
    }
    var candidate = host
    val bracketed = candidate.startsWith('[') || candidate.endsWith(']')
    if (bracketed) {
        require(candidate.startsWith('[') && candidate.endsWith(']')) {
            "exact network host IPv6 brackets must be balanced"
        }
        candidate = candidate.substring(1, candidate.length - 1)
        require(':' in candidate) {
            "exact network host brackets are only valid around an IPv6 literal"
        }
    }
    require(candidate.isNotEmpty()) { "exact network host must not be empty" }
    require(
        candidate.none { character ->
            character <= ' ' ||
                character == '/' ||
                character == '\\' ||
                character == '@' ||
                character == '?' ||
                character == '#'
        },
    ) {
        "exact network host must be one hostname or IP literal"
    }
    if (':' in candidate) {
        val address = parseExactIpv6Literal(candidate)
            ?: throw IllegalArgumentException("exact network host is not a valid IPv6 literal")
        return exactNetworkHostFromAddress(address)
    }
    if (candidate.all { character -> character.isAsciiNetworkDigit() || character == '.' }) {
        val address = parseExactIpv4Literal(candidate)
            ?: throw IllegalArgumentException("exact network host is not a canonical IPv4 literal")
        return exactNetworkHostFromAddress(address)
    }
    if (candidate.endsWith('.')) {
        candidate = candidate.dropLast(1)
    }
    require(candidate.length in 1..253) {
        "exact network hostname must contain between 1 and 253 characters"
    }
    val labels = candidate.split('.')
    require(
        labels.all { label ->
            label.length in 1..63 &&
                label.first().isAsciiNetworkLetterOrDigit() &&
                label.last().isAsciiNetworkLetterOrDigit() &&
                label.all { character ->
                    character.isAsciiNetworkLetterOrDigit() || character == '-'
                }
        },
    ) {
        "exact network host is not a valid ASCII hostname"
    }
    return candidate.lowercase()
}

private fun Char.isAsciiNetworkLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun parseExactIpv4Literal(value: String): ByteArray? {
    val octets = value.split('.')
    if (octets.size != Ipv4OctetCount) return null
    val result = ByteArray(Ipv4OctetCount)
    for (index in octets.indices) {
        val octet = octets[index]
        if (
            octet.isEmpty() ||
                octet.any { character -> !character.isAsciiNetworkDigit() } ||
                (octet.length > 1 && octet.startsWith('0'))
        ) {
            return null
        }
        val number = octet.toIntOrNull() ?: return null
        if (number !in 0..ByteMask) return null
        result[index] = number.toByte()
    }
    return result
}

private fun parseExactIpv6Literal(value: String): ByteArray? {
    if (value.isEmpty() || '%' in value) return null
    var normalized = value
    if ('.' in normalized) {
        val separator = normalized.lastIndexOf(':')
        if (separator < 0) return null
        val ipv4 = parseExactIpv4Literal(normalized.substring(separator + 1)) ?: return null
        val upper = (unsignedNetworkByte(ipv4[0]) shl Byte.SIZE_BITS) or unsignedNetworkByte(ipv4[1])
        val lower = (unsignedNetworkByte(ipv4[2]) shl Byte.SIZE_BITS) or unsignedNetworkByte(ipv4[3])
        normalized = normalized.substring(0, separator + 1) +
            upper.toString(HexRadix) +
            ":" +
            lower.toString(HexRadix)
    }
    val compression = normalized.indexOf("::")
    if (compression >= 0 && normalized.indexOf("::", compression + 2) >= 0) return null
    val groups = ArrayList<Int>(Ipv6GroupCount)
    if (compression < 0) {
        val parsed = parseExactIpv6Groups(normalized) ?: return null
        if (parsed.size != Ipv6GroupCount) return null
        groups.addAll(parsed)
    } else {
        val left = parseExactIpv6Groups(normalized.substring(0, compression)) ?: return null
        val right = parseExactIpv6Groups(normalized.substring(compression + 2)) ?: return null
        if (left.size + right.size >= Ipv6GroupCount) return null
        groups.addAll(left)
        repeat(Ipv6GroupCount - left.size - right.size) {
            groups.add(0)
        }
        groups.addAll(right)
    }
    val result = ByteArray(Ipv6GroupCount * 2)
    for (index in groups.indices) {
        result[index * 2] = (groups[index] ushr Byte.SIZE_BITS).toByte()
        result[index * 2 + 1] = groups[index].toByte()
    }
    return result
}

private fun parseExactIpv6Groups(value: String): List<Int>? {
    if (value.isEmpty()) return emptyList()
    val tokens = value.split(':')
    if (tokens.any(String::isEmpty)) return null
    val groups = ArrayList<Int>(tokens.size)
    for (token in tokens) {
        if (token.length !in 1..4 || token.any { character -> !character.isAsciiNetworkHexDigit() }) {
            return null
        }
        groups.add(token.toIntOrNull(HexRadix) ?: return null)
    }
    return groups
}

private fun exactNetworkHostFromAddress(address: ByteArray): String =
    when (address.size) {
        Ipv4OctetCount -> address.joinToString(".") { byte -> unsignedNetworkByte(byte).toString() }
        Ipv6GroupCount * 2 ->
            (0 until Ipv6GroupCount).joinToString(":") { index ->
                (
                    (unsignedNetworkByte(address[index * 2]) shl Byte.SIZE_BITS) or
                        unsignedNetworkByte(address[index * 2 + 1])
                ).toString(HexRadix)
            }
        else -> throw IllegalArgumentException("exact network address must contain 4 or 16 bytes")
    }

private fun unsignedNetworkByte(value: Byte): Int = value.toInt() and ByteMask

private fun Char.isAsciiNetworkDigit(): Boolean = this in '0'..'9'

private fun Char.isAsciiNetworkHexDigit(): Boolean =
    isAsciiNetworkDigit() || this in 'a'..'f' || this in 'A'..'F'

private const val ByteMask = 0xff
private const val HexRadix = 16
private const val Ipv4OctetCount = 4
private const val Ipv6GroupCount = 8
