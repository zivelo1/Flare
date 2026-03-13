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

## ADR-014: Group Messaging via Sender Keys (supersedes individual encryption)
**Date:** 2026-03-10
**Decision:** Group messages use Sender Keys protocol (Signal Groups v2 approach) for O(1) encryption per send, replacing O(n) per-member encryption.
**Rationale:** Per-member encryption scales linearly — a 50-member group requires 50 separate encryptions per message, each consuming BLE bandwidth. Sender Keys uses a chain ratchet: each sender distributes a seed key once via existing DH channels, then derives per-message AES-256-GCM keys using HKDF. The group receives one ciphertext regardless of size.

**Mechanism:**
- `SenderKeyChain` with HKDF chain ratchet: `message_key = HKDF(chain_key, "msg"||index)`, `next_chain_key = SHA-256(chain_key)`
- `SenderKeyDistribution` messages sent via existing pairwise DH channels on join
- `SenderKeyStore` manages own and remote keys per group
- MAX_SKIP_KEYS=256 for out-of-order message handling
- Forward secrecy within chain (ratchet is one-way; old keys are deleted)

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

## ADR-016: Adaptive Power Management with 4-Tier Duty Cycling
**Date:** 2026-03-10
**Decision:** BLE scanning and advertising use a 4-tier adaptive power management system (High/Balanced/LowPower/UltraLow) that transitions automatically based on network activity, peer presence, and battery level.
**Rationale:** Continuous BLE scanning at LOW_LATENCY mode draws ~75mA — a 4000mAh battery would last ~53 hours. In practice, full-speed scanning is only needed during active data exchange. The adaptive strategy reduces average draw to ~10-15mA by spending most time in LOW_POWER mode and bursting to BALANCED/LOW_LATENCY only when needed.

**Tier transitions:**
- **High → Balanced:** 10 seconds of inactivity or 30 seconds continuous in High
- **Balanced → LowPower:** 60 seconds without any peer discovery
- **Any → UltraLow:** Battery below 15% or user battery saver enabled
- **Any → High:** Data sent/received or pending outbound with connected peers
- **Battery cap:** High tier disabled below 30% battery (capped at Balanced)

**Burst mode:** LowPower scans 5s/30s (~17% active), UltraLow scans 3s/60s (~5% active).

**Implementation:** Canonical logic in `flare-core/src/power/mod.rs` (Rust), mirrored in Android `MeshService.kt` for direct BLE API access. Constants centralized in `Constants.kt`/`Constants.swift`.

## ADR-017: DEFLATE Compression Before Encryption
**Date:** 2026-03-10
**Decision:** Message payloads are compressed with DEFLATE before encryption. A 1-byte header indicates compression method (0x00=none, 0x01=DEFLATE).
**Rationale:** Encrypted data has maximum entropy and cannot be compressed. Compressing plaintext before AES-256-GCM encryption reduces BLE transmission size by 50-70% for text messages (including multi-byte UTF-8 like Farsi). This directly reduces BLE transmission time and power consumption.

**Properties:**
- Pure Rust implementation (flate2/miniz_oxide) — no C dependencies, critical for mobile cross-compilation
- Payloads < 64 bytes skipped (DEFLATE framing overhead exceeds savings)
- Decompression bomb protection: 1MB maximum decompressed size
- Pipeline: `compress → encrypt → chunk → transmit → reassemble → decrypt → decompress`

## ADR-018: Ed25519 Developer Signing for APK Distribution
**Date:** 2026-03-10
**Decision:** APK files distributed phone-to-phone are signed with Ed25519 developer keys, verified against a trusted developer key store on the receiving device.
**Rationale:** SHA-256 hash verification (ADR-013) only ensures integrity — it cannot verify the APK came from a legitimate developer. An attacker could modify the APK, recompute the hash, and distribute a trojaned version. Ed25519 signing provides authenticity: only the holder of the private key can produce a valid signature.

**Mechanism:**
- `DeveloperSigningKey` signs APK bytes at build time
- `TrustedDeveloperKeys` store maintains trusted public keys per device (TOFU on first install)
- `KeyRotation` protocol: old key signs endorsement of new key for graceful key succession
- `ApkVerifyResult`: Valid / InvalidSignature / UntrustedDeveloper

## ADR-019: Route Guard for Mutable Field Protection
**Date:** 2026-03-10
**Decision:** A `RouteGuard` validates incoming messages before routing, protecting against manipulation of the mutable routing fields (`hop_count`, `ttl_seconds`) that are excluded from signatures (ADR-011).
**Rationale:** Excluding mutable fields from signatures is necessary for multi-hop relay but creates attack surface: a malicious relay could inflate TTL to keep messages alive forever, decrease hop count to bypass limits, or flood the network. The route guard enforces invariants that don't require signatures.

**Protections:**
- **TTL inflation cap:** New TTL cannot exceed `original_ttl * 3.5`, hard max 7 days (604800s)
- **Hop count monotonicity:** Per-message hop tracking via `HopTracker` LRU cache; hop count must never decrease
- **Signature verification:** Ed25519 signature checked on `signable_bytes()` (immutable fields)
- **Sender rate limiting:** Max 100 active messages per sender to prevent flooding

## ADR-020: Adaptive Spray Count
**Date:** 2026-03-10
**Decision:** Spray copy count adapts dynamically to observed network density instead of using a fixed value.
**Formula:** `L = ceil(sqrt(N) × density_factor)`, clamped to [min_spray_copies, max_spray_copies].
**Rationale:** Based on Spyropoulos, Psounis & Raghavendra (2005) "Spray and Wait" paper, which proves optimal L scales as O(sqrt(N)). Fixed L=8 wastes bandwidth in dense networks (100+ peers) and may be insufficient in very sparse ones. The density_factor (default 1.5) allows tuning delivery probability vs. traffic.
**Configuration:** `PriorityStoreConfig` — `adaptive_spray_enabled`, `min_spray_copies=3`, `max_spray_copies=16`, `spray_density_factor=1.5`.
**Fallback:** When disabled or with 0 observed peers, uses `initial_spray_copies` (static value).

## ADR-021: Neighborhood-Aware Routing
**Date:** 2026-03-10
**Decision:** Peers are tagged with their neighborhood encounter type (Bridge/Intermediate/Local) from bloom filter comparison. When selecting spray targets, bridge peers are prioritized over local peers.
**Rationale:** Bridge peers connect different clusters — they are the most valuable relays for cross-cluster message delivery. Spraying to local peers wastes copies since those peers likely see the same neighbors we do. Prioritizing bridge peers ensures messages cross cluster boundaries faster with fewer wasted copies.
**Implementation:** `PeerInfo.encounter_type` tracks each peer's encounter classification. `PeerTable.connected_peers_prioritized()` sorts by: Bridge > Unknown > Intermediate > Local. Router uses `take(spray_copies)` on the prioritized list, naturally selecting the best routing candidates.

## ADR-022: Message Size Tiers and Transfer Strategy
**Date:** 2026-03-10
**Decision:** Messages are classified into size tiers (Small/Medium/Large) with recommended transfer strategies (MeshRelay/DirectPreferred/DirectRequired).
**Rationale:** BLE mesh relay is optimal for small payloads (text, ACKs, key exchanges) but unsuitable for large payloads (images, long voice clips) that would consume hundreds of relay chunks and flood the mesh. Wi-Fi Direct provides orders-of-magnitude more bandwidth but consumes more power and requires explicit connection setup.
**Thresholds:** Small ≤ 15KB (BLE mesh), Medium ≤ 64KB (prefer direct), Large > 64KB (require direct). Content-type overrides: voice/images always prefer direct; control messages (ACK, key exchange) always use mesh.
**BLE media constraints:** The BLE chunk protocol supports max 255 chunks × ~509 bytes ≈ 130KB per message. Media is sized to fit: images scaled to 400px max dimension, JPEG quality 35% (~20-60KB encoded); voice recorded at 24kbps AAC/16kHz (~90KB for 30s). A 90KB size guard in FlareRepository prevents oversized payloads from entering the BLE pipeline.

## ADR-023: Dual Transport Architecture (BLE + Wi-Fi Direct)
**Date:** 2026-03-10
**Decision:** BLE remains always-on for discovery and signaling. Wi-Fi Direct (Android: Wi-Fi P2P, iOS: MultipeerConnectivity) activates on demand for large payload transfers.
**Rationale:** BLE has universal device support, low power consumption, and sufficient bandwidth for text messaging (~100 KB/s). Wi-Fi Direct provides ~50 Mbps throughput and ~250m range but consumes significantly more power and requires connection negotiation. Using both together provides the best of both worlds.
**Protocol:** Both transports produce the same `TransportEvent` types. The router doesn't care which transport delivered a message — identical message format, same routing logic. The platform layer decides which transport to use based on the `recommendTransferStrategy()` FFI method.
**Connection deduplication:** iOS MultipeerManager uses deterministic tie-breaking (lower displayName accepts invitations, higher invites) to prevent duplicate connections when both peers discover each other simultaneously.

## ADR-024: Deterministic Identicon Avatars
**Date:** 2026-03-10
**Decision:** Contact avatars are generated deterministically from device IDs using SHA-256 hashing into a curated 12-color palette. No user-uploaded images.
**Rationale:** In an offline mesh network, there is no server to host profile pictures. Users need visual differentiation between contacts at a glance. A deterministic algorithm ensures the same device ID always produces the same avatar colors across all devices, providing consistent recognition without any data exchange.

**Mechanism:**
- SHA-256 hash of device ID string → first byte selects palette index
- 12-color palette: perceptually distinct, accessible on light and dark backgrounds
- 5x5 horizontally-symmetric boolean grid pattern from hash bytes
- 1-2 character initials from display name (or device ID prefix as fallback)
- Implemented identically on Android (`IdenticonGenerator.kt`) and iOS (`IdenticonGenerator.swift`)
