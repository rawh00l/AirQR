package com.example.airqr

import java.nio.ByteBuffer
import java.util.zip.CRC32

data class QRChunkFrame(
    val fileId: Int,
    val totalChunks: Short,
    val chunkIndex: Short,
    val payloadSize: Short,
    val checksum: Short,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as QRChunkFrame

        if (fileId != other.fileId) return false
        if (totalChunks != other.totalChunks) return false
        if (chunkIndex != other.chunkIndex) return false
        if (payloadSize != other.payloadSize) return false
        if (checksum != other.checksum) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = fileId
        result = 31 * result + totalChunks
        result = 31 * result + chunkIndex
        result = 31 * result + payloadSize
        result = 31 * result + checksum
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object DataEncoderDecoder {

    // Simple CRC16 CCITT
    fun crc16(bytes: ByteArray, length: Int): Short {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = (crc ushr 8) xor crc16Table[(crc xor bytes[i].toInt()) and 0xFF]
        }
        return (crc xor 0xFFFF).toShort()
    }

    private val crc16Table = IntArray(256)

    init {
        val poly = 0x1021
        for (i in 0 until 256) {
            var temp = i shl 8
            for (j in 0 until 8) {
                if ((temp and 0x8000) != 0) {
                    temp = (temp shl 1) xor poly
                } else {
                    temp = temp shl 1
                }
            }
            crc16Table[i] = temp and 0xFFFF
        }
    }

    fun encodeChunk(frame: QRChunkFrame): ByteArray {
        val buffer = ByteBuffer.allocate(12 + frame.payloadSize)
        buffer.putInt(frame.fileId)
        buffer.putShort(frame.totalChunks)
        buffer.putShort(frame.chunkIndex)
        buffer.putShort(frame.payloadSize)
        buffer.putShort(frame.checksum)
        buffer.put(frame.payload)
        return buffer.array()
    }

    fun decodeChunk(bytes: ByteArray): QRChunkFrame? {
        if (bytes.size < 12) return null
        try {
            val buffer = ByteBuffer.wrap(bytes)
            val fileId = buffer.int
            val totalChunks = buffer.short
            val chunkIndex = buffer.short
            val payloadSize = buffer.short
            val checksum = buffer.short

            if (payloadSize < 0 || buffer.remaining() < payloadSize) return null

            val payload = ByteArray(payloadSize.toInt())
            buffer.get(payload)

            val calculatedChecksum = crc16(payload, payload.size)
            if (calculatedChecksum != checksum) {
                return null
            }

            return QRChunkFrame(
                fileId = fileId,
                totalChunks = totalChunks,
                chunkIndex = chunkIndex,
                payloadSize = payloadSize,
                checksum = checksum,
                payload = payload
            )
        } catch (e: Exception) {
            return null
        }
    }
}
