//! Priority message store with size budget and adaptive TTL.
//!
//! Stores messages awaiting forwarding with three priority tiers:
//! 1. Own messages (sent/received) — never auto-evicted
//! 2. Active relay messages — sorted by deliverability score
//! 3. Expired/low-priority relay messages — evicted first
//!
//! Messages have adaptive TTL that extends when crossing cluster boundaries
//! (bridge encounters), enabling long-distance delivery without GPS tracking.

use std::collections::HashMap;
use std::sync::Mutex;

use crate::protocol::message::MeshMessage;

/// Configuration for the priority message store.
/// All values are configurable — no hardcoded constants in business logic.
pub struct PriorityStoreConfig {
    /// Maximum storage budget in bytes for relay messages.
    pub max_storage_bytes: usize,
    /// Maximum number of messages to store (as a secondary limit).
    pub max_message_count: usize,

    /// Initial TTL for new messages (seconds). Default: 48 hours.
    pub initial_ttl_seconds: u32,
    /// TTL after first bridge encounter (seconds). Default: 72 hours.
    pub bridge_1_ttl_seconds: u32,
    /// TTL after second+ bridge encounter (seconds). Default: 7 days.
    pub bridge_2_ttl_seconds: u32,
    /// Absolute maximum TTL — never extended beyond this (seconds). Default: 7 days.
    pub absolute_max_ttl_seconds: u32,

    /// Initial spray copies for new messages.
    pub initial_spray_copies: u8,
    /// Spray copies refreshed on bridge encounter.
    pub bridge_spray_copies: u8,
}

impl Default for PriorityStoreConfig {
    fn default() -> Self {
        PriorityStoreConfig {
            max_storage_bytes: 50 * 1024 * 1024, // 50 MB
            max_message_count: 5000,

            initial_ttl_seconds: 48 * 3600,          // 48 hours
            bridge_1_ttl_seconds: 72 * 3600,         // 72 hours
            bridge_2_ttl_seconds: 7 * 24 * 3600,     // 7 days
            absolute_max_ttl_seconds: 7 * 24 * 3600, // 7 days hard cap

            initial_spray_copies: 8,
            bridge_spray_copies: 8,
        }
    }
}

/// Priority tier for stored messages.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum MessagePriority {
    /// Own messages (sent to us or sent by us). Never auto-evicted.
    Own = 0,
    /// Active relay messages with remaining spray copies and valid TTL.
    ActiveRelay = 1,
    /// Relay messages with no remaining spray copies (Wait phase).
    WaitingRelay = 2,
}

/// A stored message with routing metadata.
#[derive(Debug, Clone)]
pub struct StoredMessage {
    /// The actual mesh message.
    pub message: MeshMessage,
    /// Remaining spray copies for Binary Spray-and-Wait.
    pub remaining_copies: u8,
    /// Number of distinct clusters this message has crossed.
    pub clusters_crossed: u8,
    /// Priority tier (determines eviction order).
    pub priority: MessagePriority,
    /// Approximate size in bytes (cached for budget tracking).
    pub size_bytes: usize,
}

impl StoredMessage {
    /// Creates a new stored message with initial priority.
    fn new(message: MeshMessage, spray_copies: u8, priority: MessagePriority) -> Self {
        let size_bytes = message.wire_size();
        StoredMessage {
            message,
            remaining_copies: spray_copies,
            clusters_crossed: 0,
            priority,
            size_bytes,
        }
    }

    /// Returns a score for eviction ordering (lower = evict first).
    /// Considers: priority tier, TTL remaining, clusters crossed.
    fn eviction_score(&self) -> u64 {
        let priority_weight: u64 = match self.priority {
            MessagePriority::Own => u64::MAX, // Never evict
            MessagePriority::ActiveRelay => 1_000_000,
            MessagePriority::WaitingRelay => 0,
        };

        let ttl_remaining = self.ttl_remaining_seconds();
        let cluster_bonus = self.clusters_crossed as u64 * 10_000;

        priority_weight + ttl_remaining as u64 + cluster_bonus
    }

    /// Returns seconds until this message expires.
    fn ttl_remaining_seconds(&self) -> u32 {
        let now_ms = chrono::Utc::now().timestamp_millis();
        let age_seconds = ((now_ms - self.message.created_at_ms) / 1000).max(0) as u32;
        self.message.ttl_seconds.saturating_sub(age_seconds)
    }

    /// Returns true if the message has expired.
    fn is_expired(&self) -> bool {
        self.message.is_expired()
    }
}

/// Thread-safe priority message store.
pub struct PriorityStore {
    messages: Mutex<HashMap<[u8; 32], StoredMessage>>,
    config: PriorityStoreConfig,
    total_size_bytes: Mutex<usize>,
}

impl PriorityStore {
    /// Creates a new priority store with the given configuration.
    pub fn new(config: PriorityStoreConfig) -> Self {
        PriorityStore {
            messages: Mutex::new(HashMap::new()),
            config,
            total_size_bytes: Mutex::new(0),
        }
    }

    /// Creates a store with default configuration.
    pub fn with_defaults() -> Self {
        Self::new(PriorityStoreConfig::default())
    }

    /// Returns the store configuration (read-only).
    pub fn config(&self) -> &PriorityStoreConfig {
        &self.config
    }

    /// Stores a relay message with initial TTL and spray copies.
    /// Caps the message TTL to the configured absolute maximum.
    pub fn store_relay(&self, mut message: MeshMessage, spray_copies: u8) {
        // Enforce absolute TTL cap on insertion
        if message.ttl_seconds > self.config.absolute_max_ttl_seconds {
            message.ttl_seconds = self.config.absolute_max_ttl_seconds;
        }
        let stored = StoredMessage::new(message, spray_copies, MessagePriority::ActiveRelay);
        self.insert(stored);
    }

    /// Stores a message for the local user (own message). Never auto-evicted.
    pub fn store_own(&self, message: MeshMessage) {
        let stored = StoredMessage::new(message, 0, MessagePriority::Own);
        self.insert(stored);
    }

    /// Called when a bridge encounter is detected.
    /// Extends TTL and refreshes spray copies for all relay messages
    /// that haven't yet reached the maximum TTL.
    pub fn on_bridge_encounter(&self) -> usize {
        let mut messages = self.messages.lock().expect("Store lock");
        let mut upgraded = 0;

        for stored in messages.values_mut() {
            if stored.priority == MessagePriority::Own {
                continue; // Don't modify own messages
            }

            stored.clusters_crossed = stored.clusters_crossed.saturating_add(1);

            let new_ttl = match stored.clusters_crossed {
                1 => self.config.bridge_1_ttl_seconds,
                _ => self.config.bridge_2_ttl_seconds,
            };

            // Only extend TTL, never reduce it
            let capped_ttl = new_ttl.min(self.config.absolute_max_ttl_seconds);
            if capped_ttl > stored.message.ttl_seconds {
                stored.message.ttl_seconds = capped_ttl;
                upgraded += 1;
            }

            // Refresh spray copies if we had few remaining
            if stored.remaining_copies < self.config.bridge_spray_copies {
                stored.remaining_copies = self.config.bridge_spray_copies;
                // Reactivate waiting messages
                if stored.priority == MessagePriority::WaitingRelay {
                    stored.priority = MessagePriority::ActiveRelay;
                }
            }
        }

        upgraded
    }

    /// Gets messages to forward to a newly connected peer.
    /// Uses Binary Spray-and-Wait: gives half the remaining copies.
    pub fn get_messages_for_peer(
        &self,
        peer_device_id: &crate::crypto::identity::DeviceId,
    ) -> Vec<MeshMessage> {
        let mut messages = self.messages.lock().expect("Store lock");
        let mut to_forward = Vec::new();
        let mut updates: Vec<([u8; 32], u8, bool)> = Vec::new();

        for (msg_id, stored) in messages.iter() {
            if stored.is_expired() || stored.priority == MessagePriority::Own {
                continue;
            }

            if stored.message.recipient_id == *peer_device_id {
                // Direct delivery — send and mark for removal
                to_forward.push(stored.message.clone());
                updates.push((*msg_id, 0, true)); // remove = true
            } else if stored.remaining_copies > 1 {
                // Binary Spray: give half the copies
                let copies_to_give = stored.remaining_copies / 2;
                if copies_to_give > 0 {
                    to_forward.push(stored.message.clone());
                    updates.push((*msg_id, stored.remaining_copies - copies_to_give, false));
                }
            }
        }

        // Apply updates
        for (msg_id, new_copies, remove) in updates {
            if remove {
                if let Some(removed) = messages.remove(&msg_id) {
                    let mut size = self.total_size_bytes.lock().expect("Size lock");
                    *size = size.saturating_sub(removed.size_bytes);
                }
            } else if let Some(stored) = messages.get_mut(&msg_id) {
                stored.remaining_copies = new_copies;
                if new_copies == 0 {
                    stored.priority = MessagePriority::WaitingRelay;
                }
            }
        }

        to_forward
    }

    /// Processes a delivery ACK — removes the original message from store.
    /// Returns true if the message was found and removed.
    pub fn process_ack(&self, original_message_id: &[u8; 32]) -> bool {
        let mut messages = self.messages.lock().expect("Store lock");
        if let Some(removed) = messages.remove(original_message_id) {
            let mut size = self.total_size_bytes.lock().expect("Size lock");
            *size = size.saturating_sub(removed.size_bytes);
            true
        } else {
            false
        }
    }

    /// Removes all expired messages. Returns count removed.
    pub fn prune_expired(&self) -> usize {
        let mut messages = self.messages.lock().expect("Store lock");
        let before = messages.len();

        let expired_ids: Vec<[u8; 32]> = messages
            .iter()
            .filter(|(_, s)| s.is_expired())
            .map(|(id, _)| *id)
            .collect();

        let mut size = self.total_size_bytes.lock().expect("Size lock");
        for id in &expired_ids {
            if let Some(removed) = messages.remove(id) {
                *size = size.saturating_sub(removed.size_bytes);
            }
        }

        before - messages.len()
    }

    /// Returns the number of stored messages.
    pub fn len(&self) -> usize {
        self.messages.lock().expect("Store lock").len()
    }

    /// Returns true if the store is empty.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Returns total bytes used by stored messages.
    pub fn total_bytes(&self) -> usize {
        *self.total_size_bytes.lock().expect("Size lock")
    }

    /// Returns a snapshot of store statistics.
    pub fn stats(&self) -> StoreStats {
        let messages = self.messages.lock().expect("Store lock");
        let total_bytes = *self.total_size_bytes.lock().expect("Size lock");

        let mut own_count = 0usize;
        let mut active_relay_count = 0usize;
        let mut waiting_relay_count = 0usize;

        for stored in messages.values() {
            match stored.priority {
                MessagePriority::Own => own_count += 1,
                MessagePriority::ActiveRelay => active_relay_count += 1,
                MessagePriority::WaitingRelay => waiting_relay_count += 1,
            }
        }

        StoreStats {
            total_messages: messages.len(),
            own_messages: own_count,
            active_relay_messages: active_relay_count,
            waiting_relay_messages: waiting_relay_count,
            total_bytes,
            budget_bytes: self.config.max_storage_bytes,
        }
    }

    /// Inserts a message, evicting lowest-priority messages if needed.
    fn insert(&self, stored: StoredMessage) {
        let msg_id = stored.message.message_id;
        let msg_size = stored.size_bytes;

        let mut messages = self.messages.lock().expect("Store lock");
        let mut total_size = self.total_size_bytes.lock().expect("Size lock");

        // Evict by count limit
        while messages.len() >= self.config.max_message_count {
            if !self.evict_lowest_priority(&mut messages, &mut total_size) {
                break;
            }
        }

        // Evict by size budget
        while *total_size + msg_size > self.config.max_storage_bytes {
            if !self.evict_lowest_priority(&mut messages, &mut total_size) {
                break;
            }
        }

        *total_size += msg_size;
        messages.insert(msg_id, stored);
    }

    /// Evicts the single lowest-priority message. Returns false if nothing to evict.
    fn evict_lowest_priority(
        &self,
        messages: &mut HashMap<[u8; 32], StoredMessage>,
        total_size: &mut usize,
    ) -> bool {
        // Find the message with the lowest eviction score (excluding Own)
        let victim_id = messages
            .iter()
            .filter(|(_, s)| s.priority != MessagePriority::Own)
            .min_by_key(|(_, s)| s.eviction_score())
            .map(|(id, _)| *id);

        if let Some(id) = victim_id {
            if let Some(removed) = messages.remove(&id) {
                *total_size = total_size.saturating_sub(removed.size_bytes);
                return true;
            }
        }

        false
    }
}

/// Statistics about the message store.
#[derive(Debug, Clone)]
pub struct StoreStats {
    pub total_messages: usize,
    pub own_messages: usize,
    pub active_relay_messages: usize,
    pub waiting_relay_messages: usize,
    pub total_bytes: usize,
    pub budget_bytes: usize,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::identity::Identity;
    use crate::protocol::message::{ContentType, MessageBuilder};

    fn make_config() -> PriorityStoreConfig {
        PriorityStoreConfig {
            max_storage_bytes: 10 * 1024, // 10 KB for testing
            max_message_count: 10,
            ..Default::default()
        }
    }

    fn make_message(
        sender: &Identity,
        recipient_id: crate::crypto::identity::DeviceId,
    ) -> MeshMessage {
        MessageBuilder::new(sender.device_id().clone(), recipient_id)
            .content_type(ContentType::Text)
            .payload(b"test message for store".to_vec())
            .build(|data| sender.sign(data))
    }

    #[test]
    fn test_store_and_retrieve_relay() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();
        let recipient = Identity::generate();
        let msg = make_message(&sender, recipient.device_id().clone());

        store.store_relay(msg, 8);
        assert_eq!(store.len(), 1);
        assert!(store.total_bytes() > 0);
    }

    #[test]
    fn test_own_messages_never_evicted() {
        let config = PriorityStoreConfig {
            max_message_count: 3,
            max_storage_bytes: 100 * 1024 * 1024,
            ..Default::default()
        };
        let store = PriorityStore::new(config);
        let sender = Identity::generate();
        let recipient = Identity::generate();

        // Store 2 own messages
        let msg1 = make_message(&sender, recipient.device_id().clone());
        let msg2 = make_message(&sender, recipient.device_id().clone());
        store.store_own(msg1);
        store.store_own(msg2);

        // Store relay messages that should cause eviction
        for _ in 0..5 {
            let r = Identity::generate();
            let msg = make_message(&sender, r.device_id().clone());
            store.store_relay(msg, 4);
        }

        // Own messages should still be there
        let stats = store.stats();
        assert_eq!(stats.own_messages, 2);
        assert!(stats.total_messages <= 3); // Count limit respected
    }

    #[test]
    fn test_bridge_encounter_extends_ttl() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_message(&sender, recipient.device_id().clone());
        let original_ttl = msg.ttl_seconds;
        store.store_relay(msg, 4);

        // Simulate first bridge encounter
        let upgraded = store.on_bridge_encounter();
        assert_eq!(upgraded, 1);

        let messages = store.messages.lock().unwrap();
        let stored = messages.values().next().unwrap();
        assert!(stored.message.ttl_seconds >= store.config.bridge_1_ttl_seconds);
        assert_eq!(stored.clusters_crossed, 1);
        assert!(stored.message.ttl_seconds > original_ttl);
    }

    #[test]
    fn test_second_bridge_extends_to_max() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_message(&sender, recipient.device_id().clone());
        store.store_relay(msg, 4);

        store.on_bridge_encounter(); // First bridge
        store.on_bridge_encounter(); // Second bridge

        let messages = store.messages.lock().unwrap();
        let stored = messages.values().next().unwrap();
        assert_eq!(stored.clusters_crossed, 2);
        assert!(stored.message.ttl_seconds <= store.config.absolute_max_ttl_seconds);
    }

    #[test]
    fn test_bridge_refreshes_spray_copies() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_message(&sender, recipient.device_id().clone());
        store.store_relay(msg, 2); // Start with low copies

        store.on_bridge_encounter();

        let messages = store.messages.lock().unwrap();
        let stored = messages.values().next().unwrap();
        assert_eq!(stored.remaining_copies, store.config.bridge_spray_copies);
    }

    #[test]
    fn test_get_messages_for_direct_delivery() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_message(&sender, recipient.device_id().clone());
        store.store_relay(msg, 4);
        assert_eq!(store.len(), 1);

        // Recipient connects — direct delivery
        let forwarded = store.get_messages_for_peer(recipient.device_id());
        assert_eq!(forwarded.len(), 1);
        assert_eq!(store.len(), 0); // Removed after direct delivery
    }

    #[test]
    fn test_get_messages_binary_spray() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();
        let recipient = Identity::generate();
        let relay = Identity::generate();

        let msg = make_message(&sender, recipient.device_id().clone());
        store.store_relay(msg, 8);

        // Relay peer connects — binary spray (give half)
        let forwarded = store.get_messages_for_peer(relay.device_id());
        assert_eq!(forwarded.len(), 1);
        assert_eq!(store.len(), 1); // Still in store with remaining copies

        let messages = store.messages.lock().unwrap();
        let stored = messages.values().next().unwrap();
        assert_eq!(stored.remaining_copies, 4); // 8 / 2 = 4 remaining
    }

    #[test]
    fn test_process_ack_removes_message() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_message(&sender, recipient.device_id().clone());
        let msg_id = msg.message_id;
        store.store_relay(msg, 4);

        assert_eq!(store.len(), 1);
        assert!(store.process_ack(&msg_id));
        assert_eq!(store.len(), 0);
    }

    #[test]
    fn test_process_ack_unknown_message() {
        let store = PriorityStore::new(make_config());
        assert!(!store.process_ack(&[0u8; 32]));
    }

    #[test]
    fn test_prune_expired() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();
        let recipient = Identity::generate();

        // Create an already-expired message
        let mut msg =
            MessageBuilder::new(sender.device_id().clone(), recipient.device_id().clone())
                .ttl_seconds(0)
                .payload(b"expired".to_vec())
                .build(|data| sender.sign(data));
        msg.created_at_ms -= 2000; // Ensure expired

        store.store_relay(msg, 4);
        assert_eq!(store.len(), 1);

        let pruned = store.prune_expired();
        assert_eq!(pruned, 1);
        assert_eq!(store.len(), 0);
    }

    #[test]
    fn test_eviction_by_count_limit() {
        let config = PriorityStoreConfig {
            max_message_count: 5,
            max_storage_bytes: 100 * 1024 * 1024,
            ..Default::default()
        };
        let store = PriorityStore::new(config);
        let sender = Identity::generate();

        for _ in 0..10 {
            let recipient = Identity::generate();
            let msg = make_message(&sender, recipient.device_id().clone());
            store.store_relay(msg, 4);
        }

        assert!(store.len() <= 5);
    }

    #[test]
    fn test_store_stats() {
        let store = PriorityStore::new(make_config());
        let sender = Identity::generate();

        let r1 = Identity::generate();
        let r2 = Identity::generate();

        store.store_own(make_message(&sender, r1.device_id().clone()));
        store.store_relay(make_message(&sender, r2.device_id().clone()), 4);

        let stats = store.stats();
        assert_eq!(stats.total_messages, 2);
        assert_eq!(stats.own_messages, 1);
        assert_eq!(stats.active_relay_messages, 1);
        assert_eq!(stats.waiting_relay_messages, 0);
        assert!(stats.total_bytes > 0);
    }

    #[test]
    fn test_ttl_never_exceeds_absolute_max() {
        let config = PriorityStoreConfig {
            absolute_max_ttl_seconds: 100,
            bridge_1_ttl_seconds: 200, // Higher than absolute max
            bridge_2_ttl_seconds: 300,
            ..Default::default()
        };
        let store = PriorityStore::new(config);
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_message(&sender, recipient.device_id().clone());
        store.store_relay(msg, 4);

        store.on_bridge_encounter();

        let messages = store.messages.lock().unwrap();
        let stored = messages.values().next().unwrap();
        // TTL should be capped at absolute_max_ttl_seconds
        assert!(stored.message.ttl_seconds <= 100);
    }
}
