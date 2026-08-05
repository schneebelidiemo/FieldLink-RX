package ch.fieldlink.rx.protocol

import ch.fieldlink.rx.model.DecodeMode

data class FieldLinkPacket(
    val mode: DecodeMode,
    val messageId: ByteArray,
    val index: Int,
    val count: Int,
    val payload: ByteArray,
)

object FieldLinkPacketCodec {
    private val magic = byteArrayOf('F'.code.toByte(), 'P'.code.toByte())
    private const val HEADER_LENGTH = 18
    private const val CRC_LENGTH = 4
    private const val VERSION = 1

    fun fixedBlockToPacket(block: ByteArray): FieldLinkPacket {
        require(block.size == FieldLinkFec.FIXED_BLOCK_LENGTH) { "Invalid FEC block length." }
        val packetLength = block.readU16(0)
        require(packetLength in (HEADER_LENGTH + CRC_LENGTH)..(FieldLinkFec.FIXED_BLOCK_LENGTH - 2)) {
            "Invalid FieldLink packet length."
        }
        return decode(block.copyOfRange(2, 2 + packetLength))
    }

    private fun decode(bytes: ByteArray): FieldLinkPacket {
        require(bytes.size >= HEADER_LENGTH + CRC_LENGTH) { "FieldLink packet is too short." }
        require(bytes[0] == magic[0] && bytes[1] == magic[1] && bytes[2].toInt() == VERSION) {
            "Unknown FieldLink packet format."
        }
        val payloadLength = bytes.readU16(16)
        val expectedLength = HEADER_LENGTH + payloadLength + CRC_LENGTH
        require(bytes.size == expectedLength) { "FieldLink packet length mismatch." }
        val content = bytes.copyOfRange(0, bytes.size - CRC_LENGTH)
        require(Crc32.calculate(content) == bytes.readU32(bytes.size - CRC_LENGTH)) {
            "FieldLink packet CRC failed."
        }
        val mode = when (bytes[3].toInt() and 0xff) {
            1 -> DecodeMode.FIELDLINK_FAST
            2 -> DecodeMode.FIELDLINK_WIDE
            else -> error("Unknown FieldLink transmission mode.")
        }
        val count = bytes.readU16(14)
        val index = bytes.readU16(12)
        require(count in 1..1024 && index < count) { "Invalid FieldLink packet index." }
        return FieldLinkPacket(
            mode = mode,
            messageId = bytes.copyOfRange(4, 12),
            index = index,
            count = count,
            payload = bytes.copyOfRange(HEADER_LENGTH, HEADER_LENGTH + payloadLength),
        )
    }
}

class FieldLinkPacketAssembler {
    private data class PendingMessage(
        val count: Int,
        val packets: MutableMap<Int, FieldLinkPacket>,
        var lastUpdateMillis: Long,
    )

    private val pending = mutableMapOf<String, PendingMessage>()

    fun add(packet: FieldLinkPacket, nowMillis: Long = System.currentTimeMillis()): ByteArray? {
        pending.entries.removeIf { nowMillis - it.value.lastUpdateMillis > 15 * 60_000L }
        val key = packet.messageId.hex()
        val message = pending.getOrPut(key) {
            PendingMessage(packet.count, mutableMapOf(), nowMillis)
        }
        if (message.count != packet.count) {
            pending.remove(key)
            return null
        }
        message.lastUpdateMillis = nowMillis
        message.packets[packet.index] = packet
        if (message.packets.size != message.count) return null

        val result = ArrayList<Byte>()
        for (index in 0 until message.count) {
            val part = message.packets[index] ?: return null
            part.payload.forEach(result::add)
        }
        pending.remove(key)
        return result.toByteArray()
    }
}

