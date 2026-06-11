package uk.shusek.krwa.wasi

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Objects
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource

/** Provides null stream utilities compatible with Android API levels below 33. */
class IO private constructor() {
    companion object {
        @JvmStatic
        fun nullOutputStream(): OutputStream =
            object : OutputStream() {
                @Volatile private var closed = false

                @Throws(IOException::class)
                private fun ensureOpen() {
                    if (closed) {
                        throw IOException("Stream closed")
                    }
                }

                @Throws(IOException::class)
                override fun write(b: Int) {
                    ensureOpen()
                }

                @Throws(IOException::class)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    Objects.checkFromIndexSize(off, len, b.size)
                    ensureOpen()
                }

                override fun close() {
                    closed = true
                }
            }

        @JvmStatic
        fun nullSink(): RawSink =
            object : RawSink {
                @Volatile private var closed = false

                @Throws(IOException::class)
                private fun ensureOpen() {
                    if (closed) {
                        throw IOException("Stream closed")
                    }
                }

                @Throws(IOException::class)
                override fun write(source: Buffer, byteCount: Long) {
                    ensureOpen()
                    source.skip(byteCount)
                }

                @Throws(IOException::class)
                override fun flush() {
                    ensureOpen()
                }

                override fun close() {
                    closed = true
                }
            }

        @JvmStatic
        fun nullInputStream(): InputStream =
            object : InputStream() {
                @Volatile private var closed = false

                @Throws(IOException::class)
                private fun ensureOpen() {
                    if (closed) {
                        throw IOException("Stream closed")
                    }
                }

                @Throws(IOException::class)
                override fun available(): Int {
                    ensureOpen()
                    return 0
                }

                @Throws(IOException::class)
                override fun read(): Int {
                    ensureOpen()
                    return -1
                }

                @Throws(IOException::class)
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    Objects.checkFromIndexSize(off, len, b.size)
                    if (len == 0) {
                        return 0
                    }
                    ensureOpen()
                    return -1
                }

                @Throws(IOException::class)
                override fun readAllBytes(): ByteArray {
                    ensureOpen()
                    return ByteArray(0)
                }

                @Throws(IOException::class)
                override fun readNBytes(b: ByteArray, off: Int, len: Int): Int {
                    Objects.checkFromIndexSize(off, len, b.size)
                    ensureOpen()
                    return 0
                }

                @Throws(IOException::class)
                override fun readNBytes(len: Int): ByteArray {
                    if (len < 0) {
                        throw IllegalArgumentException("len < 0")
                    }
                    ensureOpen()
                    return ByteArray(0)
                }

                @Throws(IOException::class)
                override fun skip(n: Long): Long {
                    ensureOpen()
                    return 0L
                }

                @Throws(IOException::class)
                override fun transferTo(out: OutputStream): Long {
                    Objects.requireNonNull(out)
                    ensureOpen()
                    return 0L
                }

                override fun close() {
                    closed = true
                }
            }

        @JvmStatic
        fun nullSource(): RawSource =
            object : RawSource {
                @Volatile private var closed = false

                @Throws(IOException::class)
                private fun ensureOpen() {
                    if (closed) {
                        throw IOException("Stream closed")
                    }
                }

                @Throws(IOException::class)
                override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                    ensureOpen()
                    return -1L
                }

                override fun close() {
                    closed = true
                }
            }
    }
}
