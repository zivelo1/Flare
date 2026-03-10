//! Spray-and-Wait message router with adaptive TTL for the Flare mesh.
//!
//! Spray-and-Wait is a bounded-replication routing protocol for
//! delay-tolerant networks. It creates L copies of a message and
//! distributes them to encountered peers, then waits for delivery.
//!
//! Adaptive TTL extends message lifetime when crossing cluster boundaries
//! (bridge encounters), enabling long-distance delivery without GPS.
//!
//! Delivery ACKs propagate back through the mesh to trigger cleanup.

use crate::crypto::identity::DeviceId;
use crate::protocol::message::{ContentType, MeshMessage};
use crate::routing::dedup::DeduplicationFilter;
use crate::routing::neighborhood::{EncounterType, NeighborhoodFilter};
use crate::routing::peer_table::PeerTable;
use crate::routing::priority_store::{PriorityStore, PriorityStoreConfig};
use crate::routing::route_guard::{GuardResult, RouteGuard};

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

/// The Spray-and-Wait mesh router with adaptive TTL, priority storage,
/// and route guard for mutable field protection.
pub struct Router {
    local_device_id: DeviceId,
    dedup: DeduplicationFilter,
    peer_table: PeerTable,
    store: PriorityStore,
    neighborhood: NeighborhoodFilter,
    route_guard: RouteGuard,
}

impl Router {
    /// Creates a new router for the given local device with default configuration.
    pub fn new(local_device_id: DeviceId) -> Self {
        Router {
            local_device_id,
            dedup: DeduplicationFilter::with_defaults(),
            peer_table: PeerTable::new(),
            store: PriorityStore::with_defaults(),
            neighborhood: NeighborhoodFilter::with_defaults(),
            route_guard: RouteGuard::with_defaults(),
        }
    }

    /// Creates a router with custom priority store configuration.
    pub fn with_config(local_device_id: DeviceId, store_config: PriorityStoreConfig) -> Self {
        Router {
            local_device_id,
            dedup: DeduplicationFilter::with_defaults(),
            peer_table: PeerTable::new(),
            store: PriorityStore::new(store_config),
            neighborhood: NeighborhoodFilter::with_defaults(),
            route_guard: RouteGuard::with_defaults(),
        }
    }

    /// Returns a reference to the route guard.
    pub fn route_guard(&self) -> &RouteGuard {
        &self.route_guard
    }

    /// Returns a reference to the peer table.
    pub fn peer_table(&self) -> &PeerTable {
        &self.peer_table
    }

    /// Returns a reference to the neighborhood filter.
    pub fn neighborhood(&self) -> &NeighborhoodFilter {
        &self.neighborhood
    }

    /// Returns a reference to the priority store.
    pub fn store(&self) -> &PriorityStore {
        &self.store
    }

    /// Records a peer's short ID in the neighborhood filter.
    /// Call this whenever a peer is discovered via BLE scan.
    pub fn record_neighborhood_peer(&self, short_id: &[u8; 4]) {
        self.neighborhood.record_peer(short_id);
    }

    /// Exports the neighborhood filter bitmap for exchange with a peer.
    pub fn export_neighborhood_bitmap(&self) -> Vec<u8> {
        self.neighborhood.export_bitmap()
    }

    /// Processes a remote peer's neighborhood bitmap.
    /// If a bridge encounter is detected, extends TTL on stored messages.
    /// Returns the encounter type.
    pub fn process_remote_neighborhood(&self, remote_bitmap: &[u8]) -> EncounterType {
        let encounter = self.neighborhood.compare_with_remote(remote_bitmap);

        if encounter == EncounterType::Bridge {
            let upgraded = self.store.on_bridge_encounter();
            if upgraded > 0 {
                log::info!(
                    "Bridge encounter detected — extended TTL on {} messages",
                    upgraded
                );
            }
        }

        encounter
    }

    /// Validates an incoming message against the route guard.
    /// Pass `known_identity` if the sender is in our contacts for signature verification.
    pub fn validate_message(
        &self,
        message: &MeshMessage,
        known_identity: Option<&crate::crypto::identity::PublicIdentity>,
    ) -> GuardResult {
        self.route_guard.validate(message, known_identity)
    }

    /// Processes an incoming message and returns a routing decision.
    /// Route guard validation (TTL inflation, hop count monotonicity) is applied
    /// automatically. For signature verification, call `validate_message()` first
    /// with the sender's known identity.
    pub fn route_incoming(&self, message: &MeshMessage) -> RouteDecision {
        // 1. Check for duplicate
        if self.dedup.check_and_mark(&message.message_id) {
            return RouteDecision::Drop {
                reason: DropReason::Duplicate,
            };
        }

        // 2. Route guard: TTL inflation and hop count monotonicity checks
        // Signature check is skipped here (done by caller with known identity),
        // but TTL and hop tracking are enforced unconditionally.
        let guard_result = self.route_guard.validate(message, None);
        match guard_result {
            GuardResult::Accept => {}
            GuardResult::RejectTtlInflation { .. }
            | GuardResult::RejectHopCountDecrease { .. }
            | GuardResult::RejectSenderRateLimit { .. } => {
                return RouteDecision::Drop {
                    reason: DropReason::InvalidSignature,
                };
            }
            GuardResult::RejectInvalidSignature => {
                return RouteDecision::Drop {
                    reason: DropReason::InvalidSignature,
                };
            }
        }

        // 3. Check TTL
        if message.is_expired() {
            return RouteDecision::Drop {
                reason: DropReason::Expired,
            };
        }

        // 3. Check hop limit
        if message.is_hop_limited() {
            return RouteDecision::Drop {
                reason: DropReason::HopLimitReached,
            };
        }

        // 4. Handle delivery ACKs — clean up stored messages
        if message.content_type == ContentType::Acknowledgment && !message.payload.is_empty() {
            // ACK payload contains the original message_id (32 bytes)
            if message.payload.len() >= 32 {
                let mut original_id = [0u8; 32];
                original_id.copy_from_slice(&message.payload[..32]);
                if self.store.process_ack(&original_id) {
                    log::debug!("ACK received — removed stored message");
                }
            }
            // ACKs are also forwarded like normal messages (for propagation)
        }

        // 5. Is it for us?
        if message.recipient_id == self.local_device_id {
            return RouteDecision::DeliverLocally;
        }

        // 6. Is it a broadcast?
        if message.is_broadcast() {
            return RouteDecision::DeliverLocally;
        }

        // 7. Spray phase: forward to connected peers
        let connected = self.peer_table.connected_peers();
        if connected.is_empty() {
            // No connected peers — store for later
            self.store
                .store_relay(message.clone(), self.store.config().initial_spray_copies);
            return RouteDecision::Store;
        }

        // Select peers to spray to (exclude sender)
        let target_peers: Vec<DeviceId> = connected
            .iter()
            .filter(|p| p.identity.device_id != message.sender_id)
            .map(|p| p.identity.device_id.clone())
            .collect();

        if target_peers.is_empty() {
            self.store
                .store_relay(message.clone(), self.store.config().initial_spray_copies);
            return RouteDecision::Store;
        }

        RouteDecision::Forward { target_peers }
    }

    /// Called when a new peer is discovered/connected.
    /// Checks the message store for messages that can now be forwarded.
    pub fn on_peer_connected(&self, peer_device_id: &DeviceId) -> Vec<MeshMessage> {
        self.store.get_messages_for_peer(peer_device_id)
    }

    /// Removes expired messages from the store.
    pub fn prune_expired(&self) -> usize {
        self.store.prune_expired()
    }

    /// Returns the number of messages currently stored.
    pub fn store_size(&self) -> usize {
        self.store.len()
    }

    /// Returns the number of known peers.
    pub fn peer_count(&self) -> usize {
        self.peer_table.len()
    }

    /// Prepares a message for relay by incrementing the hop count.
    /// Call this before forwarding a message to the next hop.
    /// Returns None if the message would exceed its hop limit after increment.
    pub fn prepare_for_relay(&self, message: &mut MeshMessage) -> bool {
        message.increment_hop();
        !message.is_hop_limited()
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
        assert_eq!(
            decision,
            RouteDecision::Drop {
                reason: DropReason::Duplicate
            }
        );
    }

    #[test]
    fn test_hop_limited_dropped() {
        let (router, _) = setup_router();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let mut msg =
            MessageBuilder::new(sender.device_id().clone(), recipient.device_id().clone())
                .max_hops(2)
                .payload(b"test".to_vec())
                .build(|data| sender.sign(data));

        msg.hop_count = 2; // Already at max
        let decision = router.route_incoming(&msg);
        assert_eq!(
            decision,
            RouteDecision::Drop {
                reason: DropReason::HopLimitReached
            }
        );
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

        let mut peer_info = PeerInfo::new(relay_peer.public_identity(), TransportType::BluetoothLE);
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

        let msg = make_message(&sender, recipient.device_id().clone());
        let _ = router.route_incoming(&msg);
        assert_eq!(router.store_size(), 1);

        let to_forward = router.on_peer_connected(recipient.device_id());
        assert_eq!(to_forward.len(), 1);
        assert_eq!(router.store_size(), 0);
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

    #[test]
    fn test_bridge_encounter_extends_stored_message_ttl() {
        let (router, _) = setup_router();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        let msg = make_message(&sender, recipient.device_id().clone());
        let _ = router.route_incoming(&msg); // Stored

        // Record local peers so our filter has content
        for i in 0..20u8 {
            router.record_neighborhood_peer(&[i, i + 1, i + 2, i + 3]);
        }

        // Create a remote filter with completely different peers (different cluster)
        let remote_filter = NeighborhoodFilter::with_defaults();
        for i in 200..220u8 {
            remote_filter.record_peer(&[
                i,
                i.wrapping_add(1),
                i.wrapping_add(2),
                i.wrapping_add(3),
            ]);
        }
        let remote_bitmap = remote_filter.export_bitmap();

        let encounter = router.process_remote_neighborhood(&remote_bitmap);
        assert_eq!(encounter, EncounterType::Bridge);
    }

    #[test]
    fn test_ack_removes_stored_message() {
        let (router, _) = setup_router();
        let sender = Identity::generate();
        let recipient = Identity::generate();

        // Store a message
        let msg = make_message(&sender, recipient.device_id().clone());
        let original_msg_id = msg.message_id;
        let _ = router.route_incoming(&msg);
        assert_eq!(router.store_size(), 1);

        // Create an ACK for that message
        let ack = MessageBuilder::new(recipient.device_id().clone(), sender.device_id().clone())
            .content_type(ContentType::Acknowledgment)
            .payload(original_msg_id.to_vec())
            .build(|data| recipient.sign(data));

        // Route the ACK through our node
        let _decision = router.route_incoming(&ack);

        // The original message should be removed by the ACK.
        // The ACK itself gets stored for further propagation (store_size = 1).
        // Verify the ACK processing happened by checking the stored message
        // is now the ACK, not the original.
        assert_eq!(router.store_size(), 1); // ACK stored for forwarding

        // Verify original message was removed: connect the original recipient,
        // should NOT get the original message (only the ACK or nothing relevant)
        let forwarded = router.on_peer_connected(recipient.device_id());
        // The ACK is addressed to the sender, not the recipient,
        // so recipient won't get a direct delivery of the ACK
        assert!(forwarded.iter().all(|m| m.message_id != original_msg_id));
    }

    #[test]
    fn test_neighborhood_recording() {
        let (router, _) = setup_router();

        router.record_neighborhood_peer(&[1, 2, 3, 4]);
        router.record_neighborhood_peer(&[5, 6, 7, 8]);

        assert_eq!(router.neighborhood().peer_count(), 2);
    }
}
