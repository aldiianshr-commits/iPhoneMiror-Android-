package com.aldiianshr.iphonemiror.core

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        out[i * 2] = HEX[(b.toInt() shr 4) and 0xF]
        out[i * 2 + 1] = HEX[b.toInt() and 0xF]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    val clean = filterNot { it == ':' || it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Formats 6 bytes as an upper-case MAC-style identifier (AA:BB:CC:DD:EE:FF). */
fun ByteArray.toMacString(): String = joinToString(":") { "%02X".format(it.toInt() and 0xFF) }

fun ByteArray.readIntLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

fun ByteArray.readUShortLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

fun ByteArray.readUShortBE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

fun ByteArray.readUIntBE(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) shl 24) or
        ((this[offset + 1].toLong() and 0xFF) shl 16) or
        ((this[offset + 2].toLong() and 0xFF) shl 8) or
        (this[offset + 3].toLong() and 0xFF)

fun ByteArray.readLongLE(offset: Int): Long {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (this[offset + i].toLong() and 0xFF)
    return v
}
