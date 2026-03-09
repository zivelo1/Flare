//! Mesh routing — message forwarding, deduplication, and store-and-forward.
//!
//! MVP uses Spray-and-Wait routing for fragmented mesh scenarios.
//! AODV will be added in Phase 2 for connected mesh optimization.

pub mod dedup;
pub mod router;
pub mod peer_table;

pub use dedup::DeduplicationFilter;
pub use router::{Router, RouteDecision};
pub use peer_table::{PeerTable, PeerInfo};
