//! Mesh message protocol — wire format and serialization.
//!
//! Defines the message structure that travels across the mesh network.
//! All fields are serialized to a compact binary format using bincode.

pub mod message;

pub use message::{MeshMessage, ContentType, MessageBuilder};
