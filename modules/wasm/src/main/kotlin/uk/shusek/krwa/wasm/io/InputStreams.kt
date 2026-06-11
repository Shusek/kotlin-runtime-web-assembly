package uk.shusek.krwa.wasm.io

import java.io.IOException
import java.io.InputStream
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

object InputStreams {
    @JvmStatic
    @Throws(IOException::class)
    fun readAllBytes(inputStream: InputStream?): ByteArray {
        require(inputStream != null) { "InputStream cannot be null" }

        val bufferLength = 1024
        val buffer = ByteArray(bufferLength)
        val output = Buffer()

        while (true) {
            val bytesRead = inputStream.read(buffer, 0, bufferLength)
            if (bytesRead == -1) {
                break
            }
            output.write(buffer, 0, bytesRead)
        }

        return output.readByteArray()
    }
}
