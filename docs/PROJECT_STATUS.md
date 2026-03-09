# Flare — Project Status

## Current Phase: Phase 1 — Foundation
**Goal:** Two phones exchange encrypted text messages over BLE

## What's Done

### Rust Core (85 tests passing)
- [x] Identity generation (Ed25519 signing + X25519 key agreement)
- [x] Diffie-Hellman key agreement between devices
- [x] HKDF key derivation (per-message keys from shared secrets)
- [x] AES-256-GCM authenticated encryption/decryption
- [x] Message protocol (wire format, builder, serialization)
- [x] Spray-and-Wait mesh router with adaptive TTL
- [x] Bloom filter message deduplication
- [x] Peer table with RSSI-based distance estimation
- [x] BLE message chunking/reassembly for MTU constraints
- [x] SQLCipher encrypted database (identity, contacts, messages, outbox)
- [x] Transport event model and abstraction layer
- [x] UniFFI FFI layer (`FlareNode` object with proc macros)
- [x] Neighborhood Bloom Filter — privacy-preserving cluster detection (deterministic SHA-256)
- [x] Priority Message Store — 50MB budget, 3-tier eviction, adaptive TTL (48h → 72h → 7d)
- [x] Delivery ACK processing — relay cleanup on ACK receipt
- [x] Bridge encounter detection — Jaccard similarity on neighborhood bitmaps

### UniFFI Bridge (Rust → Kotlin)
- [x] FFI types: `FfiPublicIdentity`, `FfiContact`, `FfiChatMessage`, `FfiMeshMessage`, `FfiMeshStatus`, `FfiStoreStats`
- [x] `FlareNode` object exposed via UniFFI proc macros (no UDL file)
- [x] Kotlin bindings auto-generated from compiled Rust library
- [x] JNA dependency added for Android runtime FFI
- [x] Neighborhood detection FFI: `recordNeighborhoodPeer`, `exportNeighborhoodBitmap`, `processRemoteNeighborhood`
- [x] Store stats FFI: `getStoreStats` returning priority store metrics

### Android App
- [x] Gradle project setup (AGP 8.7, Kotlin 2.1, Compose BOM 2024.12)
- [x] AndroidManifest with BLE, Wi-Fi, foreground service permissions
- [x] Material 3 theme with dynamic color support (Material You)
- [x] Data models (DeviceIdentity, Contact, Conversation, ChatMessage, MeshPeer, MeshStatus)
- [x] Constants centralized (UUIDs, scan params, message defaults — no hardcoding)
- [x] BLE Scanner — discovers Flare devices, RSSI distance estimation, stale peer pruning
- [x] GATT Server — advertises service, accepts connections, receives messages, sends notifications
- [x] GATT Client — connects to peers, MTU negotiation, message write, notification subscription
- [x] MeshService — foreground service, message routing via Rust core, outbound queue, neighborhood bitmap exchange
- [x] FlareRepository — bridge layer between UniFFI bindings and Android app (incl. neighborhood + store stats)
- [x] FlareApplication — initializes FlareNode with device-bound passphrase (Android Keystore)
- [x] ChatViewModel — conversation list, message sending via Rust encryption, delivery status
- [x] ContactsViewModel — contact management, QR code data generation/parsing
- [x] NetworkViewModel — mesh status, nearby peers from BLE scanner
- [x] Navigation — bottom tabs (Chats, Contacts, Network) with Compose Navigation
- [x] ConversationListScreen — conversation list with mesh status, unread badges, avatars
- [x] ChatScreen — message bubbles, delivery status icons, encrypted send via Rust core
- [x] ContactsScreen — contact list, verified badges, QR code actions, last-seen formatting
- [x] NetworkScreen — mesh status card, stats row, nearby peer list with signal strength

### Infrastructure
- [x] GitHub repo (github.com/zivelo1/Flare)
- [x] .gitignore (secrets, venv, .claude, IDE files excluded)
- [x] Documentation (feasibility, architecture decisions, dev setup)

### CI/CD Pipeline
- [x] GitHub Actions workflow: Rust tests, cross-compilation (aarch64, armv7, x86_64), Kotlin binding generation, APK build
- [x] Build script (`scripts/build-android.sh`) for local cross-compilation with NDK auto-detection

### QR Code Contact Exchange
- [x] QR display screen — shows device identity as QR code with safety number
- [x] QR scanner screen — CameraX + ML Kit barcode scanning with format validation
- [x] Navigation wired — scanner and display accessible from Contacts tab
- [x] Camera permission handling with runtime request
- [x] QR format validation (device ID + 32-byte signing key + 32-byte agreement key)

## What's Next (Phase 1 Remaining)
- [ ] Install Android NDK locally or rely on CI/CD for cross-compilation
- [ ] Integration test: Two physical Android devices, encrypted chat over BLE

## Phase Overview
| Phase | Scope | Status |
|---|---|---|
| 1 — Foundation | Rust core + Android BLE + UI + UniFFI bridge | **In Progress** (CI/CD + QR exchange complete, awaiting device test) |
| 2 — Multi-Hop & iOS | Relay routing + iOS app + cross-platform | Not started |
| 3 — Full Messaging | Groups, voice msgs, images, receipts | Not started |
| 4 — Security & Distribution | Duress PIN, camouflage, offline install | Not started |
| 5 — Launch | Optimization, audit, localization, release | Not started |
