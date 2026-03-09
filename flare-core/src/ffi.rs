//! FFI layer — exposes Flare core to Kotlin (Android) and Swift (iOS) via UniFFI.
//!
//! This module bridges the internal Rust types to the flat structures
//! defined in flare.udl. All complexity is hidden behind a clean API.

use std::sync::Mutex;

use crate::crypto::identity::{DeviceId, Identity, PublicIdentity};
use crate::crypto::{decrypt_message, encrypt_message};
use crate::crypto::keys::derive_message_key;
use crate::protocol::message::{ContentType, MeshMessage, MessageBuilder};
use crate::routing::router::{RouteDecision, Router};
use crate::storage::database::FlareDatabase;
use base64::Engine as _;
use crate::{PROTOCOL_VERSION, SERVICE_IDENTIFIER};

/// Error type exposed to mobile platforms via UniFFI.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FlareError {
    #[error("Cryptographic operation failed: {msg}")]
    CryptoError { msg: String },

    #[error("Database operation failed: {msg}")]
    StorageError { msg: String },

    #[error("Routing error: {msg}")]
    RoutingError { msg: String },

    #[error("Serialization error: {msg}")]
    SerializationError { msg: String },

    #[error("Invalid input: {msg}")]
    InvalidInput { msg: String },
}

// ── FFI Data Structures ──────────────────────────────────────────────

#[derive(uniffi::Record)]
pub struct FfiPublicIdentity {
    pub device_id: String,
    pub signing_public_key: Vec<u8>,
    pub agreement_public_key: Vec<u8>,
}

#[derive(uniffi::Record)]
pub struct FfiContact {
    pub device_id: String,
    pub signing_public_key: Vec<u8>,
    pub agreement_public_key: Vec<u8>,
    pub display_name: Option<String>,
    pub is_verified: bool,
}

#[derive(uniffi::Record)]
pub struct FfiChatMessage {
    pub message_id: String,
    pub conversation_id: String,
    pub sender_device_id: String,
    pub content: String,
    pub timestamp_ms: i64,
    pub is_outgoing: bool,
    pub delivery_status: u8,
}

#[derive(uniffi::Record)]
pub struct FfiMeshMessage {
    pub serialized: Vec<u8>,
    pub message_id: String,
    pub sender_id: String,
    pub recipient_id: String,
    pub hop_count: u8,
    pub max_hops: u8,
}

#[derive(uniffi::Enum)]
pub enum FfiRouteDecision {
    DeliverLocally,
    Forward,
    Store,
    DropDuplicate,
    DropExpired,
    DropHopLimit,
}

#[derive(uniffi::Record)]
pub struct FfiMeshStatus {
    pub connected_peers: u32,
    pub discovered_peers: u32,
    pub stored_messages: u32,
    pub messages_relayed: u64,
}

// ── Namespace Functions ──────────────────────────────────────────────

#[uniffi::export]
pub fn get_protocol_version() -> String {
    PROTOCOL_VERSION.to_string()
}

#[uniffi::export]
pub fn get_service_identifier() -> String {
    SERVICE_IDENTIFIER.to_string()
}

// ── FlareNode (main entry point for mobile) ──────────────────────────

/// The main interface for mobile apps. Wraps identity, crypto, routing, and storage.
/// Thread-safe via internal Mutex guards.
#[derive(uniffi::Object)]
pub struct FlareNode {
    identity: Identity,
    router: Router,
    db: Mutex<FlareDatabase>,
    messages_relayed: Mutex<u64>,
}

#[uniffi::export]
impl FlareNode {
    /// Creates or loads a FlareNode. On first run, generates a new identity.
    /// On subsequent runs, loads the existing identity from the encrypted database.
    #[uniffi::constructor]
    pub fn new(db_path: String, passphrase: String) -> Result<Self, FlareError> {
        let db = FlareDatabase::open(&db_path, &passphrase)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;

        let identity = match db.load_identity()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })? {
            Some((signing, agreement)) => {
                Identity::from_key_bytes(&signing, &agreement)
                    .map_err(|e| FlareError::CryptoError { msg: e.to_string() })?
            }
            None => {
                let id = Identity::generate();
                db.store_identity(id.signing_key_bytes(), &id.agreement_key_bytes())
                    .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;
                id
            }
        };

        let router = Router::new(identity.device_id().clone());

        Ok(FlareNode {
            identity,
            router,
            db: Mutex::new(db),
            messages_relayed: Mutex::new(0),
        })
    }

    /// Returns the public identity (safe to share).
    pub fn get_public_identity(&self) -> Result<FfiPublicIdentity, FlareError> {
        let public = self.identity.public_identity();
        Ok(FfiPublicIdentity {
            device_id: public.device_id.to_hex(),
            signing_public_key: public.signing_public_key.to_vec(),
            agreement_public_key: public.agreement_public_key.to_vec(),
        })
    }

    /// Returns the device ID as a hex string.
    pub fn get_device_id(&self) -> String {
        self.identity.device_id().to_hex()
    }

    /// Returns the safety number for contact verification.
    pub fn get_safety_number(&self) -> String {
        self.identity.public_identity().safety_number()
    }

    /// Encrypts a plaintext message for a specific recipient.
    /// Returns the encrypted payload bytes.
    pub fn create_encrypted_message(
        &self,
        recipient_device_id: String,
        recipient_agreement_key: Vec<u8>,
        plaintext: String,
    ) -> Result<Vec<u8>, FlareError> {
        if recipient_agreement_key.len() != 32 {
            return Err(FlareError::InvalidInput {
                msg: format!("Agreement key must be 32 bytes, got {}", recipient_agreement_key.len()),
            });
        }

        let mut key_bytes = [0u8; 32];
        key_bytes.copy_from_slice(&recipient_agreement_key);

        // Derive shared secret via Diffie-Hellman
        let shared_secret = self.identity.agree(&key_bytes);

        // Derive per-message key using recipient device ID as salt
        let key_material = derive_message_key(&shared_secret, recipient_device_id.as_bytes());

        // Encrypt the plaintext
        let encrypted = encrypt_message(&key_material, plaintext.as_bytes())
            .map_err(|e| FlareError::CryptoError { msg: e.to_string() })?;

        // Serialize the encrypted payload
        bincode::serialize(&encrypted)
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })
    }

    /// Decrypts an incoming encrypted message from a sender.
    /// Returns the plaintext string, or None if decryption fails.
    pub fn decrypt_incoming_message(
        &self,
        _sender_device_id: String,
        sender_agreement_key: Vec<u8>,
        encrypted_data: Vec<u8>,
    ) -> Result<Option<String>, FlareError> {
        if sender_agreement_key.len() != 32 {
            return Err(FlareError::InvalidInput {
                msg: format!("Agreement key must be 32 bytes, got {}", sender_agreement_key.len()),
            });
        }

        let mut key_bytes = [0u8; 32];
        key_bytes.copy_from_slice(&sender_agreement_key);

        // Derive same shared secret (DH is symmetric)
        let shared_secret = self.identity.agree(&key_bytes);

        // Derive per-message key using OUR device ID as salt
        // (sender used our device ID when encrypting to us)
        let my_device_id = self.identity.device_id().to_hex();
        let key_material = derive_message_key(&shared_secret, my_device_id.as_bytes());

        // Deserialize and decrypt
        let encrypted = bincode::deserialize(&encrypted_data)
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

        match decrypt_message(&key_material, &encrypted) {
            Ok(plaintext) => {
                let text = String::from_utf8(plaintext)
                    .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;
                Ok(Some(text))
            }
            Err(_) => Ok(None), // Decryption failed — wrong key or tampered
        }
    }

    /// Builds a mesh message ready for BLE transmission.
    pub fn build_mesh_message(
        &self,
        recipient_device_id: String,
        encrypted_payload: Vec<u8>,
    ) -> Result<FfiMeshMessage, FlareError> {
        let recipient_id = DeviceId::from_hex(&recipient_device_id)
            .map_err(|e| FlareError::InvalidInput { msg: e.to_string() })?;

        let msg = MessageBuilder::new(
            self.identity.device_id().clone(),
            recipient_id,
        )
        .content_type(ContentType::Text)
        .payload(encrypted_payload)
        .build(|data| self.identity.sign(data));

        let serialized = msg.to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

        let message_id_hex = msg.message_id.iter()
            .map(|b| format!("{:02x}", b))
            .collect::<String>();

        Ok(FfiMeshMessage {
            serialized,
            message_id: message_id_hex,
            sender_id: msg.sender_id.to_hex(),
            recipient_id: msg.recipient_id.to_hex(),
            hop_count: msg.hop_count,
            max_hops: msg.max_hops,
        })
    }

    /// Parses a raw mesh message received from BLE.
    pub fn parse_mesh_message(&self, raw_data: Vec<u8>) -> Result<Option<FfiMeshMessage>, FlareError> {
        match MeshMessage::from_bytes(&raw_data) {
            Ok(msg) => {
                let message_id_hex = msg.message_id.iter()
                    .map(|b| format!("{:02x}", b))
                    .collect::<String>();

                Ok(Some(FfiMeshMessage {
                    serialized: raw_data,
                    message_id: message_id_hex,
                    sender_id: msg.sender_id.to_hex(),
                    recipient_id: msg.recipient_id.to_hex(),
                    hop_count: msg.hop_count,
                    max_hops: msg.max_hops,
                }))
            }
            Err(_) => Ok(None),
        }
    }

    /// Routes an incoming mesh message and returns the routing decision.
    pub fn route_incoming(&self, raw_message: Vec<u8>) -> FfiRouteDecision {
        let msg = match MeshMessage::from_bytes(&raw_message) {
            Ok(m) => m,
            Err(_) => return FfiRouteDecision::DropDuplicate,
        };

        match self.router.route_incoming(&msg) {
            RouteDecision::DeliverLocally => FfiRouteDecision::DeliverLocally,
            RouteDecision::Forward { .. } => {
                let mut count = self.messages_relayed.lock().expect("relayed lock");
                *count += 1;
                FfiRouteDecision::Forward
            }
            RouteDecision::Store => FfiRouteDecision::Store,
            RouteDecision::Drop { reason } => {
                use crate::routing::router::DropReason;
                match reason {
                    DropReason::Duplicate => FfiRouteDecision::DropDuplicate,
                    DropReason::Expired => FfiRouteDecision::DropExpired,
                    DropReason::HopLimitReached => FfiRouteDecision::DropHopLimit,
                    DropReason::InvalidSignature => FfiRouteDecision::DropDuplicate,
                }
            }
        }
    }

    /// Notifies the router that a peer has connected.
    pub fn notify_peer_connected(&self, device_id: String) {
        if let Ok(did) = DeviceId::from_hex(&device_id) {
            let _ = self.router.on_peer_connected(&did);
        }
    }

    /// Gets stored messages that should be forwarded to a specific peer.
    pub fn get_messages_for_peer(&self, device_id: String) -> Vec<Vec<u8>> {
        if let Ok(did) = DeviceId::from_hex(&device_id) {
            self.router.on_peer_connected(&did)
                .into_iter()
                .filter_map(|msg| msg.to_bytes().ok())
                .collect()
        } else {
            Vec::new()
        }
    }

    /// Adds or updates a contact.
    pub fn add_contact(&self, contact: FfiContact) -> Result<(), FlareError> {
        if contact.signing_public_key.len() != 32 || contact.agreement_public_key.len() != 32 {
            return Err(FlareError::InvalidInput {
                msg: "Public keys must be 32 bytes each".to_string(),
            });
        }

        let mut signing = [0u8; 32];
        let mut agreement = [0u8; 32];
        signing.copy_from_slice(&contact.signing_public_key);
        agreement.copy_from_slice(&contact.agreement_public_key);

        let device_id = DeviceId::from_hex(&contact.device_id)
            .map_err(|e| FlareError::InvalidInput { msg: e.to_string() })?;

        let public_identity = PublicIdentity {
            signing_public_key: signing,
            agreement_public_key: agreement,
            device_id,
        };

        let db = self.db.lock().expect("db lock");
        db.upsert_contact(&public_identity, contact.display_name.as_deref(), contact.is_verified)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Lists all contacts.
    pub fn list_contacts(&self) -> Result<Vec<FfiContact>, FlareError> {
        let db = self.db.lock().expect("db lock");
        let contacts = db.list_contacts()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;

        Ok(contacts.into_iter().map(|(identity, name, verified)| {
            FfiContact {
                device_id: identity.device_id.to_hex(),
                signing_public_key: identity.signing_public_key.to_vec(),
                agreement_public_key: identity.agreement_public_key.to_vec(),
                display_name: name,
                is_verified: verified,
            }
        }).collect())
    }

    /// Stores a chat message locally.
    pub fn store_chat_message(&self, message: FfiChatMessage) -> Result<(), FlareError> {
        let db = self.db.lock().expect("db lock");
        let created_at = chrono::DateTime::from_timestamp_millis(message.timestamp_ms)
            .map(|dt| dt.to_rfc3339())
            .unwrap_or_default();

        db.store_message(
            &message.message_id,
            &message.conversation_id,
            &message.sender_device_id,
            message.delivery_status,
            message.content.as_bytes(),
            &created_at,
            message.is_outgoing,
        ).map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Gets all messages for a conversation.
    pub fn get_messages_for_conversation(&self, _conversation_id: String) -> Result<Vec<FfiChatMessage>, FlareError> {
        // TODO: Implement conversation query in database
        Ok(Vec::new())
    }

    /// Queues an outbound message for delivery.
    pub fn queue_outbound_message(
        &self,
        message_id: String,
        recipient_device_id: String,
        encrypted_payload: Vec<u8>,
    ) -> Result<(), FlareError> {
        let db = self.db.lock().expect("db lock");
        db.queue_outbound(&message_id, &recipient_device_id, &encrypted_payload, 86400)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Gets all pending outbound messages.
    pub fn get_pending_outbound(&self) -> Result<Vec<FfiChatMessage>, FlareError> {
        let db = self.db.lock().expect("db lock");
        let pending = db.get_pending_outbound()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;

        Ok(pending.into_iter().map(|(msg_id, recipient, payload)| {
            FfiChatMessage {
                message_id: msg_id,
                conversation_id: recipient.clone(),
                sender_device_id: self.identity.device_id().to_hex(),
                content: base64::engine::general_purpose::STANDARD.encode(&payload),
                timestamp_ms: chrono::Utc::now().timestamp_millis(),
                is_outgoing: true,
                delivery_status: 0,
            }
        }).collect())
    }

    /// Removes a delivered message from the outbox.
    pub fn remove_from_outbox(&self, message_id: String) -> Result<(), FlareError> {
        let db = self.db.lock().expect("db lock");
        db.remove_from_outbox(&message_id)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Returns current mesh network status.
    pub fn get_mesh_status(&self) -> FfiMeshStatus {
        let relayed = *self.messages_relayed.lock().expect("relayed lock");
        FfiMeshStatus {
            connected_peers: self.router.peer_count() as u32,
            discovered_peers: 0,
            stored_messages: self.router.store_size() as u32,
            messages_relayed: relayed,
        }
    }

    /// Prunes expired messages from the routing store.
    pub fn prune_expired_messages(&self) -> u32 {
        self.router.prune_expired() as u32
    }

    /// Returns serialized peer info bytes for BLE advertising.
    /// Contains: protocol_version (1) + device_id short (4) = 5 bytes
    pub fn get_peer_info_bytes(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(5);
        bytes.push(PROTOCOL_VERSION);
        bytes.extend_from_slice(&self.identity.device_id().short_id());
        bytes
    }
}
