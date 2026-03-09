//! Mesh routing — message forwarding, deduplication, and store-and-forward.
//!
//! MVP uses Spray-and-Wait routing for fragmented mesh scenarios.
//! Adaptive TTL extends message lifetime when crossing cluster boundaries.

pub mod dedup;
pub mod neighborhood;
pub mod peer_table;
pub mod priority_store;
pub mod router;

pub use dedup::DeduplicationFilter;
pub use neighborhood::{NeighborhoodFilter, EncounterType};
pub use peer_table::{PeerTable, PeerInfo};
pub use priority_store::PriorityStore;
pub use router::{Router, RouteDecision};
