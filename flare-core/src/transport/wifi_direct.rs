//! Wi-Fi Direct session management for high-bandwidth peer transfers.
//!
//! Wi-Fi Direct (Android) and MultipeerConnectivity (iOS) provide:
//! - ~250m range (vs ~30m for BLE)
//! - ~50 Mbps throughput (vs ~100 KB/s for BLE)
//! - Higher power consumption (activated on demand, not always-on)
//!
//! This module manages the transfer queue for messages that require or prefer
//! direct peer-to-peer transport (voice clips, images, large payloads).
//! The actual Wi-Fi Direct/Multipeer APIs are platform-specific — this module
//! provides the Rust-side state management and queue logic.

use std::collections::VecDeque;
use std::sync::Mutex;

use crate::crypto::identity::DeviceId;

/// Configuration for Wi-Fi Direct transport.
pub struct WifiDirectConfig {
    /// Maximum number of pending transfers in the queue.
    pub max_queue_size: usize,

    /// How long a transfer can wait in queue before being dropped (seconds).
    pub transfer_timeout_seconds: u64,

    /// Maximum payload size for a single Wi-Fi Direct transfer (bytes). Default: 10 MB.
    pub max_transfer_bytes: usize,

    /// Number of concurrent Wi-Fi Direct connections allowed.
    /// Most devices support only 1 active Wi-Fi Direct group.
    pub max_concurrent_connections: usize,
}

impl Default for WifiDirectConfig {
    fn default() -> Self {
        WifiDirectConfig {
            max_queue_size: 50,
            transfer_timeout_seconds: 300,        // 5 minutes
            max_transfer_bytes: 10 * 1024 * 1024, // 10 MB
            max_concurrent_connections: 1,
        }
    }
}

/// State of a Wi-Fi Direct connection to a peer.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WifiDirectConnectionState {
    /// No connection. Call platform layer to initiate.
    Disconnected,
    /// Connection negotiation in progress.
    Connecting,
    /// Active Wi-Fi Direct link established.
    Connected,
    /// Connection failed. Will retry on next opportunity.
    Failed,
}

/// A pending transfer waiting for a Wi-Fi Direct connection.
#[derive(Debug, Clone)]
pub struct PendingTransfer {
    /// Unique transfer ID (message_id).
    pub transfer_id: [u8; 32],
    /// Target peer for this transfer.
    pub recipient_id: DeviceId,
    /// The payload to send.
    pub payload: Vec<u8>,
    /// Content type code (for logging/metrics).
    pub content_type: u8,
    /// When this transfer was queued (Unix timestamp seconds).
    pub queued_at_secs: u64,
    /// Number of times we've attempted this transfer.
    pub attempt_count: u8,
}

/// Wi-Fi Direct transfer queue and session state.
/// The platform layer (Android/iOS) drives the actual connection,
/// while this struct manages the queue and state.
pub struct WifiDirectManager {
    config: WifiDirectConfig,
    queue: Mutex<VecDeque<PendingTransfer>>,
    connection_state: Mutex<WifiDirectConnectionState>,
    connected_peer: Mutex<Option<DeviceId>>,
    /// Transfers completed successfully.
    completed_count: Mutex<u64>,
    /// Transfers that failed or timed out.
    failed_count: Mutex<u64>,
}

impl WifiDirectManager {
    pub fn new(config: WifiDirectConfig) -> Self {
        WifiDirectManager {
            config,
            queue: Mutex::new(VecDeque::new()),
            connection_state: Mutex::new(WifiDirectConnectionState::Disconnected),
            connected_peer: Mutex::new(None),
            completed_count: Mutex::new(0),
            failed_count: Mutex::new(0),
        }
    }

    pub fn with_defaults() -> Self {
        Self::new(WifiDirectConfig::default())
    }

    /// Queues a payload for Wi-Fi Direct transfer to a specific peer.
    /// Returns false if the queue is full or the payload exceeds max size.
    pub fn enqueue_transfer(
        &self,
        transfer_id: [u8; 32],
        recipient_id: DeviceId,
        payload: Vec<u8>,
        content_type: u8,
        now_secs: u64,
    ) -> bool {
        if payload.len() > self.config.max_transfer_bytes {
            return false;
        }

        let mut queue = self.queue.lock().expect("Queue lock");

        if queue.len() >= self.config.max_queue_size {
            return false;
        }

        // Don't enqueue duplicate transfers
        if queue.iter().any(|t| t.transfer_id == transfer_id) {
            return true; // Already queued
        }

        queue.push_back(PendingTransfer {
            transfer_id,
            recipient_id,
            payload,
            content_type,
            queued_at_secs: now_secs,
            attempt_count: 0,
        });

        true
    }

    /// Returns the next transfer for a specific connected peer, if any.
    /// Does NOT remove it from the queue — call `complete_transfer` or
    /// `fail_transfer` after the platform layer finishes.
    pub fn next_transfer_for_peer(&self, peer_id: &DeviceId) -> Option<PendingTransfer> {
        let queue = self.queue.lock().expect("Queue lock");
        queue.iter().find(|t| t.recipient_id == *peer_id).cloned()
    }

    /// Returns all pending transfers for a specific peer.
    pub fn transfers_for_peer(&self, peer_id: &DeviceId) -> Vec<PendingTransfer> {
        let queue = self.queue.lock().expect("Queue lock");
        queue
            .iter()
            .filter(|t| t.recipient_id == *peer_id)
            .cloned()
            .collect()
    }

    /// Marks a transfer as completed and removes it from the queue.
    pub fn complete_transfer(&self, transfer_id: &[u8; 32]) -> bool {
        let mut queue = self.queue.lock().expect("Queue lock");
        let before = queue.len();
        queue.retain(|t| t.transfer_id != *transfer_id);
        if queue.len() < before {
            let mut count = self.completed_count.lock().expect("Count lock");
            *count += 1;
            true
        } else {
            false
        }
    }

    /// Marks a transfer as failed. Increments attempt count.
    /// Removes it if max attempts exceeded.
    pub fn fail_transfer(&self, transfer_id: &[u8; 32]) -> bool {
        let mut queue = self.queue.lock().expect("Queue lock");
        if let Some(transfer) = queue.iter_mut().find(|t| t.transfer_id == *transfer_id) {
            transfer.attempt_count += 1;
            if transfer.attempt_count >= 3 {
                queue.retain(|t| t.transfer_id != *transfer_id);
                let mut count = self.failed_count.lock().expect("Count lock");
                *count += 1;
            }
            true
        } else {
            false
        }
    }

    /// Prunes transfers that have exceeded the timeout.
    /// Returns the number of transfers removed.
    pub fn prune_expired(&self, now_secs: u64) -> usize {
        let mut queue = self.queue.lock().expect("Queue lock");
        let before = queue.len();
        queue.retain(|t| {
            now_secs.saturating_sub(t.queued_at_secs) < self.config.transfer_timeout_seconds
        });
        let removed = before - queue.len();
        if removed > 0 {
            let mut count = self.failed_count.lock().expect("Count lock");
            *count += removed as u64;
        }
        removed
    }

    /// Called by platform layer when Wi-Fi Direct connection state changes.
    pub fn on_connection_state_changed(
        &self,
        state: WifiDirectConnectionState,
        peer_id: Option<DeviceId>,
    ) {
        let mut conn_state = self.connection_state.lock().expect("State lock");
        *conn_state = state;
        drop(conn_state);

        let mut connected = self.connected_peer.lock().expect("Peer lock");
        *connected = if state == WifiDirectConnectionState::Connected {
            peer_id
        } else {
            None
        };
    }

    /// Returns the current connection state.
    pub fn connection_state(&self) -> WifiDirectConnectionState {
        *self.connection_state.lock().expect("State lock")
    }

    /// Returns the currently connected peer, if any.
    pub fn connected_peer(&self) -> Option<DeviceId> {
        self.connected_peer.lock().expect("Peer lock").clone()
    }

    /// Returns true if we have pending transfers that need a connection.
    pub fn has_pending_transfers(&self) -> bool {
        !self.queue.lock().expect("Queue lock").is_empty()
    }

    /// Returns the number of pending transfers.
    pub fn pending_count(&self) -> usize {
        self.queue.lock().expect("Queue lock").len()
    }

    /// Returns the device ID of the peer we most need to connect to
    /// (the one with the most pending transfers).
    pub fn most_needed_peer(&self) -> Option<DeviceId> {
        let queue = self.queue.lock().expect("Queue lock");
        let mut peer_counts: std::collections::HashMap<DeviceId, usize> =
            std::collections::HashMap::new();

        for transfer in queue.iter() {
            *peer_counts
                .entry(transfer.recipient_id.clone())
                .or_insert(0) += 1;
        }

        peer_counts
            .into_iter()
            .max_by_key(|(_, count)| *count)
            .map(|(id, _)| id)
    }

    /// Returns transfer queue statistics.
    pub fn stats(&self) -> WifiDirectStats {
        let queue = self.queue.lock().expect("Queue lock");
        let total_bytes: usize = queue.iter().map(|t| t.payload.len()).sum();

        WifiDirectStats {
            pending_transfers: queue.len(),
            total_pending_bytes: total_bytes,
            completed_transfers: *self.completed_count.lock().expect("Count lock"),
            failed_transfers: *self.failed_count.lock().expect("Count lock"),
            connection_state: *self.connection_state.lock().expect("State lock"),
        }
    }

    /// Returns the config (read-only).
    pub fn config(&self) -> &WifiDirectConfig {
        &self.config
    }
}

/// Statistics about the Wi-Fi Direct transfer queue.
#[derive(Debug, Clone)]
pub struct WifiDirectStats {
    pub pending_transfers: usize,
    pub total_pending_bytes: usize,
    pub completed_transfers: u64,
    pub failed_transfers: u64,
    pub connection_state: WifiDirectConnectionState,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::identity::Identity;

    fn make_device_id() -> DeviceId {
        Identity::generate().device_id().clone()
    }

    #[test]
    fn test_enqueue_and_retrieve() {
        let mgr = WifiDirectManager::with_defaults();
        let peer = make_device_id();
        let id = [1u8; 32];

        assert!(mgr.enqueue_transfer(id, peer.clone(), vec![0u8; 1000], 0x03, 100));
        assert_eq!(mgr.pending_count(), 1);

        let transfer = mgr.next_transfer_for_peer(&peer).unwrap();
        assert_eq!(transfer.transfer_id, id);
        assert_eq!(transfer.payload.len(), 1000);
    }

    #[test]
    fn test_duplicate_enqueue_ignored() {
        let mgr = WifiDirectManager::with_defaults();
        let peer = make_device_id();
        let id = [1u8; 32];

        assert!(mgr.enqueue_transfer(id, peer.clone(), vec![0u8; 100], 0x03, 100));
        assert!(mgr.enqueue_transfer(id, peer.clone(), vec![0u8; 100], 0x03, 100));
        assert_eq!(mgr.pending_count(), 1);
    }

    #[test]
    fn test_oversized_rejected() {
        let config = WifiDirectConfig {
            max_transfer_bytes: 100,
            ..Default::default()
        };
        let mgr = WifiDirectManager::new(config);
        let peer = make_device_id();

        assert!(!mgr.enqueue_transfer([1u8; 32], peer, vec![0u8; 101], 0x03, 100));
        assert_eq!(mgr.pending_count(), 0);
    }

    #[test]
    fn test_queue_full_rejected() {
        let config = WifiDirectConfig {
            max_queue_size: 2,
            ..Default::default()
        };
        let mgr = WifiDirectManager::new(config);
        let peer = make_device_id();

        assert!(mgr.enqueue_transfer([1u8; 32], peer.clone(), vec![0u8; 10], 0x03, 100));
        assert!(mgr.enqueue_transfer([2u8; 32], peer.clone(), vec![0u8; 10], 0x03, 100));
        assert!(!mgr.enqueue_transfer([3u8; 32], peer, vec![0u8; 10], 0x03, 100));
    }

    #[test]
    fn test_complete_transfer() {
        let mgr = WifiDirectManager::with_defaults();
        let peer = make_device_id();
        let id = [1u8; 32];

        mgr.enqueue_transfer(id, peer, vec![0u8; 100], 0x03, 100);
        assert!(mgr.complete_transfer(&id));
        assert_eq!(mgr.pending_count(), 0);
        assert_eq!(mgr.stats().completed_transfers, 1);
    }

    #[test]
    fn test_fail_transfer_retries() {
        let mgr = WifiDirectManager::with_defaults();
        let peer = make_device_id();
        let id = [1u8; 32];

        mgr.enqueue_transfer(id, peer, vec![0u8; 100], 0x03, 100);

        // First two failures should keep it in queue
        mgr.fail_transfer(&id);
        assert_eq!(mgr.pending_count(), 1);
        mgr.fail_transfer(&id);
        assert_eq!(mgr.pending_count(), 1);

        // Third failure removes it
        mgr.fail_transfer(&id);
        assert_eq!(mgr.pending_count(), 0);
        assert_eq!(mgr.stats().failed_transfers, 1);
    }

    #[test]
    fn test_prune_expired() {
        let config = WifiDirectConfig {
            transfer_timeout_seconds: 60,
            ..Default::default()
        };
        let mgr = WifiDirectManager::new(config);
        let peer = make_device_id();

        mgr.enqueue_transfer([1u8; 32], peer.clone(), vec![0u8; 100], 0x03, 100);
        mgr.enqueue_transfer([2u8; 32], peer, vec![0u8; 100], 0x03, 150);

        // At t=165, first transfer expired (100+60=160), second still valid (150+60=210)
        let pruned = mgr.prune_expired(165);
        assert_eq!(pruned, 1);
        assert_eq!(mgr.pending_count(), 1);
    }

    #[test]
    fn test_connection_state_tracking() {
        let mgr = WifiDirectManager::with_defaults();
        let peer = make_device_id();

        assert_eq!(
            mgr.connection_state(),
            WifiDirectConnectionState::Disconnected
        );
        assert!(mgr.connected_peer().is_none());

        mgr.on_connection_state_changed(WifiDirectConnectionState::Connected, Some(peer.clone()));
        assert_eq!(mgr.connection_state(), WifiDirectConnectionState::Connected);
        assert_eq!(mgr.connected_peer(), Some(peer));

        mgr.on_connection_state_changed(WifiDirectConnectionState::Disconnected, None);
        assert!(mgr.connected_peer().is_none());
    }

    #[test]
    fn test_most_needed_peer() {
        let mgr = WifiDirectManager::with_defaults();
        let peer_a = make_device_id();
        let peer_b = make_device_id();

        mgr.enqueue_transfer([1u8; 32], peer_a.clone(), vec![0u8; 100], 0x03, 100);
        mgr.enqueue_transfer([2u8; 32], peer_b.clone(), vec![0u8; 100], 0x03, 100);
        mgr.enqueue_transfer([3u8; 32], peer_b.clone(), vec![0u8; 100], 0x03, 100);

        // peer_b has 2 pending, peer_a has 1
        assert_eq!(mgr.most_needed_peer(), Some(peer_b));
    }

    #[test]
    fn test_transfers_for_peer() {
        let mgr = WifiDirectManager::with_defaults();
        let peer_a = make_device_id();
        let peer_b = make_device_id();

        mgr.enqueue_transfer([1u8; 32], peer_a.clone(), vec![0u8; 100], 0x03, 100);
        mgr.enqueue_transfer([2u8; 32], peer_b.clone(), vec![0u8; 100], 0x03, 100);
        mgr.enqueue_transfer([3u8; 32], peer_a.clone(), vec![0u8; 100], 0x03, 100);

        let transfers_a = mgr.transfers_for_peer(&peer_a);
        assert_eq!(transfers_a.len(), 2);

        let transfers_b = mgr.transfers_for_peer(&peer_b);
        assert_eq!(transfers_b.len(), 1);
    }

    #[test]
    fn test_stats() {
        let mgr = WifiDirectManager::with_defaults();
        let peer = make_device_id();

        mgr.enqueue_transfer([1u8; 32], peer.clone(), vec![0u8; 500], 0x03, 100);
        mgr.enqueue_transfer([2u8; 32], peer, vec![0u8; 300], 0x03, 100);

        let stats = mgr.stats();
        assert_eq!(stats.pending_transfers, 2);
        assert_eq!(stats.total_pending_bytes, 800);
        assert_eq!(stats.completed_transfers, 0);
        assert_eq!(stats.failed_transfers, 0);
    }
}
