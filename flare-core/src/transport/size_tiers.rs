//! Message size tiers and transfer strategy selection.
//!
//! Different message types have vastly different sizes:
//! - Text messages: ~100 bytes — easily fit in BLE mesh relay
//! - Voice clips: ~10-80 KB — may exceed BLE chunk limits
//! - Images: ~100 KB-5 MB — far too large for mesh relay
//!
//! This module determines the optimal transfer strategy based on
//! message size, content type, and available transports. It prevents
//! large payloads from flooding the mesh with hundreds of relay copies.

use crate::protocol::message::ContentType;

/// Transfer strategy recommended for a message based on its size and type.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransferStrategy {
    /// Send via BLE mesh relay (Spray-and-Wait). Suitable for small payloads
    /// that fit within chunk limits and don't strain the mesh.
    MeshRelay,

    /// Prefer direct peer-to-peer transfer (Wi-Fi Direct / Multipeer Connectivity).
    /// Falls back to mesh relay if direct link is unavailable and payload fits.
    DirectPreferred,

    /// Require direct peer-to-peer transfer. Too large for mesh relay.
    /// If no direct link is available, queue until one is established.
    DirectRequired,
}

/// Message size tier classification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SizeTier {
    /// Small: fits easily in BLE mesh (text, ACKs, key exchanges).
    Small,
    /// Medium: can use mesh but direct is preferred (short voice clips).
    Medium,
    /// Large: too big for mesh relay, must use direct transfer.
    Large,
}

/// Configuration for size tier thresholds.
/// All values are in bytes of the encrypted payload (after compression, before chunking).
pub struct SizeTierConfig {
    /// Maximum payload size for mesh relay (Small tier). Default: 15 KB.
    /// Based on: 255 max chunks × ~60 bytes/chunk (conservative MTU after overhead).
    /// Actual BLE capacity is higher with large MTU, but this is safe across all devices.
    pub mesh_relay_max_bytes: usize,

    /// Maximum payload size for direct-preferred tier (Medium). Default: 64 KB.
    /// Payloads between mesh_relay_max and this threshold can use mesh as fallback
    /// but perform better over direct transport.
    pub direct_preferred_max_bytes: usize,

    /// Absolute maximum payload size for any transfer. Default: 10 MB.
    /// Anything larger is rejected at the application layer.
    pub absolute_max_bytes: usize,
}

impl Default for SizeTierConfig {
    fn default() -> Self {
        SizeTierConfig {
            mesh_relay_max_bytes: 15 * 1024,       // 15 KB
            direct_preferred_max_bytes: 64 * 1024, // 64 KB
            absolute_max_bytes: 10 * 1024 * 1024,  // 10 MB
        }
    }
}

impl SizeTierConfig {
    /// Classifies a payload into a size tier.
    pub fn classify_size(&self, payload_bytes: usize) -> SizeTier {
        if payload_bytes <= self.mesh_relay_max_bytes {
            SizeTier::Small
        } else if payload_bytes <= self.direct_preferred_max_bytes {
            SizeTier::Medium
        } else {
            SizeTier::Large
        }
    }

    /// Determines the optimal transfer strategy for a message.
    ///
    /// Considers both payload size and content type semantics:
    /// - ACKs, key exchanges, read receipts: always mesh relay (control traffic)
    /// - Text: mesh relay unless unusually large
    /// - Voice: direct preferred (latency-sensitive, moderate size)
    /// - Image: direct required (large payloads shouldn't flood mesh)
    /// - APK chunks: direct required (large binary data)
    pub fn recommend_strategy(
        &self,
        content_type: ContentType,
        payload_bytes: usize,
    ) -> TransferStrategy {
        // Control messages always use mesh regardless of size
        match content_type {
            ContentType::Acknowledgment
            | ContentType::ReadReceipt
            | ContentType::KeyExchange
            | ContentType::PeerAnnounce
            | ContentType::RouteRequest
            | ContentType::RouteReply => return TransferStrategy::MeshRelay,
            _ => {}
        }

        let size_tier = self.classify_size(payload_bytes);

        match (content_type, size_tier) {
            // Text: mesh for small, direct-preferred for medium, direct-required for large
            (ContentType::Text, SizeTier::Small) => TransferStrategy::MeshRelay,
            (ContentType::Text, SizeTier::Medium) => TransferStrategy::DirectPreferred,
            (ContentType::Text, SizeTier::Large) => TransferStrategy::DirectRequired,

            // Voice: prefer direct even for small clips (latency matters)
            (ContentType::VoiceMessage, SizeTier::Small) => TransferStrategy::DirectPreferred,
            (ContentType::VoiceMessage, _) => TransferStrategy::DirectRequired,

            // Images: always direct (even small thumbnails benefit from bandwidth)
            (ContentType::Image, SizeTier::Small) => TransferStrategy::DirectPreferred,
            (ContentType::Image, _) => TransferStrategy::DirectRequired,

            // APK sharing: always direct (binary data, large)
            (ContentType::ApkOffer | ContentType::ApkRequest, _) => {
                TransferStrategy::DirectPreferred
            }

            // Group messages: same as text tier rules
            (ContentType::GroupMessage, SizeTier::Small) => TransferStrategy::MeshRelay,
            (ContentType::GroupMessage, _) => TransferStrategy::DirectPreferred,

            // Control messages that weren't caught by the early return (unreachable),
            // and any future content types default to size-based strategy.
            (_, SizeTier::Small) => TransferStrategy::MeshRelay,
            (_, SizeTier::Medium) => TransferStrategy::DirectPreferred,
            (_, SizeTier::Large) => TransferStrategy::DirectRequired,
        }
    }

    /// Returns true if the payload exceeds the absolute maximum size.
    pub fn is_oversized(&self, payload_bytes: usize) -> bool {
        payload_bytes > self.absolute_max_bytes
    }

    /// Returns the maximum number of BLE chunks needed for a payload of given size.
    /// Uses a conservative MTU estimate for safety.
    pub fn estimated_ble_chunks(&self, payload_bytes: usize, mtu: usize) -> usize {
        let chunk_data = mtu.saturating_sub(super::ChunkHeader::SIZE);
        if chunk_data == 0 {
            return usize::MAX;
        }
        payload_bytes.div_ceil(chunk_data)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_size_classification() {
        let config = SizeTierConfig::default();

        assert_eq!(config.classify_size(100), SizeTier::Small);
        assert_eq!(config.classify_size(1024), SizeTier::Small);
        assert_eq!(config.classify_size(15 * 1024), SizeTier::Small);
        assert_eq!(config.classify_size(15 * 1024 + 1), SizeTier::Medium);
        assert_eq!(config.classify_size(64 * 1024), SizeTier::Medium);
        assert_eq!(config.classify_size(64 * 1024 + 1), SizeTier::Large);
        assert_eq!(config.classify_size(5 * 1024 * 1024), SizeTier::Large);
    }

    #[test]
    fn test_text_strategy() {
        let config = SizeTierConfig::default();

        assert_eq!(
            config.recommend_strategy(ContentType::Text, 100),
            TransferStrategy::MeshRelay
        );
        assert_eq!(
            config.recommend_strategy(ContentType::Text, 20_000),
            TransferStrategy::DirectPreferred
        );
        assert_eq!(
            config.recommend_strategy(ContentType::Text, 100_000),
            TransferStrategy::DirectRequired
        );
    }

    #[test]
    fn test_voice_always_prefers_direct() {
        let config = SizeTierConfig::default();

        // Even small voice clips prefer direct (latency-sensitive)
        assert_eq!(
            config.recommend_strategy(ContentType::VoiceMessage, 100),
            TransferStrategy::DirectPreferred
        );
        assert_eq!(
            config.recommend_strategy(ContentType::VoiceMessage, 50_000),
            TransferStrategy::DirectRequired
        );
    }

    #[test]
    fn test_image_always_prefers_direct() {
        let config = SizeTierConfig::default();

        assert_eq!(
            config.recommend_strategy(ContentType::Image, 5_000),
            TransferStrategy::DirectPreferred
        );
        assert_eq!(
            config.recommend_strategy(ContentType::Image, 500_000),
            TransferStrategy::DirectRequired
        );
    }

    #[test]
    fn test_control_messages_always_mesh() {
        let config = SizeTierConfig::default();

        for content_type in [
            ContentType::Acknowledgment,
            ContentType::ReadReceipt,
            ContentType::KeyExchange,
            ContentType::PeerAnnounce,
        ] {
            assert_eq!(
                config.recommend_strategy(content_type, 100_000),
                TransferStrategy::MeshRelay,
                "Control message {:?} should always use mesh",
                content_type
            );
        }
    }

    #[test]
    fn test_oversized_detection() {
        let config = SizeTierConfig::default();

        assert!(!config.is_oversized(1_000_000));
        assert!(!config.is_oversized(10 * 1024 * 1024));
        assert!(config.is_oversized(10 * 1024 * 1024 + 1));
    }

    #[test]
    fn test_ble_chunk_estimation() {
        let config = SizeTierConfig::default();

        // 247 byte MTU - 4 byte header = 243 bytes per chunk
        assert_eq!(config.estimated_ble_chunks(243, 247), 1);
        assert_eq!(config.estimated_ble_chunks(244, 247), 2);
        assert_eq!(config.estimated_ble_chunks(1000, 247), 5); // ceil(1000/243)
    }

    #[test]
    fn test_group_message_strategy() {
        let config = SizeTierConfig::default();

        assert_eq!(
            config.recommend_strategy(ContentType::GroupMessage, 100),
            TransferStrategy::MeshRelay
        );
        assert_eq!(
            config.recommend_strategy(ContentType::GroupMessage, 20_000),
            TransferStrategy::DirectPreferred
        );
    }
}
