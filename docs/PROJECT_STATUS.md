# Flare — Project Status

## Current Phase: Phase 2-4 — Multi-Hop, Full Messaging, Security
**Goal:** Complete Rust core for all features, Android integration, iOS prep

## What's Done

### Rust Core (161 tests passing)
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
- [x] **Adaptive power management** — `PowerManager` with 4-tier duty cycling (High/Balanced/LowPower/UltraLow), configurable thresholds, burst mode scanning, battery-aware tier transitions (12 tests)
- [x] **DEFLATE compression** — `compress_payload`/`decompress_payload` with 1-byte header, MIN_COMPRESS_SIZE=64, decompression bomb protection (8 tests)
- [x] **Ed25519 APK signing** — `DeveloperSigningKey`, `TrustedDeveloperKeys` store, `ApkSignature` verification, key rotation protocol with old-key endorsement (10 tests)
- [x] **Sender Keys group encryption** — O(1) group messaging via HKDF chain ratchet, `SenderKeyStore` with per-group key management, forward secrecy within chains (12 tests)
- [x] **Route guard** — `RouteGuard` with signature verification, TTL inflation cap (3.5x factor, 7-day max), monotonic hop count enforcement, per-sender rate limiting (100 msgs), `HopTracker` LRU cache (10 tests)
- [x] **Blind Rendezvous Protocol** — decentralized peer discovery without servers:
  - Shared Phrase mode: Argon2id(normalized_phrase, salt=epoch_week), ~50+ bits entropy, nation-state resistant
  - Phone Number mode: Bilateral hash = Argon2id(sort(phone_A, phone_B), salt=epoch_week), with explicit security warning
  - Contact Import mode: Pre-compute bilateral tokens for all phone contacts
  - Proof-of-work anti-spam: 16 leading zero bits in SHA256(token || nonce), ~50ms per token
  - Weekly epoch rotation: tokens rotate via `epoch_week = unix_timestamp / (7 * 86400)`
  - Ephemeral X25519 keypair per search for forward secrecy
  - Identity encryption in replies: HKDF(ephemeral_key, salt=token) → AES-256-GCM
  - `RendezvousManager` with active search tracking, token registration, request/reply processing
  - 15 dedicated tests for rendezvous protocol

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
- [x] Rendezvous: `startPassphraseSearch`, `startPhoneSearch`, `registerMyPhone`, `importPhoneContacts`, `cancelSearch`, `buildRendezvousBroadcasts`, `processRendezvousMessage`, `processRendezvousRequest`, `activeSearchCount`

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
- [x] **Adaptive power management** — MeshService evaluates battery + network state every scan cycle, transitions BLE scan/advertise tiers (High/Balanced/LowPower/UltraLow), burst mode duty cycling for low-power tiers, battery-level-aware capping
- [x] **BLE scanner power tiers** — `ScanPowerTier` enum maps to Android `SCAN_MODE_LOW_LATENCY`/`BALANCED`/`LOW_POWER`/`OPPORTUNISTIC`
- [x] **GATT server power tiers** — `AdvertisePowerTier` enum maps to `ADVERTISE_MODE_*` with per-tier TX power levels
- [x] **Multi-hop relay** — calls `prepareForRelay()` before forwarding, increments hop count
- [x] **Delivery ACK** — automatically sends ACK back through mesh on local delivery
- [x] FlareRepository — bridge layer between UniFFI bindings and Android app (incl. neighborhood + store stats + message persistence + relay + receipts)
- [x] FlareApplication — initializes FlareNode with device-bound passphrase (Android Keystore)
- [x] ChatViewModel — conversation list, message sending via Rust encryption, incoming message delivery, persisted chat history
- [x] ContactsViewModel — contact management, QR code data generation/parsing
- [x] DiscoveryViewModel — shared phrase search, phone number search, contact import with SearchState management
- [x] NetworkViewModel — mesh status, nearby peers from BLE scanner
- [x] Navigation — bottom tabs (Chats, Contacts, Network) with Compose Navigation
- [x] ConversationListScreen — conversation list with mesh status, unread badges, avatars
- [x] ChatScreen — message bubbles, delivery status icons, encrypted send via Rust core
- [x] ContactsScreen — contact list, verified badges, QR code actions, last-seen formatting
- [x] FindContactScreen — discovery hub with 4 methods (Shared Phrase, QR Code, Phone Number, Contact Import)
- [x] SharedPhraseSearchScreen — passphrase entry, searching animation, contact found state
- [x] PhoneSearchScreen — bilateral phone number entry, security warning, risk acceptance
- [x] NetworkScreen — mesh status card, stats row, nearby peer list with signal strength

### Blind Rendezvous Discovery (Android)
- [x] Find Contact screen — discovery hub with 4 methods
- [x] Shared Phrase search — passphrase entry, mesh broadcast, contact discovery
- [x] Phone Number search — bilateral phone hash, security warning with risk acceptance
- [x] Rendezvous broadcast loop — 30-second periodic broadcast while searches active
- [x] READ_CONTACTS permission — for phone-based peer discovery
- [x] Navigation routes — find-contact, phrase-search, phone-search

### iOS App (Swift + SwiftUI)
- [x] Xcode project via xcodegen (project.yml + .xcodeproj)
- [x] Info.plist — BLE, camera, contacts permissions, background modes (bluetooth-central, bluetooth-peripheral)
- [x] Data models (DeviceIdentity, Contact, Conversation, ChatMessage, MeshPeer, MeshStatus)
- [x] FlareRepository — bridge to Rust FFI (Keychain passphrase, messaging, contacts, rendezvous, neighborhood, duress)
- [x] BLEManager — CoreBluetooth CBCentralManager + CBPeripheralManager with state restoration
- [x] MeshService — message routing, rendezvous broadcast, delivery ACK, peer connection handling
- [x] ChatViewModel, ContactsViewModel, DiscoveryViewModel, NetworkViewModel
- [x] ConversationListView — conversation list, mesh status indicator, empty state
- [x] ChatView — message bubbles, delivery status icons, encrypted send
- [x] ContactsView — contact list, verified badges, last-seen formatting
- [x] FindContactView — discovery hub (Shared Phrase, QR Code, Phone Number)
- [x] SharedPhraseSearchView — phrase input, searching animation, contact found
- [x] PhoneSearchView — bilateral phone entry, security warning, risk acceptance
- [x] QRDisplayView — QR code generation with safety number
- [x] QRScannerView — AVFoundation camera with QR detection and format validation
- [x] NetworkView — mesh status card, stats row, nearby peer list with signal strength
- [x] MainTabView — tab navigation (Chats, Contacts, Network)

### Infrastructure
- [x] GitHub repo (github.com/zivelo1/Flare)
- [x] .gitignore (secrets, venv, IDE files excluded)
- [x] Documentation (architecture decisions, dev setup, project status)
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

### iOS App — Remaining Work
- [ ] Cross-compile Rust core for iOS ARM (`aarch64-apple-ios`)
- [ ] Link `libflare_core.a` into Xcode project and verify build
- [ ] Integration test: physical iOS device, BLE scanning + advertising
- [ ] Cross-platform test: Android ↔ iOS message exchange over BLE
- [ ] Background execution tuning (CoreBluetooth state restoration is wired but untested)

### Android UI Enhancements
- [ ] Group messaging UI (create group, add members, group chat)
- [ ] Read receipt indicators in chat bubbles
- [ ] Duress PIN setup screen in Settings
- [ ] APK sharing UI (offer/request/progress)

### Rust Core Integration (pending wiring)
- [ ] Wire `compress_payload`/`decompress_payload` into message build/parse flow in `ffi.rs`
- [ ] Wire `RouteGuard.validate()` into `Router::route_incoming()`
- [ ] Expose `SenderKeyStore` via FFI for group messaging
- [ ] Expose `TrustedDeveloperKeys` via FFI for APK verification
- [ ] Expose `PowerManager` via FFI (alternative to Kotlin-native reimplementation)

### iOS App — Remaining Work
- [ ] Adaptive power management in `BLEManager.swift` (power tier enums, burst mode)

### Phase 5 — Launch
- [ ] Security audit
- [ ] Farsi/Persian localization
- [ ] Performance optimization (battery, memory)
- [ ] Release builds and distribution

## Phase Overview
| Phase | Scope | Status |
|---|---|---|
| 1 — Foundation | Rust core + Android BLE + UI + UniFFI bridge | **Complete** (awaiting device test with NDK) |
| 2 — Multi-Hop & iOS | Relay routing + iOS app | **Rust core complete**, iOS app implemented (awaiting cross-compile + device test) |
| 3 — Full Messaging | Groups, receipts, content types | **Rust core complete**, Android UI pending |
| 4 — Security & Distribution | Duress PIN, APK signing, route guard, compression | **Rust core complete**, Android power mgmt integrated, FFI wiring pending |
| 5 — Launch | Optimization, audit, localization, release | Not started |
