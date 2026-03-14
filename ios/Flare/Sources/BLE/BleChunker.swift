import Foundation
import os.log

/// Handles chunking of BLE messages that exceed MTU.
///
/// Messages are split into chunks with a 5-byte header:
///   [0]   = MAGIC (0xF1) — distinguishes chunked from raw data
///   [1-2] = msg_id (16-bit, big-endian) — groups chunks of the same message
///   [3]   = chunk_index (0-based)
///   [4]   = total_chunks (1-based, max 255)
///   [5..] = payload data
///
/// Protocol is identical to Android's BleChunker for cross-platform interop.
final class BleChunker: @unchecked Sendable {
    static let shared = BleChunker()

    private let logger = Logger(subsystem: "com.flare.mesh", category: "BleChunker")
    private let lock = NSLock()
    private var nextMsgId: UInt16

    private init() {
        // Seed from system uptime to avoid collisions across app launches
        let seed = UInt16(truncatingIfNeeded: UInt64(ProcessInfo.processInfo.systemUptime * 1_000_000))
        nextMsgId = seed
    }

    /// Splits `data` into chunks that each fit within `mtu` bytes (including header).
    /// Returns an empty array if the message exceeds the maximum chunk count.
    func chunk(_ data: Data, mtu: Int) -> [Data] {
        let usableMtu = max(mtu, Constants.bleChunkHeaderSize + 1)
        let chunkDataSize = usableMtu - Constants.bleChunkHeaderSize
        let totalChunks = data.isEmpty ? 1 : (data.count + chunkDataSize - 1) / chunkDataSize

        if totalChunks > Constants.bleChunkMaxCount {
            logger.error("Message too large for BLE chunking: \(data.count) bytes, \(totalChunks) chunks needed (max \(Constants.bleChunkMaxCount))")
            return []
        }

        let msgId = nextMessageId()

        if data.isEmpty {
            return [buildPacket(msgId: msgId, index: 0, total: 1, payload: Data())]
        }

        return (0..<totalChunks).map { i in
            let start = i * chunkDataSize
            let end = min(start + chunkDataSize, data.count)
            let payload = data[data.startIndex.advanced(by: start)..<data.startIndex.advanced(by: end)]
            return buildPacket(msgId: msgId, index: i, total: totalChunks, payload: Data(payload))
        }
    }

    /// Returns true if `data` is a chunked packet (starts with magic byte and has valid header).
    static func isChunked(_ data: Data) -> Bool {
        data.count >= Constants.bleChunkHeaderSize && data[data.startIndex] == Constants.bleChunkMagic
    }

    // MARK: - Private

    private func nextMessageId() -> UInt16 {
        lock.lock()
        defer { lock.unlock() }
        let id = nextMsgId
        nextMsgId &+= 1
        return id
    }

    private func buildPacket(msgId: UInt16, index: Int, total: Int, payload: Data) -> Data {
        var packet = Data(capacity: Constants.bleChunkHeaderSize + payload.count)
        packet.append(Constants.bleChunkMagic)
        packet.append(UInt8(msgId >> 8))        // msg_id high byte
        packet.append(UInt8(msgId & 0xFF))      // msg_id low byte
        packet.append(UInt8(index))              // chunk_index
        packet.append(UInt8(total))              // total_chunks
        packet.append(payload)
        return packet
    }
}
