package uk.shusek.krwa.runtime.wasmtime.android

import android.os.Process
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import uk.shusek.krwa.runtime.ExecutionBackend
import uk.shusek.krwa.runtime.Instance
import uk.shusek.krwa.runtime.WasmtimePreview3ComponentConfig
import uk.shusek.krwa.runtime.WasmtimePreview3Preopen
import uk.shusek.krwa.wasm.UninstantiableException
import uk.shusek.krwa.wasm.WasmEngineException
import uk.shusek.krwa.wasm.WasmParser

@RunWith(AndroidJUnit4::class)
class AndroidWasmtimePreview3BridgeDeviceTest {
    @Test
    fun mapsPulleyStartTrapToUninstantiableException() {
        installAndroidWasmtimePulleyExecutionProviderIfAvailable()
        val module = WasmParser.parse(START_TRAP_WASM)

        val exception = assertFailsWith<UninstantiableException> {
            Instance.builder(module)
                .withExecutionBackend(ExecutionBackend.PULLEY)
                .build()
        }
        assertTrue(exception.message.orEmpty().contains("unreachable"))
    }

    @Test
    fun resolvesMultibyteUtf8PulleyFunctionAndMemoryExportNames() {
        installAndroidWasmtimePulleyExecutionProviderIfAvailable()
        val module = WasmParser.parse(MULTIBYTE_EXPORTS_WASM)

        Instance.builder(module)
            .withExecutionBackend(ExecutionBackend.PULLEY)
            .build()
            .use { instance ->
                assertEquals(42L, instance.export(MULTIBYTE_FUNCTION_EXPORT).apply()[0])
                val memory = instance.exports().memory(MULTIBYTE_MEMORY_EXPORT)
                memory.writeByte(0, 42)
                assertEquals(42, memory.read(0).toInt())
            }
    }

    @Test
    fun preview3BridgeLoadsAndReachesWasmtime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preopenRoot = File(context.cacheDir, "krwa-preview3-bridge-${System.nanoTime()}")
        preopenRoot.mkdirs()

        val reason = androidWasmtimePreview3ComponentUnavailableReason(
            WasmtimePreview3ComponentConfig(
                precompiledComponentBytes = byteArrayOf(0, 1, 2, 3),
                preopens = listOf(
                    WasmtimePreview3Preopen(
                        hostRoot = preopenRoot.absolutePath,
                        guestRoot = "/",
                        writable = true,
                    ),
                ),
            ),
        )

        val message = assertNotNull(reason)
        assertFalse(message.contains("not linked", ignoreCase = true), message)
        assertFalse(message.contains("failed to load", ignoreCase = true), message)
        assertTrue(
            message.contains("deserialize", ignoreCase = true) ||
                message.contains("component", ignoreCase = true) ||
                message.contains("wasm", ignoreCase = true),
            message,
        )
    }

    @Test
    fun preview3CommandRunStringLoadsAndReachesWasmtime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preopenRoot = File(context.cacheDir, "krwa-preview3-command-run-${System.nanoTime()}")
        preopenRoot.mkdirs()

        val error = assertFailsWith<WasmEngineException> {
            androidWasmtimePreview3CommandRunString(
                WasmtimePreview3ComponentConfig(
                    precompiledComponentBytes = byteArrayOf(0, 1, 2, 3),
                    preopens = listOf(
                        WasmtimePreview3Preopen(
                            hostRoot = preopenRoot.absolutePath,
                            guestRoot = "/",
                            writable = true,
                        ),
                    ),
                ),
                stdin = "{}",
            )
        }
        val message = error.message.orEmpty()
        assertFalse(message.contains("not linked", ignoreCase = true), message)
        assertFalse(message.contains("failed to load", ignoreCase = true), message)
        assertTrue(
            message.contains("deserialize", ignoreCase = true) ||
                message.contains("component", ignoreCase = true) ||
                message.contains("wasm", ignoreCase = true),
            message,
        )
    }

    @Test
    fun preview3CommandRunStringRunsWasip2CommandComponent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preopenRoot = File(context.cacheDir, "krwa-wasip2-command-run-${System.nanoTime()}")
        preopenRoot.mkdirs()
        val componentBytes = minimalWasip2CommandComponentBytes()

        val stdout = androidWasmtimePreview3CommandRunString(
            WasmtimePreview3ComponentConfig(
                precompiledComponentBytes = componentBytes,
                preopens = listOf(
                    WasmtimePreview3Preopen(
                        hostRoot = preopenRoot.absolutePath,
                        guestRoot = "/",
                        writable = true,
                    ),
                ),
            ),
            stdin = "",
        )

        assertTrue(stdout.isEmpty(), stdout)
    }
}

private val START_TRAP_WASM =
    byteArrayOf(
        0x00, 0x61, 0x73, 0x6D,
        0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x08, 0x01, 0x00,
        0x0A, 0x05, 0x01, 0x03, 0x00, 0x00, 0x0B,
    )

private const val MULTIBYTE_FUNCTION_EXPORT = "ꠀ"
private const val MULTIBYTE_MEMORY_EXPORT = "記憶"
private val MULTIBYTE_EXPORTS_WASM =
    byteArrayOf(
        0x00, 0x61, 0x73, 0x6D,
        0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F,
        0x03, 0x02, 0x01, 0x00,
        0x05, 0x03, 0x01, 0x00, 0x01,
        0x07, 0x10, 0x02,
        0x03, 0xEA.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x00, 0x00,
        0x06,
        0xE8.toByte(), 0xA8.toByte(), 0x98.toByte(),
        0xE6.toByte(), 0x86.toByte(), 0xB6.toByte(),
        0x02, 0x00,
        0x0A, 0x06, 0x01, 0x04, 0x00, 0x41, 0x2A, 0x0B,
    )

private fun minimalWasip2CommandComponentBytes(): ByteArray {
    val encoded =
        if (Process.is64Bit()) {
            MinimalWasip2CommandPulley64GzipBase64
        } else {
            MinimalWasip2CommandPulley32GzipBase64
        }
    val compressedBytes = Base64.decode(encoded, Base64.DEFAULT)
    return GZIPInputStream(ByteArrayInputStream(compressedBytes)).use { input -> input.readBytes() }
}

private val MinimalWasip2CommandPulley64GzipBase64 = """
H4sIAKp3PWoC/+2beWBcVdn/z7n3zr5mZrI06ZJKW8rSMNmaFBQ67aQtECj7ap1OJpNmfiQzcWbSTvuCVGj8pdS3C6gV4WUVWQpY
BEQQQRSwCgiuIEEFXgVFxbIUEYT8vs85Z9JJOm2KL+/vr/vQT557n3vW5+wn4eKOziUa57uYEs7eAvvKi1Ol1QkWiqeFzMPcjGkt
86cODPb1JdfOb5k3mL4gnVmTHtPJvp66QDa5Kt7Xl0nE4n2rMtlUvrefubviiQvyWfxIpVc5MgP5WF9ydbKPWXIDyWS3I9+Xi/Vn
umEw0pl0MpDLI2QstyaVT/SWfqjoS3UlkHZM/sikVzNXKhePdSd74oN9+dBANtOVVJFT65KxvsyqJu4OlppRiHxy1VpmTaX7Uunk
lK6u2EC8uxvFEqFj/an0YC6GvDirKxpiPYPpRD6VSaNGqVXp/mQ6z5l/rJqJ3mTigmRWY5VjptXJbFcmJwqQgz2Zjnf1JSl2PBeL
p+N9a3OpnMZ9yo7QqZ4UpWBN5WIDqYTGqgYRO5FBWihud0zVG2nVqTjpeBpegFNS+JBaF6fiaaxCfR1IpdOIhvJorFbZ+vpW98fi
XalYspBPpnMIj+Smq4/9cF8K4fOxVP9AXyqRysdyeNO4azC9JpXujqXSPRmNVw9kk7lkdnUy1pON9yOfTCqdT2ZRl8r+eKIXDkWh
u/GjZ5WMsbdEY22g8dnK1puMD8TiiUQyl4uhIyTyWRQklU+tUrWZo8Llpff2H/AIFTCVTmST1Dxx6h39A6k+EQCOQhvJdkKdp6jQ
FCKeyMdUDeEajWkeVaXYmlR3vpc51Ov8FmdXalUsme5OxZEhZ+shfvHTLoaREPFkND3jw1NJAI7wo//85q6nObtlyMpqEGrIaqXA
QzZmF8pGqtpmdUDdyrW3pjJm/+fo6Oht9cxVH/FbIhV6JKgNeWVkr1dE9snIPp+I7POeAhXRgrUaO4+e9IpanZ1OTxZ/nYWGeicF
56wdaoMc1os1C9dYbx1DwqPzYOhjC3dQnjwyjenT+lCOCJuGRO07UJoXGBv7h5LZWKSCRQIakhTFkg4Y0mSxNE0US+MLOCVSgX8b
/Gw3bKdyf3hYPPGdBo+y8At2NkRBhnQWFgnpFDXK6hdpQ7pOiZ6Y8EetR3Xa9KMKs0MjFotm22oRsxKifqFmCaOxHLUcgQBHFObU
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
"""

private val MinimalWasip2CommandPulley32GzipBase64 = """
H4sIAAAAAAAAA+2WS4wcRxnHq6p7XjvP9drYjqPFiKeId7NeBwSWEu3YLEokrOQCATam1dPTM1vanu5Rd4/X60sWsVEGZHAOyAcOUQ4cHFuCHCIuHALiFYQE
llCIBGvkEOWEkKKgSIlADF9116zHs7Oxw+P2/2m/rq6vv1d9Vd2zTy5/4fOC85eZhrO/k+ymlku1WZKl5G6JlVmJMfHAp+/t9jzP3TixONfz1/xg3d8ZXa91
ZF/otm3PCxzL9tpBKOPVDis1bGctDuki/XYh6MaW555zPZaJuq7bLMReZHWCJilMP/DdfVFMlla0LmNndfTBtCcbDsW20kvgn2NFGdlW023ZPS/e3w2Dhqud
5QXX8oL2Ii/NjKqpiNhtb7Cs9D3pu4cbDatrN5tUVmJtdaTfiyzKxdmRocJq9XwnloFPK5Jtv+P6MWe1nWU6q66z5oaCHdhRnXPDRhAlBUSkd3274bnK244s
27e9jUhGgle1nqxlS6oIWRlZXekI9oEeeTsBxaJym5ZeN8U6on1826cuUFMkPZAXbFWeYNP6aVf6PrlRPYLdo3Wed65j2Q1puedj14/InsLN6ocdap8k+9iS
na4nHRlbEc0EL/b8dek3Lem3AsEPdkM3csNzrtUK7Q7lCaQfuyGt5UDHdlapoVR0ky6tdupxq6KdPRD8o1q36tpdy3YcN4osOghOHFIhMpZtvZqPabs47d7e
hvdpQ+k7oau2x1ano9OVXmJAjaI9SveJ1nxYWysL24ktvUJqjWCirJdkrctmvMoKenpicaoh25brN6VNCTnb3NzM0CV5NXhCcmcuXq/S3fApJ8vBuz98+Xec
Xa0xg9Vrmfo02yqqF4k9VSwqn60SM9WsVMrTcLBUvJ+GOp/eYlxFr2dq9Wn+cGX/I5VK7qVK5Z1+oq5cU/F4nU2Lf4rEPn99MBhc2cqmsbPZJHYujZ3LJbFz
2QINz3Pxa3LJv0v2VBWnqthWIXUrFBK3qdRtaipxmyp8QhXCavRH1arJFmMLNJwSdV7Lla6pKFRLTXRFYph/VcV+hg0GWzmRRDRETQ2mOKqGjFDe35xVjwSV
rLJtG0z0s0ov6mK2nz2m7rZpS+bopp99QE0zjOd+/K/B4CmRuPaT2Ow0u6ZS8VN6vLqpEheHUUv9oopaqpdm+0UVtURRy/cq/6KKWha/oZAUZFMH2fyPgvxo
UpCjqr0Z6jDfKnMdqtIvf0Tt4LapblU3K3Vjtm+kzWBJxtNmnWfqorZNh+kHjKUH5trRpM28Rkcjn27C91VIzj65zdjVx9ghVs+y+n6+xYe5WJ+rXIxy0a3K
xeozs/2ZJFd/Ru2J0Z9Re2L2Z9QCM3WW1TV8zqQisnWxv27M7NQxk+RN/K+phFTO/snlJCdLJEsvDcsp90uqnDKVQ7eqnLJORh04RenEcM0VVqwka64N11zV
Saq7kiTHV2eY6hdUhinKQLcqwxRtYuqZbBSFIufKMTYo3xa+pMOXRsMfJc/6DKe4hu5p2k+DwhtpP+kBU208nX2JPgJF/njyzs72M+l28nR1giJz1Wa9SLNu
mJTks6N7m1eFzExspqolOY/l9G2qJCuisqr9itq1ar0626+oY1mlY1n9oCq0olZbpTem/OBgwslUC6fPQyn9PCRR8+lbn88nb30+dyT9FKgFm1Psyj8GA90u
+vTUnqbjrt70m+pNf7/vyjcmVHSIpaiP4jzJcZKHku8OY8skj5A8RvIlkq+QtEjaJN8iuUhymeRZkp+S/IrkFZI/sqR37C8kb5BQ2sEv9aj+BbpO8nuSP5C8
SvKnMZsbNP6Z5Iae39Tj6zS+RvL6SFz1TP0IPEhdOKh2X8kZ0jxH8gbJk/RgmpTPkH5zTJSj2lr1ETC17+Auccj5CsnfSJ4m58MZBgAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA75/YjtbmQjfuhf5c2PNJw/et25E86XjyflIsLcyfmF/gjNfGtRPMMslV
pJENbjBupDGVigvG1aAuTHAmUjvOBkT1v7nopei8SZZkoCQsxzYJri5CXQx1yX59eCnkmJEtTBVLOSrYME2T5glZqrfMeIW1WguX+RNv8Y2/iuUXjOW3De81
8xJ/M+O+krVezF3kP8k/eqlwZruwbPyC3+A3qVMTO6jUaTeEakFao+AHaKJgyXNdPR/CuEkTIVRlfOhG10zibaYTvdh0NJOGKFsuhl3J3rPinu8GYXx2d1m1
lZEDcJbU2Y7bCcKNkmM3pBW6tucFTtGSvoyl7ckL7n0rdrThO3OebMVzUWw7a62eNyHwh9VSudp0ZhqCaqWiRZYZ+gAYaYm6aFMvWggujHQttbQfm0uM/Szp
Dhtu856MdJGrs5eMOsWtfvyf+S5XLTd25i09flXrh/MFPR7V+st6/oQen9f6t/R8Q4+B1q/pU76s9Z/S+hfG9O+wVP+21ntaf13rbd2WS1p/Revf1HpX67+t
9V/US7O0/mGtf1HrL2r9Ia1fMtP5o1q/rvWXtP6M1nOt3zZvr591gmbPcy06amG8cvzsyZP1MLQ3mBN0uoHv+vFcHNp060nfjVYWzs7Z6vGcQwd3buRo721P
R7ez23xX1sfJjCnbWHZcq9GTXix9y3fXLbcbOKsskm3fJlcKukjmytKKAyupxrqVctTu+F3aLbyXnXqQWLR6vhPLwF85cfaWUpsHVuq/43ab0Y7n4t14Lk7y
PH43nmTE5mP3fMzmh42cp8fdaGTunnfcrgo5qrSbzbBjd0c00m8FbN636Vbp2HwYNO3Y1pMds0YsR2P7bdWx+Wg1isPYbtDdcNzoqPF/QbRzlm9nSY+fMW7X
8zE7dw//88Zk+0lzPkG/of0/lHlv/2N75P9ePh1/fof8uT38v1ZIx8Id/A/u4d/Wjpt38P/yHv67LSfrz9zBf/xXaDzqQ3v4V7Xlx++w/5cn5lbnJrVc1Q5F
XYveluR7q/gOyViKhOfM1PG3YwnH82/t4e9nU8tnx/Tj/v8Gp+PQ2FAKAQA=
"""
