//! Device identity management.
//!
//! Each Flare device has a permanent identity consisting of:
//! - Ed25519 signing keypair (for message authentication)
//! - X25519 key agreement keypair (for Diffie-Hellman key exchange)
//! - A device ID derived from the signing public key
//!
//! The identity is generated once on first launch and stored locally.

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use rand::rngs::OsRng;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey as X25519PublicKey, StaticSecret};

use crate::crypto::keys;

/// Length of the device ID in bytes (truncated SHA-256 of signing public key).
const DEVICE_ID_LENGTH: usize = 16;

/// Errors that can occur during identity operations.
#[derive(Debug, thiserror::Error)]
pub enum IdentityError {
    #[error("Invalid signing key bytes: expected 32 bytes, got {0}")]
    InvalidSigningKeyLength(usize),

    #[error("Invalid agreement key bytes: expected 32 bytes, got {0}")]
    InvalidAgreementKeyLength(usize),

    #[error("Signature verification failed")]
    SignatureVerificationFailed,

    #[error("Serialization error: {0}")]
    SerializationError(String),
}

/// A device's complete identity (private keys included).
/// This is stored locally and NEVER transmitted.
pub struct Identity {
    signing_key: SigningKey,
    agreement_key: StaticSecret,
    device_id: DeviceId,
}

/// A device's public identity (safe to share with peers).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct PublicIdentity {
    pub signing_public_key: [u8; 32],
    pub agreement_public_key: [u8; 32],
    pub device_id: DeviceId,
}

/// Unique device identifier derived from the signing public key.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Hash)]
pub struct DeviceId(pub [u8; DEVICE_ID_LENGTH]);

impl DeviceId {
    /// Returns the first 4 bytes of the device ID, used in BLE advertising
    /// to save advertisement space.
    pub fn short_id(&self) -> [u8; 4] {
        let mut short = [0u8; 4];
        short.copy_from_slice(&self.0[..4]);
        short
    }

    /// Returns the device ID as a hex string for display purposes.
    pub fn to_hex(&self) -> String {
        self.0.iter().map(|b| format!("{:02x}", b)).collect()
    }

    /// Creates a DeviceId from a hex string.
    pub fn from_hex(hex: &str) -> Result<Self, IdentityError> {
        if hex.len() != DEVICE_ID_LENGTH * 2 {
            return Err(IdentityError::InvalidSigningKeyLength(hex.len()));
        }
        let mut bytes = [0u8; DEVICE_ID_LENGTH];
        for (i, chunk) in hex.as_bytes().chunks(2).enumerate() {
            let hex_str = std::str::from_utf8(chunk)
                .map_err(|e| IdentityError::SerializationError(e.to_string()))?;
            bytes[i] = u8::from_str_radix(hex_str, 16)
                .map_err(|e| IdentityError::SerializationError(e.to_string()))?;
        }
        Ok(DeviceId(bytes))
    }
}

impl std::fmt::Display for DeviceId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.to_hex())
    }
}

impl Identity {
    /// Generates a new random identity.
    /// Called once on first app launch.
    pub fn generate() -> Self {
        let signing_key = SigningKey::generate(&mut OsRng);
        let agreement_key = StaticSecret::random_from_rng(OsRng);
        let device_id = Self::derive_device_id(&signing_key.verifying_key());

        Identity {
            signing_key,
            agreement_key,
            device_id,
        }
    }

    /// Reconstructs an identity from stored key bytes.
    /// Used when loading identity from the encrypted database.
    pub fn from_key_bytes(
        signing_key_bytes: &[u8],
        agreement_key_bytes: &[u8],
    ) -> Result<Self, IdentityError> {
        if signing_key_bytes.len() != 32 {
            return Err(IdentityError::InvalidSigningKeyLength(
                signing_key_bytes.len(),
            ));
        }
        if agreement_key_bytes.len() != 32 {
            return Err(IdentityError::InvalidAgreementKeyLength(
                agreement_key_bytes.len(),
            ));
        }

        let mut signing_bytes = [0u8; 32];
        signing_bytes.copy_from_slice(signing_key_bytes);
        let signing_key = SigningKey::from_bytes(&signing_bytes);

        let mut agreement_bytes = [0u8; 32];
        agreement_bytes.copy_from_slice(agreement_key_bytes);
        let agreement_key = StaticSecret::from(agreement_bytes);

        let device_id = Self::derive_device_id(&signing_key.verifying_key());

        Ok(Identity {
            signing_key,
            agreement_key,
            device_id,
        })
    }

    /// Returns the device ID.
    pub fn device_id(&self) -> &DeviceId {
        &self.device_id
    }

    /// Returns the public identity (safe to share with peers).
    pub fn public_identity(&self) -> PublicIdentity {
        PublicIdentity {
            signing_public_key: self.signing_key.verifying_key().to_bytes(),
            agreement_public_key: X25519PublicKey::from(&self.agreement_key).to_bytes(),
            device_id: self.device_id.clone(),
        }
    }

    /// Returns the raw signing key bytes for secure storage.
    pub fn signing_key_bytes(&self) -> &[u8; 32] {
        self.signing_key.as_bytes()
    }

    /// Returns the raw agreement key bytes for secure storage.
    pub fn agreement_key_bytes(&self) -> [u8; 32] {
        self.agreement_key.to_bytes()
    }

    /// Signs arbitrary data with the identity's Ed25519 signing key.
    pub fn sign(&self, data: &[u8]) -> [u8; 64] {
        let signature: Signature = self.signing_key.sign(data);
        signature.to_bytes()
    }

    /// Performs X25519 Diffie-Hellman key agreement with a peer's public key.
    /// Returns a shared secret that both parties can derive independently.
    pub fn agree(&self, peer_agreement_public_key: &[u8; 32]) -> keys::SharedSecret {
        let peer_public = X25519PublicKey::from(*peer_agreement_public_key);
        let shared = self.agreement_key.diffie_hellman(&peer_public);
        keys::SharedSecret::new(shared.to_bytes())
    }

    /// Derives the device ID from a signing public key.
    /// Device ID = first 16 bytes of SHA-256(signing_public_key).
    fn derive_device_id(verifying_key: &VerifyingKey) -> DeviceId {
        let mut hasher = Sha256::new();
        hasher.update(verifying_key.as_bytes());
        let hash = hasher.finalize();
        let mut id = [0u8; DEVICE_ID_LENGTH];
        id.copy_from_slice(&hash[..DEVICE_ID_LENGTH]);
        DeviceId(id)
    }
}

impl PublicIdentity {
    /// Verifies a signature against this identity's signing public key.
    pub fn verify(&self, data: &[u8], signature: &[u8; 64]) -> Result<(), IdentityError> {
        let verifying_key = VerifyingKey::from_bytes(&self.signing_public_key)
            .map_err(|_| IdentityError::SignatureVerificationFailed)?;
        let sig = Signature::from_bytes(signature);
        verifying_key
            .verify(data, &sig)
            .map_err(|_| IdentityError::SignatureVerificationFailed)
    }

    /// Serializes the public identity to bytes for transmission.
    pub fn to_bytes(&self) -> Result<Vec<u8>, IdentityError> {
        bincode::serialize(self).map_err(|e| IdentityError::SerializationError(e.to_string()))
    }

    /// Deserializes a public identity from bytes received from a peer.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, IdentityError> {
        bincode::deserialize(bytes).map_err(|e| IdentityError::SerializationError(e.to_string()))
    }

    /// Returns a human-readable "safety number" for contact verification.
    /// Users compare these numbers in person to verify they're talking to
    /// the right person (similar to Signal's safety numbers).
    pub fn safety_number(&self) -> String {
        let mut hasher = Sha256::new();
        hasher.update(self.signing_public_key);
        hasher.update(self.agreement_public_key);
        let hash = hasher.finalize();

        // Format as groups of 5 digits (12 groups = 60 digits)
        hash.iter()
            .take(20)
            .map(|b| format!("{:03}", b))
            .collect::<Vec<_>>()
            .chunks(4)
            .map(|chunk| chunk.join(""))
            .collect::<Vec<_>>()
            .join(" ")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_identity_generation() {
        let identity = Identity::generate();
        let public = identity.public_identity();

        // Device ID should be deterministic from public key
        assert_eq!(identity.device_id(), &public.device_id);
        assert_eq!(public.device_id.0.len(), DEVICE_ID_LENGTH);
    }

    #[test]
    fn test_identity_roundtrip() {
        let identity = Identity::generate();
        let signing_bytes = identity.signing_key_bytes().to_vec();
        let agreement_bytes = identity.agreement_key_bytes();

        let restored = Identity::from_key_bytes(&signing_bytes, &agreement_bytes).unwrap();

        assert_eq!(identity.device_id(), restored.device_id());
        assert_eq!(
            identity.public_identity().signing_public_key,
            restored.public_identity().signing_public_key
        );
    }

    #[test]
    fn test_sign_and_verify() {
        let identity = Identity::generate();
        let public = identity.public_identity();
        let message = b"Hello from the mesh!";

        let signature = identity.sign(message);
        assert!(public.verify(message, &signature).is_ok());

        // Tampered message should fail verification
        let tampered = b"Hello from the mesh?";
        assert!(public.verify(tampered, &signature).is_err());
    }

    #[test]
    fn test_key_agreement() {
        let alice = Identity::generate();
        let bob = Identity::generate();

        let alice_shared = alice.agree(&bob.public_identity().agreement_public_key);
        let bob_shared = bob.agree(&alice.public_identity().agreement_public_key);

        // Both parties should derive the same shared secret
        assert_eq!(alice_shared.as_bytes(), bob_shared.as_bytes());
    }

    #[test]
    fn test_public_identity_serialization() {
        let identity = Identity::generate();
        let public = identity.public_identity();

        let bytes = public.to_bytes().unwrap();
        let restored = PublicIdentity::from_bytes(&bytes).unwrap();

        assert_eq!(public, restored);
    }

    #[test]
    fn test_device_id_hex_roundtrip() {
        let identity = Identity::generate();
        let hex = identity.device_id().to_hex();
        let restored = DeviceId::from_hex(&hex).unwrap();

        assert_eq!(identity.device_id(), &restored);
    }

    #[test]
    fn test_short_id() {
        let identity = Identity::generate();
        let short = identity.device_id().short_id();
        assert_eq!(short.len(), 4);
        assert_eq!(&short[..], &identity.device_id().0[..4]);
    }

    #[test]
    fn test_safety_number_deterministic() {
        let identity = Identity::generate();
        let public = identity.public_identity();

        let sn1 = public.safety_number();
        let sn2 = public.safety_number();
        assert_eq!(sn1, sn2);
    }

    #[test]
    fn test_different_identities_different_ids() {
        let a = Identity::generate();
        let b = Identity::generate();
        assert_ne!(a.device_id(), b.device_id());
    }
}
