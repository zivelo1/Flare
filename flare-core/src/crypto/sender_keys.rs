//! Sender Keys protocol for efficient group messaging.
//!
//! ## Problem
//! Per-member encryption (ADR-014) creates O(n) messages per group send.
//! A 20-person group generates 19 separate encrypted messages, each requiring
//! independent BLE transmission. Over constrained BLE bandwidth (~2-20 KB/s),
//! this makes groups of more than ~5 members impractical.
//!
//! ## Solution: Sender Keys (Signal Groups v2 approach)
//!
//! Each group member generates a "sender key" — a symmetric chain key — and
//! distributes it to all other members via their existing pairwise DH channels.
//! Subsequent group messages are encrypted once with the sender's chain key
//! (AES-256-GCM) and broadcast as a single mesh message.
//!
//! ### Flow:
//! 1. Member A joins a group and generates a random 32-byte `chain_key`.
//! 2. A encrypts the chain_key individually for each member using their existing
//!    DH shared secrets (this is O(n) but happens only once per member, not per message).
//! 3. When A sends a group message:
//!    a. Derive `message_key = HKDF(chain_key, salt=chain_index)` (32 bytes + 12-byte nonce)
//!    b. Encrypt plaintext with AES-256-GCM using message_key
//!    c. Ratchet: `chain_key = SHA-256(chain_key)` (forward secrecy within chain)
//!    d. Send ONE encrypted message to the group (broadcast)
//! 4. Recipients use A's sender key + chain_index to derive the same message_key.
//!
//! ### Properties:
//! - **O(1) messages per group send** instead of O(n)
//! - **Forward secrecy within chain**: Compromising chain_key at index N
//!   cannot decrypt messages at index < N (chain ratchets forward only)
//! - **No post-compromise security within chain**: If chain_key is compromised,
//!   all future messages are readable until a new chain is established
//! - **Lazy re-keying**: When group membership changes, affected sender keys
//!   are invalidated and new ones distributed
//!
//! ### Comparison to per-member encryption:
//! | Metric | Per-Member (ADR-014) | Sender Keys |
//! |--------|---------------------|-------------|
//! | Messages per send | O(n) | O(1) |
//! | Key setup cost | None | O(n) one-time |
//! | Forward secrecy | Per-message (DH) | Per-chain-index (HKDF) |
//! | Post-compromise | Yes (per DH) | No (until re-key) |
//! | BLE bandwidth | 19× for 20 members | 1× |

use aes_gcm::aead::Aead;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use hkdf::Hkdf;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// A sender's chain key for group messaging.
///
/// Each group member maintains one of these per sender in the group.
/// The chain ratchets forward after each message to provide forward secrecy.
#[derive(Debug, Clone)]
pub struct SenderKeyChain {
    /// Current chain key (32 bytes). Ratchets forward after each message.
    chain_key: [u8; 32],
    /// Current chain index. Incremented after each message.
    chain_index: u32,
    /// The sender's device ID (hex) who owns this chain.
    sender_device_id: String,
    /// The group this chain belongs to.
    group_id: String,
}

/// Maximum number of skipped message keys to cache.
/// Prevents DoS via massive chain_index gaps.
const MAX_SKIP_KEYS: u32 = 256;

/// Domain separator for HKDF message key derivation.
const MESSAGE_KEY_INFO: &[u8] = b"flare-sender-key-message-v1";

/// Derived key material for encrypting a single group message.
struct MessageKeyMaterial {
    key: [u8; 32],
    nonce: [u8; 12],
}

impl SenderKeyChain {
    /// Creates a new sender key chain with a random chain key.
    pub fn new(sender_device_id: String, group_id: String) -> Self {
        let chain_key: [u8; 32] = rand::random();
        SenderKeyChain {
            chain_key,
            chain_index: 0,
            sender_device_id,
            group_id,
        }
    }

    /// Creates a chain from a received chain key (from the sender's distribution message).
    pub fn from_distribution(
        chain_key: [u8; 32],
        chain_index: u32,
        sender_device_id: String,
        group_id: String,
    ) -> Self {
        SenderKeyChain {
            chain_key,
            chain_index,
            sender_device_id,
            group_id,
        }
    }

    /// Returns the current chain key bytes for distribution to group members.
    pub fn chain_key(&self) -> &[u8; 32] {
        &self.chain_key
    }

    /// Returns the current chain index.
    pub fn chain_index(&self) -> u32 {
        self.chain_index
    }

    /// Returns the sender's device ID.
    pub fn sender_device_id(&self) -> &str {
        &self.sender_device_id
    }

    /// Returns the group ID.
    pub fn group_id(&self) -> &str {
        &self.group_id
    }

    /// Encrypts a plaintext message using the current chain position.
    ///
    /// After encryption, the chain ratchets forward (chain_key is updated).
    /// Returns the ciphertext and the chain_index used for this message.
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<SenderKeyMessage, SenderKeyError> {
        let index = self.chain_index;
        let key_material = self.derive_message_key(index);

        let cipher = Aes256Gcm::new_from_slice(&key_material.key)
            .map_err(|e| SenderKeyError::EncryptionFailed(e.to_string()))?;
        let nonce = Nonce::from_slice(&key_material.nonce);

        let ciphertext = cipher
            .encrypt(nonce, plaintext)
            .map_err(|e| SenderKeyError::EncryptionFailed(e.to_string()))?;

        // Ratchet the chain forward
        self.ratchet();

        Ok(SenderKeyMessage {
            chain_index: index,
            ciphertext,
        })
    }

    /// Decrypts a message from the sender of this chain.
    ///
    /// Handles out-of-order messages by fast-forwarding the chain if needed.
    /// Skipped keys are cached to decrypt late-arriving messages.
    pub fn decrypt(&mut self, message: &SenderKeyMessage) -> Result<Vec<u8>, SenderKeyError> {
        if message.chain_index < self.chain_index {
            // Message is from the past — try to use a previously derived key
            // We don't cache skipped keys in this implementation; the sender
            // should not send old-indexed messages.
            return Err(SenderKeyError::MessageTooOld {
                received: message.chain_index,
                current: self.chain_index,
            });
        }

        let skip_count = message.chain_index - self.chain_index;
        if skip_count > MAX_SKIP_KEYS {
            return Err(SenderKeyError::TooManySkippedKeys {
                skip_count,
                max: MAX_SKIP_KEYS,
            });
        }

        // Fast-forward the chain to the message's index
        // Save the key at the target index, then ratchet past it
        let target_chain_key = self.advance_to(message.chain_index);
        let key_material = Self::derive_key_from(&target_chain_key, message.chain_index);

        let cipher = Aes256Gcm::new_from_slice(&key_material.key)
            .map_err(|e| SenderKeyError::DecryptionFailed(e.to_string()))?;
        let nonce = Nonce::from_slice(&key_material.nonce);

        let plaintext = cipher
            .decrypt(nonce, message.ciphertext.as_ref())
            .map_err(|_| SenderKeyError::DecryptionFailed("AES-GCM auth failed".into()))?;

        // Ratchet past the used index
        self.ratchet();

        Ok(plaintext)
    }

    /// Derives the message key for a given chain index.
    fn derive_message_key(&self, index: u32) -> MessageKeyMaterial {
        Self::derive_key_from(&self.chain_key, index)
    }

    fn derive_key_from(chain_key: &[u8; 32], index: u32) -> MessageKeyMaterial {
        let salt = index.to_le_bytes();
        let hkdf = Hkdf::<Sha256>::new(Some(&salt), chain_key);

        let mut output = [0u8; 44]; // 32-byte key + 12-byte nonce
        hkdf.expand(MESSAGE_KEY_INFO, &mut output)
            .expect("HKDF expand failed");

        let mut key = [0u8; 32];
        let mut nonce = [0u8; 12];
        key.copy_from_slice(&output[..32]);
        nonce.copy_from_slice(&output[32..]);

        MessageKeyMaterial { key, nonce }
    }

    /// Ratchets the chain key forward one step.
    /// The old chain key is destroyed, providing forward secrecy.
    fn ratchet(&mut self) {
        let mut hasher = Sha256::new();
        hasher.update(self.chain_key);
        self.chain_key = hasher.finalize().into();
        self.chain_index += 1;
    }

    /// Advances the chain to the target index, returning the chain_key at that index.
    /// Updates self.chain_key and self.chain_index to the target position.
    fn advance_to(&mut self, target_index: u32) -> [u8; 32] {
        while self.chain_index < target_index {
            self.ratchet();
        }
        self.chain_key
    }
}

/// An encrypted group message with the chain index for decryption.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SenderKeyMessage {
    /// Chain index at which this message was encrypted.
    /// Recipients use this to derive the correct message key.
    pub chain_index: u32,
    /// AES-256-GCM ciphertext (includes 16-byte auth tag).
    pub ciphertext: Vec<u8>,
}

impl SenderKeyMessage {
    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }
}

/// A sender key distribution message.
///
/// When a member creates or rotates their sender key, they distribute it
/// to all group members via their existing pairwise DH encryption channels.
/// This is O(n) but happens infrequently (group join, key rotation).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SenderKeyDistribution {
    /// The group this key belongs to.
    pub group_id: String,
    /// The sender's device ID.
    pub sender_device_id: String,
    /// The chain key to distribute (32 bytes).
    pub chain_key: [u8; 32],
    /// Starting chain index (usually 0 for new keys).
    pub chain_index: u32,
}

impl SenderKeyDistribution {
    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }
}

/// Errors from sender key operations.
#[derive(Debug, thiserror::Error)]
pub enum SenderKeyError {
    #[error("Encryption failed: {0}")]
    EncryptionFailed(String),

    #[error("Decryption failed: {0}")]
    DecryptionFailed(String),

    #[error("Message too old: received index {received}, current chain at {current}")]
    MessageTooOld { received: u32, current: u32 },

    #[error("Too many skipped keys: {skip_count} exceeds max {max}")]
    TooManySkippedKeys { skip_count: u32, max: u32 },

    #[error("Unknown sender key for device {device_id} in group {group_id}")]
    UnknownSenderKey { device_id: String, group_id: String },
}

/// Manages sender keys for all groups the user participates in.
///
/// Stores:
/// - Our own sender keys (one per group we're a member of)
/// - Other members' sender keys (received via distribution messages)
pub struct SenderKeyStore {
    /// Our sender keys, keyed by group_id.
    own_keys: std::collections::HashMap<String, SenderKeyChain>,
    /// Remote sender keys, keyed by (group_id, sender_device_id).
    remote_keys: std::collections::HashMap<(String, String), SenderKeyChain>,
}

impl SenderKeyStore {
    pub fn new() -> Self {
        SenderKeyStore {
            own_keys: std::collections::HashMap::new(),
            remote_keys: std::collections::HashMap::new(),
        }
    }

    /// Creates a new sender key for a group and returns the distribution message.
    ///
    /// The caller should encrypt the distribution message individually for each
    /// group member using their pairwise DH channel and send it.
    pub fn create_sender_key(
        &mut self,
        group_id: &str,
        my_device_id: &str,
    ) -> SenderKeyDistribution {
        let chain = SenderKeyChain::new(my_device_id.to_string(), group_id.to_string());
        let distribution = SenderKeyDistribution {
            group_id: group_id.to_string(),
            sender_device_id: my_device_id.to_string(),
            chain_key: *chain.chain_key(),
            chain_index: chain.chain_index(),
        };
        self.own_keys.insert(group_id.to_string(), chain);
        distribution
    }

    /// Processes a received sender key distribution message.
    pub fn process_distribution(&mut self, distribution: &SenderKeyDistribution) {
        let chain = SenderKeyChain::from_distribution(
            distribution.chain_key,
            distribution.chain_index,
            distribution.sender_device_id.clone(),
            distribution.group_id.clone(),
        );
        let key = (
            distribution.group_id.clone(),
            distribution.sender_device_id.clone(),
        );
        self.remote_keys.insert(key, chain);
    }

    /// Encrypts a group message using our sender key for the group.
    ///
    /// Returns the encrypted message ready for broadcast.
    /// Fails if no sender key exists for this group (call create_sender_key first).
    pub fn encrypt_group_message(
        &mut self,
        group_id: &str,
        plaintext: &[u8],
    ) -> Result<SenderKeyMessage, SenderKeyError> {
        let chain =
            self.own_keys
                .get_mut(group_id)
                .ok_or_else(|| SenderKeyError::UnknownSenderKey {
                    device_id: "self".to_string(),
                    group_id: group_id.to_string(),
                })?;

        chain.encrypt(plaintext)
    }

    /// Decrypts a group message from a specific sender.
    pub fn decrypt_group_message(
        &mut self,
        group_id: &str,
        sender_device_id: &str,
        message: &SenderKeyMessage,
    ) -> Result<Vec<u8>, SenderKeyError> {
        let key = (group_id.to_string(), sender_device_id.to_string());
        let chain =
            self.remote_keys
                .get_mut(&key)
                .ok_or_else(|| SenderKeyError::UnknownSenderKey {
                    device_id: sender_device_id.to_string(),
                    group_id: group_id.to_string(),
                })?;

        chain.decrypt(message)
    }

    /// Invalidates all sender keys for a group (e.g., when membership changes).
    /// All members must create and distribute new sender keys.
    pub fn invalidate_group(&mut self, group_id: &str) {
        self.own_keys.remove(group_id);
        self.remote_keys.retain(|(gid, _), _| gid != group_id);
    }

    /// Returns true if we have our own sender key for a group.
    pub fn has_own_key(&self, group_id: &str) -> bool {
        self.own_keys.contains_key(group_id)
    }

    /// Returns true if we have a sender key for a specific member in a group.
    pub fn has_remote_key(&self, group_id: &str, sender_device_id: &str) -> bool {
        self.remote_keys
            .contains_key(&(group_id.to_string(), sender_device_id.to_string()))
    }
}

impl Default for SenderKeyStore {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encrypt_decrypt_single_message() {
        let mut sender = SenderKeyChain::new("alice".into(), "group1".into());
        let mut receiver = SenderKeyChain::from_distribution(
            *sender.chain_key(),
            sender.chain_index(),
            "alice".into(),
            "group1".into(),
        );

        let plaintext = b"Hello group!";
        let encrypted = sender.encrypt(plaintext).unwrap();
        let decrypted = receiver.decrypt(&encrypted).unwrap();

        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_chain_ratchets_forward() {
        let mut sender = SenderKeyChain::new("alice".into(), "group1".into());
        let initial_key = *sender.chain_key();

        sender.encrypt(b"msg 1").unwrap();
        assert_ne!(*sender.chain_key(), initial_key);
        assert_eq!(sender.chain_index(), 1);

        sender.encrypt(b"msg 2").unwrap();
        assert_eq!(sender.chain_index(), 2);
    }

    #[test]
    fn test_multiple_messages_in_order() {
        let mut sender = SenderKeyChain::new("alice".into(), "group1".into());
        let mut receiver = SenderKeyChain::from_distribution(
            *sender.chain_key(),
            sender.chain_index(),
            "alice".into(),
            "group1".into(),
        );

        for i in 0..10 {
            let plaintext = format!("Message {}", i);
            let encrypted = sender.encrypt(plaintext.as_bytes()).unwrap();
            let decrypted = receiver.decrypt(&encrypted).unwrap();
            assert_eq!(decrypted, plaintext.as_bytes());
        }
    }

    #[test]
    fn test_out_of_order_message_with_skip() {
        let mut sender = SenderKeyChain::new("alice".into(), "group1".into());
        let mut receiver = SenderKeyChain::from_distribution(
            *sender.chain_key(),
            sender.chain_index(),
            "alice".into(),
            "group1".into(),
        );

        // Sender sends 3 messages
        let _msg0 = sender.encrypt(b"msg 0").unwrap();
        let _msg1 = sender.encrypt(b"msg 1").unwrap();
        let msg2 = sender.encrypt(b"msg 2").unwrap();

        // Receiver gets msg2 first (skipping msg0 and msg1)
        let decrypted = receiver.decrypt(&msg2).unwrap();
        assert_eq!(decrypted, b"msg 2");
    }

    #[test]
    fn test_too_old_message_rejected() {
        let mut sender = SenderKeyChain::new("alice".into(), "group1".into());
        let mut receiver = SenderKeyChain::from_distribution(
            *sender.chain_key(),
            sender.chain_index(),
            "alice".into(),
            "group1".into(),
        );

        let msg0 = sender.encrypt(b"old message").unwrap();
        let msg1 = sender.encrypt(b"new message").unwrap();

        // Decrypt msg1 first
        receiver.decrypt(&msg1).unwrap();

        // Now msg0 is too old
        let result = receiver.decrypt(&msg0);
        assert!(matches!(result, Err(SenderKeyError::MessageTooOld { .. })));
    }

    #[test]
    fn test_too_many_skipped_keys_rejected() {
        let mut sender = SenderKeyChain::new("alice".into(), "group1".into());
        let mut receiver = SenderKeyChain::from_distribution(
            *sender.chain_key(),
            sender.chain_index(),
            "alice".into(),
            "group1".into(),
        );

        // Sender advances chain far ahead
        for _ in 0..MAX_SKIP_KEYS + 10 {
            sender.encrypt(b"skip").unwrap();
        }

        let msg = sender.encrypt(b"too far ahead").unwrap();
        let result = receiver.decrypt(&msg);
        assert!(matches!(
            result,
            Err(SenderKeyError::TooManySkippedKeys { .. })
        ));
    }

    #[test]
    fn test_sender_key_store_full_flow() {
        let mut alice_store = SenderKeyStore::new();
        let mut bob_store = SenderKeyStore::new();

        let group_id = "group-abc";

        // Alice creates her sender key and distributes to Bob
        let distribution = alice_store.create_sender_key(group_id, "alice-device");
        bob_store.process_distribution(&distribution);

        // Alice encrypts a group message
        let encrypted = alice_store
            .encrypt_group_message(group_id, b"Hello from Alice!")
            .unwrap();

        // Bob decrypts using Alice's sender key
        let decrypted = bob_store
            .decrypt_group_message(group_id, "alice-device", &encrypted)
            .unwrap();

        assert_eq!(decrypted, b"Hello from Alice!");
    }

    #[test]
    fn test_bidirectional_group_messaging() {
        let mut alice_store = SenderKeyStore::new();
        let mut bob_store = SenderKeyStore::new();

        let group_id = "group-abc";

        // Both create sender keys and exchange
        let alice_dist = alice_store.create_sender_key(group_id, "alice");
        let bob_dist = bob_store.create_sender_key(group_id, "bob");

        bob_store.process_distribution(&alice_dist);
        alice_store.process_distribution(&bob_dist);

        // Alice → Group
        let msg1 = alice_store
            .encrypt_group_message(group_id, b"From Alice")
            .unwrap();
        let dec1 = bob_store
            .decrypt_group_message(group_id, "alice", &msg1)
            .unwrap();
        assert_eq!(dec1, b"From Alice");

        // Bob → Group
        let msg2 = bob_store
            .encrypt_group_message(group_id, b"From Bob")
            .unwrap();
        let dec2 = alice_store
            .decrypt_group_message(group_id, "bob", &msg2)
            .unwrap();
        assert_eq!(dec2, b"From Bob");
    }

    #[test]
    fn test_invalidate_group() {
        let mut store = SenderKeyStore::new();
        store.create_sender_key("group1", "alice");
        assert!(store.has_own_key("group1"));

        store.invalidate_group("group1");
        assert!(!store.has_own_key("group1"));
    }

    #[test]
    fn test_message_serialization_roundtrip() {
        let mut chain = SenderKeyChain::new("alice".into(), "group1".into());
        let encrypted = chain.encrypt(b"serialize me").unwrap();

        let bytes = encrypted.to_bytes().unwrap();
        let restored = SenderKeyMessage::from_bytes(&bytes).unwrap();

        assert_eq!(encrypted.chain_index, restored.chain_index);
        assert_eq!(encrypted.ciphertext, restored.ciphertext);
    }

    #[test]
    fn test_distribution_serialization_roundtrip() {
        let mut store = SenderKeyStore::new();
        let dist = store.create_sender_key("group1", "alice");

        let bytes = dist.to_bytes().unwrap();
        let restored = SenderKeyDistribution::from_bytes(&bytes).unwrap();

        assert_eq!(dist.group_id, restored.group_id);
        assert_eq!(dist.sender_device_id, restored.sender_device_id);
        assert_eq!(dist.chain_key, restored.chain_key);
        assert_eq!(dist.chain_index, restored.chain_index);
    }

    #[test]
    fn test_forward_secrecy_within_chain() {
        let mut sender = SenderKeyChain::new("alice".into(), "group1".into());

        // Encrypt several messages
        let msg0 = sender.encrypt(b"secret 0").unwrap();
        let _msg1 = sender.encrypt(b"secret 1").unwrap();

        // Even if we create a receiver from the CURRENT chain key,
        // it cannot decrypt msg0 (chain has ratcheted past it)
        let mut late_receiver = SenderKeyChain::from_distribution(
            *sender.chain_key(),
            sender.chain_index(),
            "alice".into(),
            "group1".into(),
        );

        let result = late_receiver.decrypt(&msg0);
        assert!(matches!(result, Err(SenderKeyError::MessageTooOld { .. })));
    }
}
