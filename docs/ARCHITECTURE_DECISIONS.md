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
