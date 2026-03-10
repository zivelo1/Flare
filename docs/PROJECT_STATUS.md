# Flare — Project Status

## Current Phase: Phase 5 — UI/UX Polish & Launch Prep
**Goal:** Settings, onboarding, groups UI, identicons, read receipts across both platforms

## What's Done

### Rust Core (191 tests passing)
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
- [x] **Adaptive spray count** — spray copies = ceil(sqrt(observed_peers) × density_factor), clamped to [3, 16]. Based on Spyropoulos et al. optimal L = O(sqrt(N)). Reduces broadcast amplification in dense networks (7 tests)
- [x] **Neighborhood-aware routing** — bridge peers prioritized in spray target selection. `connected_peers_prioritized()` sorts by encounter type: Bridge > Unknown > Intermediate > Local. Reduces wasted hops by preferring cluster-crossing peers (4 tests)
- [x] **Message size tiers** — `SizeTierConfig` with Small (≤15KB, mesh relay), Medium (≤64KB, direct preferred), Large (>64KB, direct required). Content-type-aware: voice/images always prefer direct. 8 tests
- [x] **Wi-Fi Direct transfer queue** — `WifiDirectManager` with FIFO queue, 3-retry failure handling, 5-minute timeout, 50 max pending transfers, connection state tracking. 10 tests

### UniFFI Bridge (Rust → Kotlin/Swift)
- [x] FFI types: `FfiPublicIdentity`, `FfiContact`, `FfiChatMessage`, `FfiMeshMessage`, `FfiMeshStatus`, `FfiStoreStats`, `FfiGroup`
- [x] `FlareNode` object exposed via UniFFI proc macros (no UDL file)
- [x] Kotlin bindings auto-generated from compiled Rust library
- [x] Swift bindings auto-generated for iOS
- [x] JNA dependency added for Android runtime FFI
- [x] Neighborhood detection FFI: `recordNeighborhoodPeer`, `exportNeighborhoodBitmap`, `processRemoteNeighborhood`, `processRemoteNeighborhoodForPeer`
- [x] Store stats FFI: `getStoreStats` returning priority store metrics
- [x] Multi-hop: `prepareForRelay` — increment hop count, check limit
- [x] Receipts: `createDeliveryAck`, `createReadReceipt`, `updateDeliveryStatus`
- [x] Groups: `createGroup`, `addGroupMember`, `removeGroupMember`, `listGroups`, `getGroupMembers`, `buildGroupMessages`
- [x] Duress: `setDuressPassphrase`, `hasDuressPassphrase`, `clearDuressPassphrase`, `checkDuressPassphrase`
- [x] Rendezvous: `startPassphraseSearch`, `startPhoneSearch`, `registerMyPhone`, `importPhoneContacts`, `cancelSearch`, `buildRendezvousBroadcasts`, `processRendezvousMessage`, `processRendezvousRequest`, `activeSearchCount`
- [x] Transfer strategy: `recommendTransferStrategy` → `FfiTransferRecommendation` with strategy, size tier, BLE chunk estimate
- [x] Wi-Fi Direct queue: `wifiDirectEnqueue`, `wifiDirectNextTransfer`, `wifiDirectCompleteTransfer`, `wifiDirectFailTransfer`, `wifiDirectConnectionChanged`, `wifiDirectMostNeededPeer`, `wifiDirectHasPending`, `wifiDirectPruneExpired`, `wifiDirectStats`

### Android App
- [x] Gradle project setup (AGP 8.7, Kotlin 2.1, Compose BOM 2024.12)
- [x] AndroidManifest with BLE, Wi-Fi, foreground service permissions
- [x] Material 3 theme with dynamic color support (Material You)
- [x] Data models (DeviceIdentity, Contact, Conversation, ChatMessage, MeshPeer, MeshStatus, Group, PowerTierInfo)
- [x] Constants centralized (UUIDs, scan params, message defaults, Wi-Fi Direct config, size tiers, power thresholds — no hardcoding)
- [x] BLE Scanner — discovers Flare devices, RSSI distance estimation, stale peer pruning
- [x] GATT Server — advertises service, accepts connections, receives messages, sends notifications (status code checked)
- [x] GATT Client — connects to peers, MTU negotiation, message write (status code checked), notification subscription
- [x] MeshService — foreground service, message routing via Rust core, outbound queue, neighborhood bitmap exchange, incoming message delivery to UI
- [x] **WiFiDirectManager** — Wi-Fi P2P manager with peer discovery, group formation, TCP socket data transfer (length-prefixed protocol), server socket for receiving, connection state tracking
- [x] **Adaptive power management** — MeshService evaluates battery + network state every scan cycle, transitions BLE scan/advertise tiers (High/Balanced/LowPower/UltraLow), burst mode duty cycling for low-power tiers, battery-level-aware capping
- [x] **BLE scanner power tiers** — `ScanPowerTier` enum maps to Android `SCAN_MODE_LOW_LATENCY`/`BALANCED`/`LOW_POWER`/`OPPORTUNISTIC`
- [x] **GATT server power tiers** — `AdvertisePowerTier` enum maps to `ADVERTISE_MODE_*` with per-tier TX power levels
- [x] **Multi-hop relay** — calls `prepareForRelay()` before forwarding, increments hop count
- [x] **Delivery ACK** — automatically sends ACK back through mesh on local delivery
- [x] FlareRepository — bridge layer between UniFFI bindings and Android app (incl. neighborhood + store stats + message persistence + relay + receipts + transfer strategy + Wi-Fi Direct queue)
- [x] FlareApplication — initializes FlareNode with device-bound passphrase (Android Keystore)
- [x] ChatViewModel — conversation list, message sending via Rust encryption, incoming message delivery, persisted chat history
- [x] ContactsViewModel — contact management, QR code data generation/parsing
- [x] DiscoveryViewModel — shared phrase search, phone number search, contact import with SearchState management
- [x] NetworkViewModel — mesh status, nearby peers from BLE scanner
- [x] SettingsViewModel — duress PIN management, power tier state, store stats
- [x] GroupViewModel — group CRUD, member selection, create group with members
- [x] Navigation — bottom tabs (Chats, Contacts, Network) + settings/groups routes with Compose Navigation
- [x] ConversationListScreen — conversation list with mesh status, unread badges, identicon avatars, settings/groups navigation
- [x] ChatScreen — message bubbles, delivery status icons (pending/sent/delivered/read with blue/failed with error), identicon avatars, encrypted send via Rust core
- [x] ContactsScreen — contact list, verified badges, QR code actions, last-seen formatting
- [x] FindContactScreen — discovery hub with 4 methods (Shared Phrase, QR Code, Phone Number, Contact Import)
- [x] SharedPhraseSearchScreen — passphrase entry, searching animation, contact found state
- [x] PhoneSearchScreen — bilateral phone number entry, security warning, risk acceptance
- [x] NetworkScreen — mesh status card, stats row, nearby peer list with signal strength
- [x] **Onboarding flow** — 4-page HorizontalPager (No Internet Needed, E2E Encrypted, Find Friends, Designed for Safety), skip/next/back/get-started, persisted via SharedPreferences
- [x] **Settings screen** — security (duress PIN), battery & performance (power tiers), storage stats card with progress bar, device info, about section
- [x] **Duress PIN settings** — setup form (passphrase + confirm), active status card, remove with confirmation dialog
- [x] **Power management settings** — current tier card with color coding, battery saver toggle, tier explanation cards with Constants values
- [x] **Group messaging UI** — group list with empty state, create group with contact selection and checkboxes
- [x] **Identicon avatars** — SHA-256 deterministic colors from 12-color curated palette (IdenticonGenerator)
- [x] **Splash screen** — animated flame Canvas drawing with brand gradient, configurable duration
- [x] **App icon** — adaptive icon with vector flame foreground and FlareOrange background
- [x] **Chat animations** — AnimatedVisibility entrance on new messages, animateItem() for smooth reordering
- [x] **Haptic feedback** — impact on send, vibration pattern on receive (via Vibrator system service)
- [x] **Mesh visualization** — Canvas topology with peer nodes (IdenticonGenerator colors), pulsing connection lines, RSSI-based thickness
- [x] **Voice recording** — hold-to-record with MediaRecorder, live waveform from getMaxAmplitude(), elapsed time, .m4a output
- [x] **Image capture** — ActivityResultContracts.TakePicture with FileProvider, bottom sheet preview with send FAB
- [x] **APK sharing** — share screen (version, size, SHA-256 hash, progress), receive screen (verification status, install button)
- [x] **Dark mode refinement** — extended dark color scheme (surfaceContainer, inverseSurface, errorContainer)

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
- [x] Data models (DeviceIdentity, Contact, Conversation, ChatMessage, MeshPeer, MeshStatus, ChatGroup, StoreStats)
- [x] FlareRepository — bridge to Rust FFI (Keychain passphrase, messaging, contacts, rendezvous, neighborhood, duress, power, groups, transfer strategy, Wi-Fi Direct queue)
- [x] BLEManager — CoreBluetooth CBCentralManager + CBPeripheralManager with state restoration
- [x] **MultipeerManager** — MultipeerConnectivity framework for Wi-Fi Direct transport. MCSession with automatic peer discovery, deterministic tie-breaking for connection deduplication, reliable data send
- [x] MeshService — message routing via dual transport (BLE + MultipeerConnectivity), rendezvous broadcast, delivery ACK, Wi-Fi Direct queue processing, peer connection handling
- [x] ChatViewModel, ContactsViewModel, DiscoveryViewModel, NetworkViewModel, SettingsViewModel, GroupViewModel
- [x] ConversationListView — conversation list, mesh status indicator, identicon avatars, settings/groups navigation
- [x] ChatView — message bubbles, delivery status icons (pending/sent/delivered/read/failed), encrypted send
- [x] ContactsView — contact list, identicon avatars, verified badges, last-seen formatting
- [x] FindContactView — discovery hub (Shared Phrase, QR Code, Phone Number)
- [x] SharedPhraseSearchView — phrase input, searching animation, contact found
- [x] PhoneSearchView — bilateral phone entry, security warning, risk acceptance
- [x] QRDisplayView — QR code generation with safety number
- [x] QRScannerView — AVFoundation camera with QR detection and format validation
- [x] NetworkView — mesh status card, stats row, nearby peer list with signal strength
- [x] MainTabView — tab navigation (Chats, Contacts, Network)
- [x] **Onboarding flow** — 4-page TabView pager (No Internet Needed, E2E Encrypted, Find Friends, Designed for Safety), persisted via @AppStorage
- [x] **Settings screen** — security (duress PIN), battery & performance (power tiers), storage stats with progress bar, device info, about section
- [x] **Duress PIN settings** — setup with passphrase/confirm, active status, remove with confirmation dialog
- [x] **Power management settings** — current tier card, battery saver toggle, tier explanation cards with live Constants values
- [x] **Group messaging UI** — group list, create group with contact selection, group chat placeholder
- [x] **Identicon avatars** — SHA-256 deterministic colors from 12-color curated palette (IdenticonGenerator + IdenticonAvatarView)
- [x] **Splash screen** — animated FlameShape + brand gradient, configurable via Constants
- [x] **Chat animations** — spring transitions on new messages, scale animation on send button
- [x] **Haptic feedback** — centralized HapticManager (medium impact on send, success notification on receive)
- [x] **Mesh visualization** — Canvas-based animated topology with Timer-driven pulsing, RSSI line thickness
- [x] **Voice recording** — hold-to-record with AVAudioRecorder, live waveform from averagePower, .m4a output
- [x] **Image capture** — UIImagePickerController via UIViewControllerRepresentable, preview sheet with send/cancel
- [x] **Dark mode** — semantic SwiftUI colors throughout, FlareOrange with appropriate opacity

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

### Scaling Improvements (Phase 4B)
- [x] **Adaptive spray count** — L = ceil(sqrt(N) × 1.5), clamped [3, 16]. Reduces broadcast amplification in dense networks while maintaining delivery in sparse ones
- [x] **Neighborhood-aware routing** — peers tagged with encounter type from bloom filter comparison. Bridge peers prioritized in spray target selection. Messages cross cluster boundaries faster
- [x] **Message size tiers** — small payloads (≤15KB) use BLE mesh; medium (≤64KB) prefer direct; large (>64KB) require direct. Content-type-aware: voice/images always prefer Wi-Fi Direct
- [x] **Wi-Fi Direct transport (Rust core)** — `WifiDirectManager` with transfer queue, connection state machine, retry logic, expiration, statistics
- [x] **Wi-Fi Direct transport (iOS)** — `MultipeerManager` using MultipeerConnectivity framework with automatic peer discovery, MCSession, deterministic connection deduplication
- [x] **Wi-Fi Direct transport (Android)** — `WiFiDirectManager` using Wi-Fi P2P with peer discovery, group formation, TCP socket transfer with length-prefixed protocol

## What's Next

### Requires Android NDK (deferred)
- [ ] Cross-compile Rust core for Android ARM targets
- [ ] Integration test: Two physical Android devices, encrypted chat over BLE

### iOS App — Remaining Work
- [x] Cross-compile Rust core for iOS ARM (`aarch64-apple-ios`) — build verified
- [ ] Integration test: physical iOS device, BLE scanning + advertising
- [ ] Cross-platform test: Android ↔ iOS message exchange over BLE
- [ ] Background execution tuning (CoreBluetooth state restoration is wired but untested)

### UI/UX — Visual Design & Polish (Complete)
- [x] App icon (adaptive vector drawable on Android, programmatic FlameShape on iOS) and animated splash screen
- [x] Chat bubble animations (slide-in + fade on new messages, spring animations)
- [x] Network mesh visualization (Canvas-based animated topology with peer nodes, pulsing connection lines, RSSI thickness)
- [x] Dark mode theming refinement (extended dark color scheme on Android, semantic colors on iOS)
- [x] Haptic feedback (medium impact on send, notification vibration on receive — both platforms)
- [x] Voice message recording UI (hold-to-record with live waveform, AVAudioRecorder/MediaRecorder, .m4a format)
- [x] Image capture and preview UI (UIImagePickerController/ActivityResultContracts.TakePicture, preview sheet with send/cancel)
- [x] APK sharing UI (Android: share screen with version/hash/progress, receive screen with verification status)

### Phase 6 — Launch
- [ ] Security audit
- [ ] Farsi/Persian localization
- [ ] Performance optimization (battery, memory)
- [ ] Release builds and distribution

## Phase Overview
| Phase | Scope | Status |
|---|---|---|
| 1 — Foundation | Rust core + Android BLE + UI + UniFFI bridge | **Complete** (awaiting device test with NDK) |
| 2 — Multi-Hop & iOS | Relay routing + iOS app | **Complete** (iOS cross-compiled and building, awaiting device test) |
| 3 — Full Messaging | Groups, receipts, content types | **Complete** (Rust core + Android/iOS UI) |
| 4 — Security & Distribution | Duress PIN, APK signing, route guard, compression | **Complete** |
| 4B — Scaling & Dual Transport | Adaptive spray, neighborhood routing, size tiers, Wi-Fi Direct | **Complete** |
| 5 — UI/UX & Launch Prep | Settings, onboarding, groups, identicons, animations, haptics, voice/image UI, APK sharing | **Complete** |
