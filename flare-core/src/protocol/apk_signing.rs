//! APK code signing for secure phone-to-phone distribution.
//!
//! ## Problem
//! The original APK sharing protocol uses only SHA-256 hash verification.
//! A compromised device can generate a trojaned APK, compute its SHA-256,
//! and distribute it as if it were genuine. The hash merely proves integrity,
//! not authenticity.
//!
//! ## Solution
//! Use Ed25519 digital signatures to prove the APK was built by a trusted
//! developer. The flow:
//!
//! 1. At build time, the developer signs SHA-256(apk_bytes) with their
//!    Ed25519 signing key.
//! 2. The signature (64 bytes) and developer public key (32 bytes) are
//!    included in the ApkOfferPayload.
//! 3. The receiving device verifies the signature against one or more
//!    trusted developer public keys.
//!
//! ## Trust model
//! Trusted developer keys are distributed with the app itself (embedded in
//! the binary). When a new version arrives via mesh, the receiver checks
//! the signature against its known developer keys. This creates a chain of
//! trust: the first install (from a trusted source) establishes the developer
//! key, and subsequent updates must be signed by the same key.
//!
//! Additional keys can be introduced via a key rotation mechanism where the
//! old key signs a "key rotation" message endorsing the new key.

use ed25519_dalek::{Signature, Signer, SigningKey, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// A developer's Ed25519 signing identity for APK distribution.
pub struct DeveloperSigningKey {
    signing_key: SigningKey,
}

impl DeveloperSigningKey {
    /// Creates a developer signing key from raw key bytes.
    /// In production, this is loaded from a secure keystore at build time.
    pub fn from_bytes(key_bytes: &[u8; 32]) -> Self {
        DeveloperSigningKey {
            signing_key: SigningKey::from_bytes(key_bytes),
        }
    }

    /// Generates a new random developer signing key.
    /// Used for testing and initial key generation.
    pub fn generate() -> Self {
        let signing_key = SigningKey::generate(&mut rand::thread_rng());
        DeveloperSigningKey { signing_key }
    }

    /// Returns the public key bytes (32 bytes) for distribution.
    pub fn public_key_bytes(&self) -> [u8; 32] {
        self.signing_key.verifying_key().to_bytes()
    }

    /// Signs the SHA-256 hash of APK bytes.
    /// Returns the 64-byte Ed25519 signature.
    pub fn sign_apk(&self, apk_bytes: &[u8]) -> ApkSignature {
        let hash = compute_apk_hash(apk_bytes);
        let signature = self.signing_key.sign(&hash);
        ApkSignature {
            developer_public_key: self.public_key_bytes(),
            signature: signature.to_bytes().to_vec(),
        }
    }
}

/// An Ed25519 signature over the APK hash, with the developer's public key.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ApkSignature {
    /// The developer's Ed25519 public key (32 bytes).
    pub developer_public_key: [u8; 32],
    /// Ed25519 signature over SHA-256(apk_bytes) (64 bytes).
    pub signature: Vec<u8>,
}

impl ApkSignature {
    /// Verifies this signature against the given APK bytes.
    /// Returns true if the signature is valid for this APK and developer key.
    pub fn verify(&self, apk_bytes: &[u8]) -> bool {
        let hash = compute_apk_hash(apk_bytes);
        self.verify_hash(&hash)
    }

    /// Verifies this signature against a pre-computed APK hash.
    /// Useful when the hash is already known from the ApkOfferPayload.
    pub fn verify_hash(&self, apk_hash: &[u8; 32]) -> bool {
        if self.signature.len() != 64 {
            return false;
        }
        let verifying_key = match VerifyingKey::from_bytes(&self.developer_public_key) {
            Ok(k) => k,
            Err(_) => return false,
        };
        let sig_bytes: [u8; 64] = self.signature.as_slice().try_into().unwrap();
        let signature = Signature::from_bytes(&sig_bytes);
        verifying_key.verify(apk_hash, &signature).is_ok()
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, bincode::Error> {
        bincode::serialize(self)
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, bincode::Error> {
        bincode::deserialize(bytes)
    }
}

/// Manages trusted developer public keys for APK verification.
///
/// The initial set of trusted keys is embedded in the app binary.
/// Additional keys can be added via signed key rotation messages.
pub struct TrustedDeveloperKeys {
    keys: Vec<[u8; 32]>,
}

impl TrustedDeveloperKeys {
    /// Creates a new trust store with the given initial keys.
    pub fn new(initial_keys: Vec<[u8; 32]>) -> Self {
        TrustedDeveloperKeys { keys: initial_keys }
    }

    /// Creates an empty trust store (for testing).
    pub fn empty() -> Self {
        TrustedDeveloperKeys { keys: Vec::new() }
    }

    /// Adds a trusted developer key.
    pub fn add_key(&mut self, key: [u8; 32]) {
        if !self.keys.contains(&key) {
            self.keys.push(key);
        }
    }

    /// Removes a developer key (e.g., if compromised).
    pub fn revoke_key(&mut self, key: &[u8; 32]) {
        self.keys.retain(|k| k != key);
    }

    /// Checks if a developer public key is trusted.
    pub fn is_trusted(&self, key: &[u8; 32]) -> bool {
        self.keys.contains(key)
    }

    /// Returns the number of trusted keys.
    pub fn key_count(&self) -> usize {
        self.keys.len()
    }

    /// Verifies an APK signature against the trusted key set.
    ///
    /// Returns `ApkVerifyResult` indicating success or the reason for failure.
    pub fn verify_apk(&self, apk_bytes: &[u8], signature: &ApkSignature) -> ApkVerifyResult {
        // Step 1: Check if the developer key is in our trust store
        if !self.is_trusted(&signature.developer_public_key) {
            return ApkVerifyResult::UntrustedDeveloper;
        }

        // Step 2: Verify the cryptographic signature
        if signature.verify(apk_bytes) {
            ApkVerifyResult::Valid
        } else {
            ApkVerifyResult::InvalidSignature
        }
    }

    /// Verifies an APK signature using a pre-computed hash.
    pub fn verify_apk_with_hash(
        &self,
        apk_hash: &[u8; 32],
        signature: &ApkSignature,
    ) -> ApkVerifyResult {
        if !self.is_trusted(&signature.developer_public_key) {
            return ApkVerifyResult::UntrustedDeveloper;
        }

        if signature.verify_hash(apk_hash) {
            ApkVerifyResult::Valid
        } else {
            ApkVerifyResult::InvalidSignature
        }
    }
}

/// Result of APK signature verification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApkVerifyResult {
    /// Signature is valid and developer key is trusted.
    Valid,
    /// Signature is cryptographically invalid (tampered APK or wrong key).
    InvalidSignature,
    /// Developer key is not in the trusted key set.
    UntrustedDeveloper,
}

/// Computes the SHA-256 hash of APK bytes.
fn compute_apk_hash(apk_bytes: &[u8]) -> [u8; 32] {
    let mut hasher = Sha256::new();
    hasher.update(apk_bytes);
    hasher.finalize().into()
}

/// A key rotation message signed by the old key endorsing a new key.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyRotation {
    /// The old developer key that is authorizing the rotation.
    pub old_key: [u8; 32],
    /// The new developer key being introduced.
    pub new_key: [u8; 32],
    /// Timestamp of the rotation (Unix seconds).
    pub timestamp_secs: i64,
    /// Ed25519 signature by the old key over (old_key || new_key || timestamp).
    pub signature: Vec<u8>,
}

impl KeyRotation {
    /// Creates a signed key rotation from old key to new key.
    pub fn create(
        old_signing_key: &DeveloperSigningKey,
        new_public_key: [u8; 32],
        timestamp_secs: i64,
    ) -> Self {
        let old_key = old_signing_key.public_key_bytes();
        let signable = Self::signable_bytes(&old_key, &new_public_key, timestamp_secs);
        let signature = old_signing_key.signing_key.sign(&signable);

        KeyRotation {
            old_key,
            new_key: new_public_key,
            timestamp_secs,
            signature: signature.to_bytes().to_vec(),
        }
    }

    /// Verifies the key rotation signature.
    pub fn verify(&self) -> bool {
        if self.signature.len() != 64 {
            return false;
        }
        let verifying_key = match VerifyingKey::from_bytes(&self.old_key) {
            Ok(k) => k,
            Err(_) => return false,
        };
        let signable = Self::signable_bytes(&self.old_key, &self.new_key, self.timestamp_secs);
        let sig_bytes: [u8; 64] = self.signature.as_slice().try_into().unwrap();
        let signature = Signature::from_bytes(&sig_bytes);
        verifying_key.verify(&signable, &signature).is_ok()
    }

    fn signable_bytes(old_key: &[u8; 32], new_key: &[u8; 32], timestamp: i64) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(72);
        bytes.extend_from_slice(old_key);
        bytes.extend_from_slice(new_key);
        bytes.extend_from_slice(&timestamp.to_le_bytes());
        bytes
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sign_and_verify_apk() {
        let developer = DeveloperSigningKey::generate();
        let fake_apk = b"This is a fake APK file for testing purposes with enough data";

        let sig = developer.sign_apk(fake_apk);
        assert!(sig.verify(fake_apk));
    }

    #[test]
    fn test_tampered_apk_fails_verification() {
        let developer = DeveloperSigningKey::generate();
        let original_apk = b"Original APK content that is legitimate and trusted";
        let tampered_apk = b"Tampered APK content with malware inserted by attacker";

        let sig = developer.sign_apk(original_apk);
        assert!(sig.verify(original_apk));
        assert!(!sig.verify(tampered_apk));
    }

    #[test]
    fn test_wrong_developer_key_fails() {
        let developer_a = DeveloperSigningKey::generate();
        let developer_b = DeveloperSigningKey::generate();
        let apk = b"Some APK content for developer A to sign securely";

        let sig = developer_a.sign_apk(apk);

        // Create a forged signature with developer B's key
        let forged_sig = ApkSignature {
            developer_public_key: developer_b.public_key_bytes(),
            signature: sig.signature,
        };
        assert!(!forged_sig.verify(apk));
    }

    #[test]
    fn test_trusted_keys_verification() {
        let developer = DeveloperSigningKey::generate();
        let mut trust_store = TrustedDeveloperKeys::new(vec![developer.public_key_bytes()]);

        let apk = b"Legitimate Flare APK binary content for distribution";
        let sig = developer.sign_apk(apk);

        assert_eq!(trust_store.verify_apk(apk, &sig), ApkVerifyResult::Valid);

        // Unknown developer
        let unknown = DeveloperSigningKey::generate();
        let unknown_sig = unknown.sign_apk(apk);
        assert_eq!(
            trust_store.verify_apk(apk, &unknown_sig),
            ApkVerifyResult::UntrustedDeveloper
        );

        // Add the unknown key, should now verify
        trust_store.add_key(unknown.public_key_bytes());
        assert_eq!(
            trust_store.verify_apk(apk, &unknown_sig),
            ApkVerifyResult::Valid
        );
    }

    #[test]
    fn test_revoke_key() {
        let developer = DeveloperSigningKey::generate();
        let mut trust_store = TrustedDeveloperKeys::new(vec![developer.public_key_bytes()]);

        let apk = b"APK content that was signed before key compromise event";
        let sig = developer.sign_apk(apk);
        assert_eq!(trust_store.verify_apk(apk, &sig), ApkVerifyResult::Valid);

        trust_store.revoke_key(&developer.public_key_bytes());
        assert_eq!(
            trust_store.verify_apk(apk, &sig),
            ApkVerifyResult::UntrustedDeveloper
        );
    }

    #[test]
    fn test_signature_serialization_roundtrip() {
        let developer = DeveloperSigningKey::generate();
        let apk = b"APK data for serialization roundtrip test with signature";
        let sig = developer.sign_apk(apk);

        let bytes = sig.to_bytes().unwrap();
        let restored = ApkSignature::from_bytes(&bytes).unwrap();

        assert_eq!(sig.developer_public_key, restored.developer_public_key);
        assert_eq!(sig.signature, restored.signature);
        assert!(restored.verify(apk));
    }

    #[test]
    fn test_verify_with_precomputed_hash() {
        let developer = DeveloperSigningKey::generate();
        let apk = b"APK data for hash-based verification testing scenario";
        let hash = compute_apk_hash(apk);

        let sig = developer.sign_apk(apk);
        assert!(sig.verify_hash(&hash));

        let wrong_hash = [0xFF; 32];
        assert!(!sig.verify_hash(&wrong_hash));
    }

    #[test]
    fn test_key_rotation() {
        let old_developer = DeveloperSigningKey::generate();
        let new_developer = DeveloperSigningKey::generate();

        let rotation =
            KeyRotation::create(&old_developer, new_developer.public_key_bytes(), 1709000000);

        assert!(rotation.verify());
        assert_eq!(rotation.old_key, old_developer.public_key_bytes());
        assert_eq!(rotation.new_key, new_developer.public_key_bytes());
    }

    #[test]
    fn test_key_rotation_tampered_fails() {
        let old_developer = DeveloperSigningKey::generate();
        let new_developer = DeveloperSigningKey::generate();
        let attacker = DeveloperSigningKey::generate();

        let mut rotation =
            KeyRotation::create(&old_developer, new_developer.public_key_bytes(), 1709000000);

        // Attacker tries to substitute their key
        rotation.new_key = attacker.public_key_bytes();
        assert!(!rotation.verify());
    }

    #[test]
    fn test_empty_trust_store() {
        let trust_store = TrustedDeveloperKeys::empty();
        assert_eq!(trust_store.key_count(), 0);

        let developer = DeveloperSigningKey::generate();
        let apk = b"APK that nobody trusts because trust store is empty";
        let sig = developer.sign_apk(apk);

        assert_eq!(
            trust_store.verify_apk(apk, &sig),
            ApkVerifyResult::UntrustedDeveloper
        );
    }
}
