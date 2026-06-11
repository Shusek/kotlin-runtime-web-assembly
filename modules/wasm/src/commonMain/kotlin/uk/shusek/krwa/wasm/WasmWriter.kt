package uk.shusek.krwa.wasm

import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import uk.shusek.krwa.wasm.types.RawSection
import uk.shusek.krwa.wasm.types.WasmJvmStatic

class WasmWriter {
    private val out = Buffer()

    init {
        out.write(WasmParser.MAGIC_BYTES)
        out.write(WasmParser.VERSION_BYTES)
    }

    fun writeSection(section: RawSection) {
        writeSection(section.sectionId(), section.contents())
    }

    fun writeSection(sectionId: Int, contents: ByteArray) {
        out.writeByte(sectionId.toByte())
        writeVarUInt32(out, contents.size)
        out.write(contents)
    }

    fun bytes(): ByteArray = out.copy().readByteArray()

    companion object {
        @WasmJvmStatic
        fun writeVarUInt32(out: Sink, value: Int) {
            var x = value.toUInt().toLong()
            while (true) {
                if (x < 0x80) {
                    out.writeByte(x.toByte())
                    break
                }
                out.writeByte(((x and 0x7F) or 0x80).toByte())
                x = x shr 7
            }
        }
    }
}
