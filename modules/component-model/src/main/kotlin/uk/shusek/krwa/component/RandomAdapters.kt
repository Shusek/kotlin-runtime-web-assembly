package uk.shusek.krwa.component

import kotlin.random.Random
import org.kotlincrypto.random.CryptoRand
import org.kotlincrypto.random.DelicateCryptoRandApi

internal interface CryptoRandLongSource {
    fun nextLong(): Long
}

internal fun longFromRandomBytes(bytes: ByteArray): Long {
    var value = 0L
    for (byte in bytes) {
        value = (value shl Byte.SIZE_BITS) or (byte.toLong() and 0xffL)
    }
    return value
}

@OptIn(DelicateCryptoRandApi::class)
internal class KotlinRandomCryptoRand(private val random: Random) :
    CryptoRand(), CryptoRandLongSource {
    override fun nextBytes(buf: ByteArray): ByteArray {
        random.nextBytes(buf)
        return buf
    }

    override fun nextLong(): Long = random.nextLong()
}
