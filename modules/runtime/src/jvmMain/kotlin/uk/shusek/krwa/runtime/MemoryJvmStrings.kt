package uk.shusek.krwa.runtime

import java.nio.charset.Charset

fun Memory.writeString(offset: Int, data: String, charSet: Charset) {
    write(offset, data.toByteArray(charSet))
}

fun Memory.readString(addr: Int, len: Int, charSet: Charset): String =
    String(readBytes(addr, len), charSet)

fun Memory.writeCString(offset: Int, str: String, charSet: Charset) {
    writeString(offset, "$str\u0000", charSet)
}

fun Memory.readCString(addr: Int, charSet: Charset): String {
    var current = addr
    while (read(current).toInt() != 0) {
        current++
    }
    return String(readBytes(addr, current - addr), charSet)
}
