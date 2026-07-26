package uk.shusek.krwa.wasm

import kotlin.test.Test
import kotlin.test.assertEquals

class WasmDigestTest {
    @Test
    fun preservesSha256Base64Serialization() {
        assertEquals(
            "sha-256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=",
            WasmDigest.sha256(byteArrayOf()),
        )
        assertEquals(
            "sha-256:bjQLnP+zepicpUTmu3gKLHiQHT+zNzh2hRGjBhevoB0=",
            WasmDigest.sha256(byteArrayOf(0)),
        )
        assertEquals(
            "sha-256:k6RLu5bHUSGOTADUeeTBQ1gSKjiazKFiBbHk0NxflHY=",
            WasmDigest.sha256(
                byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00),
            ),
        )
    }
}
