package link.hector.freellee.ble

import java.nio.charset.StandardCharsets

// Little/big-endian helpers shared by the protocol, codec and repository layers.
// OAP mixes endianness per field, so each accessor states its order explicitly.

internal fun ByteArray.beU16(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.beI32(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.beU32(offset: Int): Long = beI32(offset).toLong() and 0xFFFFFFFFL

internal fun ByteArray.leI32(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun putBeI32(dst: ByteArray, offset: Int, value: Int) {
    dst[offset] = (value ushr 24).toByte()
    dst[offset + 1] = (value ushr 16).toByte()
    dst[offset + 2] = (value ushr 8).toByte()
    dst[offset + 3] = value.toByte()
}

internal fun putLeI32(dst: ByteArray, offset: Int, value: Int) {
    dst[offset] = value.toByte()
    dst[offset + 1] = (value ushr 8).toByte()
    dst[offset + 2] = (value ushr 16).toByte()
    dst[offset + 3] = (value ushr 24).toByte()
}

/** Decode as ASCII and drop NUL padding (and surrounding whitespace). */
internal fun ByteArray.asciiTrimmed(): String =
    toString(StandardCharsets.US_ASCII).trimEnd('\u0000').trim()
