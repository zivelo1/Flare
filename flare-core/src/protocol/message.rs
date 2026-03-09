//! Mesh message definition and builder.
//!
//! Each message traveling through the mesh carries routing metadata
//! (visible to relay nodes) and an encrypted payload (readable only
//! by the intended recipient).

use chrono::Utc;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

use crate::crypto::identity::DeviceId;
use crate::PROTOCOL_VERSION;

/// Default maximum number of hops a message can traverse.
const DEFAULT_MAX_HOPS: u8 = 10;

/// Default time-to-live in seconds (24 hours).
const DEFAULT_TTL_SECONDS: u32 = 86400;

/// Broadcast address — messages sent to all nearby peers.
pub const BROADCAST_DEVICE_ID: DeviceId = DeviceId([0xFF; 16]);

/// Content type identifier for the encrypted payload.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum ContentType {
    Text = 0x01,
    VoiceMessage = 0x02,
    Image = 0x03,
    KeyExchange = 0x04,
    Acknowledgment = 0x05,
    ReadReceipt = 0x06,
    GroupMessage = 0x07,
    RouteRequest = 0x10,
    RouteReply = 0x11,
    PeerAnnounce = 0x20,
    ApkOffer = 0x40,
    ApkRequest = 0x41,
}

/// A message that travels through the Flare mesh network.
///
/// The `payload` field is encrypted end-to-end; relay nodes cannot read it.
/// All other fields are visible to relay nodes for routing purposes.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshMessage {
    /// Protocol version for forward compatibility.
    pub version: u8,

    /// Globally unique message ID: SHA-256(sender_id + timestamp + sequence).
    pub message_id: [u8; 32],

    /// Sender's device ID (public key fingerprint).
    pub sender_id: DeviceId,

    /// Recipient's device ID, or BROADCAST_DEVICE_ID for broadcasts.
    pub recipient_id: DeviceId,

    /// Current hop count (incremented by each relay node).
    pub hop_count: u8,

    /// Maximum allowed hops before the message is dropped.
    pub max_hops: u8,

    /// Time-to-live in seconds from creation. Expired messages are dropped.
    pub ttl_seconds: u32,

    /// UTC timestamp when the message was created (milliseconds since epoch).
    pub created_at_ms: i64,

    /// Content type of the encrypted payload.
    pub content_type: ContentType,

    /// Encrypted payload (only readable by the recipient).
    pub payload: Vec<u8>,

    /// Ed25519 signature over all fields above (by the sender).
    /// Stored as Vec<u8> because serde doesn't natively support [u8; 64].
    pub signature: Vec<u8>,
}

impl MeshMessage {
    /// Returns the bytes that are covered by the signature.
    /// This is everything except the signature field and hop_count.
    ///
    /// hop_count is excluded because relay nodes increment it during forwarding.
    /// Including it would invalidate the sender's signature after the first relay.
    /// ttl_seconds is also excluded because adaptive TTL extends it on bridge encounters.
    pub fn signable_bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::new();
        bytes.push(self.version);
        bytes.extend_from_slice(&self.message_id);
        bytes.extend_from_slice(&self.sender_id.0);
        bytes.extend_from_slice(&self.recipient_id.0);
        // hop_count intentionally excluded — mutable by relay nodes
        bytes.push(self.max_hops);
        // ttl_seconds intentionally excluded — mutable by adaptive TTL
        bytes.extend_from_slice(&self.created_at_ms.to_le_bytes());
        bytes.push(self.content_type as u8);
        bytes.extend_from_slice(&(self.payload.len() as u16).to_le_bytes());
        bytes.extend_from_slice(&self.payload);
        bytes
    }

    /// Serializes the complete message to bytes for transmission.
    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    /// Deserializes a message from bytes received from a peer.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }

    /// Returns true if this message has exceeded its TTL.
    pub fn is_expired(&self) -> bool {
        let now_ms = Utc::now().timestamp_millis();
        let age_seconds = (now_ms - self.created_at_ms) / 1000;
        age_seconds > self.ttl_seconds as i64
    }

    /// Returns true if this message has reached its maximum hop count.
    pub fn is_hop_limited(&self) -> bool {
        self.hop_count >= self.max_hops
    }

    /// Returns true if this message is a broadcast.
    pub fn is_broadcast(&self) -> bool {
        self.recipient_id == BROADCAST_DEVICE_ID
    }

    /// Increments the hop count (called by relay nodes before forwarding).
    pub fn increment_hop(&mut self) {
        self.hop_count = self.hop_count.saturating_add(1);
    }

    /// Returns the total wire size of this message in bytes.
    pub fn wire_size(&self) -> usize {
        // Approximate: header overhead + payload
        // version(1) + message_id(32) + sender(16) + recipient(16) + hop(1)
        // + max_hops(1) + ttl(4) + timestamp(8) + content_type(1) + payload_len(2)
        // + payload(N) + signature(64)
        146 + self.payload.len()
    }
}

/// Builder for constructing MeshMessage instances.
pub struct MessageBuilder {
    sender_id: DeviceId,
    recipient_id: DeviceId,
    content_type: ContentType,
    payload: Vec<u8>,
    max_hops: u8,
    ttl_seconds: u32,
}

impl MessageBuilder {
    /// Creates a new message builder.
    pub fn new(sender_id: DeviceId, recipient_id: DeviceId) -> Self {
        MessageBuilder {
            sender_id,
            recipient_id,
            content_type: ContentType::Text,
            payload: Vec::new(),
            max_hops: DEFAULT_MAX_HOPS,
            ttl_seconds: DEFAULT_TTL_SECONDS,
        }
    }

    /// Creates a broadcast message builder.
    pub fn broadcast(sender_id: DeviceId) -> Self {
        Self::new(sender_id, BROADCAST_DEVICE_ID)
    }

    pub fn content_type(mut self, content_type: ContentType) -> Self {
        self.content_type = content_type;
        self
    }

    pub fn payload(mut self, payload: Vec<u8>) -> Self {
        self.payload = payload;
        self
    }

    pub fn max_hops(mut self, max_hops: u8) -> Self {
        self.max_hops = max_hops;
        self
    }

    pub fn ttl_seconds(mut self, ttl_seconds: u32) -> Self {
        self.ttl_seconds = ttl_seconds;
        self
    }

    /// Builds the message and signs it with the sender's identity.
    ///
    /// The `sign_fn` receives the signable bytes and returns a 64-byte Ed25519 signature.
    /// This avoids the builder needing access to private keys directly.
    pub fn build<F>(self, sign_fn: F) -> MeshMessage
    where
        F: FnOnce(&[u8]) -> [u8; 64],
    {
        let created_at_ms = Utc::now().timestamp_millis();

        // Generate deterministic message ID
        let message_id = Self::generate_message_id(&self.sender_id, created_at_ms);

        let mut msg = MeshMessage {
            version: PROTOCOL_VERSION,
            message_id,
            sender_id: self.sender_id,
            recipient_id: self.recipient_id,
            hop_count: 0,
            max_hops: self.max_hops,
            ttl_seconds: self.ttl_seconds,
            created_at_ms,
            content_type: self.content_type,
            payload: self.payload,
            signature: vec![0u8; 64], // Will be filled below
        };

        let signable = msg.signable_bytes();
        msg.signature = sign_fn(&signable).to_vec();
        msg
    }

    /// Generates a unique message ID from sender + timestamp.
    /// Uses SHA-256 to ensure uniform distribution (good for Bloom filter dedup).
    fn generate_message_id(sender_id: &DeviceId, timestamp_ms: i64) -> [u8; 32] {
        let mut hasher = Sha256::new();
        hasher.update(sender_id.0);
        hasher.update(timestamp_ms.to_le_bytes());
        // Add randomness to prevent collisions for messages created at the same millisecond
        let random_bytes: [u8; 8] = rand::random();
        hasher.update(random_bytes);
        hasher.finalize().into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::identity::Identity;

    #[test]
    fn test_build_and_verify_message() {
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = MessageBuilder::new(sender.device_id().clone(), recipient.device_id().clone())
            .content_type(ContentType::Text)
            .payload(b"Hello mesh!".to_vec())
            .build(|data| sender.sign(data));

        // Verify signature
        let signable = msg.signable_bytes();
        let sig: [u8; 64] = msg.signature.as_slice().try_into().unwrap();
        let result = sender.public_identity().verify(&signable, &sig);
        assert!(result.is_ok());

        assert_eq!(msg.version, PROTOCOL_VERSION);
        assert_eq!(msg.hop_count, 0);
        assert_eq!(msg.content_type, ContentType::Text);
        assert!(!msg.is_expired());
        assert!(!msg.is_hop_limited());
        assert!(!msg.is_broadcast());
    }

    #[test]
    fn test_broadcast_message() {
        let sender = Identity::generate();

        let msg = MessageBuilder::broadcast(sender.device_id().clone())
            .payload(b"Emergency broadcast".to_vec())
            .build(|data| sender.sign(data));

        assert!(msg.is_broadcast());
    }

    #[test]
    fn test_hop_count() {
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let mut msg =
            MessageBuilder::new(sender.device_id().clone(), recipient.device_id().clone())
                .max_hops(3)
                .payload(b"test".to_vec())
                .build(|data| sender.sign(data));

        assert!(!msg.is_hop_limited());
        msg.increment_hop(); // 1
        msg.increment_hop(); // 2
        msg.increment_hop(); // 3
        assert!(msg.is_hop_limited());
    }

    #[test]
    fn test_message_serialization_roundtrip() {
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = MessageBuilder::new(sender.device_id().clone(), recipient.device_id().clone())
            .payload(b"Serialize me".to_vec())
            .build(|data| sender.sign(data));

        let bytes = msg.to_bytes().unwrap();
        let restored = MeshMessage::from_bytes(&bytes).unwrap();

        assert_eq!(msg.message_id, restored.message_id);
        assert_eq!(msg.sender_id, restored.sender_id);
        assert_eq!(msg.payload, restored.payload);
        assert_eq!(msg.signature, restored.signature);
    }

    #[test]
    fn test_expired_message() {
        let sender = Identity::generate();

        let mut msg = MessageBuilder::new(sender.device_id().clone(), DeviceId([0; 16]))
            .ttl_seconds(0) // Expire immediately
            .payload(b"expired".to_vec())
            .build(|data| sender.sign(data));

        // Set creation time to the past
        msg.created_at_ms -= 1000;
        assert!(msg.is_expired());
    }

    #[test]
    fn test_unique_message_ids() {
        let sender = Identity::generate();

        let msg1 = MessageBuilder::new(sender.device_id().clone(), DeviceId([0; 16]))
            .payload(b"msg1".to_vec())
            .build(|data| sender.sign(data));

        let msg2 = MessageBuilder::new(sender.device_id().clone(), DeviceId([0; 16]))
            .payload(b"msg2".to_vec())
            .build(|data| sender.sign(data));

        assert_ne!(msg1.message_id, msg2.message_id);
    }

    #[test]
    fn test_wire_size_reasonable() {
        let sender = Identity::generate();
        let msg = MessageBuilder::new(sender.device_id().clone(), DeviceId([0; 16]))
            .payload(b"Short text message for testing".to_vec())
            .build(|data| sender.sign(data));

        // A text message should fit in a single BLE GATT write (< 512 bytes)
        assert!(
            msg.wire_size() < 512,
            "Wire size {} exceeds BLE MTU",
            msg.wire_size()
        );
    }
}
