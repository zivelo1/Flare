//! AES-256-GCM authenticated encryption for message payloads.
//!
//! All message content is encrypted end-to-end using AES-256-GCM.
//! Keys are derived per-message from the shared secret via HKDF.

use aes_gcm::{
    aead::{Aead, KeyInit},
    Aes256Gcm, Nonce,
};
use serde::{Deserialize, Serialize};

use crate::crypto::keys::DerivedKeyMaterial;

/// Errors that can occur during encryption/decryption.
#[derive(Debug, thiserror::Error)]
pub enum EncryptionError {
    #[error("Encryption failed: {0}")]
    EncryptionFailed(String),

    #[error("Decryption failed: authentication tag mismatch or corrupted data")]
    DecryptionFailed,

    #[error("Invalid payload format")]
    InvalidPayload,
}

/// An encrypted message payload with its authentication tag.
/// The nonce is NOT included here because it's derived from the shared
/// secret + message ID via HKDF (both parties can derive it independently).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EncryptedPayload {
    /// The AES-256-GCM ciphertext (includes the 16-byte auth tag appended by aes-gcm).
    pub ciphertext: Vec<u8>,
}

/// Encrypts a plaintext message using AES-256-GCM with the provided key material.
///
/// The key material (key + nonce) should be derived from the shared secret
/// and a unique per-message salt using `derive_message_key`.
pub fn encrypt_message(
    key_material: &DerivedKeyMaterial,
    plaintext: &[u8],
) -> Result<EncryptedPayload, EncryptionError> {
    let cipher = Aes256Gcm::new_from_slice(&key_material.key)
        .map_err(|e| EncryptionError::EncryptionFailed(e.to_string()))?;

    let nonce = Nonce::from_slice(&key_material.nonce);

    let ciphertext = cipher
        .encrypt(nonce, plaintext)
        .map_err(|e| EncryptionError::EncryptionFailed(e.to_string()))?;

    Ok(EncryptedPayload { ciphertext })
}

/// Decrypts an encrypted payload using AES-256-GCM with the provided key material.
///
/// Returns the original plaintext if the authentication tag is valid.
/// Returns `DecryptionFailed` if the data has been tampered with or the wrong key is used.
pub fn decrypt_message(
    key_material: &DerivedKeyMaterial,
    payload: &EncryptedPayload,
) -> Result<Vec<u8>, EncryptionError> {
    let cipher = Aes256Gcm::new_from_slice(&key_material.key)
        .map_err(|e| EncryptionError::EncryptionFailed(e.to_string()))?;

    let nonce = Nonce::from_slice(&key_material.nonce);

    cipher
        .decrypt(nonce, payload.ciphertext.as_ref())
        .map_err(|_| EncryptionError::DecryptionFailed)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::keys::{derive_message_key, SharedSecret};

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let secret = SharedSecret::new([42u8; 32]);
        let key_material = derive_message_key(&secret, b"msg-001");

        let plaintext = b"Freedom of communication is a human right.";

        let encrypted = encrypt_message(&key_material, plaintext).unwrap();
        let decrypted = decrypt_message(&key_material, &encrypted).unwrap();

        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_ciphertext_differs_from_plaintext() {
        let secret = SharedSecret::new([42u8; 32]);
        let key_material = derive_message_key(&secret, b"msg-001");
        let plaintext = b"This should be encrypted";

        let encrypted = encrypt_message(&key_material, plaintext).unwrap();
        assert_ne!(encrypted.ciphertext, plaintext);
    }

    #[test]
    fn test_wrong_key_fails_decryption() {
        let secret1 = SharedSecret::new([1u8; 32]);
        let secret2 = SharedSecret::new([2u8; 32]);
        let salt = b"msg-001";

        let key1 = derive_message_key(&secret1, salt);
        let key2 = derive_message_key(&secret2, salt);

        let plaintext = b"Secret message";
        let encrypted = encrypt_message(&key1, plaintext).unwrap();

        // Decrypting with wrong key should fail
        let result = decrypt_message(&key2, &encrypted);
        assert!(result.is_err());
    }

    #[test]
    fn test_tampered_ciphertext_fails() {
        let secret = SharedSecret::new([42u8; 32]);
        let key_material = derive_message_key(&secret, b"msg-001");
        let plaintext = b"Do not tamper with this";

        let mut encrypted = encrypt_message(&key_material, plaintext).unwrap();

        // Tamper with one byte
        if let Some(byte) = encrypted.ciphertext.get_mut(0) {
            *byte ^= 0xff;
        }

        let result = decrypt_message(&key_material, &encrypted);
        assert!(result.is_err());
    }

    #[test]
    fn test_empty_plaintext() {
        let secret = SharedSecret::new([42u8; 32]);
        let key_material = derive_message_key(&secret, b"msg-001");

        let encrypted = encrypt_message(&key_material, b"").unwrap();
        let decrypted = decrypt_message(&key_material, &encrypted).unwrap();

        assert_eq!(decrypted, b"");
    }

    #[test]
    fn test_large_message() {
        let secret = SharedSecret::new([42u8; 32]);
        let key_material = derive_message_key(&secret, b"msg-001");

        // 10KB message (larger than typical BLE MTU, will be chunked at transport layer)
        let plaintext = vec![0xABu8; 10_240];

        let encrypted = encrypt_message(&key_material, &plaintext).unwrap();
        let decrypted = decrypt_message(&key_material, &encrypted).unwrap();

        assert_eq!(decrypted, plaintext);
    }
}
