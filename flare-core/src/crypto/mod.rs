//! Cryptographic primitives for Flare.
//!
//! Provides identity key generation, Diffie-Hellman key agreement,
//! authenticated encryption (AES-256-GCM), key derivation (HKDF),
//! and digital signatures (Ed25519).

pub mod encryption;
pub mod identity;
pub mod keys;

pub use encryption::{decrypt_message, encrypt_message, EncryptedPayload};
pub use identity::Identity;
pub use keys::{derive_message_key, SharedSecret};
