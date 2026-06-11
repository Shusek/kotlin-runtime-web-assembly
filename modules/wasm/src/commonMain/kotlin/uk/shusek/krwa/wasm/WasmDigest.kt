package uk.shusek.krwa.wasm

import okio.ByteString.Companion.toByteString

internal object WasmDigest {
    fun sha256(bytes: ByteArray): String = "sha-256:" + bytes.toByteString().sha256().base64()
}
