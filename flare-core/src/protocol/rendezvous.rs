//! Blind Rendezvous discovery protocol for decentralized peer discovery.
//!
//! Enables two devices to find each other on the mesh without servers
//! by deriving a shared rendezvous token from mutual knowledge:
//! - Shared passphrase (highest security)
//! - Bilateral phone number hash (convenience)
//! - Contact list import (bulk discovery)
//!
//! Flow:
//! 1. Both parties independently compute the same token from shared knowledge
//! 2. Both broadcast RouteRequest messages containing the token
//! 3. When a match is found, exchange encrypted public identities via RouteReply
//! 4. Tokens rotate weekly to limit passive collection window

use std::collections::HashMap;

use aes_gcm::aead::Aead;
use aes_gcm::{Aes256Gcm, KeyInit, Nonce};
use argon2::Argon2;
use hkdf::Hkdf;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};

use crate::crypto::identity::PublicIdentity;

/// Argon2id parameters for token derivation.
/// High enough to resist brute-force but usable on mobile (~1 sec per token).
const ARGON2_TIME_COST: u32 = 3;
const ARGON2_MEMORY_COST: u32 = 65536; // 64MB
const ARGON2_PARALLELISM: u32 = 1;

/// Number of leading zero bits required for proof-of-work.
/// 16 bits ≈ 65K iterations ≈ 50ms on a modern phone.
const POW_DIFFICULTY_BITS: u32 = 16;

/// Token size in bytes.
pub const TOKEN_SIZE: usize = 8;

/// A rendezvous payload broadcast as a RouteRequest.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RendezvousPayload {
    /// Argon2id-derived rendezvous token (8 bytes).
    pub token: [u8; TOKEN_SIZE],
    /// X25519 ephemeral public key for encrypting the reply.
    pub ephemeral_public_key: [u8; 32],
    /// Proof-of-work nonce (anti-spam).
    pub proof_of_work: [u8; 4],
}

/// A rendezvous reply sent as a RouteReply.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RendezvousReply {
    /// Echoed token to let the querier match the response.
    pub token: [u8; TOKEN_SIZE],
    /// PublicIdentity encrypted with ephemeral DH shared secret.
    pub encrypted_identity: Vec<u8>,
}

/// Discovery mode used to generate the token.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RendezvousMode {
    SharedPhrase,
    PhoneNumber,
    ContactImport,
}

impl RendezvousPayload {
    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }
}

impl RendezvousReply {
    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }
}

// ── Token Generation ──────────────────────────────────────────────

/// Returns the current epoch week (weeks since Unix epoch).
/// Tokens rotate weekly to limit passive collection window.
pub fn epoch_week() -> u64 {
    let now = chrono::Utc::now().timestamp() as u64;
    now / (7 * 86400)
}

/// Derives an 8-byte rendezvous token from arbitrary input using Argon2id.
///
/// The high memory cost (64MB) makes brute-force enumeration of phone numbers
/// impractical even for nation-state adversaries.
pub fn generate_token(input: &[u8]) -> [u8; TOKEN_SIZE] {
    let week = epoch_week();
    generate_token_with_epoch(input, week)
}

/// Derives a token with a specific epoch (for testing).
pub fn generate_token_with_epoch(input: &[u8], epoch: u64) -> [u8; TOKEN_SIZE] {
    let mut salt = Vec::with_capacity(input.len() + 24);
    salt.extend_from_slice(b"flare-rendezvous-");
    salt.extend_from_slice(&epoch.to_le_bytes());

    // Argon2id requires salt to be at least 8 bytes
    if salt.len() < 8 {
        salt.resize(8, 0);
    }

    let params = argon2::Params::new(
        ARGON2_MEMORY_COST,
        ARGON2_TIME_COST,
        ARGON2_PARALLELISM,
        Some(TOKEN_SIZE),
    )
    .expect("valid argon2 params");

    let argon2 = Argon2::new(argon2::Algorithm::Argon2id, argon2::Version::V0x13, params);

    let mut output = [0u8; TOKEN_SIZE];
    argon2
        .hash_password_into(input, &salt, &mut output)
        .expect("argon2 hash");
    output
}

/// Generates a token from a shared passphrase.
/// Normalizes: lowercase, trim, collapse whitespace.
pub fn generate_phrase_token(phrase: &str) -> [u8; TOKEN_SIZE] {
    let normalized = normalize_phrase(phrase);
    generate_token(normalized.as_bytes())
}

/// Generates a token from a shared passphrase with a specific epoch (for testing).
pub fn generate_phrase_token_with_epoch(phrase: &str, epoch: u64) -> [u8; TOKEN_SIZE] {
    let normalized = normalize_phrase(phrase);
    generate_token_with_epoch(normalized.as_bytes(), epoch)
}

/// Generates a bilateral token from two phone numbers.
/// Both parties derive the same token regardless of who initiates.
pub fn generate_phone_token(phone_a: &str, phone_b: &str) -> [u8; TOKEN_SIZE] {
    let a = normalize_phone(phone_a);
    let b = normalize_phone(phone_b);

    // Sort to ensure bilateral symmetry
    let mut pair = if a <= b {
        format!("{}:{}", a, b)
    } else {
        format!("{}:{}", b, a)
    };
    pair.push_str(":phone-pair");

    generate_token(pair.as_bytes())
}

/// Normalizes a passphrase: lowercase, trim, collapse whitespace.
fn normalize_phrase(phrase: &str) -> String {
    phrase
        .trim()
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

/// Normalizes a phone number: strip all non-digit characters except leading +.
fn normalize_phone(phone: &str) -> String {
    let trimmed = phone.trim();
    if let Some(rest) = trimmed.strip_prefix('+') {
        let digits: String = rest.chars().filter(|c| c.is_ascii_digit()).collect();
        format!("+{}", digits)
    } else {
        trimmed.chars().filter(|c| c.is_ascii_digit()).collect()
    }
}

// ── Proof of Work ─────────────────────────────────────────────────

/// Generates a proof-of-work nonce for the given token.
/// Iterates until SHA256(token || nonce) has the required leading zero bits.
pub fn generate_proof_of_work(token: &[u8; TOKEN_SIZE]) -> [u8; 4] {
    let mut nonce = 0u32;
    loop {
        let nonce_bytes = nonce.to_le_bytes();
        if verify_proof_of_work(token, &nonce_bytes) {
            return nonce_bytes;
        }
        nonce = nonce.wrapping_add(1);
    }
}

/// Verifies that the proof-of-work nonce produces the required leading zero bits.
pub fn verify_proof_of_work(token: &[u8; TOKEN_SIZE], pow: &[u8; 4]) -> bool {
    let mut hasher = Sha256::new();
    hasher.update(token);
    hasher.update(pow);
    let hash = hasher.finalize();
    leading_zero_bits(&hash) >= POW_DIFFICULTY_BITS
}

/// Counts the number of leading zero bits in a byte slice.
fn leading_zero_bits(data: &[u8]) -> u32 {
    let mut count = 0;
    for byte in data {
        if *byte == 0 {
            count += 8;
        } else {
            count += byte.leading_zeros();
            break;
        }
    }
    count
}

// ── Rendezvous Manager ────────────────────────────────────────────

/// Tracks active searches and registered tokens for rendezvous matching.
pub struct RendezvousManager {
    /// Tokens I'm actively searching for (outbound searches).
    /// Maps token → (ephemeral private key bytes, mode, query hint).
    active_searches: HashMap<[u8; TOKEN_SIZE], ActiveSearch>,
    /// Tokens where I'm the responder (others searching for me).
    my_tokens: HashMap<[u8; TOKEN_SIZE], RendezvousMode>,
}

struct ActiveSearch {
    ephemeral_secret_bytes: [u8; 32],
    mode: RendezvousMode,
}

impl Default for RendezvousManager {
    fn default() -> Self {
        Self::new()
    }
}

impl RendezvousManager {
    pub fn new() -> Self {
        RendezvousManager {
            active_searches: HashMap::new(),
            my_tokens: HashMap::new(),
        }
    }

    /// Starts a search and returns the rendezvous payload to broadcast.
    pub fn start_search(
        &mut self,
        token: [u8; TOKEN_SIZE],
        mode: RendezvousMode,
    ) -> RendezvousPayload {
        // Generate ephemeral X25519 keypair
        let secret = StaticSecret::random_from_rng(rand::thread_rng());
        let public = PublicKey::from(&secret);
        let pow = generate_proof_of_work(&token);

        let secret_bytes: [u8; 32] = secret.to_bytes();

        self.active_searches.insert(
            token,
            ActiveSearch {
                ephemeral_secret_bytes: secret_bytes,
                mode,
            },
        );

        // Also register as my_token so we respond to the same token from the other side
        self.my_tokens.insert(token, mode);

        RendezvousPayload {
            token,
            ephemeral_public_key: public.to_bytes(),
            proof_of_work: pow,
        }
    }

    /// Registers a token that I'll respond to (e.g., from contact import).
    pub fn register_token(&mut self, token: [u8; TOKEN_SIZE], mode: RendezvousMode) {
        self.my_tokens.insert(token, mode);
    }

    /// Cancels a search.
    pub fn cancel_search(&mut self, token: &[u8; TOKEN_SIZE]) {
        self.active_searches.remove(token);
        self.my_tokens.remove(token);
    }

    /// Returns all active search tokens.
    pub fn active_search_tokens(&self) -> Vec<[u8; TOKEN_SIZE]> {
        self.active_searches.keys().copied().collect()
    }

    /// Checks if an incoming RouteRequest matches one of my tokens.
    /// If so, generates an encrypted reply containing my identity.
    pub fn process_incoming_request(
        &self,
        payload: &RendezvousPayload,
        my_identity: &PublicIdentity,
    ) -> Option<RendezvousReply> {
        // Check if this token matches any of my registered tokens
        if !self.my_tokens.contains_key(&payload.token) {
            return None;
        }

        // Verify proof of work
        if !verify_proof_of_work(&payload.token, &payload.proof_of_work) {
            return None;
        }

        // Encrypt our identity for the querier using their ephemeral public key
        let encrypted =
            encrypt_identity_for_reply(my_identity, &payload.ephemeral_public_key, &payload.token)?;

        Some(RendezvousReply {
            token: payload.token,
            encrypted_identity: encrypted,
        })
    }

    /// Processes an incoming RouteReply, decrypting the discovered identity.
    pub fn process_incoming_reply(
        &self,
        reply: &RendezvousReply,
    ) -> Option<(PublicIdentity, RendezvousMode)> {
        let search = self.active_searches.get(&reply.token)?;

        let identity = decrypt_identity_from_reply(
            &reply.encrypted_identity,
            &search.ephemeral_secret_bytes,
            &reply.token,
        )?;

        Some((identity, search.mode))
    }

    /// Returns the number of active searches.
    pub fn active_search_count(&self) -> usize {
        self.active_searches.len()
    }

    /// Returns the number of registered tokens.
    pub fn registered_token_count(&self) -> usize {
        self.my_tokens.len()
    }
}

// ── Identity Encryption for Replies ───────────────────────────────

/// Encrypts a PublicIdentity for a rendezvous reply using X25519 ECDH.
///
/// The responder generates an ephemeral X25519 keypair and performs DH with the
/// querier's ephemeral public key. The shared secret is fed into HKDF with the
/// token as salt. Only the querier (who holds the ephemeral private key) can
/// derive the same shared secret to decrypt.
///
/// Returns (encrypted_identity, responder_ephemeral_public_key).
fn encrypt_identity_for_reply(
    identity: &PublicIdentity,
    querier_ephemeral_public_bytes: &[u8; 32],
    token: &[u8; TOKEN_SIZE],
) -> Option<Vec<u8>> {
    // Serialize the identity
    let mut plaintext = Vec::new();
    plaintext.extend_from_slice(&identity.device_id.0); // 16 bytes
    plaintext.extend_from_slice(&identity.signing_public_key); // 32 bytes
    plaintext.extend_from_slice(&identity.agreement_public_key); // 32 bytes
                                                                 // total: 80 bytes

    // Perform X25519 DH: responder's ephemeral secret × querier's ephemeral public
    let responder_secret = StaticSecret::random_from_rng(rand::thread_rng());
    let responder_public = PublicKey::from(&responder_secret);
    let querier_public = PublicKey::from(*querier_ephemeral_public_bytes);
    let shared_secret = responder_secret.diffie_hellman(&querier_public);

    // Derive encryption key from DH shared secret + token
    let hkdf = Hkdf::<sha2::Sha256>::new(Some(token), shared_secret.as_bytes());
    let mut key = [0u8; 32];
    hkdf.expand(b"flare-rendezvous-reply-key-v2", &mut key)
        .ok()?;

    // Derive nonce from token + responder public key
    let mut nonce_input = Vec::with_capacity(TOKEN_SIZE + 32);
    nonce_input.extend_from_slice(token);
    nonce_input.extend_from_slice(responder_public.as_bytes());
    let nonce_hash = Sha256::digest(&nonce_input);
    let mut nonce_arr = [0u8; 12];
    nonce_arr.copy_from_slice(&nonce_hash[..12]);
    let nonce = Nonce::from_slice(&nonce_arr);

    // Encrypt with AES-256-GCM
    let cipher = Aes256Gcm::new_from_slice(&key).ok()?;
    let ciphertext = cipher.encrypt(nonce, plaintext.as_ref()).ok()?;

    // Prepend responder's ephemeral public key so the querier can perform DH
    let mut result = Vec::with_capacity(32 + ciphertext.len());
    result.extend_from_slice(responder_public.as_bytes());
    result.extend_from_slice(&ciphertext);
    Some(result)
}

/// Decrypts a PublicIdentity from a rendezvous reply using X25519 ECDH.
///
/// The querier uses their ephemeral private key and the responder's ephemeral
/// public key (prepended to the encrypted data) to derive the shared secret.
fn decrypt_identity_from_reply(
    encrypted: &[u8],
    ephemeral_secret_bytes: &[u8; 32],
    token: &[u8; TOKEN_SIZE],
) -> Option<PublicIdentity> {
    // First 32 bytes are the responder's ephemeral public key
    if encrypted.len() < 32 {
        return None;
    }
    let mut responder_public_bytes = [0u8; 32];
    responder_public_bytes.copy_from_slice(&encrypted[..32]);
    let ciphertext = &encrypted[32..];

    // Perform X25519 DH: querier's ephemeral secret × responder's ephemeral public
    let querier_secret = StaticSecret::from(*ephemeral_secret_bytes);
    let responder_public = PublicKey::from(responder_public_bytes);
    let shared_secret = querier_secret.diffie_hellman(&responder_public);

    // Derive the same key the encryptor used
    let hkdf = Hkdf::<sha2::Sha256>::new(Some(token), shared_secret.as_bytes());
    let mut key = [0u8; 32];
    hkdf.expand(b"flare-rendezvous-reply-key-v2", &mut key)
        .ok()?;

    // Derive nonce from token + responder public key
    let mut nonce_input = Vec::with_capacity(TOKEN_SIZE + 32);
    nonce_input.extend_from_slice(token);
    nonce_input.extend_from_slice(&responder_public_bytes);
    let nonce_hash = Sha256::digest(&nonce_input);
    let mut nonce_arr = [0u8; 12];
    nonce_arr.copy_from_slice(&nonce_hash[..12]);
    let nonce = Nonce::from_slice(&nonce_arr);

    // Decrypt with AES-256-GCM
    let cipher = Aes256Gcm::new_from_slice(&key).ok()?;
    let plaintext = cipher.decrypt(nonce, ciphertext).ok()?;

    if plaintext.len() != 80 {
        return None;
    }

    let mut device_id_bytes = [0u8; 16];
    let mut signing_key = [0u8; 32];
    let mut agreement_key = [0u8; 32];

    device_id_bytes.copy_from_slice(&plaintext[0..16]);
    signing_key.copy_from_slice(&plaintext[16..48]);
    agreement_key.copy_from_slice(&plaintext[48..80]);

    Some(PublicIdentity {
        device_id: crate::crypto::identity::DeviceId(device_id_bytes),
        signing_public_key: signing_key,
        agreement_public_key: agreement_key,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::identity::Identity;

    #[test]
    fn test_phrase_token_deterministic() {
        let t1 = generate_phrase_token_with_epoch("grandmother's house on hafez street", 100);
        let t2 = generate_phrase_token_with_epoch("grandmother's house on hafez street", 100);
        assert_eq!(t1, t2);
    }

    #[test]
    fn test_phrase_normalization() {
        let t1 = generate_phrase_token_with_epoch("  Hello   World  ", 100);
        let t2 = generate_phrase_token_with_epoch("hello world", 100);
        assert_eq!(t1, t2);
    }

    #[test]
    fn test_different_phrases_different_tokens() {
        let t1 = generate_phrase_token_with_epoch("phrase one", 100);
        let t2 = generate_phrase_token_with_epoch("phrase two", 100);
        assert_ne!(t1, t2);
    }

    #[test]
    fn test_phone_token_bilateral_symmetry() {
        let t1 = generate_phone_token("+98 912 345 6789", "+98 935 111 2222");
        let t2 = generate_phone_token("+98 935 111 2222", "+98 912 345 6789");
        assert_eq!(t1, t2);
    }

    #[test]
    fn test_phone_normalization() {
        let t1 = generate_phone_token("+98-912-345-6789", "+98 935 111 2222");
        let t2 = generate_phone_token("+989123456789", "+989351112222");
        assert_eq!(t1, t2);
    }

    #[test]
    fn test_epoch_rotation() {
        let t1 = generate_phrase_token_with_epoch("same phrase", 100);
        let t2 = generate_phrase_token_with_epoch("same phrase", 101);
        assert_ne!(t1, t2, "Tokens should differ across epochs");
    }

    #[test]
    fn test_proof_of_work() {
        let token = [0xAA; TOKEN_SIZE];
        let pow = generate_proof_of_work(&token);
        assert!(verify_proof_of_work(&token, &pow));
    }

    #[test]
    fn test_proof_of_work_invalid() {
        let token = [0xBB; TOKEN_SIZE];
        let good_pow = generate_proof_of_work(&token);
        assert!(verify_proof_of_work(&token, &good_pow));
    }

    #[test]
    fn test_payload_serialization_roundtrip() {
        let payload = RendezvousPayload {
            token: [1, 2, 3, 4, 5, 6, 7, 8],
            ephemeral_public_key: [0x42; 32],
            proof_of_work: [0xDE, 0xAD, 0xBE, 0xEF],
        };

        let bytes = payload.to_bytes().unwrap();
        let restored = RendezvousPayload::from_bytes(&bytes).unwrap();
        assert_eq!(restored.token, payload.token);
        assert_eq!(restored.ephemeral_public_key, payload.ephemeral_public_key);
    }

    #[test]
    fn test_reply_serialization_roundtrip() {
        let reply = RendezvousReply {
            token: [8, 7, 6, 5, 4, 3, 2, 1],
            encrypted_identity: vec![0xFF; 96],
        };

        let bytes = reply.to_bytes().unwrap();
        let restored = RendezvousReply::from_bytes(&bytes).unwrap();
        assert_eq!(restored.token, reply.token);
        assert_eq!(restored.encrypted_identity, reply.encrypted_identity);
    }

    #[test]
    fn test_full_rendezvous_flow() {
        // Alice and Bob both know the phrase "our school in isfahan"
        let _alice_identity = Identity::generate();
        let bob_identity = Identity::generate();

        let phrase = "our school in Isfahan";
        let token = generate_phrase_token(phrase);

        // Alice starts a search
        let mut alice_mgr = RendezvousManager::new();
        let payload = alice_mgr.start_search(token, RendezvousMode::SharedPhrase);

        // Bob registers the same phrase token
        let mut bob_mgr = RendezvousManager::new();
        let bob_token = generate_phrase_token(phrase);
        bob_mgr.register_token(bob_token, RendezvousMode::SharedPhrase);

        // Bob receives Alice's RouteRequest and generates a reply
        let reply = bob_mgr
            .process_incoming_request(&payload, &bob_identity.public_identity())
            .expect("Bob should match Alice's token");

        // Alice receives Bob's RouteReply and decrypts his identity
        let (discovered, mode) = alice_mgr
            .process_incoming_reply(&reply)
            .expect("Alice should decrypt Bob's identity");

        assert_eq!(discovered.device_id, bob_identity.device_id().clone());
        assert_eq!(
            discovered.signing_public_key,
            bob_identity.public_identity().signing_public_key
        );
        assert_eq!(
            discovered.agreement_public_key,
            bob_identity.public_identity().agreement_public_key
        );
        assert_eq!(mode, RendezvousMode::SharedPhrase);
    }

    #[test]
    fn test_mismatched_tokens_no_match() {
        let bob_identity = Identity::generate();

        let mut alice_mgr = RendezvousManager::new();
        let payload = alice_mgr.start_search(
            generate_phrase_token_with_epoch("alice phrase", 100),
            RendezvousMode::SharedPhrase,
        );

        let mut bob_mgr = RendezvousManager::new();
        bob_mgr.register_token(
            generate_phrase_token_with_epoch("different phrase", 100),
            RendezvousMode::SharedPhrase,
        );

        let reply = bob_mgr.process_incoming_request(&payload, &bob_identity.public_identity());
        assert!(
            reply.is_none(),
            "Mismatched tokens should not produce a reply"
        );
    }

    #[test]
    fn test_phone_rendezvous_flow() {
        let _alice_identity = Identity::generate();
        let bob_identity = Identity::generate();

        // Alice knows Bob's number, Bob knows Alice's number
        let alice_phone = "+989123456789";
        let bob_phone = "+989351112222";

        // Both compute the same bilateral token
        let token_alice = generate_phone_token(alice_phone, bob_phone);
        let token_bob = generate_phone_token(bob_phone, alice_phone);
        assert_eq!(token_alice, token_bob);

        // Alice searches
        let mut alice_mgr = RendezvousManager::new();
        let payload = alice_mgr.start_search(token_alice, RendezvousMode::PhoneNumber);

        // Bob has registered his token
        let mut bob_mgr = RendezvousManager::new();
        bob_mgr.register_token(token_bob, RendezvousMode::PhoneNumber);

        let reply = bob_mgr
            .process_incoming_request(&payload, &bob_identity.public_identity())
            .unwrap();

        let (discovered, _) = alice_mgr.process_incoming_reply(&reply).unwrap();
        assert_eq!(discovered.device_id, bob_identity.device_id().clone());
    }

    #[test]
    fn test_cancel_search() {
        let mut mgr = RendezvousManager::new();
        let token = [0xAA; TOKEN_SIZE];
        let _ = mgr.start_search(token, RendezvousMode::SharedPhrase);
        assert_eq!(mgr.active_search_count(), 1);

        mgr.cancel_search(&token);
        assert_eq!(mgr.active_search_count(), 0);
        assert_eq!(mgr.registered_token_count(), 0);
    }

    #[test]
    fn test_leading_zero_bits() {
        assert_eq!(leading_zero_bits(&[0x00, 0x00, 0xFF]), 16);
        assert_eq!(leading_zero_bits(&[0x00, 0x01, 0xFF]), 15);
        assert_eq!(leading_zero_bits(&[0x80, 0x00, 0x00]), 0);
        assert_eq!(leading_zero_bits(&[0x00]), 8);
        assert_eq!(leading_zero_bits(&[0x0F]), 4);
    }
}
