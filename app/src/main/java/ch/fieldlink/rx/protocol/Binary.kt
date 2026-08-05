package ch.fieldlink.rx.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun ByteArray.readU16(offset: Int): Int =
    ByteBuffer.wrap(this, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff

internal fun ByteArray.readU32(offset: Int): Long =
    ByteBuffer.wrap(this, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffff_ffffL

internal fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var difference = 0
    for (index in left.indices) difference = difference or (left[index].toInt() xor right[index].toInt())
    return difference == 0
}

