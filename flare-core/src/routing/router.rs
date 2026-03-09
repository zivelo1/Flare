//! Spray-and-Wait message router for the Flare mesh.
//!
//! Spray-and-Wait is a bounded-replication routing protocol for
//! delay-tolerant networks. It creates L copies of a message and
//! distributes them to encountered peers, then waits for delivery.
//!
//! This provides good delivery probability with bounded overhead,
//! making it suitable for phone-based mesh where connectivity is
//! intermittent and unpredictable.

use std::collections::HashMap;
use std::sync::Mutex;

use crate::crypto::identity::DeviceId;
use crate::protocol::message::MeshMessage;
use crate::routing::dedup::DeduplicationFilter;
use crate::routing::peer_table::PeerTable;

/// Default number of message copies in Spray-and-Wait.
const DEFAULT_SPRAY_COPIES: u8 = 8;

/// Maximum number of messages to store for forwarding.
const MAX_STORE_SIZE: usize = 1000;

/// Decision made by the router for an incoming message.
#[derive(Debug, PartialEq)]
pub enum RouteDecision {
    /// Message is for us — deliver to the application layer.
    DeliverLocally,
    /// Message should be forwarded to specific peers.
    Forward { target_peers: Vec<DeviceId> },
    /// Message should be stored for later forwarding (no suitable peers now).
    Store,
    /// Message should be dropped (duplicate, expired, or hop-limited).
    Drop { reason: DropReason },
}

#[derive(Debug, PartialEq)]
pub enum DropReason {
    Duplicate,
    Expired,
    HopLimitReached,
    InvalidSignature,
}

/// Stored message awaiting forwarding opportunities.
struct StoredMessage {
    message: MeshMessage,
    remaining_copies: u8,
}

/// The Spray-and-Wait mesh router.
pub struct Router {
    local_device_id: DeviceId,
    dedup: DeduplicationFilter,
    peer_table: PeerTable,
    store: Mutex<HashMap<[u8; 32], StoredMessage>>,
    spray_copies: u8,
}

impl Router {
    /// Creates a new router for the given local device.
    pub fn new(local_device_id: DeviceId) -> Self {
        Router {
            local_device_id,
            dedup: DeduplicationFilter::with_defaults(),
            peer_table: PeerTable::new(),
            store: Mutex::new(HashMap::new()),
            spray_copies: DEFAULT_SPRAY_COPIES,
        }
    }

    /// Creates a router with custom spray copy count.
    pub fn with_spray_copies(local_device_id: DeviceId, spray_copies: u8) -> Self {
        Router {
            spray_copies,
            ..Self::new(local_device_id)
        }
    }

    /// Returns a reference to the peer table.
    pub fn peer_table(&self) -> &PeerTable {
        &self.peer_table
    }

    /// Processes an incoming message and returns a routing decision.
    pub fn route_incoming(&self, message: &MeshMessage) -> RouteDecision {
        // 1. Check for duplicate
        if self.dedup.check_and_mark(&message.message_id) {
            return RouteDecision::Drop { reason: DropReason::Duplicate };
        }

        // 2. Check TTL
        if message.is_expired() {
            return RouteDecision::Drop { reason: DropReason::Expired };
        }

        // 3. Check hop limit
        if message.is_hop_limited() {
            return RouteDecision::Drop { reason: DropReason::HopLimitReached };
        }

        // 4. Is it for us?
        if message.recipient_id == self.local_device_id {
            return RouteDecision::DeliverLocally;
        }

        // 5. Is it a broadcast?
        if message.is_broadcast() {
            // Deliver locally AND forward
            return RouteDecision::DeliverLocally;
        }

        // 6. Spray phase: forward to connected peers
        let connected = self.peer_table.connected_peers();
        if connected.is_empty() {
            // No connected peers — store for later
            self.store_message(message.clone(), self.spray_copies);
            return RouteDecision::Store;
        }

        // Select peers to spray to (half of remaining copies, per Binary Spray-and-Wait)
        let target_peers: Vec<DeviceId> = connected
            .iter()
            .filter(|p| p.identity.device_id != message.sender_id) // Don't send back to sender
            .map(|p| p.identity.device_id.clone())
            .collect();

        if target_peers.is_empty() {
            self.store_message(message.clone(), self.spray_copies);
            return RouteDecision::Store;
        }

        RouteDecision::Forward { target_peers }
    }

    /// Called when a new peer is discovered/connected.
    /// Checks the message store for messages that can now be forwarded.
    pub fn on_peer_connected(&self, peer_device_id: &DeviceId) -> Vec<MeshMessage> {
        let mut store = self.store.lock().expect("Store lock poisoned");
        let mut to_forward = Vec::new();

        // Collect message IDs that should be forwarded
        let mut to_update = Vec::new();

        for (msg_id, stored) in store.iter() {
            if stored.remaining_copies > 0 {
                // Check if this message is for the newly connected peer (direct delivery)
                if stored.message.recipient_id == *peer_device_id {
                    to_forward.push(stored.message.clone());
                    to_update.push((*msg_id, 0)); // Will be fully delivered
                } else if stored.remaining_copies > 1 {
                    // Binary Spray: give half the copies to the new peer
                    let copies_to_give = stored.remaining_copies / 2;
                    if copies_to_give > 0 {
                        to_forward.push(stored.message.clone());
                        to_update.push((*msg_id, stored.remaining_copies - copies_to_give));
                    }
                }
            }
        }

        // Update remaining copies
        for (msg_id, new_copies) in to_update {
            if new_copies == 0 {
                store.remove(&msg_id);
            } else if let Some(stored) = store.get_mut(&msg_id) {
                stored.remaining_copies = new_copies;
            }
        }

        to_forward
    }

    /// Stores a message for later forwarding.
    fn store_message(&self, message: MeshMessage, copies: u8) {
        let mut store = self.store.lock().expect("Store lock poisoned");

        // Evict oldest messages if store is full
        while store.len() >= MAX_STORE_SIZE {
            if let Some(oldest_id) = store
                .iter()
                .min_by_key(|(_, s)| s.message.created_at_ms)
                .map(|(id, _)| *id)
            {
                store.remove(&oldest_id);
            } else {
                break;
            }
        }

        store.insert(
            message.message_id,
            StoredMessage {
                message,
                remaining_copies: copies,
            },
        );
    }

    /// Removes expired messages from the store.
    pub fn prune_expired(&self) -> usize {
        let mut store = self.store.lock().expect("Store lock poisoned");
        let before = store.len();
        store.retain(|_, s| !s.message.is_expired());
        before - store.len()
    }

    /// Returns the number of messages currently stored for forwarding.
    pub fn store_size(&self) -> usize {
        let store = self.store.lock().expect("Store lock poisoned");
        store.len()
    }

    /// Returns the number of known peers.
    pub fn peer_count(&self) -> usize {
        self.peer_table.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::identity::Identity;
    use crate::protocol::message::{ContentType, MessageBuilder};
    use crate::routing::peer_table::{PeerInfo, TransportType};

    fn setup_router() -> (Router, Identity) {
        let local = Identity::generate();
        let router = Router::new(local.device_id().clone());
        (router, local)
    }

    fn make_message(sender: &Identity, recipient_id: DeviceId) -> MeshMessage {
        MessageBuilder::new(sender.device_id().clone(), recipient_id)
            .content_type(ContentType::Text)
            .payload(b"test message".to_vec())
            .build(|data| sender.sign(data))
    }

    #[test]
    fn test_deliver_locally() {
        let (router, local) = setup_router();
        let sender = Identity::generate();
        let msg = make_message(&sender, local.device_id().clone());

        let decision = router.route_incoming(&msg);
        assert_eq!(decision, RouteDecision::DeliverLocally);
    }

    #[test]
    fn test_duplicate_dropped() {
        let (router, _) = setup_router();
        let sender = Identity::generate();
        let recipient = Identity::generate();
        let msg = make_message(&sender, recipient.device_id().clone());

        let _ = router.route_incoming(&msg);
        let decision = router.route_incoming(&msg);
        assert_eq!(decision, RouteDecision::Drop { reason: DropReason::Duplicate });
    }

    #[test]
    fn test_hop_limited_dropped() {
        let (router, _) = setup_router();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let mut msg = MessageBuilder::new(
            sender.device_id().clone(),
            recipient.device_id().clone(),
        )
        .max_hops(2)
        .payload(b"test".to_vec())
        .build(|data| sender.sign(data));

        msg.hop_count = 2; // Already at max
        let decision = router.route_incoming(&msg);
        assert_eq!(decision, RouteDecision::Drop { reason: DropReason::HopLimitReached });
    }

    #[test]
    fn test_store_when_no_peers() {
        let (router, _) = setup_router();
        let sender = Identity::generate();
        let recipient = Identity::generate();
        let msg = make_message(&sender, recipient.device_id().clone());

        let decision = router.route_incoming(&msg);
        assert_eq!(decision, RouteDecision::Store);
        assert_eq!(router.store_size(), 1);
    }

    #[test]
    fn test_forward_to_connected_peers() {
        let (router, _) = setup_router();
        let sender = Identity::generate();
        let recipient = Identity::generate();
        let relay_peer = Identity::generate();

        // Add a connected peer
        let mut peer_info = PeerInfo::new(
            relay_peer.public_identity(),
            TransportType::BluetoothLE,
        );
        peer_info.is_connected = true;
        router.peer_table().upsert(peer_info);

        let msg = make_message(&sender, recipient.device_id().clone());
        let decision = router.route_incoming(&msg);

        match decision {
            RouteDecision::Forward { target_peers } => {
                assert!(!target_peers.is_empty());
            }
            other => panic!("Expected Forward, got {:?}", other),
        }
    }

    #[test]
    fn test_on_peer_connected_delivers_stored() {
        let (router, _) = setup_router();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        // Route a message when no peers are connected → stored
        let msg = make_message(&sender, recipient.device_id().clone());
        let _ = router.route_incoming(&msg);
        assert_eq!(router.store_size(), 1);

        // Now the recipient connects
        let to_forward = router.on_peer_connected(recipient.device_id());
        assert_eq!(to_forward.len(), 1);
        assert_eq!(router.store_size(), 0); // Removed after direct delivery
    }

    #[test]
    fn test_broadcast_delivered_locally() {
        let (router, _) = setup_router();
        let sender = Identity::generate();

        let msg = MessageBuilder::broadcast(sender.device_id().clone())
            .payload(b"broadcast".to_vec())
            .build(|data| sender.sign(data));

        let decision = router.route_incoming(&msg);
        assert_eq!(decision, RouteDecision::DeliverLocally);
    }
}
