# Flare — Project Status

## Current Phase: Phase 2-4 — Multi-Hop, Full Messaging, Security
**Goal:** Complete Rust core for all features, Android integration, iOS prep

## What's Done

### Rust Core (94 tests passing)
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
- [x] UniFFI FFI layer (`FlareNode` object with proc macros, payload extraction, conversation query)
- [x] Neighborhood Bloom Filter — privacy-preserving cluster detection (deterministic SHA-256)
- [x] Priority Message Store — 50MB budget, 3-tier eviction, adaptive TTL (48h → 72h → 7d)
- [x] Delivery ACK processing — relay cleanup on ACK receipt
- [x] Bridge encounter detection — Jaccard similarity on neighborhood bitmaps
- [x] **Multi-hop relay** — `signable_bytes()` excludes `hop_count` + `ttl_seconds` (mutable by relay nodes), `prepare_for_relay()` increments hop count
- [x] **Content type generalization** — `build_mesh_message` accepts `content_type` parameter (Text, Voice, Image, KeyExchange, ACK, ReadReceipt, GroupMessage, ApkOffer, ApkRequest)
- [x] **Delivery receipts** — `create_delivery_ack()`, `create_read_receipt()`, `update_delivery_status()` FFI methods
- [x] **Group messaging (database layer)** — `groups` and `group_members` tables, CRUD operations, `build_group_messages()` FFI
- [x] **Duress PIN** — Argon2id-hashed duress passphrase stored in `duress_config` table, `check_duress_passphrase()` standalone function for pre-login check
- [x] **APK sharing protocol** — `ApkOfferPayload`, `ApkRequestPayload`, `ApkChunk` types, chunked transfer with SHA-256 verification, 16KB chunk size

### UniFFI Bridge (Rust → Kotlin/Swift)
- [x] FFI types: `FfiPublicIdentity`, `FfiContact`, `FfiChatMessage`, `FfiMeshMessage`, `FfiMeshStatus`, `FfiStoreStats`, `FfiGroup`
- [x] `FlareNode` object exposed via UniFFI proc macros (no UDL file)
- [x] Kotlin bindings auto-generated from compiled Rust library
- [x] Swift bindings auto-generated for iOS
- [x] JNA dependency added for Android runtime FFI
- [x] Neighborhood detection FFI: `recordNeighborhoodPeer`, `exportNeighborhoodBitmap`, `processRemoteNeighborhood`
- [x] Store stats FFI: `getStoreStats` returning priority store metrics
- [x] Multi-hop: `prepareForRelay` — increment hop count, check limit
- [x] Receipts: `createDeliveryAck`, `createReadReceipt`, `updateDeliveryStatus`
- [x] Groups: `createGroup`, `addGroupMember`, `removeGroupMember`, `listGroups`, `getGroupMembers`, `buildGroupMessages`
- [x] Duress: `setDuressPassphrase`, `hasDuressPassphrase`, `clearDuressPassphrase`, `checkDuressPassphrase`

### Android App
- [x] Gradle project setup (AGP 8.7, Kotlin 2.1, Compose BOM 2024.12)
- [x] AndroidManifest with BLE, Wi-Fi, foreground service permissions
- [x] Material 3 theme with dynamic color support (Material You)
- [x] Data models (DeviceIdentity, Contact, Conversation, ChatMessage, MeshPeer, MeshStatus)
- [x] Constants centralized (UUIDs, scan params, message defaults — no hardcoding)
- [x] BLE Scanner — discovers Flare devices, RSSI distance estimation, stale peer pruning
- [x] GATT Server — advertises service, accepts connections, receives messages, sends notifications (status code checked)
- [x] GATT Client — connects to peers, MTU negotiation, message write (status code checked), notification subscription
- [x] MeshService — foreground service, message routing via Rust core, outbound queue, neighborhood bitmap exchange, incoming message delivery to UI
- [x] **Multi-hop relay** — calls `prepareForRelay()` before forwarding, increments hop count
- [x] **Delivery ACK** — automatically sends ACK back through mesh on local delivery
- [x] FlareRepository — bridge layer between UniFFI bindings and Android app (incl. neighborhood + store stats + message persistence + relay + receipts)
- [x] FlareApplication — initializes FlareNode with device-bound passphrase (Android Keystore)
- [x] ChatViewModel — conversation list, message sending via Rust encryption, incoming message delivery, persisted chat history
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
- [x] ProGuard rules for JNA, UniFFI, BLE callbacks (release build safety)

### CI/CD Pipeline
- [x] GitHub Actions workflow: Rust tests, cross-compilation (aarch64, armv7, x86_64), Kotlin binding generation, APK build
- [x] Build script (`scripts/build-android.sh`) for local cross-compilation with NDK auto-detection

### QR Code Contact Exchange
- [x] QR display screen — shows device identity as QR code with safety number
- [x] QR scanner screen — CameraX + ML Kit barcode scanning with format validation
- [x] Navigation wired — scanner and display accessible from Contacts tab
- [x] Camera permission handling with runtime request
- [x] QR format validation (device ID + 32-byte signing key + 32-byte agreement key)

## What's Next

### Requires Android NDK (deferred)
- [ ] Cross-compile Rust core for Android ARM targets
- [ ] Integration test: Two physical Android devices, encrypted chat over BLE

### iOS App (Phase 2B)
- [ ] Xcode project setup with Swift Package Manager
- [ ] CoreBluetooth BLE layer (central + peripheral)
- [ ] Swift FlareRepository bridge layer (bindings already generated)
- [ ] SwiftUI screens (Chat, Contacts, Network)
- [ ] Background execution with CoreBluetooth state restoration

### Android UI Enhancements
- [ ] Group messaging UI (create group, add members, group chat)
- [ ] Read receipt indicators in chat bubbles
- [ ] Duress PIN setup screen in Settings
- [ ] APK sharing UI (offer/request/progress)

### Phase 5 — Launch
- [ ] Security audit
- [ ] Farsi/Persian localization
- [ ] Performance optimization (battery, memory)
- [ ] Release builds and distribution

## Phase Overview
| Phase | Scope | Status |
|---|---|---|
| 1 — Foundation | Rust core + Android BLE + UI + UniFFI bridge | **Complete** (awaiting device test with NDK) |
| 2 — Multi-Hop & iOS | Relay routing + iOS bindings | **Rust core complete**, iOS Swift bindings generated |
| 3 — Full Messaging | Groups, receipts, content types | **Rust core complete**, Android UI pending |
| 4 — Security & Distribution | Duress PIN, APK sharing protocol | **Rust core complete**, Android UI pending |
| 5 — Launch | Optimization, audit, localization, release | Not started |
