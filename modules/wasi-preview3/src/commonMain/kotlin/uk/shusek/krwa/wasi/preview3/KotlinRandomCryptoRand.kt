package uk.shusek.krwa.wasi.preview3

import kotlin.random.Random
import org.kotlincrypto.random.CryptoRand
import org.kotlincrypto.random.DelicateCryptoRandApi

@OptIn(DelicateCryptoRandApi::class)
internal class KotlinRandomCryptoRand(private val random: Random) : CryptoRand() {
    override fun nextBytes(buf: ByteArray): ByteArray {
        random.nextBytes(buf)
        return buf
    }
}
