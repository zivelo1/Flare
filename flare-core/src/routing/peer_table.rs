//! Peer table — tracks nearby discovered mesh nodes.
//!
//! Maintains a list of peers discovered via BLE scanning, along with
//! connection quality metrics and last-seen timestamps.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Mutex;

use crate::crypto::identity::{DeviceId, PublicIdentity};
use crate::routing::neighborhood::EncounterType;

/// How long before a peer is considered stale and removed from the table.
const PEER_STALE_TIMEOUT_SECONDS: i64 = 300; // 5 minutes

/// Maximum number of peers to track simultaneously.
const MAX_PEER_TABLE_SIZE: usize = 256;

/// Information about a discovered mesh peer.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerInfo {
    /// The peer's public identity (keys + device ID).
    pub identity: PublicIdentity,

    /// Signal strength indicator (platform-specific: RSSI on Android/iOS).
    /// Lower (more negative) values mean weaker signal.
    pub rssi: Option<i16>,

    /// Estimated distance in meters (derived from RSSI, approximate).
    pub estimated_distance_m: Option<f32>,

    /// Which transport was used to discover this peer.
    pub transport: TransportType,

    /// When this peer was last seen.
    pub last_seen: DateTime<Utc>,

    /// When this peer was first discovered.
    pub first_seen: DateTime<Utc>,

    /// Whether this peer is currently connected (active BLE/WiFi link).
    pub is_connected: bool,

    /// Number of messages successfully relayed through this peer.
    pub relay_success_count: u32,

    /// Number of messages that failed to relay through this peer.
    pub relay_failure_count: u32,

    /// Neighborhood encounter type — indicates whether this peer bridges
    /// to a different cluster (based on bloom filter comparison).
    /// `None` means not yet determined (no bitmap exchange occurred).
    pub encounter_type: Option<EncounterType>,
}

/// Type of transport used to communicate with a peer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransportType {
    /// BLE GATT — cross-platform, lower bandwidth.
    BluetoothLE,
    /// Wi-Fi Direct — Android only, higher bandwidth.
    WiFiDirect,
    /// Wi-Fi Aware (NAN) — Android 8+, mesh-optimized.
    WiFiAware,
    /// Apple Multipeer Connectivity — iOS only.
    MultipeerConnectivity,
}

impl PeerInfo {
    /// Creates a new PeerInfo for a freshly discovered peer.
    pub fn new(identity: PublicIdentity, transport: TransportType) -> Self {
        let now = Utc::now();
        PeerInfo {
            identity,
            rssi: None,
            estimated_distance_m: None,
            transport,
            last_seen: now,
            first_seen: now,
            is_connected: false,
            relay_success_count: 0,
            relay_failure_count: 0,
            encounter_type: None,
        }
    }

    /// Updates the last-seen timestamp and signal strength.
    pub fn update_seen(&mut self, rssi: Option<i16>) {
        self.last_seen = Utc::now();
        self.rssi = rssi;
        self.estimated_distance_m = rssi.map(estimate_distance_from_rssi);
    }

    /// Returns the relay success rate (0.0 to 1.0).
    pub fn relay_success_rate(&self) -> f32 {
        let total = self.relay_success_count + self.relay_failure_count;
        if total == 0 {
            return 0.5; // Unknown peers get a neutral score
        }
        self.relay_success_count as f32 / total as f32
    }

    /// Returns true if this peer hasn't been seen recently.
    pub fn is_stale(&self) -> bool {
        let age = Utc::now().signed_duration_since(self.last_seen);
        age.num_seconds() > PEER_STALE_TIMEOUT_SECONDS
    }
}

/// Estimates distance in meters from BLE RSSI using the log-distance path loss model.
///
/// Parameters calibrated for typical smartphones in urban indoor environments:
/// - Reference RSSI at 1 meter: -59 dBm (typical BLE)
/// - Path loss exponent: 2.7 (urban indoor with obstacles)
fn estimate_distance_from_rssi(rssi: i16) -> f32 {
    const RSSI_AT_1M: f32 = -59.0;
    const PATH_LOSS_EXPONENT: f32 = 2.7;

    let rssi_f = rssi as f32;
    10.0_f32.powf((RSSI_AT_1M - rssi_f) / (10.0 * PATH_LOSS_EXPONENT))
}

/// Thread-safe table of discovered mesh peers.
pub struct PeerTable {
    peers: Mutex<HashMap<DeviceId, PeerInfo>>,
}

impl PeerTable {
    pub fn new() -> Self {
        PeerTable {
            peers: Mutex::new(HashMap::new()),
        }
    }

    /// Adds or updates a peer in the table.
    pub fn upsert(&self, peer: PeerInfo) {
        let mut peers = self.peers.lock().expect("Peer table lock poisoned");

        // If table is full, evict the stalest peer
        if peers.len() >= MAX_PEER_TABLE_SIZE && !peers.contains_key(&peer.identity.device_id) {
            if let Some(stalest_id) = self.find_stalest_peer(&peers) {
                peers.remove(&stalest_id);
            }
        }

        let device_id = peer.identity.device_id.clone();
        peers
            .entry(device_id)
            .and_modify(|existing| {
                existing.update_seen(peer.rssi);
                existing.transport = peer.transport;
                existing.is_connected = peer.is_connected;
                // Preserve encounter_type — only overwrite if new peer has a value
                if peer.encounter_type.is_some() {
                    existing.encounter_type = peer.encounter_type;
                }
            })
            .or_insert(peer);
    }

    /// Gets a peer by device ID.
    pub fn get(&self, device_id: &DeviceId) -> Option<PeerInfo> {
        let peers = self.peers.lock().expect("Peer table lock poisoned");
        peers.get(device_id).cloned()
    }

    /// Returns all currently known peers.
    pub fn all_peers(&self) -> Vec<PeerInfo> {
        let peers = self.peers.lock().expect("Peer table lock poisoned");
        peers.values().cloned().collect()
    }

    /// Returns all connected peers.
    pub fn connected_peers(&self) -> Vec<PeerInfo> {
        let peers = self.peers.lock().expect("Peer table lock poisoned");
        peers
            .values()
            .filter(|p| p.is_connected && !p.is_stale())
            .cloned()
            .collect()
    }

    /// Removes stale peers that haven't been seen within the timeout.
    pub fn prune_stale(&self) -> usize {
        let mut peers = self.peers.lock().expect("Peer table lock poisoned");
        let before = peers.len();
        peers.retain(|_, peer| !peer.is_stale());
        before - peers.len()
    }

    /// Marks a peer as disconnected.
    pub fn mark_disconnected(&self, device_id: &DeviceId) {
        let mut peers = self.peers.lock().expect("Peer table lock poisoned");
        if let Some(peer) = peers.get_mut(device_id) {
            peer.is_connected = false;
        }
    }

    /// Sets the neighborhood encounter type for a peer (from bloom filter comparison).
    pub fn set_encounter_type(&self, device_id: &DeviceId, encounter: EncounterType) {
        let mut peers = self.peers.lock().expect("Peer table lock poisoned");
        if let Some(peer) = peers.get_mut(device_id) {
            peer.encounter_type = Some(encounter);
        }
    }

    /// Returns connected peers sorted for optimal routing:
    /// 1. Bridge peers first (reach different clusters — highest routing value)
    /// 2. Unknown encounter type second (may be bridges)
    /// 3. Local/Intermediate peers last (same cluster — lower routing value)
    ///
    /// Within each group, peers are sorted by relay success rate (descending).
    pub fn connected_peers_prioritized(&self) -> Vec<PeerInfo> {
        let mut peers = self.connected_peers();

        peers.sort_by(|a, b| {
            let priority_a = encounter_routing_priority(&a.encounter_type);
            let priority_b = encounter_routing_priority(&b.encounter_type);

            priority_a.cmp(&priority_b).then_with(|| {
                // Within same priority: prefer higher relay success rate
                b.relay_success_rate()
                    .partial_cmp(&a.relay_success_rate())
                    .unwrap_or(std::cmp::Ordering::Equal)
            })
        });

        peers
    }

    /// Records a relay outcome for a peer (used for routing quality scoring).
    pub fn record_relay_outcome(&self, device_id: &DeviceId, success: bool) {
        let mut peers = self.peers.lock().expect("Peer table lock poisoned");
        if let Some(peer) = peers.get_mut(device_id) {
            if success {
                peer.relay_success_count += 1;
            } else {
                peer.relay_failure_count += 1;
            }
        }
    }

    /// Returns the number of peers in the table.
    pub fn len(&self) -> usize {
        let peers = self.peers.lock().expect("Peer table lock poisoned");
        peers.len()
    }

    /// Returns true if the table is empty.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    fn find_stalest_peer(&self, peers: &HashMap<DeviceId, PeerInfo>) -> Option<DeviceId> {
        peers
            .iter()
            .min_by_key(|(_, p)| p.last_seen)
            .map(|(id, _)| id.clone())
    }
}

/// Maps encounter type to routing priority (lower = higher priority).
/// Bridge peers are most valuable for routing across clusters.
fn encounter_routing_priority(encounter: &Option<EncounterType>) -> u8 {
    match encounter {
        Some(EncounterType::Bridge) => 0, // Highest priority — different cluster
        None => 1,                        // Unknown — might be a bridge
        Some(EncounterType::Intermediate) => 2, // Moderate overlap
        Some(EncounterType::Local) => 3,  // Same cluster — lowest priority
    }
}

impl Default for PeerTable {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::identity::Identity;

    fn make_peer(transport: TransportType) -> PeerInfo {
        let identity = Identity::generate();
        PeerInfo::new(identity.public_identity(), transport)
    }

    #[test]
    fn test_add_and_get_peer() {
        let table = PeerTable::new();
        let peer = make_peer(TransportType::BluetoothLE);
        let device_id = peer.identity.device_id.clone();

        table.upsert(peer);
        let retrieved = table.get(&device_id);
        assert!(retrieved.is_some());
        assert_eq!(retrieved.unwrap().identity.device_id, device_id);
    }

    #[test]
    fn test_connected_peers_filter() {
        let table = PeerTable::new();

        let mut peer1 = make_peer(TransportType::BluetoothLE);
        peer1.is_connected = true;
        let mut peer2 = make_peer(TransportType::BluetoothLE);
        peer2.is_connected = false;

        table.upsert(peer1);
        table.upsert(peer2);

        let connected = table.connected_peers();
        assert_eq!(connected.len(), 1);
    }

    #[test]
    fn test_peer_update_on_upsert() {
        let table = PeerTable::new();
        let identity = Identity::generate();

        let mut peer = PeerInfo::new(identity.public_identity(), TransportType::BluetoothLE);
        peer.rssi = Some(-70);
        table.upsert(peer);

        let mut peer_update = PeerInfo::new(identity.public_identity(), TransportType::BluetoothLE);
        peer_update.rssi = Some(-50);
        table.upsert(peer_update);

        let retrieved = table.get(&identity.public_identity().device_id).unwrap();
        assert_eq!(retrieved.rssi, Some(-50)); // Updated
    }

    #[test]
    fn test_relay_success_rate() {
        let mut peer = make_peer(TransportType::BluetoothLE);
        assert_eq!(peer.relay_success_rate(), 0.5); // Unknown

        peer.relay_success_count = 8;
        peer.relay_failure_count = 2;
        assert!((peer.relay_success_rate() - 0.8).abs() < f32::EPSILON);
    }

    #[test]
    fn test_distance_estimation() {
        // At reference distance (1m), RSSI should be approximately -59 dBm
        let distance = estimate_distance_from_rssi(-59);
        assert!((distance - 1.0).abs() < 0.1);

        // Weaker signal = farther away
        let far = estimate_distance_from_rssi(-80);
        assert!(far > distance);
    }

    #[test]
    fn test_table_size_limit() {
        let table = PeerTable::new();

        // Add more than MAX_PEER_TABLE_SIZE peers
        for _ in 0..MAX_PEER_TABLE_SIZE + 10 {
            table.upsert(make_peer(TransportType::BluetoothLE));
        }

        assert!(table.len() <= MAX_PEER_TABLE_SIZE);
    }
}
