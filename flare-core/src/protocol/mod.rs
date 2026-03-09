//! Mesh message protocol — wire format and serialization.
//!
//! Defines the message structure that travels across the mesh network.
//! All fields are serialized to a compact binary format using bincode.

pub mod apk_share;
pub mod message;
pub mod rendezvous;

pub use message::{ContentType, MeshMessage, MessageBuilder};
