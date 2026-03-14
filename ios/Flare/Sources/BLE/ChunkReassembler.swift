import Foundation
import os.log

/// Thread-safe reassembly buffer for incoming BLE chunks.
///
/// Handles both chunked packets (with 0xF1 magic header) and raw
/// non-chunked data (backward compatibility with pre-chunking clients).
///
/// Create one instance per receive direction.
final class ChunkReassembler: @unchecked Sendable {

    private let logger = Logger(subsystem: "com.flare.mesh", category: "ChunkReassembler")
    private let lock = NSLock()
    private var buffers: [UInt16: ReassemblyState] = [:]

    private struct ReassemblyState {
        let totalChunks: Int
        var chunks: [Data?]
        var receivedCount: Int = 0
        let createdAt: Date = Date()

        init(totalChunks: Int) {
            self.totalChunks = totalChunks
            self.chunks = Array(repeating: nil, count: totalChunks)
        }
    }

    /// Processes incoming BLE data.
    ///
    /// - If the data is a chunked packet, accumulates it and returns the complete
    ///   reassembled payload when all chunks arrive (or nil if still waiting).
    /// - If the data is NOT chunked (no magic byte), returns it immediately.
    func onDataReceived(_ data: Data) -> Data? {
        guard BleChunker.isChunked(data) else {
            return data // Raw (non-chunked) — pass through
        }

        guard data.count >= Constants.bleChunkHeaderSize else { return nil }

        let msgId = (UInt16(data[data.startIndex + 1]) << 8) | UInt16(data[data.startIndex + 2])
        let chunkIndex = Int(data[data.startIndex + 3])
        let totalChunks = Int(data[data.startIndex + 4])

        guard totalChunks > 0, chunkIndex < totalChunks else {
            logger.warning("Invalid chunk header: index=\(chunkIndex) total=\(totalChunks)")
            return nil
        }

        let payload = data.suffix(from: data.startIndex + Constants.bleChunkHeaderSize)

        // Single-chunk message — return payload immediately
        if totalChunks == 1 {
            return Data(payload)
        }

        lock.lock()
        defer { lock.unlock() }

        if buffers[msgId] == nil {
            buffers[msgId] = ReassemblyState(totalChunks: totalChunks)
        }

        guard var state = buffers[msgId] else { return nil }

        if state.totalChunks != totalChunks {
            logger.warning("Chunk total mismatch for msgId \(String(format: "%04x", msgId)): expected \(state.totalChunks), got \(totalChunks)")
            return nil
        }

        // Duplicate chunk
        guard state.chunks[chunkIndex] == nil else { return nil }

        state.chunks[chunkIndex] = Data(payload)
        state.receivedCount += 1
        buffers[msgId] = state

        logger.debug("Chunk \(chunkIndex + 1)/\(totalChunks) received for msgId \(String(format: "%04x", msgId))")

        if state.receivedCount == totalChunks {
            buffers.removeValue(forKey: msgId)

            var result = Data()
            for chunk in state.chunks {
                if let chunk = chunk {
                    result.append(chunk)
                }
            }
            logger.info("Reassembled \(totalChunks) chunks into \(result.count) bytes (msgId \(String(format: "%04x", msgId)))")
            return result
        }

        return nil
    }

    /// Removes incomplete reassembly buffers older than the timeout.
    /// Call periodically to prevent memory leaks from lost chunks.
    func pruneStale() {
        let cutoff = Date().addingTimeInterval(-Constants.bleChunkReassemblyTimeoutSeconds)
        lock.lock()
        buffers = buffers.filter { $0.value.createdAt > cutoff }
        lock.unlock()
    }
}
