//! Key derivation and shared secret management.
//!
//! Uses HKDF-SHA256 to derive symmetric encryption keys from
//! X25519 Diffie-Hellman shared secrets.

use hkdf::Hkdf;
use sha2::Sha256;

/// Domain separation string for message key derivation.
/// Prevents key reuse across different contexts.
const MESSAGE_KEY_INFO: &[u8] = b"flare-mesh-message-key-v1";

/// Domain separation string for transport key derivation.
const TRANSPORT_KEY_INFO: &[u8] = b"flare-mesh-transport-key-v1";

/// Length of derived symmetric keys in bytes (256-bit AES).
const DERIVED_KEY_LENGTH: usize = 32;

/// Length of derived nonce in bytes (96-bit for AES-GCM).
const DERIVED_NONCE_LENGTH: usize = 12;

/// A shared secret derived from X25519 Diffie-Hellman key agreement.
pub struct SharedSecret {
    bytes: [u8; 32],
}

/// A symmetric key and nonce pair derived from a shared secret,
/// ready for AES-256-GCM encryption.
pub struct DerivedKeyMaterial {
    pub key: [u8; DERIVED_KEY_LENGTH],
    pub nonce: [u8; DERIVED_NONCE_LENGTH],
}

impl SharedSecret {
    pub fn new(bytes: [u8; 32]) -> Self {
        SharedSecret { bytes }
    }

    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.bytes
    }
}

/// Derives a message encryption key from a shared secret and a per-message salt.
///
/// The salt should be unique per message (e.g., message_id bytes) to ensure
/// each message gets a unique key even with the same shared secret.
pub fn derive_message_key(shared_secret: &SharedSecret, salt: &[u8]) -> DerivedKeyMaterial {
    derive_key_material(shared_secret, salt, MESSAGE_KEY_INFO)
}

/// Derives a transport encryption key for link-level encryption between peers.
pub fn derive_transport_key(shared_secret: &SharedSecret, salt: &[u8]) -> DerivedKeyMaterial {
    derive_key_material(shared_secret, salt, TRANSPORT_KEY_INFO)
}

/// Internal HKDF key derivation.
fn derive_key_material(
    shared_secret: &SharedSecret,
    salt: &[u8],
    info: &[u8],
) -> DerivedKeyMaterial {
    let hkdf = Hkdf::<Sha256>::new(Some(salt), shared_secret.as_bytes());

    let mut output = [0u8; DERIVED_KEY_LENGTH + DERIVED_NONCE_LENGTH];
    // HKDF expand cannot fail when output length <= 255 * hash_length (8160 bytes for SHA-256).
    // Our output is 44 bytes, so this is safe.
    hkdf.expand(info, &mut output)
        .expect("HKDF expand failed: output length exceeds maximum");

    let mut key = [0u8; DERIVED_KEY_LENGTH];
    let mut nonce = [0u8; DERIVED_NONCE_LENGTH];
    key.copy_from_slice(&output[..DERIVED_KEY_LENGTH]);
    nonce.copy_from_slice(&output[DERIVED_KEY_LENGTH..]);

    DerivedKeyMaterial { key, nonce }
}

impl Drop for SharedSecret {
    /// Zeroize the shared secret when dropped to prevent memory leaks.
    fn drop(&mut self) {
        self.bytes.fill(0);
    }
}

impl Drop for DerivedKeyMaterial {
    /// Zeroize derived key material when dropped.
    fn drop(&mut self) {
        self.key.fill(0);
        self.nonce.fill(0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_derive_message_key_deterministic() {
        let secret = SharedSecret::new([42u8; 32]);
        let salt = b"unique-message-id";

        let key1 = derive_message_key(&secret, salt);
        let key2 = derive_message_key(&secret, salt);

        assert_eq!(key1.key, key2.key);
        assert_eq!(key1.nonce, key2.nonce);
    }

    #[test]
    fn test_different_salts_produce_different_keys() {
        let secret = SharedSecret::new([42u8; 32]);

        let key1 = derive_message_key(&secret, b"message-1");
        let key2 = derive_message_key(&secret, b"message-2");

        assert_ne!(key1.key, key2.key);
    }

    #[test]
    fn test_different_secrets_produce_different_keys() {
        let secret1 = SharedSecret::new([1u8; 32]);
        let secret2 = SharedSecret::new([2u8; 32]);
        let salt = b"same-salt";

        let key1 = derive_message_key(&secret1, salt);
        let key2 = derive_message_key(&secret2, salt);

        assert_ne!(key1.key, key2.key);
    }

    #[test]
    fn test_message_vs_transport_key_differ() {
        let secret = SharedSecret::new([42u8; 32]);
        let salt = b"same-salt";

        let msg_key = derive_message_key(&secret, salt);
        let transport_key = derive_transport_key(&secret, salt);

        assert_ne!(msg_key.key, transport_key.key);
    }

    #[test]
    fn test_key_lengths() {
        let secret = SharedSecret::new([42u8; 32]);
        let derived = derive_message_key(&secret, b"test");

        assert_eq!(derived.key.len(), 32); // AES-256
        assert_eq!(derived.nonce.len(), 12); // AES-GCM nonce
    }
}
