# Flare — Project Status

## Current Phase: Phase 9 — UI Polish (Android)
**Strategy:** Android-first. In target markets (Iran, Syria, Yemen, Sudan, Myanmar, Cuba, Venezuela, Ethiopia), Android has 85-98% market share. iOS deferred to backlog.
**Previous:** Phase 8 localization (6 languages), Phase 6A device testing verified — encrypted BLE mesh messaging working on 2 physical Android devices.

## What's Done

### Rust Core (193 tests passing)
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
- [x] **Route guard** — `RouteGuard` with signature verification, TTL inflation cap (3.5x factor based on inferred original TTL, 7-day absolute max), monotonic hop count enforcement, per-sender rate limiting (100 msgs), `HopTracker` LRU cache (10 tests)
- [x] **Blind Rendezvous Protocol** — decentralized peer discovery without servers:
  - Shared Phrase mode: Argon2id(normalized_phrase, salt=epoch_week), ~50+ bits entropy, nation-state resistant
  - Phone Number mode: Bilateral hash = Argon2id(sort(phone_A, phone_B), salt=epoch_week), with explicit security warning
  - Contact Import mode: Pre-compute bilateral tokens for all phone contacts
  - Proof-of-work anti-spam: 16 leading zero bits in SHA256(token || nonce), ~50ms per token
  - Weekly epoch rotation: tokens rotate via `epoch_week = unix_timestamp / (7 * 86400)`
  - Ephemeral X25519 keypair per search for forward secrecy
  - Identity encryption in replies: X25519 ECDH → HKDF(shared_secret, salt=token) → AES-256-GCM (eavesdropper-resistant)
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
- [x] Contact rename: `updateContactDisplayName` — update display name in encrypted DB
- [x] Broadcast: `buildBroadcastMessage` — signed (not encrypted) broadcast to all peers

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
- [x] ContactsViewModel — contact management, QR code data generation/parsing, shareable identity deep links
- [x] DiscoveryViewModel — shared phrase search, phone number search, contact import with SearchState management
- [x] NetworkViewModel — mesh status, nearby peers from BLE scanner
- [x] SettingsViewModel — duress PIN management, power tier state, store stats
- [x] GroupViewModel — group CRUD, member selection, create group with members
- [x] Navigation — bottom tabs (Chats, Contacts, Network) + settings/groups routes with Compose Navigation
- [x] ConversationListScreen — conversation list with mesh status, unread badges, identicon avatars, settings/groups navigation
- [x] ChatScreen — message bubbles, delivery status icons (pending/sent/delivered/read with blue/failed with error), identicon avatars, encrypted send via Rust core
- [x] ContactsScreen — contact list, verified badges, QR code actions, last-seen formatting
- [x] FindContactScreen — discovery hub with 5 methods (Shared Phrase, Share Identity Link, QR Code, Phone Number, Contact Import)
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
- [x] **Localization** — all hardcoded strings extracted to strings.xml, translations for 6 languages (Farsi, Arabic, Spanish, Russian, Chinese, Korean)
- [x] **Language settings** — LanguageSettingsScreen with runtime locale switching via AppCompatDelegate
- [x] **Contact rename** — long-press to rename, Rust DB `update_contact_display_name` + FFI binding
- [x] **Broadcast messaging** — BroadcastScreen for sending to all contacts, with security warning and confirmation
- [x] **Profile name** — editable display name in Settings, persisted via SharedPreferences

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
- [x] FindContactView — discovery hub (Shared Phrase, Share Identity Link, QR Code, Phone Number)
- [x] SharedPhraseSearchView — phrase input, searching animation, contact found
- [x] PhoneSearchView — bilateral phone entry, security warning, risk acceptance
- [x] QRDisplayView — QR code generation with safety number and shareable identity link
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

### Shareable Identity Link (Remote Contact Exchange)
- [x] **Deep link format** — `flare://add?id=<deviceId>&sk=<signingKey>&ak=<agreementKey>&name=<displayName>`
- [x] **Android share button** — QR display screen has "Share My Identity Link" button + toolbar icon, opens system share sheet
- [x] **Android deep link handler** — intent filter for `flare://add` scheme, singleTask launch mode, toast confirmation
- [x] **iOS share button** — QR display screen has share button + toolbar icon, opens UIActivityViewController
- [x] **iOS URL scheme handler** — `flare` scheme registered in Info.plist, `.onOpenURL` handler in FlareApp
- [x] **FindContactScreen** — "Share Identity Link" added as a discovery method (Android + iOS)
- [x] **Validation** — hex key length checks, scheme/host validation, missing parameter detection
- [x] **Security** — link-added contacts marked as unverified (no in-person confirmation); share message includes app download link
- [x] **Constants** — deep link scheme, host, and query parameter keys centralized in both platform Constants files

### Scaling Improvements (Phase 4B)
- [x] **Adaptive spray count** — L = ceil(sqrt(N) × 1.5), clamped [3, 16]. Reduces broadcast amplification in dense networks while maintaining delivery in sparse ones
- [x] **Neighborhood-aware routing** — peers tagged with encounter type from bloom filter comparison. Bridge peers prioritized in spray target selection. Messages cross cluster boundaries faster
- [x] **Message size tiers** — small payloads (≤15KB) use BLE mesh; medium (≤64KB) prefer direct; large (>64KB) require direct. Content-type-aware: voice/images always prefer Wi-Fi Direct
- [x] **Wi-Fi Direct transport (Rust core)** — `WifiDirectManager` with transfer queue, connection state machine, retry logic, expiration, statistics
- [x] **Wi-Fi Direct transport (iOS)** — `MultipeerManager` using MultipeerConnectivity framework with automatic peer discovery, MCSession, deterministic connection deduplication
- [x] **Wi-Fi Direct transport (Android)** — `WiFiDirectManager` using Wi-Fi P2P with peer discovery, group formation, TCP socket transfer with length-prefixed protocol

## What's Next

### Phase 6A — Device Testing (Verified on 2 Physical Devices)
- [x] Cross-compile Rust core for Android ARM targets (`aarch64-linux-android`, `armv7-linux-androideabi`) — arm64: 7.6MB, armv7: 5.3MB
- [x] Debug APK builds successfully with both ABIs — **app-debug.apk**
- [x] Install and test on physical Android devices via adb
  - Phone 1: Samsung (RFCT804CZEP) — Android 14 (API 34), arm64-v8a
  - Phone 2: Samsung (R9HR105EYKJ) — Android 12 (API 31), armeabi-v7a
- [x] **Integration test: Encrypted chat over BLE mesh — WORKING**
  - QR code contact exchange between devices
  - End-to-end encrypted message send/receive over BLE GATT
  - Zero internet required — pure mesh networking
- [x] BLE GATT auto-connection — devices discover and connect automatically
- [x] BLE GATT MTU negotiation — 517 bytes (514 usable) confirmed on both devices
- [x] API backward compatibility — BLE GATT APIs work on both API 31 (deprecated path) and API 34 (new path)
- [ ] Wi-Fi Direct group formation on real hardware
- [ ] Power management tier behavior on real battery

#### Bugs Fixed During Device Testing
- **Missing GATT auto-connection:** BleScanner discovered peers but never initiated GATT connections. Added auto-connect flow in MeshService triggered by new peer discovery
- **Missing conversation creation:** `add_contact()` in Rust core only created contact row but not conversation row, causing FOREIGN KEY constraint failure when storing messages. Fixed in `ffi.rs` + `database.rs` with `ensure_conversation()`
- **API 31 BLE crash:** `writeDescriptor(descriptor, value)`, `writeCharacteristic(char, data, type)`, and `notifyCharacteristicChanged(device, char, confirm, data)` are API 33+. Added backward-compatible paths using deprecated APIs for older devices
- **Permission request loop:** `requestBluetoothPermissions()` called every `onResume` even when already granted, causing infinite dialog loop. Fixed to check permissions first and start MeshService directly if all granted
- **armv7 native library missing:** Second phone (armeabi-v7a) crashed with `library "libflare_core.so" not found`. Cross-compiled for armv7 target and included in APK

### Phase 7 — Security Hardening (Complete)
- [x] **Full security code audit** — read all security-critical Rust files, identified 5 vulnerabilities
- [x] **CRITICAL FIX: Database key derivation** — `derive_key()` used random salt on every open, making DB unopenable on restart. Fixed: deterministic salt via SHA-256(domain_separator || passphrase). Added 2 regression tests (determinism + uniqueness)
- [x] **CRITICAL FIX: Hardware-backed key fallback** — Android Keystore hardware-backed path used guessable `Build.FINGERPRINT.hashCode()`. Fixed: encrypt a fixed challenge with the hardware key, store the ciphertext hash as passphrase (unforgeable without TEE access)
- [x] **HIGH FIX: Rendezvous reply eavesdropping** — reply encryption used HKDF(ephemeral_public_key) as input, but the ephemeral public key is broadcast in cleartext — any eavesdropper could decrypt. Fixed: proper X25519 ECDH between responder's ephemeral key and querier's ephemeral key, nonce derived from token + responder public key
- [x] **HIGH FIX: Payload length truncation** — `signable_bytes()` cast `payload.len()` to `u16`, truncating for payloads >65535 bytes. An attacker could append data without invalidating signatures. Fixed: cast to `u64`
- [x] **MEDIUM FIX: TTL extension factor ignored** — `compute_max_allowed_ttl()` returned hard-coded 7-day cap regardless of original TTL. `max_ttl_extension_factor` config was defined but never used. Fixed: infers original TTL from message age + current TTL, applies 3.5x factor, capped at 7-day absolute max
- [ ] Duress PIN forensic analysis (dual-database detectability)
- [ ] Traffic analysis resistance (BLE fingerprinting)
- [ ] Bloom filter privacy validation (initial review: 4-byte short_id + 6-hour rollover looks sound)

### Phase 8 — Localization & UX Improvements (Android) (Complete)
- [x] **Farsi/Persian** — Farsi string translations (values-fa)
- [x] **Arabic** — Arabic string translations (values-ar)
- [x] **Spanish** — string translations (values-es)
- [x] **Russian** — string translations (values-ru)
- [x] **Chinese (Simplified)** — string translations (values-zh-rCN)
- [x] **Korean** — string translations (values-ko)
- [x] **Language selector** — LanguageSettingsScreen with runtime locale switching via AppCompatDelegate
- [x] **All hardcoded strings extracted** — every UI string moved to strings.xml with positional format specifiers
- [x] **Contact rename** — long-press to rename contacts, backed by Rust DB `update_contact_display_name`
- [x] **Broadcast messaging** — BroadcastScreen for sending messages to all contacts with confirmation dialog
- [x] **Profile name** — editable display name in Settings, persisted via SharedPreferences
- [ ] RTL layout testing (Farsi/Arabic) — chat bubbles, navigation direction

### Phase 9 — UI Polish (Android) (Complete)
- [x] **Dark mode toggle** — user-selectable light/dark/system in Settings with AlertDialog and RadioButton options, activity recreate on change
- [x] **Version display** — BuildConfig.VERSION_NAME shown in Settings About section
- [x] **Language confirmation dialog** — OK/Cancel approval before switching language (prevents accidental changes)
- [x] **AppCompatActivity migration** — MainActivity uses AppCompatActivity for proper locale switching via AppCompatDelegate
- [x] **Voice recording permission fix** — permission check at composition time via ContextCompat.checkSelfPermission
- [x] **Voice message sending** — Base64-encoded audio over mesh with playback UI (MediaPlayer)
- [x] **Image message sending** — scaled/compressed JPEG, Base64-encoded, rendered in chat bubbles
- [x] **Contact deletion** — cascading delete (messages → conversations → contact) with confirmation dialog
- [x] **CI fix** — x86_64-linux-android OpenSSL cross-compilation (RANLIB env var)
- [ ] **Emoji picker** — in-chat emoji selector for quick access beyond system keyboard

### Phase 10 — Android Release
- [ ] Battery drain profiling across power tiers
- [ ] Memory profiling under relay load
- [ ] Wi-Fi Direct testing on real hardware
- [ ] Power management tier behavior on real battery
- [ ] Signed release APK
- [ ] F-Droid submission
- [ ] First GitHub Release (tagged APK download)

### Future Enhancements (Backlog)
- [ ] **iOS device testing** — physical iPhone BLE, cross-platform Android↔iOS messaging (Rust iOS cross-compilation already verified)
- [ ] **iOS App Store submission** — $99/year Apple Developer Program, App Store review
- [ ] iOS localization (SwiftUI RTL, translations for all 6 languages)
- [ ] iOS background execution tuning (CoreBluetooth state restoration is wired but untested)

### Known Issues (Remaining)
- **FFI method gaps:** Several Kotlin methods in `FlareRepository.kt` called FFI methods that don't exist in the UniFFI bindings (`wifiDirect*`, `power*`, `recommendTransferStrategy`, `processRemoteNeighborhoodForPeer`). These are currently stubbed with local implementations. The Rust FFI layer needs to expose these methods properly.

### Known Issues (Resolved)
- **Coil dependency was missing:** `ImagePreviewSheet.kt` imports `coil` for async image loading but it wasn't in `build.gradle.kts`. **Fixed.**
- **`settings.gradle.kts` had `dependencyResolution` instead of `dependencyResolutionManagement`** — invalid Gradle API name. **Fixed.**
- **Gradle wrapper (`gradlew`)** was missing from the repo — generated during this phase. **Fixed.**
- **SQLCipher cross-compilation** required switching from `bundled-sqlcipher` to `bundled-sqlcipher-vendored-openssl` in Cargo.toml to bundle OpenSSL source for Android NDK builds. **Fixed.**
- **UniFFI metadata stripped** — `strip = true` in release profile removed UniFFI metadata from .so. Changed to `strip = "debuginfo"`. **Fixed.**
- **Crash on update from pre-v0.8:** Old `derive_key()` used random salt, making databases encrypted with an irrecoverable key. New deterministic salt produces a different key, so old DB can't be opened. **Fixed:** `FlareRepository.initialize()` catches the error, deletes the old DB, and creates a fresh one. One-time migration — all future updates preserve data.

## Phase Overview
| Phase | Scope | Status |
|---|---|---|
| 1 — Foundation | Rust core + Android BLE + UI + UniFFI bridge | **Complete** |
| 2 — Multi-Hop & iOS | Relay routing + iOS app | **Complete** (iOS code complete, device test deferred) |
| 3 — Full Messaging | Groups, receipts, content types | **Complete** (Rust core + Android/iOS UI) |
| 4 — Security & Distribution | Duress PIN, APK signing, route guard, compression | **Complete** |
| 4B — Scaling & Dual Transport | Adaptive spray, neighborhood routing, size tiers, Wi-Fi Direct | **Complete** |
| 5 — UI/UX & Launch Prep | Settings, onboarding, groups, identicons, animations, haptics, voice/image UI, APK sharing | **Complete** |
| 6A — Device Testing | Android APK build, cross-compilation, device install, BLE mesh verified | **Complete** (2 devices, encrypted chat working) |
| 7 — Security Hardening | Crypto review, DB key fix, rendezvous DH fix, payload sig fix, TTL guard fix | **Complete** (5 vulnerabilities fixed, 193 tests) |
| 8 — Localization & UX (Android) | 6 languages, language selector, contact rename, broadcast, profile | **Complete** |
| 9 — UI Polish (Android) | Dark mode, voice/image sending, contact deletion, CI fix | **Complete** |
| 10 — Android Release | Battery/memory profiling, signed APK, F-Droid, GitHub Release | Planned |
| Backlog — iOS | iOS device testing, App Store ($99/yr), iOS localization | Deferred |

### Target Market Analysis (Android-First Rationale)
| Country | Android | iOS | Context |
|---|---|---|---|
| Iran | 86% | 14% | Internet shutdowns, App Store blocked by sanctions |
| Syria | 95% | 5% | Active conflict |
| Yemen | 96% | 4% | Active civil war |
| Sudan | 97% | 3% | Active civil war |
| Myanmar | 86% | 14% | Military coup, internet shutdowns |
| Ethiopia | 98% | 2% | Conflict, internet shutdowns |
| Cuba | 95% | 5% | Government-controlled internet |
| Venezuela | 88% | 12% | Economic crisis, sanctions |
| Afghanistan | 86% | 14% | Taliban rule |
| Libya | 85% | 15% | Post-conflict instability |
| Somalia | 89% | 11% | Ongoing conflict |
| Ukraine | 64% | 35% | Active war (higher iOS due to income) |
| China | 77% | 22% | Google Play blocked, HarmonyOS rising |

Source: [StatCounter Global Stats](https://gs.statcounter.com/os-market-share/mobile/) (Feb 2026)
