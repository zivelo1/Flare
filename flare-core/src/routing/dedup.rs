//! Message deduplication using a Bloom filter.
//!
//! Each node maintains a Bloom filter of recently seen message IDs.
//! This prevents re-forwarding messages that have already been relayed,
//! which is critical to prevent flooding storms in the mesh.

use bloomfilter::Bloom;
use std::sync::Mutex;

/// Configuration for the deduplication filter.
pub struct DeduplicationConfig {
    /// Expected number of unique messages to track.
    pub expected_items: usize,
    /// Acceptable false positive rate (e.g., 0.01 = 1%).
    pub false_positive_rate: f64,
}

impl Default for DeduplicationConfig {
    fn default() -> Self {
        DeduplicationConfig {
            // Track up to 100,000 recent messages (~120KB memory at 1% FP rate)
            expected_items: 100_000,
            false_positive_rate: 0.01,
        }
    }
}

/// A thread-safe Bloom filter for deduplicating mesh messages.
pub struct DeduplicationFilter {
    filter: Mutex<Bloom<[u8; 32]>>,
    config: DeduplicationConfig,
    item_count: Mutex<usize>,
}

impl DeduplicationFilter {
    /// Creates a new deduplication filter with the given configuration.
    pub fn new(config: DeduplicationConfig) -> Self {
        let filter = Bloom::new_for_fp_rate(config.expected_items, config.false_positive_rate);
        DeduplicationFilter {
            filter: Mutex::new(filter),
            config,
            item_count: Mutex::new(0),
        }
    }

    /// Creates a filter with default configuration.
    pub fn with_defaults() -> Self {
        Self::new(DeduplicationConfig::default())
    }

    /// Checks if a message ID has been seen before.
    /// Returns `true` if the message is a duplicate (should be dropped).
    /// Returns `false` if the message is new.
    ///
    /// Note: Due to the probabilistic nature of Bloom filters, there is a
    /// small chance of false positives (new messages incorrectly identified
    /// as duplicates). The configured false positive rate controls this.
    pub fn is_duplicate(&self, message_id: &[u8; 32]) -> bool {
        let filter = self.filter.lock().expect("Dedup filter lock poisoned");
        filter.check(message_id)
    }

    /// Marks a message ID as seen. Future calls to `is_duplicate` with the
    /// same ID will return `true`.
    pub fn mark_seen(&self, message_id: &[u8; 32]) {
        let mut filter = self.filter.lock().expect("Dedup filter lock poisoned");
        filter.set(message_id);

        let mut count = self.item_count.lock().expect("Item count lock poisoned");
        *count += 1;

        // If we've exceeded capacity, reset the filter to maintain FP rate.
        // This means very old messages could be re-forwarded, which is acceptable
        // since they'll likely be expired by TTL anyway.
        if *count >= self.config.expected_items {
            let new_filter = Bloom::new_for_fp_rate(
                self.config.expected_items,
                self.config.false_positive_rate,
            );
            *filter = new_filter;
            *count = 0;
            log::info!(
                "Deduplication filter reset after reaching {} items",
                self.config.expected_items
            );
        }
    }

    /// Checks if a message is a duplicate and marks it as seen in one atomic operation.
    /// Returns `true` if the message was already seen (duplicate).
    /// Returns `false` if the message is new (and has been marked as seen).
    pub fn check_and_mark(&self, message_id: &[u8; 32]) -> bool {
        let mut filter = self.filter.lock().expect("Dedup filter lock poisoned");

        if filter.check(message_id) {
            return true; // Duplicate
        }

        filter.set(message_id);

        let mut count = self.item_count.lock().expect("Item count lock poisoned");
        *count += 1;

        if *count >= self.config.expected_items {
            *filter = Bloom::new_for_fp_rate(
                self.config.expected_items,
                self.config.false_positive_rate,
            );
            *count = 0;
        }

        false // New message
    }

    /// Returns the approximate number of items tracked.
    pub fn item_count(&self) -> usize {
        *self.item_count.lock().expect("Item count lock poisoned")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use sha2::{Digest, Sha256};

    fn make_id(seed: u8) -> [u8; 32] {
        let mut hasher = Sha256::new();
        hasher.update([seed]);
        hasher.finalize().into()
    }

    #[test]
    fn test_new_message_not_duplicate() {
        let filter = DeduplicationFilter::with_defaults();
        let id = make_id(1);
        assert!(!filter.is_duplicate(&id));
    }

    #[test]
    fn test_seen_message_is_duplicate() {
        let filter = DeduplicationFilter::with_defaults();
        let id = make_id(1);

        filter.mark_seen(&id);
        assert!(filter.is_duplicate(&id));
    }

    #[test]
    fn test_check_and_mark() {
        let filter = DeduplicationFilter::with_defaults();
        let id = make_id(1);

        assert!(!filter.check_and_mark(&id)); // First time: new
        assert!(filter.check_and_mark(&id));  // Second time: duplicate
    }

    #[test]
    fn test_different_ids_not_confused() {
        let filter = DeduplicationFilter::with_defaults();
        let id1 = make_id(1);
        let id2 = make_id(2);

        filter.mark_seen(&id1);
        assert!(filter.is_duplicate(&id1));
        assert!(!filter.is_duplicate(&id2));
    }

    #[test]
    fn test_filter_reset_on_capacity() {
        let config = DeduplicationConfig {
            expected_items: 10,
            false_positive_rate: 0.01,
        };
        let filter = DeduplicationFilter::new(config);

        // Fill the filter to exactly capacity (triggers reset on 10th item)
        for i in 0..10u8 {
            filter.mark_seen(&make_id(i));
        }

        // The 10th item hit capacity, filter was reset, count goes to 0
        assert_eq!(filter.item_count(), 0);

        // New items work on the fresh filter
        filter.mark_seen(&make_id(100));
        assert_eq!(filter.item_count(), 1);
        assert!(filter.is_duplicate(&make_id(100)));

        // Old items are no longer tracked after reset
        // (they may or may not show as duplicate due to Bloom filter FP,
        //  but the count is reset)
    }

    #[test]
    fn test_item_count_tracks() {
        let filter = DeduplicationFilter::with_defaults();
        assert_eq!(filter.item_count(), 0);

        filter.mark_seen(&make_id(1));
        assert_eq!(filter.item_count(), 1);

        filter.mark_seen(&make_id(2));
        assert_eq!(filter.item_count(), 2);
    }
}
