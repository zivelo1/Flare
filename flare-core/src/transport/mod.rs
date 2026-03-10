//! Transport abstraction layer.
//!
//! Provides a unified interface for sending/receiving data regardless
//! of the underlying transport (BLE, Wi-Fi Direct, Multipeer Connectivity).
//! The routing and messaging layers use this abstraction and never
//! interact with platform-specific Bluetooth/Wi-Fi APIs directly.

pub mod compression;
pub mod size_tiers;
pub mod wifi_direct;

use crate::crypto::identity::DeviceId;
use crate::routing::peer_table::TransportType;

/// Errors from transport operations.
#[derive(Debug, thiserror::Error)]
pub enum TransportError {
    #[error("Connection failed to peer {0}")]
    ConnectionFailed(String),

    #[error("Send failed: {0}")]
    SendFailed(String),

    #[error("Peer not found: {0}")]
    PeerNotFound(String),

    #[error("Transport not available: {0:?}")]
    NotAvailable(TransportType),

    #[error("Payload too large: {size} bytes exceeds max {max} bytes")]
    PayloadTooLarge { size: usize, max: usize },
}

/// Events emitted by the transport layer.
#[derive(Debug, Clone)]
pub enum TransportEvent {
    /// A new peer was discovered.
    PeerDiscovered {
        device_id: DeviceId,
        transport: TransportType,
        rssi: Option<i16>,
    },

    /// A peer connection was established.
    PeerConnected {
        device_id: DeviceId,
        transport: TransportType,
    },

    /// A peer disconnected.
    PeerDisconnected { device_id: DeviceId },

    /// Data received from a peer.
    DataReceived {
        from_device_id: DeviceId,
        data: Vec<u8>,
    },

    /// Data was successfully sent to a peer.
    DataSent {
        to_device_id: DeviceId,
        message_id: [u8; 32],
    },

    /// Transport became available or unavailable.
    TransportStateChanged {
        transport: TransportType,
        available: bool,
    },
}

/// Chunk header for splitting large messages across BLE MTU boundaries.
///
/// BLE GATT has limited MTU (typically 247-512 bytes after negotiation).
/// Messages larger than the MTU are split into chunks with this header.
#[derive(Debug, Clone)]
pub struct ChunkHeader {
    /// Short message ID for reassembly (first 2 bytes of full message_id).
    pub message_id_short: u16,
    /// Index of this chunk (0-based).
    pub chunk_index: u8,
    /// Total number of chunks.
    pub total_chunks: u8,
}

impl ChunkHeader {
    pub const SIZE: usize = 4;

    pub fn to_bytes(&self) -> [u8; Self::SIZE] {
        let mut bytes = [0u8; Self::SIZE];
        bytes[0..2].copy_from_slice(&self.message_id_short.to_le_bytes());
        bytes[2] = self.chunk_index;
        bytes[3] = self.total_chunks;
        bytes
    }

    pub fn from_bytes(bytes: &[u8; Self::SIZE]) -> Self {
        ChunkHeader {
            message_id_short: u16::from_le_bytes([bytes[0], bytes[1]]),
            chunk_index: bytes[2],
            total_chunks: bytes[3],
        }
    }
}

/// Splits a payload into chunks that fit within the given MTU.
///
/// Each chunk includes a 4-byte header for reassembly.
pub fn chunk_payload(
    message_id: &[u8; 32],
    payload: &[u8],
    mtu: usize,
) -> Result<Vec<Vec<u8>>, TransportError> {
    let chunk_data_size = mtu - ChunkHeader::SIZE;
    if chunk_data_size == 0 {
        return Err(TransportError::PayloadTooLarge {
            size: payload.len(),
            max: 0,
        });
    }

    let total_chunks = payload.len().div_ceil(chunk_data_size);
    if total_chunks > 255 {
        return Err(TransportError::PayloadTooLarge {
            size: payload.len(),
            max: 255 * chunk_data_size,
        });
    }

    // Use first 2 bytes of message_id as short ID
    let message_id_short = u16::from_le_bytes([message_id[0], message_id[1]]);

    let mut chunks = Vec::with_capacity(total_chunks);
    for (i, chunk_data) in payload.chunks(chunk_data_size).enumerate() {
        let header = ChunkHeader {
            message_id_short,
            chunk_index: i as u8,
            total_chunks: total_chunks as u8,
        };

        let mut chunk = Vec::with_capacity(ChunkHeader::SIZE + chunk_data.len());
        chunk.extend_from_slice(&header.to_bytes());
        chunk.extend_from_slice(chunk_data);
        chunks.push(chunk);
    }

    Ok(chunks)
}

/// Reassembles chunks into a complete payload.
///
/// Returns `None` if not all chunks have been received yet.
pub fn reassemble_chunks(chunks: &[Vec<u8>]) -> Option<Vec<u8>> {
    if chunks.is_empty() {
        return None;
    }

    // Parse first chunk to get total count
    if chunks[0].len() < ChunkHeader::SIZE {
        return None;
    }

    let header_bytes: [u8; ChunkHeader::SIZE] = chunks[0][..ChunkHeader::SIZE].try_into().ok()?;
    let first_header = ChunkHeader::from_bytes(&header_bytes);

    if chunks.len() != first_header.total_chunks as usize {
        return None; // Not all chunks received
    }

    // Sort by chunk_index and reassemble
    let mut sorted: Vec<(u8, &[u8])> = Vec::new();
    for chunk in chunks {
        if chunk.len() < ChunkHeader::SIZE {
            return None;
        }
        let header_bytes: [u8; ChunkHeader::SIZE] = chunk[..ChunkHeader::SIZE].try_into().ok()?;
        let header = ChunkHeader::from_bytes(&header_bytes);
        sorted.push((header.chunk_index, &chunk[ChunkHeader::SIZE..]));
    }
    sorted.sort_by_key(|(idx, _)| *idx);

    let mut payload = Vec::new();
    for (_, data) in sorted {
        payload.extend_from_slice(data);
    }

    Some(payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_chunk_and_reassemble_small() {
        let msg_id = [0u8; 32];
        let payload = b"Hello mesh!";
        let mtu = 247; // Typical BLE MTU

        let chunks = chunk_payload(&msg_id, payload, mtu).unwrap();
        assert_eq!(chunks.len(), 1); // Small enough for one chunk

        let reassembled = reassemble_chunks(&chunks).unwrap();
        assert_eq!(reassembled, payload);
    }

    #[test]
    fn test_chunk_and_reassemble_large() {
        let msg_id = [42u8; 32];
        let payload = vec![0xAB; 1000]; // Larger than one MTU
        let mtu = 247;

        let chunks = chunk_payload(&msg_id, &payload, mtu).unwrap();
        assert!(chunks.len() > 1);

        let reassembled = reassemble_chunks(&chunks).unwrap();
        assert_eq!(reassembled, payload);
    }

    #[test]
    fn test_incomplete_reassembly_returns_none() {
        let msg_id = [42u8; 32];
        let payload = vec![0xAB; 1000];
        let mtu = 247;

        let chunks = chunk_payload(&msg_id, &payload, mtu).unwrap();

        // Only provide first chunk
        let incomplete = reassemble_chunks(&chunks[..1]);
        assert!(incomplete.is_none());
    }

    #[test]
    fn test_chunk_header_roundtrip() {
        let header = ChunkHeader {
            message_id_short: 0x1234,
            chunk_index: 3,
            total_chunks: 7,
        };

        let bytes = header.to_bytes();
        let restored = ChunkHeader::from_bytes(&bytes);

        assert_eq!(header.message_id_short, restored.message_id_short);
        assert_eq!(header.chunk_index, restored.chunk_index);
        assert_eq!(header.total_chunks, restored.total_chunks);
    }

    #[test]
    fn test_empty_payload() {
        let msg_id = [0u8; 32];
        let chunks = chunk_payload(&msg_id, b"", 247).unwrap();
        // Empty payload should produce zero chunks since there's nothing to split
        // Actually, chunks(0) on empty slice produces no iterations
        assert!(chunks.is_empty() || chunks.len() == 1);
    }
}
