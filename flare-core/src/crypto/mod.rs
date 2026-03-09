//! Cryptographic primitives for Flare.
//!
//! Provides identity key generation, Diffie-Hellman key agreement,
//! authenticated encryption (AES-256-GCM), key derivation (HKDF),
//! and digital signatures (Ed25519).

pub mod identity;
pub mod encryption;
pub mod keys;

pub use identity::Identity;
pub use encryption::{encrypt_message, decrypt_message, EncryptedPayload};
pub use keys::{SharedSecret, derive_message_key};
