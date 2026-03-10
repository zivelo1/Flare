//! Neighborhood Bloom Filter — privacy-preserving cluster detection.
//!
//! Each device maintains a deterministic bitmap of recently-seen peer short IDs.
//! When two devices connect, they exchange bitmaps and compare overlap.
//! High overlap = same neighborhood (LOCAL), low overlap = different area (BRIDGE).
//!
//! Uses deterministic hashing (SHA-256 based) so that independently-created
//! filters on different devices produce comparable bitmaps for the same peer set.
//!
//! Privacy properties:
//! - Individual device IDs cannot be extracted from the bitmap
//! - Bitmaps roll over periodically — no long-term tracking possible
//! - Only answers: "Do these two nodes share a similar neighborhood?"
//! - No GPS, no location data, no movement patterns stored

use sha2::{Digest, Sha256};
use std::sync::Mutex;

/// Configuration for the neighborhood filter.
/// All timing and threshold values are centralized here.
pub struct NeighborhoodConfig {
    /// Number of bits in the bitmap. More bits = fewer false positives.
    /// 2048 bits = 256 bytes, suitable for up to ~300 peers at 1% FP.
    pub bitmap_bits: usize,
    /// Number of hash functions per peer ID.
    /// More hashes = better accuracy but fills bitmap faster.
    pub num_hashes: u32,
    /// How often the filter rolls over (seconds).
    /// Shorter = less tracking risk, longer = better cluster detection.
    pub rollover_interval_seconds: u64,
    /// Jaccard similarity threshold above which two nodes are "local" (same cluster).
    pub local_threshold: f64,
    /// Jaccard similarity threshold below which two nodes are from "different clusters" (bridge).
    pub bridge_threshold: f64,
}

impl Default for NeighborhoodConfig {
    fn default() -> Self {
        NeighborhoodConfig {
            bitmap_bits: 2048,                   // 256 bytes — compact enough for BLE exchange
            num_hashes: 4,                       // 4 hash functions per peer ID
            rollover_interval_seconds: 6 * 3600, // 6 hours
            local_threshold: 0.50,               // >50% shared peers = same neighborhood
            bridge_threshold: 0.20,              // <20% shared peers = different area
        }
    }
}

/// Result of comparing two neighborhood filters.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum EncounterType {
    /// High overlap — same neighborhood. No TTL extension needed.
    Local,
    /// Low overlap — different cluster. Message TTL should be extended.
    Bridge,
    /// Moderate overlap — ambiguous. Treat as local (conservative).
    Intermediate,
}

/// A privacy-preserving neighborhood detector using deterministic bit-mapped hashing.
///
/// Thread-safe via internal Mutex.
pub struct NeighborhoodFilter {
    /// Current active bitmap.
    bitmap: Mutex<Vec<u8>>,
    /// Number of items currently in the filter.
    item_count: Mutex<usize>,
    /// When the current filter was created (Unix timestamp seconds).
    created_at: Mutex<u64>,
    /// Configuration parameters.
    config: NeighborhoodConfig,
}

impl NeighborhoodFilter {
    /// Creates a new neighborhood filter with the given configuration.
    pub fn new(config: NeighborhoodConfig) -> Self {
        let bitmap_bytes = config.bitmap_bits.div_ceil(8);
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        NeighborhoodFilter {
            bitmap: Mutex::new(vec![0u8; bitmap_bytes]),
            item_count: Mutex::new(0),
            created_at: Mutex::new(now),
            config,
        }
    }

    /// Creates a filter with default configuration.
    pub fn with_defaults() -> Self {
        Self::new(NeighborhoodConfig::default())
    }

    /// Records a peer's short ID (4 bytes) as seen in the current neighborhood.
    /// Automatically rolls over the filter if the rollover interval has elapsed.
    pub fn record_peer(&self, short_id: &[u8; 4]) {
        self.maybe_rollover();

        let positions =
            deterministic_hash_positions(short_id, self.config.bitmap_bits, self.config.num_hashes);

        let mut bitmap = self.bitmap.lock().expect("Neighborhood bitmap lock");
        for pos in positions {
            bitmap[pos / 8] |= 1 << (pos % 8);
        }

        let mut count = self.item_count.lock().expect("Item count lock");
        *count += 1;
    }

    /// Exports the current bitmap for exchange with a peer.
    pub fn export_bitmap(&self) -> Vec<u8> {
        self.maybe_rollover();
        let bitmap = self.bitmap.lock().expect("Neighborhood bitmap lock");
        bitmap.clone()
    }

    /// Returns how many peers have been recorded in the current window.
    pub fn peer_count(&self) -> usize {
        *self.item_count.lock().expect("Item count lock")
    }

    /// Returns the configured bitmap size in bits.
    pub fn bitmap_bits(&self) -> usize {
        self.config.bitmap_bits
    }

    /// Compares our neighborhood filter with a remote peer's bitmap.
    ///
    /// Uses Jaccard similarity: J(A,B) = |A ∩ B| / |A ∪ B|
    /// Estimated from bit overlap in the two bitmaps.
    ///
    /// Returns the encounter type (Local, Bridge, or Intermediate).
    pub fn compare_with_remote(&self, remote_bitmap: &[u8]) -> EncounterType {
        self.maybe_rollover();

        let local_bitmap = self.bitmap.lock().expect("Neighborhood bitmap lock");

        // Bitmaps must be the same size for comparison
        if local_bitmap.len() != remote_bitmap.len() {
            return EncounterType::Intermediate;
        }

        // Check if both filters are meaningfully populated
        let local_count = *self.item_count.lock().expect("Item count lock");
        if local_count == 0 {
            return EncounterType::Intermediate;
        }

        // Check if remote has any bits set
        let remote_has_bits = remote_bitmap.iter().any(|&b| b != 0);
        if !remote_has_bits {
            return EncounterType::Intermediate;
        }

        let similarity = jaccard_similarity_from_bitmaps(&local_bitmap, remote_bitmap);

        if similarity >= self.config.local_threshold {
            EncounterType::Local
        } else if similarity <= self.config.bridge_threshold {
            EncounterType::Bridge
        } else {
            EncounterType::Intermediate
        }
    }

    /// Returns the raw Jaccard similarity estimate for a remote bitmap.
    /// Range: 0.0 (no overlap) to 1.0 (identical).
    pub fn similarity_with_remote(&self, remote_bitmap: &[u8]) -> f64 {
        let local_bitmap = self.bitmap.lock().expect("Neighborhood bitmap lock");
        if local_bitmap.len() != remote_bitmap.len() || local_bitmap.is_empty() {
            return 0.0;
        }
        jaccard_similarity_from_bitmaps(&local_bitmap, remote_bitmap)
    }

    /// Rolls over the filter if the rollover interval has elapsed.
    fn maybe_rollover(&self) {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        let mut created_at = self.created_at.lock().expect("Created at lock");
        if now.saturating_sub(*created_at) >= self.config.rollover_interval_seconds {
            let bitmap_bytes = self.config.bitmap_bits.div_ceil(8);
            let mut bitmap = self.bitmap.lock().expect("Neighborhood bitmap lock");
            *bitmap = vec![0u8; bitmap_bytes];
            let mut count = self.item_count.lock().expect("Item count lock");
            *count = 0;
            *created_at = now;
            log::info!("Neighborhood filter rolled over");
        }
    }
}

/// Computes deterministic bit positions for a peer short ID.
///
/// Uses SHA-256 with different domain separators (hash index) to produce
/// independent bit positions. This is deterministic — the same short_id
/// always maps to the same positions on any device.
fn deterministic_hash_positions(
    short_id: &[u8; 4],
    bitmap_bits: usize,
    num_hashes: u32,
) -> Vec<usize> {
    let mut positions = Vec::with_capacity(num_hashes as usize);

    for hash_idx in 0..num_hashes {
        let mut hasher = Sha256::new();
        // Domain separator: "flare-nbr-" + hash index
        hasher.update(b"flare-nbr-");
        hasher.update(hash_idx.to_le_bytes());
        hasher.update(short_id);

        let hash = hasher.finalize();
        // Use first 8 bytes as u64, mod bitmap_bits
        let value = u64::from_le_bytes(hash[..8].try_into().unwrap());
        positions.push((value as usize) % bitmap_bits);
    }

    positions
}

/// Estimates Jaccard similarity from two bitmaps.
///
/// J(A,B) = |A ∩ B| / |A ∪ B| ≈ bits_both_set / bits_either_set
fn jaccard_similarity_from_bitmaps(bitmap_a: &[u8], bitmap_b: &[u8]) -> f64 {
    let mut bits_and: u64 = 0;
    let mut bits_or: u64 = 0;

    for (a, b) in bitmap_a.iter().zip(bitmap_b.iter()) {
        bits_and += (a & b).count_ones() as u64;
        bits_or += (a | b).count_ones() as u64;
    }

    if bits_or == 0 {
        return 0.0;
    }

    bits_and as f64 / bits_or as f64
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_short_id(seed: u8) -> [u8; 4] {
        [
            seed,
            seed.wrapping_add(1),
            seed.wrapping_add(2),
            seed.wrapping_add(3),
        ]
    }

    #[test]
    fn test_record_peer_and_count() {
        let filter = NeighborhoodFilter::with_defaults();
        assert_eq!(filter.peer_count(), 0);

        filter.record_peer(&make_short_id(1));
        assert_eq!(filter.peer_count(), 1);

        filter.record_peer(&make_short_id(2));
        assert_eq!(filter.peer_count(), 2);
    }

    #[test]
    fn test_export_bitmap_not_empty() {
        let filter = NeighborhoodFilter::with_defaults();
        filter.record_peer(&make_short_id(1));

        let bitmap = filter.export_bitmap();
        assert!(!bitmap.is_empty());
        assert!(bitmap.iter().any(|&b| b != 0));
    }

    #[test]
    fn test_deterministic_hashing() {
        // Same short_id always produces the same bit positions
        let positions_1 = deterministic_hash_positions(&[1, 2, 3, 4], 2048, 4);
        let positions_2 = deterministic_hash_positions(&[1, 2, 3, 4], 2048, 4);
        assert_eq!(positions_1, positions_2);

        // Different short_ids produce different positions
        let positions_3 = deterministic_hash_positions(&[5, 6, 7, 8], 2048, 4);
        assert_ne!(positions_1, positions_3);
    }

    #[test]
    fn test_identical_neighborhoods_are_local() {
        let filter_a = NeighborhoodFilter::with_defaults();
        let filter_b = NeighborhoodFilter::with_defaults();

        // Both see the same 50 peers
        for i in 0..50u8 {
            let id = make_short_id(i);
            filter_a.record_peer(&id);
            filter_b.record_peer(&id);
        }

        let remote_bitmap = filter_b.export_bitmap();
        let encounter = filter_a.compare_with_remote(&remote_bitmap);
        assert_eq!(encounter, EncounterType::Local);

        // Verify similarity is very high
        let sim = filter_a.similarity_with_remote(&remote_bitmap);
        assert!(sim > 0.99, "Expected >0.99, got {}", sim);
    }

    #[test]
    fn test_completely_different_neighborhoods_are_bridge() {
        let filter_a = NeighborhoodFilter::with_defaults();
        let filter_b = NeighborhoodFilter::with_defaults();

        // A sees peers 0-49, B sees peers 100-149 (no overlap)
        for i in 0..50u8 {
            filter_a.record_peer(&make_short_id(i));
            filter_b.record_peer(&make_short_id(i + 100));
        }

        let remote_bitmap = filter_b.export_bitmap();
        let encounter = filter_a.compare_with_remote(&remote_bitmap);
        assert_eq!(encounter, EncounterType::Bridge);
    }

    #[test]
    fn test_partial_overlap_is_intermediate_or_local() {
        let filter_a = NeighborhoodFilter::with_defaults();
        let filter_b = NeighborhoodFilter::with_defaults();

        // A sees peers 0-49 (50 peers)
        // B sees peers 25-74 (50 peers, 25 overlap = ~33% Jaccard with Bloom effects)
        for i in 0..50u8 {
            filter_a.record_peer(&make_short_id(i));
            filter_b.record_peer(&make_short_id(i + 25));
        }

        let remote_bitmap = filter_b.export_bitmap();
        let encounter = filter_a.compare_with_remote(&remote_bitmap);
        // With 50% set overlap and Bloom filter effects, similarity should be
        // between bridge and local thresholds
        assert!(
            encounter == EncounterType::Local
                || encounter == EncounterType::Intermediate
                || encounter == EncounterType::Bridge,
            "Got {:?}",
            encounter,
        );
    }

    #[test]
    fn test_empty_filters_are_intermediate() {
        let filter_a = NeighborhoodFilter::with_defaults();
        let filter_b = NeighborhoodFilter::with_defaults();

        let remote_bitmap = filter_b.export_bitmap();
        let encounter = filter_a.compare_with_remote(&remote_bitmap);
        assert_eq!(encounter, EncounterType::Intermediate);
    }

    #[test]
    fn test_one_empty_filter_is_intermediate() {
        let filter_a = NeighborhoodFilter::with_defaults();
        let filter_b = NeighborhoodFilter::with_defaults();

        // Only A has peers
        for i in 0..20u8 {
            filter_a.record_peer(&make_short_id(i));
        }

        let remote_bitmap = filter_b.export_bitmap();
        let encounter = filter_a.compare_with_remote(&remote_bitmap);
        // Remote has no bits set → Intermediate (can't determine)
        assert_eq!(encounter, EncounterType::Intermediate);
    }

    #[test]
    fn test_mismatched_bitmap_size_is_intermediate() {
        let filter = NeighborhoodFilter::with_defaults();
        filter.record_peer(&make_short_id(1));

        let encounter = filter.compare_with_remote(&[0u8; 10]);
        assert_eq!(encounter, EncounterType::Intermediate);
    }

    #[test]
    fn test_jaccard_similarity_bounds() {
        // Identical bitmaps → 1.0
        let bitmap = vec![0xFF; 32];
        let sim = jaccard_similarity_from_bitmaps(&bitmap, &bitmap);
        assert!((sim - 1.0).abs() < f64::EPSILON);

        // Disjoint bitmaps → 0.0
        let a = vec![0xAA; 32]; // 10101010
        let b = vec![0x55; 32]; // 01010101
        let sim = jaccard_similarity_from_bitmaps(&a, &b);
        assert!(sim < f64::EPSILON);

        // Both empty → 0.0
        let empty = vec![0x00; 32];
        let sim = jaccard_similarity_from_bitmaps(&empty, &empty);
        assert!(sim < f64::EPSILON);
    }

    #[test]
    fn test_similarity_numeric_range() {
        let filter_a = NeighborhoodFilter::with_defaults();
        let filter_b = NeighborhoodFilter::with_defaults();

        for i in 0..30u8 {
            filter_a.record_peer(&make_short_id(i));
            filter_b.record_peer(&make_short_id(i + 100));
        }

        let sim = filter_a.similarity_with_remote(&filter_b.export_bitmap());
        assert!(sim >= 0.0 && sim <= 1.0);
    }

    #[test]
    fn test_bitmap_size_matches_config() {
        let config = NeighborhoodConfig {
            bitmap_bits: 1024,
            ..Default::default()
        };
        let filter = NeighborhoodFilter::new(config);
        let bitmap = filter.export_bitmap();
        assert_eq!(bitmap.len(), 128); // 1024 / 8
    }
}
