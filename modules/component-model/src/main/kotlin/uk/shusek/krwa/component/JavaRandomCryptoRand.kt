package uk.shusek.krwa.component

import java.util.Random
import org.kotlincrypto.random.CryptoRand
import org.kotlincrypto.random.DelicateCryptoRandApi

@OptIn(DelicateCryptoRandApi::class)
internal class JavaRandomCryptoRand(private val random: Random) :
    CryptoRand(), CryptoRandLongSource {
    override fun nextBytes(buf: ByteArray): ByteArray {
        synchronized(random) { random.nextBytes(buf) }
        return buf
    }

    override fun nextLong(): Long = synchronized(random) { random.nextLong() }
}
