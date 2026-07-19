package uk.shusek.krwa.wasm.io

import java.io.IOException
import java.io.InputStream
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import uk.shusek.krwa.wasm.requireWithinLimit

object InputStreams {
    @JvmStatic
    @Throws(IOException::class)
    fun readAllBytes(inputStream: InputStream?): ByteArray {
        return readAllBytes(inputStream, Int.MAX_VALUE.toLong())
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readAllBytes(inputStream: InputStream?, maxBytes: Long): ByteArray {
        require(inputStream != null) { "InputStream cannot be null" }
        require(maxBytes in 0..Int.MAX_VALUE.toLong()) {
            "maxBytes must be between 0 and ${Int.MAX_VALUE}"
        }

        val bufferLength = 1024
        val buffer = ByteArray(bufferLength)
        val output = Buffer()
        var total = 0L

        while (true) {
            val requested = minOf(bufferLength.toLong(), maxBytes - total + 1).toInt()
            val bytesRead = inputStream.read(buffer, 0, requested)
            if (bytesRead == -1) {
                break
            }
            if (bytesRead == 0) {
                throw IOException("InputStream returned no data before reaching end of input")
            }
            total += bytesRead
            requireWithinLimit("maxModuleBytes", maxBytes, total)
            output.write(buffer, 0, bytesRead)
        }

        return output.readByteArray()
    }
}
