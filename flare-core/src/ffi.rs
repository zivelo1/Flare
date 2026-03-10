//! FFI layer — exposes Flare core to Kotlin (Android) and Swift (iOS) via UniFFI.
//!
//! This module bridges the internal Rust types to the flat structures
//! defined in flare.udl. All complexity is hidden behind a clean API.

use std::sync::Mutex;

use crate::crypto::identity::{DeviceId, Identity, PublicIdentity};
use crate::crypto::keys::derive_message_key;
use crate::crypto::sender_keys::{SenderKeyDistribution, SenderKeyMessage, SenderKeyStore};
use crate::crypto::{decrypt_message, encrypt_message};
use crate::power::{PowerManager, PowerTier};
use crate::protocol::apk_signing::{ApkSignature, ApkVerifyResult, TrustedDeveloperKeys};
use crate::protocol::message::{ContentType, MeshMessage, MessageBuilder};
use crate::protocol::rendezvous::{
    self, RendezvousManager, RendezvousMode, RendezvousPayload, RendezvousReply,
};
use crate::routing::router::{RouteDecision, Router};
use crate::storage::database::FlareDatabase;
use crate::transport::compression::{compress_payload, decompress_payload};
use crate::{PROTOCOL_VERSION, SERVICE_IDENTIFIER};
use base64::Engine as _;
use sha2::Digest;

fn hex_to_bytes(hex: &str) -> Result<Vec<u8>, FlareError> {
    if !hex.len().is_multiple_of(2) {
        return Err(FlareError::InvalidInput {
            msg: "Hex string must have even length".to_string(),
        });
    }
    (0..hex.len())
        .step_by(2)
        .map(|i| {
            u8::from_str_radix(&hex[i..i + 2], 16)
                .map_err(|e| FlareError::InvalidInput { msg: e.to_string() })
        })
        .collect()
}

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
    /// The encrypted payload bytes from the mesh message envelope.
    /// Pass this to decrypt_incoming_message() — NOT the full serialized frame.
    pub payload: Vec<u8>,
}

#[derive(uniffi::Enum)]
pub enum FfiRouteDecision {
    DeliverLocally,
    Forward,
    Store,
    DropDuplicate,
    DropExpired,
    DropHopLimit,
    DropInvalidSignature,
    DropTtlInflation,
    DropHopCountDecrease,
    DropSenderRateLimit,
}

/// Power tier recommendation from the adaptive power manager.
#[derive(uniffi::Record)]
pub struct FfiPowerTierRecommendation {
    /// Tier name: "high", "balanced", "low_power", "ultra_low"
    pub tier: String,
    pub scan_window_ms: u32,
    pub scan_interval_ms: u32,
    pub advertise_interval_ms: u32,
    pub burst_scan_duration_ms: u32,
    pub burst_sleep_duration_ms: u32,
    pub use_burst_mode: bool,
}

/// APK verification result.
#[derive(uniffi::Enum)]
pub enum FfiApkVerifyResult {
    Valid,
    InvalidSignature,
    UntrustedDeveloper,
}

#[derive(uniffi::Record)]
pub struct FfiGroup {
    pub group_id: String,
    pub group_name: String,
    pub created_at: String,
    pub creator_device_id: String,
}

#[derive(uniffi::Record)]
pub struct FfiDiscoveredContact {
    pub device_id: String,
    pub signing_public_key: Vec<u8>,
    pub agreement_public_key: Vec<u8>,
    pub discovery_method: String,
}

#[derive(uniffi::Record)]
pub struct FfiMeshStatus {
    pub connected_peers: u32,
    pub discovered_peers: u32,
    pub stored_messages: u32,
    pub messages_relayed: u64,
}

#[derive(uniffi::Record)]
pub struct FfiStoreStats {
    pub total_messages: u32,
    pub own_messages: u32,
    pub active_relay_messages: u32,
    pub waiting_relay_messages: u32,
    pub total_bytes: u64,
    pub budget_bytes: u64,
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

/// Checks if a passphrase is the duress passphrase for a given database.
/// Call this BEFORE constructing FlareNode to decide which database to open.
/// Returns false if the database doesn't exist or has no duress config.
#[uniffi::export]
pub fn check_duress_passphrase(
    db_path: String,
    passphrase: String,
    check_passphrase: String,
) -> bool {
    let db = match FlareDatabase::open(&db_path, &passphrase) {
        Ok(db) => db,
        Err(_) => return false,
    };
    db.check_duress_passphrase(&check_passphrase)
        .unwrap_or(false)
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
    rendezvous: Mutex<RendezvousManager>,
    power_manager: PowerManager,
    sender_key_store: Mutex<SenderKeyStore>,
    trusted_developer_keys: Mutex<TrustedDeveloperKeys>,
}

#[uniffi::export]
impl FlareNode {
    /// Creates or loads a FlareNode. On first run, generates a new identity.
    /// On subsequent runs, loads the existing identity from the encrypted database.
    #[uniffi::constructor]
    pub fn new(db_path: String, passphrase: String) -> Result<Self, FlareError> {
        let db = FlareDatabase::open(&db_path, &passphrase)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;

        let identity = match db
            .load_identity()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?
        {
            Some((signing, agreement)) => Identity::from_key_bytes(&signing, &agreement)
                .map_err(|e| FlareError::CryptoError { msg: e.to_string() })?,
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
            rendezvous: Mutex::new(RendezvousManager::new()),
            power_manager: PowerManager::with_defaults(),
            sender_key_store: Mutex::new(SenderKeyStore::new()),
            trusted_developer_keys: Mutex::new(TrustedDeveloperKeys::empty()),
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
                msg: format!(
                    "Agreement key must be 32 bytes, got {}",
                    recipient_agreement_key.len()
                ),
            });
        }

        let mut key_bytes = [0u8; 32];
        key_bytes.copy_from_slice(&recipient_agreement_key);

        // Derive shared secret via Diffie-Hellman
        let shared_secret = self.identity.agree(&key_bytes);

        // Derive per-message key using recipient device ID as salt
        let key_material = derive_message_key(&shared_secret, recipient_device_id.as_bytes());

        // Compress plaintext before encryption (compressed data doesn't compress)
        let compressed = compress_payload(plaintext.as_bytes());

        // Encrypt the compressed plaintext
        let encrypted = encrypt_message(&key_material, &compressed)
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
                msg: format!(
                    "Agreement key must be 32 bytes, got {}",
                    sender_agreement_key.len()
                ),
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
            Ok(compressed_plaintext) => {
                // Decompress after decryption
                let decompressed = decompress_payload(&compressed_plaintext).map_err(|e| {
                    FlareError::SerializationError {
                        msg: format!("Decompression failed: {}", e),
                    }
                })?;
                let text = String::from_utf8(decompressed)
                    .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;
                Ok(Some(text))
            }
            Err(_) => Ok(None), // Decryption failed — wrong key or tampered
        }
    }

    /// Builds a mesh message ready for BLE transmission.
    /// content_type: 1=Text, 2=VoiceMessage, 3=Image, 4=KeyExchange, 5=Acknowledgment, 6=ReadReceipt
    pub fn build_mesh_message(
        &self,
        recipient_device_id: String,
        encrypted_payload: Vec<u8>,
        content_type: u8,
    ) -> Result<FfiMeshMessage, FlareError> {
        let recipient_id = DeviceId::from_hex(&recipient_device_id)
            .map_err(|e| FlareError::InvalidInput { msg: e.to_string() })?;

        let ct = match content_type {
            0x01 => ContentType::Text,
            0x02 => ContentType::VoiceMessage,
            0x03 => ContentType::Image,
            0x04 => ContentType::KeyExchange,
            0x05 => ContentType::Acknowledgment,
            0x06 => ContentType::ReadReceipt,
            0x07 => ContentType::GroupMessage,
            0x10 => ContentType::RouteRequest,
            0x11 => ContentType::RouteReply,
            0x20 => ContentType::PeerAnnounce,
            0x40 => ContentType::ApkOffer,
            0x41 => ContentType::ApkRequest,
            _ => {
                return Err(FlareError::InvalidInput {
                    msg: format!("Unknown content type: {}", content_type),
                })
            }
        };

        let msg = MessageBuilder::new(self.identity.device_id().clone(), recipient_id)
            .content_type(ct)
            .payload(encrypted_payload)
            .build(|data| self.identity.sign(data));

        let payload = msg.payload.clone();
        let serialized = msg
            .to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

        let message_id_hex = msg
            .message_id
            .iter()
            .map(|b| format!("{:02x}", b))
            .collect::<String>();

        Ok(FfiMeshMessage {
            serialized,
            message_id: message_id_hex,
            sender_id: msg.sender_id.to_hex(),
            recipient_id: msg.recipient_id.to_hex(),
            hop_count: msg.hop_count,
            max_hops: msg.max_hops,
            payload,
        })
    }

    /// Parses a raw mesh message received from BLE.
    pub fn parse_mesh_message(
        &self,
        raw_data: Vec<u8>,
    ) -> Result<Option<FfiMeshMessage>, FlareError> {
        match MeshMessage::from_bytes(&raw_data) {
            Ok(msg) => {
                let message_id_hex = msg
                    .message_id
                    .iter()
                    .map(|b| format!("{:02x}", b))
                    .collect::<String>();

                Ok(Some(FfiMeshMessage {
                    serialized: raw_data,
                    message_id: message_id_hex,
                    sender_id: msg.sender_id.to_hex(),
                    recipient_id: msg.recipient_id.to_hex(),
                    hop_count: msg.hop_count,
                    max_hops: msg.max_hops,
                    payload: msg.payload,
                }))
            }
            Err(_) => Ok(None),
        }
    }

    /// Routes an incoming mesh message and returns the routing decision.
    /// Route guard validation (TTL inflation, hop count monotonicity) is applied
    /// automatically by the router.
    pub fn route_incoming(&self, raw_message: Vec<u8>) -> FfiRouteDecision {
        let msg = match MeshMessage::from_bytes(&raw_message) {
            Ok(m) => m,
            Err(_) => return FfiRouteDecision::DropInvalidSignature,
        };

        // Attempt signature verification if we know the sender
        let db = self.db.lock().expect("db lock");
        let known_identity = db.load_contact(&msg.sender_id).ok().flatten();
        drop(db);

        if let Some(ref identity) = known_identity {
            let guard_result = self.router.validate_message(&msg, Some(identity));
            match guard_result {
                crate::routing::route_guard::GuardResult::Accept => {}
                crate::routing::route_guard::GuardResult::RejectInvalidSignature => {
                    return FfiRouteDecision::DropInvalidSignature;
                }
                crate::routing::route_guard::GuardResult::RejectTtlInflation { .. } => {
                    return FfiRouteDecision::DropTtlInflation;
                }
                crate::routing::route_guard::GuardResult::RejectHopCountDecrease { .. } => {
                    return FfiRouteDecision::DropHopCountDecrease;
                }
                crate::routing::route_guard::GuardResult::RejectSenderRateLimit { .. } => {
                    return FfiRouteDecision::DropSenderRateLimit;
                }
            }
        }

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
                    DropReason::InvalidSignature => FfiRouteDecision::DropInvalidSignature,
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
            self.router
                .on_peer_connected(&did)
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
        db.upsert_contact(
            &public_identity,
            contact.display_name.as_deref(),
            contact.is_verified,
        )
        .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Lists all contacts.
    pub fn list_contacts(&self) -> Result<Vec<FfiContact>, FlareError> {
        let db = self.db.lock().expect("db lock");
        let contacts = db
            .list_contacts()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;

        Ok(contacts
            .into_iter()
            .map(|(identity, name, verified)| FfiContact {
                device_id: identity.device_id.to_hex(),
                signing_public_key: identity.signing_public_key.to_vec(),
                agreement_public_key: identity.agreement_public_key.to_vec(),
                display_name: name,
                is_verified: verified,
            })
            .collect())
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
        )
        .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Gets all messages for a conversation, ordered by creation time.
    pub fn get_messages_for_conversation(
        &self,
        conversation_id: String,
    ) -> Result<Vec<FfiChatMessage>, FlareError> {
        let db = self.db.lock().expect("db lock");
        let messages = db
            .get_messages_for_conversation(&conversation_id)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;

        Ok(messages
            .into_iter()
            .map(
                |(
                    msg_id,
                    conv_id,
                    sender_id,
                    _content_type,
                    plaintext,
                    created_at,
                    is_outgoing,
                    delivery_status,
                )| {
                    let content = String::from_utf8(plaintext).unwrap_or_default();
                    let timestamp_ms = chrono::DateTime::parse_from_rfc3339(&created_at)
                        .map(|dt| dt.timestamp_millis())
                        .unwrap_or(0);

                    FfiChatMessage {
                        message_id: msg_id,
                        conversation_id: conv_id,
                        sender_device_id: sender_id,
                        content,
                        timestamp_ms,
                        is_outgoing,
                        delivery_status,
                    }
                },
            )
            .collect())
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
        let pending = db
            .get_pending_outbound()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;

        Ok(pending
            .into_iter()
            .map(|(msg_id, recipient, payload)| FfiChatMessage {
                message_id: msg_id,
                conversation_id: recipient.clone(),
                sender_device_id: self.identity.device_id().to_hex(),
                content: base64::engine::general_purpose::STANDARD.encode(&payload),
                timestamp_ms: chrono::Utc::now().timestamp_millis(),
                is_outgoing: true,
                delivery_status: 0,
            })
            .collect())
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

    // ── Multi-Hop Relay ─────────────────────────────────────────────

    /// Prepares a raw mesh message for relay by incrementing its hop count.
    /// Returns the updated serialized message, or an error if hop limit is reached.
    pub fn prepare_for_relay(&self, raw_message: Vec<u8>) -> Result<Vec<u8>, FlareError> {
        let mut msg = MeshMessage::from_bytes(&raw_message)
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

        if !self.router.prepare_for_relay(&mut msg) {
            return Err(FlareError::RoutingError {
                msg: "Message has reached its hop limit".to_string(),
            });
        }

        msg.to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })
    }

    // ── Receipts ──────────────────────────────────────────────────────

    /// Creates a delivery acknowledgment for a received message.
    /// Returns serialized mesh message bytes ready for BLE transmission.
    pub fn create_delivery_ack(
        &self,
        original_message_id: String,
        sender_device_id: String,
    ) -> Result<Vec<u8>, FlareError> {
        let sender_id = DeviceId::from_hex(&sender_device_id)
            .map_err(|e| FlareError::InvalidInput { msg: e.to_string() })?;

        let msg_id_bytes = hex_to_bytes(&original_message_id)?;

        let msg = MessageBuilder::new(self.identity.device_id().clone(), sender_id)
            .content_type(ContentType::Acknowledgment)
            .payload(msg_id_bytes)
            .build(|data| self.identity.sign(data));

        msg.to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })
    }

    /// Creates a read receipt for a message the user has viewed.
    /// Returns serialized mesh message bytes ready for BLE transmission.
    pub fn create_read_receipt(
        &self,
        original_message_id: String,
        sender_device_id: String,
    ) -> Result<Vec<u8>, FlareError> {
        let sender_id = DeviceId::from_hex(&sender_device_id)
            .map_err(|e| FlareError::InvalidInput { msg: e.to_string() })?;

        let msg_id_bytes = hex_to_bytes(&original_message_id)?;

        let msg = MessageBuilder::new(self.identity.device_id().clone(), sender_id)
            .content_type(ContentType::ReadReceipt)
            .payload(msg_id_bytes)
            .build(|data| self.identity.sign(data));

        msg.to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })
    }

    /// Updates the delivery status of a stored message.
    pub fn update_delivery_status(&self, message_id: String, status: u8) -> Result<(), FlareError> {
        let db = self.db.lock().expect("db lock");
        db.update_delivery_status(&message_id, status)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    // ── Neighborhood Detection ─────────────────────────────────────

    /// Records a peer's short ID (4 bytes) in the neighborhood filter.
    /// Call this whenever a peer is discovered via BLE scan.
    pub fn record_neighborhood_peer(&self, short_id: Vec<u8>) {
        if short_id.len() >= 4 {
            let mut id = [0u8; 4];
            id.copy_from_slice(&short_id[..4]);
            self.router.record_neighborhood_peer(&id);
        }
    }

    /// Exports the neighborhood filter bitmap for exchange with a remote peer.
    pub fn export_neighborhood_bitmap(&self) -> Vec<u8> {
        self.router.export_neighborhood_bitmap()
    }

    /// Processes a remote peer's neighborhood bitmap.
    /// Returns the encounter type as a string: "local", "bridge", or "intermediate".
    /// If "bridge", stored messages have their TTL automatically extended.
    pub fn process_remote_neighborhood(&self, remote_bitmap: Vec<u8>) -> String {
        let encounter = self.router.process_remote_neighborhood(&remote_bitmap);
        match encounter {
            crate::routing::neighborhood::EncounterType::Local => "local".to_string(),
            crate::routing::neighborhood::EncounterType::Bridge => "bridge".to_string(),
            crate::routing::neighborhood::EncounterType::Intermediate => "intermediate".to_string(),
        }
    }

    // ── Store Statistics ───────────────────────────────────────────

    // ── Group Messaging ─────────────────────────────────────────────

    /// Creates a new group.
    pub fn create_group(&self, group_id: String, group_name: String) -> Result<(), FlareError> {
        let creator_id = self.identity.device_id().to_hex();
        let db = self.db.lock().expect("db lock");
        db.create_group(&group_id, &group_name, &creator_id)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;
        // Add ourselves as first member
        db.add_group_member(&group_id, &creator_id)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Adds a member to a group.
    pub fn add_group_member(&self, group_id: String, device_id: String) -> Result<(), FlareError> {
        let db = self.db.lock().expect("db lock");
        db.add_group_member(&group_id, &device_id)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Removes a member from a group.
    pub fn remove_group_member(
        &self,
        group_id: String,
        device_id: String,
    ) -> Result<(), FlareError> {
        let db = self.db.lock().expect("db lock");
        db.remove_group_member(&group_id, &device_id)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Lists all groups.
    pub fn list_groups(&self) -> Result<Vec<FfiGroup>, FlareError> {
        let db = self.db.lock().expect("db lock");
        let groups = db
            .list_groups()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })?;

        Ok(groups
            .into_iter()
            .map(|(id, name, created_at, creator)| FfiGroup {
                group_id: id,
                group_name: name,
                created_at,
                creator_device_id: creator,
            })
            .collect())
    }

    /// Gets all member device IDs for a group.
    pub fn get_group_members(&self, group_id: String) -> Result<Vec<String>, FlareError> {
        let db = self.db.lock().expect("db lock");
        db.get_group_members(&group_id)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Sends a group message by encrypting it individually for each member.
    /// Returns the serialized mesh messages (one per member, excluding self).
    pub fn build_group_messages(
        &self,
        _group_id: String,
        encrypted_payloads: Vec<Vec<u8>>,
        member_device_ids: Vec<String>,
    ) -> Result<Vec<FfiMeshMessage>, FlareError> {
        if encrypted_payloads.len() != member_device_ids.len() {
            return Err(FlareError::InvalidInput {
                msg: "Payload count must match member count".to_string(),
            });
        }

        let my_device_id = self.identity.device_id().to_hex();
        let mut results = Vec::new();

        for (payload, recipient_id) in encrypted_payloads
            .into_iter()
            .zip(member_device_ids.into_iter())
        {
            // Skip ourselves
            if recipient_id == my_device_id {
                continue;
            }

            let msg = self.build_mesh_message(recipient_id, payload, 0x07)?;
            results.push(msg);
        }

        Ok(results)
    }

    // ── Rendezvous Discovery ─────────────────────────────────────────

    /// Starts a shared-phrase rendezvous search.
    /// Returns the token hex string and a serialized broadcast RouteRequest message.
    pub fn start_passphrase_search(&self, passphrase: String) -> Result<Vec<u8>, FlareError> {
        let token = rendezvous::generate_phrase_token(&passphrase);

        let mut mgr = self.rendezvous.lock().expect("rendezvous lock");
        let payload = mgr.start_search(token, RendezvousMode::SharedPhrase);

        let payload_bytes = payload
            .to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

        // Build a broadcast mesh message with the rendezvous payload
        let msg = MessageBuilder::broadcast(self.identity.device_id().clone())
            .content_type(ContentType::RouteRequest)
            .payload(payload_bytes)
            .build(|data| self.identity.sign(data));

        msg.to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })
    }

    /// Starts a phone-number rendezvous search.
    /// Returns serialized broadcast RouteRequest message.
    pub fn start_phone_search(
        &self,
        my_phone: String,
        their_phone: String,
    ) -> Result<Vec<u8>, FlareError> {
        let token = rendezvous::generate_phone_token(&my_phone, &their_phone);

        let mut mgr = self.rendezvous.lock().expect("rendezvous lock");
        let payload = mgr.start_search(token, RendezvousMode::PhoneNumber);

        let payload_bytes = payload
            .to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

        let msg = MessageBuilder::broadcast(self.identity.device_id().clone())
            .content_type(ContentType::RouteRequest)
            .payload(payload_bytes)
            .build(|data| self.identity.sign(data));

        msg.to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })
    }

    /// Registers the user's phone number for incoming rendezvous searches.
    /// Stores a hash of the phone number (never the number itself).
    pub fn register_my_phone(&self, phone_number: String) -> Result<(), FlareError> {
        let normalized = phone_number.trim().to_string();
        let hash = sha2::Sha256::digest(normalized.as_bytes());
        let db = self.db.lock().expect("db lock");
        db.store_phone_hash(&hash)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Imports phone contacts and pre-computes bilateral rendezvous tokens.
    /// The user's own phone number must be registered first via register_my_phone.
    /// Returns the number of tokens generated.
    pub fn import_phone_contacts(
        &self,
        my_phone: String,
        contacts: Vec<String>,
    ) -> Result<u32, FlareError> {
        let mut mgr = self.rendezvous.lock().expect("rendezvous lock");
        let mut count = 0u32;

        for contact_phone in &contacts {
            let token = rendezvous::generate_phone_token(&my_phone, contact_phone);
            mgr.register_token(token, RendezvousMode::ContactImport);
            count += 1;
        }

        Ok(count)
    }

    /// Cancels an active rendezvous search by token hex string.
    pub fn cancel_search(&self, token_hex: String) -> Result<(), FlareError> {
        let token_bytes = hex_to_bytes(&token_hex)?;
        if token_bytes.len() != 8 {
            return Err(FlareError::InvalidInput {
                msg: "Token must be 8 bytes".to_string(),
            });
        }
        let mut token = [0u8; 8];
        token.copy_from_slice(&token_bytes);

        let mut mgr = self.rendezvous.lock().expect("rendezvous lock");
        mgr.cancel_search(&token);
        Ok(())
    }

    /// Builds broadcast messages for all active rendezvous searches.
    /// Call this periodically (e.g., every 30 seconds) to re-broadcast.
    pub fn build_rendezvous_broadcasts(&self) -> Result<Vec<Vec<u8>>, FlareError> {
        let mgr = self.rendezvous.lock().expect("rendezvous lock");
        let tokens = mgr.active_search_tokens();
        drop(mgr);

        let mut broadcasts = Vec::new();
        for token in tokens {
            let payload = RendezvousPayload {
                token,
                ephemeral_public_key: [0u8; 32],
                proof_of_work: rendezvous::generate_proof_of_work(&token),
            };

            let payload_bytes = payload
                .to_bytes()
                .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

            let msg = MessageBuilder::broadcast(self.identity.device_id().clone())
                .content_type(ContentType::RouteRequest)
                .payload(payload_bytes)
                .build(|data| self.identity.sign(data));

            let serialized = msg
                .to_bytes()
                .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;
            broadcasts.push(serialized);
        }
        Ok(broadcasts)
    }

    /// Processes an incoming RouteRequest or RouteReply message.
    /// For RouteRequest: checks if token matches, generates reply if so.
    /// For RouteReply: decrypts discovered contact identity.
    /// Returns: (discovered_contact, reply_bytes_to_send)
    pub fn process_rendezvous_message(
        &self,
        raw_payload: Vec<u8>,
        content_type: u8,
    ) -> Result<Option<FfiDiscoveredContact>, FlareError> {
        let mgr = self.rendezvous.lock().expect("rendezvous lock");

        match content_type {
            0x10 => {
                // RouteRequest — check if we should respond
                let payload = RendezvousPayload::from_bytes(&raw_payload)
                    .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

                if let Some(_reply) =
                    mgr.process_incoming_request(&payload, &self.identity.public_identity())
                {
                    // We matched! But we return None here because WE didn't discover anyone —
                    // we need to send the reply back. The caller should send the reply.
                    // For now, we auto-send the reply as a mesh message.
                    // TODO: Return the reply bytes for the caller to send
                    Ok(None)
                } else {
                    Ok(None)
                }
            }
            0x11 => {
                // RouteReply — try to decrypt the discovered identity
                let reply = RendezvousReply::from_bytes(&raw_payload)
                    .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

                if let Some((identity, mode)) = mgr.process_incoming_reply(&reply) {
                    let method = match mode {
                        RendezvousMode::SharedPhrase => "phrase",
                        RendezvousMode::PhoneNumber => "phone",
                        RendezvousMode::ContactImport => "contact",
                    };
                    Ok(Some(FfiDiscoveredContact {
                        device_id: identity.device_id.to_hex(),
                        signing_public_key: identity.signing_public_key.to_vec(),
                        agreement_public_key: identity.agreement_public_key.to_vec(),
                        discovery_method: method.to_string(),
                    }))
                } else {
                    Ok(None)
                }
            }
            _ => Ok(None),
        }
    }

    /// Processes a RouteRequest and returns reply bytes to send back, if matched.
    pub fn process_rendezvous_request(
        &self,
        raw_payload: Vec<u8>,
        sender_device_id: String,
    ) -> Result<Option<Vec<u8>>, FlareError> {
        let payload = RendezvousPayload::from_bytes(&raw_payload)
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

        let mgr = self.rendezvous.lock().expect("rendezvous lock");
        if let Some(reply) =
            mgr.process_incoming_request(&payload, &self.identity.public_identity())
        {
            let reply_bytes = reply
                .to_bytes()
                .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

            let sender_id = DeviceId::from_hex(&sender_device_id)
                .map_err(|e| FlareError::InvalidInput { msg: e.to_string() })?;

            let msg = MessageBuilder::new(self.identity.device_id().clone(), sender_id)
                .content_type(ContentType::RouteReply)
                .payload(reply_bytes)
                .build(|data| self.identity.sign(data));

            let serialized = msg
                .to_bytes()
                .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;
            Ok(Some(serialized))
        } else {
            Ok(None)
        }
    }

    /// Returns the number of active rendezvous searches.
    pub fn active_search_count(&self) -> u32 {
        let mgr = self.rendezvous.lock().expect("rendezvous lock");
        mgr.active_search_count() as u32
    }

    // ── Duress PIN ──────────────────────────────────────────────────

    /// Sets a duress passphrase. When entered at login, a decoy database is opened
    /// with innocent data while the real database stays hidden.
    pub fn set_duress_passphrase(&self, passphrase: String) -> Result<(), FlareError> {
        let db = self.db.lock().expect("db lock");
        db.set_duress_passphrase(&passphrase)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Returns true if a duress passphrase has been configured.
    pub fn has_duress_passphrase(&self) -> Result<bool, FlareError> {
        let db = self.db.lock().expect("db lock");
        db.has_duress_passphrase()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Removes the duress passphrase configuration.
    pub fn clear_duress_passphrase(&self) -> Result<(), FlareError> {
        let db = self.db.lock().expect("db lock");
        db.clear_duress_passphrase()
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    /// Checks whether the given passphrase is the duress passphrase.
    /// The mobile app should call this BEFORE opening the main database.
    /// If true, the app should open the decoy database instead.
    pub fn check_duress_passphrase(&self, passphrase: String) -> Result<bool, FlareError> {
        let db = self.db.lock().expect("db lock");
        db.check_duress_passphrase(&passphrase)
            .map_err(|e| FlareError::StorageError { msg: e.to_string() })
    }

    // ── Store Statistics ───────────────────────────────────────────

    /// Returns detailed store statistics.
    pub fn get_store_stats(&self) -> FfiStoreStats {
        let stats = self.router.store().stats();
        FfiStoreStats {
            total_messages: stats.total_messages as u32,
            own_messages: stats.own_messages as u32,
            active_relay_messages: stats.active_relay_messages as u32,
            waiting_relay_messages: stats.waiting_relay_messages as u32,
            total_bytes: stats.total_bytes as u64,
            budget_bytes: stats.budget_bytes as u64,
        }
    }

    // ── Adaptive Power Management ──────────────────────────────────

    /// Notifies the power manager that data was sent or received.
    /// Call this on every incoming/outgoing message to trigger High tier promotion.
    pub fn power_on_data_activity(&self, now_secs: i64) {
        self.power_manager.on_data_activity(now_secs);
    }

    /// Notifies the power manager that a peer was discovered via BLE scan.
    pub fn power_on_peer_discovered(&self, now_secs: i64) {
        self.power_manager.on_peer_discovered(now_secs);
    }

    /// Updates the battery percentage in the power manager.
    pub fn power_update_battery(&self, percent: u8) {
        self.power_manager.update_battery(percent);
    }

    /// Sets whether the user has enabled battery saver mode.
    pub fn power_set_battery_saver(&self, enabled: bool) {
        self.power_manager.set_user_battery_saver(enabled);
    }

    /// Updates the connected peer count in the power manager.
    pub fn power_update_connected_peers(&self, count: u32) {
        self.power_manager.update_connected_peers(count);
    }

    /// Updates whether there are pending outbound messages.
    pub fn power_set_has_pending_outbound(&self, has_pending: bool) {
        self.power_manager.set_has_pending_outbound(has_pending);
    }

    /// Evaluates the current state and returns the recommended power tier.
    /// Call this periodically (e.g., every scan cycle) from the mobile layer.
    pub fn power_evaluate(&self, now_secs: i64) -> FfiPowerTierRecommendation {
        let rec = self.power_manager.evaluate(now_secs);
        FfiPowerTierRecommendation {
            tier: match rec.tier {
                PowerTier::High => "high".to_string(),
                PowerTier::Balanced => "balanced".to_string(),
                PowerTier::LowPower => "low_power".to_string(),
                PowerTier::UltraLow => "ultra_low".to_string(),
            },
            scan_window_ms: rec.scan_window_ms,
            scan_interval_ms: rec.scan_interval_ms,
            advertise_interval_ms: rec.advertise_interval_ms,
            burst_scan_duration_ms: rec.burst_scan_duration_ms,
            burst_sleep_duration_ms: rec.burst_sleep_duration_ms,
            use_burst_mode: rec.use_burst_mode,
        }
    }

    /// Returns the current power tier without re-evaluating.
    pub fn power_current_tier(&self) -> String {
        match self.power_manager.current_tier() {
            PowerTier::High => "high".to_string(),
            PowerTier::Balanced => "balanced".to_string(),
            PowerTier::LowPower => "low_power".to_string(),
            PowerTier::UltraLow => "ultra_low".to_string(),
        }
    }

    // ── Sender Keys (Group Encryption) ─────────────────────────────

    /// Creates a sender key for a group and returns the distribution bytes.
    /// The distribution bytes should be sent to each group member via
    /// their existing pairwise DH channel (one-time per member).
    pub fn create_sender_key(&self, group_id: String) -> Result<Vec<u8>, FlareError> {
        let my_device_id = self.identity.device_id().to_hex();
        let mut store = self.sender_key_store.lock().expect("sender key lock");
        let distribution = store.create_sender_key(&group_id, &my_device_id);
        distribution
            .to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })
    }

    /// Processes a received sender key distribution from another group member.
    /// Call this when receiving a key distribution via pairwise DH channel.
    pub fn process_sender_key_distribution(
        &self,
        distribution_bytes: Vec<u8>,
    ) -> Result<(), FlareError> {
        let distribution = SenderKeyDistribution::from_bytes(&distribution_bytes)
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;
        let mut store = self.sender_key_store.lock().expect("sender key lock");
        store.process_distribution(&distribution);
        Ok(())
    }

    /// Encrypts a plaintext message for a group using sender keys.
    /// Returns serialized SenderKeyMessage bytes.
    /// The group must have been set up via `create_sender_key` first.
    pub fn encrypt_group_sender_key(
        &self,
        group_id: String,
        plaintext: String,
    ) -> Result<Vec<u8>, FlareError> {
        let compressed = compress_payload(plaintext.as_bytes());
        let mut store = self.sender_key_store.lock().expect("sender key lock");
        let message = store
            .encrypt_group_message(&group_id, &compressed)
            .map_err(|e| FlareError::CryptoError { msg: e.to_string() })?;
        message
            .to_bytes()
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })
    }

    /// Decrypts a group message using the sender's key.
    /// Returns the plaintext string, or None if decryption fails.
    pub fn decrypt_group_sender_key(
        &self,
        group_id: String,
        sender_device_id: String,
        encrypted_bytes: Vec<u8>,
    ) -> Result<Option<String>, FlareError> {
        let message = SenderKeyMessage::from_bytes(&encrypted_bytes)
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;
        let mut store = self.sender_key_store.lock().expect("sender key lock");
        match store.decrypt_group_message(&group_id, &sender_device_id, &message) {
            Ok(compressed) => {
                let decompressed = decompress_payload(&compressed).map_err(|e| {
                    FlareError::SerializationError {
                        msg: format!("Decompression failed: {}", e),
                    }
                })?;
                let text = String::from_utf8(decompressed)
                    .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;
                Ok(Some(text))
            }
            Err(_) => Ok(None),
        }
    }

    /// Invalidates all sender keys for a group (call on membership change).
    pub fn invalidate_group_keys(&self, group_id: String) {
        let mut store = self.sender_key_store.lock().expect("sender key lock");
        store.invalidate_group(&group_id);
    }

    // ── APK Signing Verification ────────────────────────────────────

    /// Adds a trusted developer public key (32 bytes).
    /// On first install, the installing APK's developer key is trusted (TOFU).
    pub fn add_trusted_developer_key(&self, public_key: Vec<u8>) -> Result<(), FlareError> {
        if public_key.len() != 32 {
            return Err(FlareError::InvalidInput {
                msg: format!("Developer key must be 32 bytes, got {}", public_key.len()),
            });
        }
        let mut key = [0u8; 32];
        key.copy_from_slice(&public_key);
        let mut store = self.trusted_developer_keys.lock().expect("dev keys lock");
        store.add_key(key);
        Ok(())
    }

    /// Verifies an APK against its signature using the trusted developer key store.
    /// `apk_hash`: SHA-256 hash of the APK file (32 bytes).
    /// `signature_bytes`: Serialized ApkSignature.
    pub fn verify_apk_signature(
        &self,
        apk_hash: Vec<u8>,
        signature_bytes: Vec<u8>,
    ) -> Result<FfiApkVerifyResult, FlareError> {
        if apk_hash.len() != 32 {
            return Err(FlareError::InvalidInput {
                msg: format!("APK hash must be 32 bytes, got {}", apk_hash.len()),
            });
        }
        let mut hash = [0u8; 32];
        hash.copy_from_slice(&apk_hash);

        let signature = ApkSignature::from_bytes(&signature_bytes)
            .map_err(|e| FlareError::SerializationError { msg: e.to_string() })?;

        let store = self.trusted_developer_keys.lock().expect("dev keys lock");
        Ok(match store.verify_apk_with_hash(&hash, &signature) {
            ApkVerifyResult::Valid => FfiApkVerifyResult::Valid,
            ApkVerifyResult::InvalidSignature => FfiApkVerifyResult::InvalidSignature,
            ApkVerifyResult::UntrustedDeveloper => FfiApkVerifyResult::UntrustedDeveloper,
        })
    }

    /// Returns the number of trusted developer keys.
    pub fn trusted_developer_key_count(&self) -> u32 {
        let store = self.trusted_developer_keys.lock().expect("dev keys lock");
        store.key_count() as u32
    }

    // ── Compression (standalone) ─────────────────────────────────────

    /// Compresses a payload for efficient BLE transmission.
    /// Compression is already integrated into encrypt/decrypt, but this
    /// is exposed for direct use (e.g., compressing non-encrypted data).
    pub fn compress(&self, data: Vec<u8>) -> Vec<u8> {
        compress_payload(&data)
    }

    /// Decompresses a payload that was compressed with `compress`.
    pub fn decompress(&self, data: Vec<u8>) -> Result<Vec<u8>, FlareError> {
        decompress_payload(&data).map_err(|e| FlareError::SerializationError {
            msg: format!("Decompression failed: {}", e),
        })
    }
}
