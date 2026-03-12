package com.flare.mesh.ble

import com.flare.mesh.util.Constants
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles chunking and reassembly of BLE messages that exceed MTU.
 *
 * Messages are split into chunks with a 5-byte header:
 *   [0]   = MAGIC (0xF1) — distinguishes chunked from raw data
 *   [1-2] = msg_id (16-bit, big-endian) — groups chunks of the same message
 *   [3]   = chunk_index (0-based)
 *   [4]   = total_chunks (1-based, max 255)
 *   [5..] = payload data
 *
 * Non-chunked messages (from older clients) lack the magic byte and pass through.
 */
object BleChunker {

    const val HEADER_SIZE = 5
    private const val MAGIC: Byte = 0xF1.toByte()

    /** Incrementing ID for outbound chunk groups. Thread-safe via synchronized. */
    private var nextMsgId = (System.nanoTime() and 0xFFFFL).toInt()

    /**
     * Splits [data] into chunks that each fit within [mtu] bytes (including header).
     * Returns an empty list if the message exceeds the maximum chunk count.
     */
    fun chunk(data: ByteArray, mtu: Int): List<ByteArray> {
        val usableMtu = mtu.coerceAtLeast(HEADER_SIZE + 1)
        val chunkDataSize = usableMtu - HEADER_SIZE
        val totalChunks = if (data.isEmpty()) 1 else (data.size + chunkDataSize - 1) / chunkDataSize

        if (totalChunks > Constants.BLE_CHUNK_MAX_COUNT) {
            Timber.e(
                "Message too large for BLE chunking: %d bytes, %d chunks needed (max %d)",
                data.size, totalChunks, Constants.BLE_CHUNK_MAX_COUNT,
            )
            return emptyList()
        }

        val msgId = synchronized(this) { nextMsgId++ and 0xFFFF }

        if (data.isEmpty()) {
            return listOf(buildPacket(msgId, 0, 1, ByteArray(0)))
        }

        return (0 until totalChunks).map { i ->
            val start = i * chunkDataSize
            val end = minOf(start + chunkDataSize, data.size)
            buildPacket(msgId, i, totalChunks, data.copyOfRange(start, end))
        }
    }

    /**
     * Returns true if [data] is a chunked packet (starts with the magic byte
     * and has a valid header).
     */
    fun isChunked(data: ByteArray): Boolean =
        data.size >= HEADER_SIZE && data[0] == MAGIC

    private fun buildPacket(msgId: Int, index: Int, total: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(HEADER_SIZE + payload.size)
        packet[0] = MAGIC
        packet[1] = (msgId shr 8).toByte()
        packet[2] = (msgId and 0xFF).toByte()
        packet[3] = index.toByte()
        packet[4] = total.toByte()
        System.arraycopy(payload, 0, packet, HEADER_SIZE, payload.size)
        return packet
    }
}

/**
 * Thread-safe reassembly buffer for incoming BLE chunks.
 * Create one instance per receive direction (GATT server receives, GATT client receives).
 */
class ChunkReassembler {

    private class ReassemblyState(
        val totalChunks: Int,
    ) {
        val chunks: Array<ByteArray?> = arrayOfNulls(totalChunks)
        var receivedCount: Int = 0
        val createdAt: Long = System.currentTimeMillis()
    }

    private val buffers = ConcurrentHashMap<Int, ReassemblyState>()

    /**
     * Processes incoming BLE data.
     *
     * - If the data is a chunked packet, accumulates it and returns the complete
     *   reassembled payload when all chunks arrive (or null if still waiting).
     * - If the data is NOT chunked (no magic byte), returns it immediately
     *   for backward compatibility with pre-chunking clients.
     */
    fun onDataReceived(data: ByteArray): ByteArray? {
        if (!BleChunker.isChunked(data)) {
            return data // Raw (non-chunked) — pass through
        }

        if (data.size < BleChunker.HEADER_SIZE) return null

        val msgId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val chunkIndex = data[3].toInt() and 0xFF
        val totalChunks = data[4].toInt() and 0xFF

        if (totalChunks == 0 || chunkIndex >= totalChunks) {
            Timber.w("Invalid chunk header: index=%d total=%d", chunkIndex, totalChunks)
            return null
        }

        // Single-chunk message — return payload immediately
        if (totalChunks == 1) {
            return data.copyOfRange(BleChunker.HEADER_SIZE, data.size)
        }

        val state = buffers.getOrPut(msgId) { ReassemblyState(totalChunks) }

        if (state.totalChunks != totalChunks) {
            Timber.w(
                "Chunk total mismatch for msgId %04x: expected %d, got %d",
                msgId, state.totalChunks, totalChunks,
            )
            return null
        }

        if (state.chunks[chunkIndex] != null) {
            return null // Duplicate chunk
        }

        state.chunks[chunkIndex] = data.copyOfRange(BleChunker.HEADER_SIZE, data.size)
        state.receivedCount++

        Timber.d("Chunk %d/%d received for msgId %04x", chunkIndex + 1, totalChunks, msgId)

        if (state.receivedCount == totalChunks) {
            buffers.remove(msgId)
            val totalSize = state.chunks.sumOf { it?.size ?: 0 }
            val result = ByteArray(totalSize)
            var offset = 0
            for (chunk in state.chunks) {
                chunk?.let {
                    System.arraycopy(it, 0, result, offset, it.size)
                    offset += it.size
                }
            }
            Timber.i(
                "Reassembled %d chunks into %d bytes (msgId %04x)",
                totalChunks, totalSize, msgId,
            )
            return result
        }

        return null // Still waiting for more chunks
    }

    /**
     * Removes incomplete reassembly buffers older than [timeoutMs].
     * Call periodically to prevent memory leaks from lost chunks.
     */
    fun pruneStale(timeoutMs: Long = Constants.BLE_CHUNK_REASSEMBLY_TIMEOUT_MS) {
        val now = System.currentTimeMillis()
        buffers.entries.removeIf { (_, state) -> now - state.createdAt > timeoutMs }
    }
}
