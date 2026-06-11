package uk.shusek.krwa.wasi

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.util.ArrayList
import java.util.TreeSet
import okio.Buffer
import okio.Sink
import okio.Source
import okio.sink
import okio.source

class Descriptors {
    private val descriptors: MutableList<Descriptor?> = ArrayList()
    private val freeFds = TreeSet<Int>()

    fun get(fd: Int): Descriptor? =
        if (fd < 0 || fd >= descriptors.size) {
            null
        } else {
            descriptors[fd]
        }

    fun allocate(descriptor: Descriptor): Int {
        val fd = freeFds.pollFirst()
        if (fd != null) {
            descriptors[fd] = descriptor
            return fd
        }
        descriptors.add(descriptor)
        return descriptors.size - 1
    }

    fun free(fd: Int) {
        descriptors[fd] = null
        freeFds.add(fd)
    }

    fun set(fd: Int, descriptor: Descriptor) {
        descriptors[fd] = descriptor
    }

    fun closeAll() {
        var exception: RuntimeException? = null
        for (descriptor in descriptors) {
            try {
                if (descriptor is Closeable) {
                    descriptor.close()
                }
            } catch (t: Throwable) {
                if (exception == null) {
                    exception = RuntimeException()
                }
                exception.addSuppressed(t)
            }
        }
        exception?.let { throw it }
    }

    interface Descriptor

    interface DataReader {
        @Throws(IOException::class) fun read(data: ByteArray): Int
    }

    interface DataWriter {
        @Throws(IOException::class) fun write(data: ByteArray): Int
    }

    interface Directory {
        fun path(): Path
    }

    class InStream
    @JvmOverloads
    constructor(
        private val input: Source,
        private val tty: Boolean = true,
        private val available: (() -> Int)? = null,
    ) : Descriptor, DataReader {
        @JvmOverloads
        constructor(
            input: InputStream,
            tty: Boolean = true,
        ) : this(input.source(), tty, { input.available() })

        fun isTty(): Boolean = tty

        @Throws(IOException::class)
        override fun read(data: ByteArray): Int {
            if (data.isEmpty()) {
                return 0
            }
            val buffer = Buffer()
            val read = input.read(buffer, data.size.toLong())
            if (read < 0) {
                return -1
            }
            return buffer.read(data, 0, read.toInt())
        }

        @Throws(IOException::class) fun available(): Int = available?.invoke() ?: 0
    }

    class OutStream
    @JvmOverloads
    constructor(private val output: Sink, private val tty: Boolean = true) :
        Descriptor, DataWriter {
        @JvmOverloads
        constructor(output: OutputStream, tty: Boolean = true) : this(output.sink(), tty)

        fun isTty(): Boolean = tty

        @Throws(IOException::class)
        override fun write(data: ByteArray): Int {
            output.write(Buffer().write(data), data.size.toLong())
            return data.size
        }
    }

    class PreopenedDirectory(private val name: ByteArray, private val path: Path) :
        Descriptor, Directory {
        fun name(): ByteArray = name

        override fun path(): Path = path
    }

    class OpenDirectory(private val path: Path) : Descriptor, Directory {
        override fun path(): Path = path
    }

    class OpenFile(
        private val path: Path,
        private val channel: FileChannel,
        private val fdFlags: Int,
        private val rights: Long,
    ) : Descriptor, Closeable, DataReader, DataWriter {
        fun path(): Path = path

        fun channel(): FileChannel = channel

        fun fdFlags(): Int = fdFlags

        fun rights(): Long = rights

        @Throws(IOException::class)
        override fun read(data: ByteArray): Int = channel.read(ByteBuffer.wrap(data))

        @Throws(IOException::class)
        fun read(data: ByteArray, position: Long): Int =
            channel.read(ByteBuffer.wrap(data), position)

        @Throws(IOException::class)
        override fun write(data: ByteArray): Int = channel.write(ByteBuffer.wrap(data))

        @Throws(IOException::class)
        fun write(data: ByteArray, position: Long): Int =
            channel.write(ByteBuffer.wrap(data), position)

        @Throws(IOException::class)
        override fun close() {
            channel.close()
        }
    }
}
