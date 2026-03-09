//! Flare Core — Encrypted mesh messaging library
//!
//! This is the shared core used by both Android and iOS clients.
//! It provides: identity management, encryption, message protocol,
//! routing, and local storage.

pub mod crypto;
pub mod protocol;
pub mod routing;
pub mod storage;
pub mod transport;

/// Flare protocol version. Increment on breaking wire-format changes.
pub const PROTOCOL_VERSION: u8 = 1;

/// Application identifier used in BLE service discovery.
pub const SERVICE_IDENTIFIER: &str = "flare-mesh-v1";
