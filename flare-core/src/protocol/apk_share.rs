//! APK sharing protocol for offline app distribution.
//!
//! Allows Flare to spread phone-to-phone without app stores or internet.
//! Uses `ApkOffer` and `ApkRequest` content types over the mesh.
//!
//! Flow:
//! 1. Device A broadcasts `ApkOffer` with APK metadata (version, size, hash)
//! 2. Device B receives the offer and sends `ApkRequest` to Device A
//! 3. Device A transfers the APK in chunks over BLE/Wi-Fi Direct
//! 4. Device B verifies the SHA-256 hash before installing

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Metadata about an APK available for sharing.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkOfferPayload {
    /// Application version code (e.g., 1, 2, 3).
    pub version_code: u32,
    /// Application version name (e.g., "1.0.0").
    pub version_name: String,
    /// Total APK size in bytes.
    pub apk_size: u64,
    /// SHA-256 hash of the complete APK file.
    pub apk_hash: [u8; 32],
    /// Number of chunks the APK will be split into.
    pub chunk_count: u32,
    /// Size of each chunk in bytes (last chunk may be smaller).
    pub chunk_size: u32,
}

/// A request for the APK from a peer.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkRequestPayload {
    /// The hash of the APK being requested (from the offer).
    pub apk_hash: [u8; 32],
    /// Starting chunk index (for resumable transfers).
    pub start_chunk: u32,
}

/// A single chunk of the APK file.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkChunk {
    /// The hash of the complete APK this chunk belongs to.
    pub apk_hash: [u8; 32],
    /// Zero-based chunk index.
    pub chunk_index: u32,
    /// Total number of chunks.
    pub total_chunks: u32,
    /// The chunk data.
    pub data: Vec<u8>,
}

/// Default chunk size: 16KB (safe for BLE + Wi-Fi Direct).
pub const DEFAULT_CHUNK_SIZE: u32 = 16384;

impl ApkOfferPayload {
    /// Creates an offer payload from APK file bytes.
    pub fn from_apk_bytes(apk_bytes: &[u8], version_code: u32, version_name: &str) -> Self {
        let mut hasher = Sha256::new();
        hasher.update(apk_bytes);
        let apk_hash: [u8; 32] = hasher.finalize().into();

        let apk_size = apk_bytes.len() as u64;
        let chunk_count = apk_size.div_ceil(DEFAULT_CHUNK_SIZE as u64) as u32;

        ApkOfferPayload {
            version_code,
            version_name: version_name.to_string(),
            apk_size,
            apk_hash,
            chunk_count,
            chunk_size: DEFAULT_CHUNK_SIZE,
        }
    }

    /// Serializes the offer payload for inclusion in a mesh message.
    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    /// Deserializes an offer payload from mesh message bytes.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }
}

impl ApkRequestPayload {
    /// Creates a request for an APK from its offer.
    pub fn new(apk_hash: [u8; 32], start_chunk: u32) -> Self {
        ApkRequestPayload {
            apk_hash,
            start_chunk,
        }
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }
}

impl ApkChunk {
    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }
}

/// Splits APK bytes into chunks for transmission.
pub fn split_apk_into_chunks(apk_bytes: &[u8], apk_hash: [u8; 32]) -> Vec<ApkChunk> {
    let total_chunks = (apk_bytes.len() as u64).div_ceil(DEFAULT_CHUNK_SIZE as u64) as u32;

    apk_bytes
        .chunks(DEFAULT_CHUNK_SIZE as usize)
        .enumerate()
        .map(|(i, chunk_data)| ApkChunk {
            apk_hash,
            chunk_index: i as u32,
            total_chunks,
            data: chunk_data.to_vec(),
        })
        .collect()
}

/// Verifies the integrity of a reassembled APK against its expected hash.
pub fn verify_apk_hash(apk_bytes: &[u8], expected_hash: &[u8; 32]) -> bool {
    let mut hasher = Sha256::new();
    hasher.update(apk_bytes);
    let actual_hash: [u8; 32] = hasher.finalize().into();
    actual_hash == *expected_hash
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_apk_offer_roundtrip() {
        let fake_apk: Vec<u8> = [0xDE, 0xAD, 0xBE, 0xEF]
            .iter()
            .copied()
            .cycle()
            .take(4096)
            .collect();
        let offer = ApkOfferPayload::from_apk_bytes(&fake_apk, 1, "1.0.0");

        assert_eq!(offer.version_code, 1);
        assert_eq!(offer.apk_size, 4096);
        assert_eq!(offer.chunk_count, 1); // 4096 < 16384

        let bytes = offer.to_bytes().unwrap();
        let restored = ApkOfferPayload::from_bytes(&bytes).unwrap();
        assert_eq!(restored.apk_hash, offer.apk_hash);
        assert_eq!(restored.version_name, "1.0.0");
    }

    #[test]
    fn test_apk_request_roundtrip() {
        let hash = [0xAA; 32];
        let request = ApkRequestPayload::new(hash, 5);

        let bytes = request.to_bytes().unwrap();
        let restored = ApkRequestPayload::from_bytes(&bytes).unwrap();
        assert_eq!(restored.apk_hash, hash);
        assert_eq!(restored.start_chunk, 5);
    }

    #[test]
    fn test_split_and_verify() {
        // Create a fake APK larger than one chunk
        let fake_apk: Vec<u8> = (0..40000u32).map(|i| (i % 256) as u8).collect();
        let offer = ApkOfferPayload::from_apk_bytes(&fake_apk, 2, "2.0.0");

        let chunks = split_apk_into_chunks(&fake_apk, offer.apk_hash);
        assert_eq!(chunks.len(), 3); // 40000 / 16384 = 2.44 → 3 chunks
        assert_eq!(chunks[0].chunk_index, 0);
        assert_eq!(chunks[2].chunk_index, 2);

        // Reassemble
        let mut reassembled = Vec::new();
        for chunk in &chunks {
            reassembled.extend_from_slice(&chunk.data);
        }

        assert_eq!(reassembled, fake_apk);
        assert!(verify_apk_hash(&reassembled, &offer.apk_hash));
    }

    #[test]
    fn test_verify_apk_hash_mismatch() {
        let data = b"some apk data";
        let wrong_hash = [0xFF; 32];
        assert!(!verify_apk_hash(data, &wrong_hash));
    }

    #[test]
    fn test_chunk_roundtrip() {
        let chunk = ApkChunk {
            apk_hash: [0x42; 32],
            chunk_index: 7,
            total_chunks: 10,
            data: vec![1, 2, 3, 4, 5],
        };

        let bytes = chunk.to_bytes().unwrap();
        let restored = ApkChunk::from_bytes(&bytes).unwrap();
        assert_eq!(restored.chunk_index, 7);
        assert_eq!(restored.total_chunks, 10);
        assert_eq!(restored.data, vec![1, 2, 3, 4, 5]);
    }
}
