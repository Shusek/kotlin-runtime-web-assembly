package uk.shusek.krwa.wasm

import okio.ByteString.Companion.toByteString

internal object WasmDigest {
    fun sha256(bytes: ByteArray): String =
        "sha-256:" + bytes.toByteString().sha256().toByteArray().encodeBase64()
}

// Keep digest serialization independent of Okio's Base64 ABI. Okio 3.18 changed the
// Kotlin/Native and Kotlin/Wasm symbol while retaining source compatibility.
private fun ByteArray.encodeBase64(): String {
    val result = StringBuilder(((size + 2) / 3) * 4)
    var index = 0
    while (index < size) {
        val first = this[index++].toInt() and 0xff
        val second = if (index < size) this[index++].toInt() and 0xff else -1
        val third = if (index < size) this[index++].toInt() and 0xff else -1

        result.append(BASE64_ALPHABET[first shr 2])
        result.append(
            BASE64_ALPHABET[
                ((first and 0x03) shl 4) or
                    if (second >= 0) second shr 4 else 0,
            ],
        )
        result.append(
            if (second >= 0) {
                BASE64_ALPHABET[
                    ((second and 0x0f) shl 2) or
                        if (third >= 0) third shr 6 else 0,
                ]
            } else {
                '='
            },
        )
        result.append(if (third >= 0) BASE64_ALPHABET[third and 0x3f] else '=')
    }
    return result.toString()
}

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
