# Flare — Architecture Decision Log

## ADR-001: Shared Core in Rust
**Date:** 2026-03-09
**Decision:** Business logic (crypto, routing, storage, protocol) written in Rust.
**Rationale:** Memory-safe, cross-platform (Android NDK + iOS C ABI), libsignal is Rust-native, single implementation prevents divergence bugs.
**Bindings:** Mozilla UniFFI generates Kotlin and Swift bindings automatically.

## ADR-002: BLE GATT as Universal Transport
**Date:** 2026-03-09
**Decision:** BLE GATT is the primary cross-platform transport.
**Rationale:** Only wireless protocol available on both Android and iOS for third-party app data exchange. Wi-Fi Direct (Android-only) and Multipeer Connectivity (iOS-only) used as same-platform bandwidth upgrades.

## ADR-003: Native UI Per Platform
**Date:** 2026-03-09
**Decision:** Jetpack Compose (Android) + SwiftUI (iOS), not Flutter/React Native.
**Rationale:** BLE and background services require deep platform integration. CoreBluetooth state restoration (iOS) not available in cross-platform frameworks. Native UI provides best UX.

## ADR-004: Signal Protocol for E2E Encryption
**Date:** 2026-03-09
**Decision:** Use libsignal-protocol-rust for end-to-end encryption.
**Rationale:** Battle-tested, peer-reviewed, provides forward secrecy and post-compromise security. Adapted for DTN via mesh-specific key exchange (QR code + mesh-relayed prekey bundles).

## ADR-005: Hybrid Routing (Spray-and-Wait for MVP)
**Date:** 2026-03-09
**Decision:** MVP uses Spray-and-Wait. AODV added in Phase 2.
**Rationale:** Spray-and-Wait is simpler, works well in fragmented mesh (the common case), bounded overhead. AODV added later for connected mesh optimization.

## ADR-006: SQLCipher for Local Storage
**Date:** 2026-03-09
**Decision:** All local data stored in SQLCipher (encrypted SQLite).
**Rationale:** Protects data at rest against device seizure. Key derived from user passphrase via Argon2id.

## ADR-007: Adaptive TTL with Neighborhood Bloom Filter
**Date:** 2026-03-09
**Decision:** Messages use adaptive TTL tiers based on network topology detection via Neighborhood Bloom Filters. No GPS, no individual tracking.
**Rationale:** Long-distance delivery (e.g., 100km across Iran) requires messages to survive days in the mesh. Static TTL is either too short (messages die before reaching far destinations) or too long (wastes storage on local messages). Adaptive TTL solves this by detecting "bridge encounters" — when a relay node from a different mesh cluster connects, the message's TTL is extended.

**Mechanism:**
- Each device maintains a Bloom filter of recently-seen peer short IDs (rolling 6-hour window)
- On peer connection, compare Bloom filters via Jaccard similarity estimate
- High overlap (>50%) = same neighborhood = LOCAL encounter
- Low overlap (<20%) = different area = BRIDGE encounter
- Messages start with 48h TTL; extended to 72h on first bridge, 7 days on second bridge
- Hard maximum: 7 days from creation timestamp, never extended beyond

**Privacy properties:**
- No GPS or location data used
- Bloom filters are probabilistic — individual device IDs cannot be extracted
- Filters roll over every 6 hours — no long-term tracking possible
- Only answers: "Do these two nodes share a similar neighborhood?"

## ADR-008: Priority Message Store with Size Budget
**Date:** 2026-03-09
**Decision:** Relay message storage uses a priority queue with configurable size budget (default 50MB) and three priority tiers.
**Rationale:** Phone storage is finite. Without prioritization, relay nodes fill up with old messages and can't accept new ones. Priority eviction ensures the most deliverable messages are retained.

**Priority tiers:**
1. Own messages (sent/received) — never auto-evicted
2. Active relay messages — sorted by TTL remaining and cluster-crossing count
3. Older relay messages — evicted first when budget is reached

## ADR-009: Delivery ACK and Relay Cleanup
**Date:** 2026-03-09
**Decision:** Recipients generate lightweight signed ACK messages that propagate back through the mesh to trigger relay cleanup.
**Rationale:** Without ACKs, relay nodes store messages until TTL expiry even after successful delivery. ACK-triggered cleanup frees storage and provides delivery confirmation to senders.

**Properties:**
- ACK is a small MeshMessage (ContentType::Acknowledgment, ~100 bytes)
- ACK references the original message_id
- ACK has its own TTL (48h) and spray copies (4)
- Relay nodes that hold the original message delete it upon receiving the ACK
- Sender's UI shows delivered status (✓✓) upon receiving the ACK

## ADR-010: No Social-Aware Routing
**Date:** 2026-03-09
**Decision:** Routing decisions must NEVER track individual movement patterns, travel habits, or social connections between users.
**Rationale:** In authoritarian contexts (Iran, Myanmar, etc.), tracking which users travel between cities or frequently encounter each other creates a surveillance tool. Even anonymized movement data can be deanonymized with auxiliary information. The Neighborhood Bloom Filter approach detects network topology diversity without identifying individuals.
