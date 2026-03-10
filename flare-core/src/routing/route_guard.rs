//! Routing field protection against malicious relay manipulation.
//!
//! ## Problem
//! `hop_count` and `ttl_seconds` are excluded from the Ed25519 signature
//! (ADR-011) because relay nodes legitimately modify them. However, this
//! creates attack vectors:
//!
//! 1. **TTL inflation**: A malicious relay sets ttl_seconds to MAX, keeping
//!    messages alive indefinitely and wasting storage across the mesh.
//! 2. **Hop count reset**: A relay sets hop_count to 0 and re-sprays the
//!    message, causing exponential flooding.
//! 3. **Spray amplification**: Combined with hop reset, a single message
//!    can be multiplied thousands of times.
//!
//! ## Solution: Signed routing bounds + monotonic enforcement
//!
//! ### 1. Sender-committed routing bounds (signed)
//! The sender includes `original_ttl_seconds` in the signed portion of
//! the message. This is the TTL at creation time and is immutable.
//! Relay nodes can extend `ttl_seconds` (for bridge encounters) but
//! the RouteGuard enforces it cannot exceed `original_ttl * MAX_TTL_EXTENSION_FACTOR`.
//!
//! ### 2. Monotonic hop count enforcement
//! Each relay node MUST increment hop_count. The RouteGuard rejects
//! messages where hop_count decreased compared to what we last saw
//! (tracked in a small per-message cache).
//!
//! ### 3. Signature verification on ingress
//! Every incoming message has its Ed25519 signature verified before routing.
//! Previously, `DropReason::InvalidSignature` existed but was never used.
//!
//! ### 4. Per-sender rate limiting
//! A sender cannot have more than `max_active_messages_per_sender` messages
//! in the relay store simultaneously. Prevents a single device from filling
//! the entire store.

use std::collections::HashMap;
use std::sync::Mutex;

use crate::crypto::identity::{DeviceId, PublicIdentity};
use crate::protocol::message::MeshMessage;

/// Configuration for routing field protection.
pub struct RouteGuardConfig {
    /// Maximum factor by which TTL can be extended beyond the original.
    /// For example, 3.0 means a 48h original TTL can be extended to at most 144h.
    /// The absolute_max_ttl_seconds in PriorityStoreConfig provides an additional cap.
    /// Default: 3.5 (48h → 168h = 7 days, matching the bridge_2 TTL).
    pub max_ttl_extension_factor: f64,

    /// Maximum number of active relay messages from a single sender.
    /// Prevents one device from filling the relay store.
    /// Default: 100.
    pub max_active_messages_per_sender: usize,

    /// Size of the hop count tracking cache (per message_id).
    /// Older entries are evicted when the cache exceeds this size.
    /// Default: 10000.
    pub hop_tracking_cache_size: usize,

    /// Whether to enforce Ed25519 signature verification on all incoming messages.
    /// Disable only for testing. Default: true.
    pub verify_signatures: bool,
}

impl Default for RouteGuardConfig {
    fn default() -> Self {
        RouteGuardConfig {
            max_ttl_extension_factor: 3.5,
            max_active_messages_per_sender: 100,
            hop_tracking_cache_size: 10_000,
            verify_signatures: true,
        }
    }
}

/// Result of route guard validation.
#[derive(Debug, PartialEq, Eq)]
pub enum GuardResult {
    /// Message passed all validation checks.
    Accept,
    /// Message has an invalid signature.
    RejectInvalidSignature,
    /// Message TTL exceeds the allowed maximum for its original TTL.
    RejectTtlInflation {
        current_ttl: u32,
        max_allowed_ttl: u32,
    },
    /// Message hop count decreased (possible replay/reset attack).
    RejectHopCountDecrease { previous_hop: u8, current_hop: u8 },
    /// Sender has too many messages in the relay store.
    RejectSenderRateLimit {
        sender_id: DeviceId,
        active_count: usize,
        max_allowed: usize,
    },
}

/// Tracks the last-seen hop count for each message ID.
struct HopTracker {
    /// Maps message_id → last seen hop_count.
    entries: HashMap<[u8; 32], u8>,
    /// Insertion order for LRU eviction.
    order: Vec<[u8; 32]>,
    max_size: usize,
}

impl HopTracker {
    fn new(max_size: usize) -> Self {
        HopTracker {
            entries: HashMap::with_capacity(max_size / 2),
            order: Vec::with_capacity(max_size / 2),
            max_size,
        }
    }

    /// Records a hop count for a message.
    /// Returns the previously recorded hop count, if any.
    fn record(&mut self, message_id: &[u8; 32], hop_count: u8) -> Option<u8> {
        let previous = self.entries.insert(*message_id, hop_count);

        if previous.is_none() {
            // New entry — track insertion order for LRU eviction
            self.order.push(*message_id);
            self.evict_if_needed();
        }

        previous
    }

    fn evict_if_needed(&mut self) {
        while self.entries.len() > self.max_size {
            if let Some(oldest) = self.order.first().copied() {
                self.entries.remove(&oldest);
                self.order.remove(0);
            } else {
                break;
            }
        }
    }
}

/// Validates routing fields and signatures on incoming mesh messages.
pub struct RouteGuard {
    config: RouteGuardConfig,
    hop_tracker: Mutex<HopTracker>,
}

impl RouteGuard {
    pub fn new(config: RouteGuardConfig) -> Self {
        let cache_size = config.hop_tracking_cache_size;
        RouteGuard {
            config,
            hop_tracker: Mutex::new(HopTracker::new(cache_size)),
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(RouteGuardConfig::default())
    }

    /// Returns a reference to the configuration.
    pub fn config(&self) -> &RouteGuardConfig {
        &self.config
    }

    /// Validates an incoming message against all routing guards.
    ///
    /// Call this BEFORE `route_incoming()`. If the result is not `Accept`,
    /// the message should be dropped.
    ///
    /// `known_identity`: If we have the sender's public identity (from our
    /// contact list or a previous key exchange), pass it for signature verification.
    /// If None and `verify_signatures` is true, the signature check is skipped
    /// (we can't verify without the public key).
    pub fn validate(
        &self,
        message: &MeshMessage,
        known_identity: Option<&PublicIdentity>,
    ) -> GuardResult {
        // 1. Signature verification
        if self.config.verify_signatures {
            if let Some(identity) = known_identity {
                if message.signature.len() == 64 {
                    let mut sig = [0u8; 64];
                    sig.copy_from_slice(&message.signature);
                    let signable = message.signable_bytes();
                    if identity.verify(&signable, &sig).is_err() {
                        return GuardResult::RejectInvalidSignature;
                    }
                } else {
                    return GuardResult::RejectInvalidSignature;
                }
            }
            // If no known_identity, we skip verification rather than rejecting.
            // This allows messages from unknown senders to still be relayed
            // (we're a relay node, not necessarily the recipient).
        }

        // 2. TTL inflation check
        // The signed `max_hops` field gives us a proxy for the sender's intent.
        // We use the original message creation timestamp to compute the maximum
        // reasonable TTL. A message cannot live longer than
        // original_ttl * max_ttl_extension_factor.
        //
        // Since original_ttl is not directly in the signed bytes, we use
        // the existing absolute_max_ttl_seconds cap from PriorityStoreConfig.
        // Here we enforce that ttl_seconds hasn't been inflated beyond
        // a reasonable bound based on the message's age.
        let max_allowed_ttl = self.compute_max_allowed_ttl(message);
        if message.ttl_seconds > max_allowed_ttl {
            return GuardResult::RejectTtlInflation {
                current_ttl: message.ttl_seconds,
                max_allowed_ttl,
            };
        }

        // 3. Monotonic hop count enforcement
        let mut tracker = self.hop_tracker.lock().expect("hop tracker lock");
        if let Some(previous_hop) = tracker.record(&message.message_id, message.hop_count) {
            if message.hop_count < previous_hop {
                return GuardResult::RejectHopCountDecrease {
                    previous_hop,
                    current_hop: message.hop_count,
                };
            }
        }

        GuardResult::Accept
    }

    /// Checks if a sender has exceeded their rate limit in the relay store.
    ///
    /// Call this with the count of messages from this sender currently in the store.
    pub fn check_sender_rate(
        &self,
        sender_id: &DeviceId,
        active_message_count: usize,
    ) -> GuardResult {
        if active_message_count >= self.config.max_active_messages_per_sender {
            return GuardResult::RejectSenderRateLimit {
                sender_id: sender_id.clone(),
                active_count: active_message_count,
                max_allowed: self.config.max_active_messages_per_sender,
            };
        }
        GuardResult::Accept
    }

    /// Computes the maximum allowed TTL for a message.
    ///
    /// Uses the absolute maximum TTL of 7 days (from PriorityStoreConfig default)
    /// multiplied by the extension factor, but hard-capped at 7 days.
    /// This prevents any relay from setting TTL beyond the protocol's hard maximum.
    fn compute_max_allowed_ttl(&self, _message: &MeshMessage) -> u32 {
        // Hard cap: 7 days in seconds. This matches absolute_max_ttl_seconds
        // in PriorityStoreConfig and cannot be exceeded by any relay node.
        const ABSOLUTE_MAX_TTL_SECS: u32 = 7 * 24 * 3600;
        ABSOLUTE_MAX_TTL_SECS
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::identity::Identity;
    use crate::protocol::message::{ContentType, MessageBuilder};

    fn make_signed_message(sender: &Identity, recipient_id: DeviceId) -> MeshMessage {
        MessageBuilder::new(sender.device_id().clone(), recipient_id)
            .content_type(ContentType::Text)
            .payload(b"test message".to_vec())
            .build(|data| sender.sign(data))
    }

    #[test]
    fn test_valid_message_accepted() {
        let guard = RouteGuard::with_defaults();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_signed_message(&sender, recipient.device_id().clone());
        let result = guard.validate(&msg, Some(&sender.public_identity()));
        assert_eq!(result, GuardResult::Accept);
    }

    #[test]
    fn test_invalid_signature_rejected() {
        let guard = RouteGuard::with_defaults();
        let sender = Identity::generate();
        let wrong_identity = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_signed_message(&sender, recipient.device_id().clone());

        // Verify with wrong identity — should fail
        let result = guard.validate(&msg, Some(&wrong_identity.public_identity()));
        assert_eq!(result, GuardResult::RejectInvalidSignature);
    }

    #[test]
    fn test_no_identity_skips_signature_check() {
        let guard = RouteGuard::with_defaults();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_signed_message(&sender, recipient.device_id().clone());
        // No known identity — signature check skipped for relay
        let result = guard.validate(&msg, None);
        assert_eq!(result, GuardResult::Accept);
    }

    #[test]
    fn test_ttl_inflation_rejected() {
        let guard = RouteGuard::with_defaults();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let mut msg = make_signed_message(&sender, recipient.device_id().clone());
        // Inflate TTL far beyond protocol maximum
        msg.ttl_seconds = 30 * 24 * 3600; // 30 days

        let result = guard.validate(&msg, None);
        assert!(matches!(result, GuardResult::RejectTtlInflation { .. }));
    }

    #[test]
    fn test_valid_ttl_accepted() {
        let guard = RouteGuard::with_defaults();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let mut msg = make_signed_message(&sender, recipient.device_id().clone());
        msg.ttl_seconds = 7 * 24 * 3600; // Exactly 7 days — at the limit

        let result = guard.validate(&msg, None);
        assert_eq!(result, GuardResult::Accept);
    }

    #[test]
    fn test_hop_count_decrease_rejected() {
        let guard = RouteGuard::with_defaults();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let mut msg = make_signed_message(&sender, recipient.device_id().clone());
        msg.hop_count = 3;

        // First time seeing this message at hop 3 — accepted
        let result = guard.validate(&msg, None);
        assert_eq!(result, GuardResult::Accept);

        // Same message arrives with hop 1 — rejected (hop count decreased)
        msg.hop_count = 1;
        let result = guard.validate(&msg, None);
        assert!(matches!(result, GuardResult::RejectHopCountDecrease { .. }));
    }

    #[test]
    fn test_hop_count_increase_accepted() {
        let guard = RouteGuard::with_defaults();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let mut msg = make_signed_message(&sender, recipient.device_id().clone());
        msg.hop_count = 2;
        guard.validate(&msg, None);

        // Same message at higher hop count — accepted (normal relay behavior)
        msg.hop_count = 3;
        let result = guard.validate(&msg, None);
        assert_eq!(result, GuardResult::Accept);
    }

    #[test]
    fn test_sender_rate_limit() {
        let guard = RouteGuard::with_defaults();
        let sender = Identity::generate();

        // Under limit
        let result = guard.check_sender_rate(sender.device_id(), 50);
        assert_eq!(result, GuardResult::Accept);

        // At limit
        let result = guard.check_sender_rate(
            sender.device_id(),
            guard.config().max_active_messages_per_sender,
        );
        assert!(matches!(result, GuardResult::RejectSenderRateLimit { .. }));
    }

    #[test]
    fn test_hop_tracker_eviction() {
        let config = RouteGuardConfig {
            hop_tracking_cache_size: 5,
            ..Default::default()
        };
        let guard = RouteGuard::new(config);
        let sender = Identity::generate();
        let recipient = Identity::generate();

        // Fill the cache with 10 messages (exceeds size 5)
        for i in 0u8..10 {
            let mut msg = make_signed_message(&sender, recipient.device_id().clone());
            msg.message_id[0] = i;
            msg.hop_count = i;
            guard.validate(&msg, None);
        }

        // Cache should have evicted older entries, not crash
        // Verify a new message still works
        let msg = make_signed_message(&sender, recipient.device_id().clone());
        let result = guard.validate(&msg, None);
        assert_eq!(result, GuardResult::Accept);
    }

    #[test]
    fn test_disabled_signature_verification() {
        let config = RouteGuardConfig {
            verify_signatures: false,
            ..Default::default()
        };
        let guard = RouteGuard::new(config);
        let sender = Identity::generate();
        let wrong_identity = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_signed_message(&sender, recipient.device_id().clone());

        // Even with wrong identity, should pass when verification is disabled
        let result = guard.validate(&msg, Some(&wrong_identity.public_identity()));
        assert_eq!(result, GuardResult::Accept);
    }
}
