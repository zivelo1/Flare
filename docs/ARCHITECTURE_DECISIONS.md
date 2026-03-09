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
**Rationale:** Long-distance delivery (e.g., 100km across a country) requires messages to survive days in the mesh. Static TTL is either too short (messages die before reaching far destinations) or too long (wastes storage on local messages). Adaptive TTL solves this by detecting "bridge encounters" — when a relay node from a different mesh cluster connects, the message's TTL is extended.

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
**Rationale:** In authoritarian contexts, tracking which users travel between cities or frequently encounter each other creates a surveillance tool. Even anonymized movement data can be deanonymized with auxiliary information. The Neighborhood Bloom Filter approach detects network topology diversity without identifying individuals.

## ADR-011: Multi-Hop Signature Exclusion
**Date:** 2026-03-09
**Decision:** `hop_count` and `ttl_seconds` are excluded from the Ed25519 `signable_bytes()`.
**Rationale:** Relay nodes must increment `hop_count` during forwarding and the adaptive TTL system may extend `ttl_seconds` on bridge encounters. Including these mutable fields in the signature would cause signature verification to fail after the first relay hop, breaking multi-hop delivery entirely.

## ADR-012: Duress PIN with Dual Database
**Date:** 2026-03-09
**Decision:** Users can configure a duress passphrase. Entering it at login opens a separate decoy database with innocent data while the real database stays hidden and encrypted.
**Rationale:** In authoritarian contexts, users may be forced to unlock their phone and show their messaging app. The duress PIN provides plausible deniability — the decoy database shows a convincing but harmless message history. The duress passphrase hash is stored via Argon2id in the main database; the mobile app checks it before constructing the FlareNode to decide which database file to open.

## ADR-013: APK Sharing Protocol
**Date:** 2026-03-09
**Decision:** Flare can share its own APK file phone-to-phone using `ApkOffer`/`ApkRequest` mesh messages and chunked transfer with SHA-256 verification.
**Rationale:** During internet shutdowns, app stores are inaccessible. Users need to install Flare from nearby phones. The protocol advertises APK metadata (version, size, hash), allows peers to request it, and transfers in 16KB chunks verified by SHA-256 hash. This enables viral distribution without any internet connectivity.

## ADR-014: Group Messaging via Individual Encryption
**Date:** 2026-03-09
**Decision:** Group messages are encrypted individually for each group member using their respective DH shared secrets, not a shared group key.
**Rationale:** In a mesh network without a reliable server to manage group key distribution, per-member encryption is more robust. Each member receives their own copy encrypted with their unique shared secret. This avoids the complexity of group key agreement protocols in a delay-tolerant network where members may be offline for days.

## ADR-015: Blind Rendezvous Protocol for Peer Discovery
**Date:** 2026-03-09
**Decision:** Decentralized peer discovery using three-tier Blind Rendezvous: Shared Phrase (recommended), Phone Number (convenience), and Contact Import.
**Rationale:** Without servers, users need a way to find each other on the mesh. Traditional approaches (usernames, phone numbers as identifiers) require a directory service. Blind Rendezvous lets two parties who share a secret (a phrase, phone numbers) independently derive the same token and find each other through the mesh without revealing their identities to anyone else.

**Mechanism:**
- **Shared Phrase** (recommended): Both parties enter the same passphrase (a shared memory, inside joke, etc.). Token = Argon2id(normalized_phrase, salt=epoch_week). High entropy (~50+ bits), resistant to brute-force even by nation-state adversaries.
- **Phone Number** (convenience): Both parties enter each other's phone numbers. Token = Argon2id(sort(phone_A, phone_B), salt=epoch_week). Bilateral — both must participate. Vulnerable to targeted brute-force by adversary who knows one number (~10^8 possibilities). Explicit security warning shown in UI.
- **Contact Import**: Pre-compute bilateral tokens for all phone contacts, matching against tokens broadcast on the mesh.

**Anti-spam:** Proof-of-work (16 leading zero bits in SHA256(token || nonce), ~65K iterations, ~50ms) prevents token flooding.

**Forward secrecy:** Each search generates an ephemeral X25519 keypair. The reply encrypts the responder's identity using HKDF(ephemeral_public_key, salt=token) → AES-256-GCM.

**Token rotation:** Tokens rotate weekly via epoch_week = unix_timestamp / (7 * 86400). Old tokens cannot be used to correlate searches across weeks.

**Privacy properties:**
- Passphrases and phone numbers never leave the device — only Argon2id-derived tokens are broadcast
- Tokens are 8-byte truncated hashes — cannot be reversed to the input
- Ephemeral keys provide forward secrecy for the discovery handshake
- Bilateral phone hashing means both parties must actively participate
