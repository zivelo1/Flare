# Flare — Project Status

## Current Phase: Phase 1 — Foundation
**Goal:** Two phones exchange encrypted text messages over BLE

## What's Done

### Rust Core (57 tests passing)
- [x] Identity generation (Ed25519 signing + X25519 key agreement)
- [x] Diffie-Hellman key agreement between devices
- [x] HKDF key derivation (per-message keys from shared secrets)
- [x] AES-256-GCM authenticated encryption/decryption
- [x] Message protocol (wire format, builder, serialization)
- [x] Spray-and-Wait mesh router
- [x] Bloom filter message deduplication
- [x] Peer table with RSSI-based distance estimation
- [x] BLE message chunking/reassembly for MTU constraints
- [x] SQLCipher encrypted database (identity, contacts, messages, outbox)
- [x] Transport event model and abstraction layer

### Android App
- [x] Gradle project setup (AGP 8.7, Kotlin 2.1, Compose BOM 2024.12)
- [x] AndroidManifest with BLE, Wi-Fi, foreground service permissions
- [x] Material 3 theme with dynamic color support (Material You)
- [x] Data models (DeviceIdentity, Contact, Conversation, ChatMessage, MeshPeer, MeshStatus)
- [x] Constants centralized (UUIDs, scan params, message defaults — no hardcoding)
- [x] BLE Scanner — discovers Flare devices, RSSI distance estimation, stale peer pruning
- [x] GATT Server — advertises service, accepts connections, receives messages, sends notifications
- [x] GATT Client — connects to peers, MTU negotiation, message write, notification subscription
- [x] MeshService — foreground service, lifecycle-aware, scan/advertise management
- [x] Navigation — bottom tabs (Chats, Contacts, Network) with Compose Navigation
- [x] ConversationListScreen — conversation list with mesh status, unread badges, avatars
- [x] ChatScreen — message bubbles, delivery status icons, message input with send
- [x] ContactsScreen — contact list, verified badges, QR code actions, last-seen formatting
- [x] NetworkScreen — mesh status card, stats row, nearby peer list with signal strength

### Infrastructure
- [x] GitHub repo (github.com/zivelo1/Flare)
- [x] .gitignore (secrets, venv, .claude, IDE files excluded)
- [x] Documentation (feasibility, architecture decisions, dev setup)

## What's Next (Phase 1 Remaining)
- [ ] UniFFI bindings: Rust → Kotlin (bridge Rust core to Android)
- [ ] Wire up BLE layer to Rust router (message serialization/deserialization)
- [ ] Wire up UI to real data (replace mutableStateListOf with Room/ViewModel)
- [ ] Integration test: Two physical Android devices, encrypted chat over BLE

## Phase Overview
| Phase | Scope | Status |
|---|---|---|
| 1 — Foundation | Rust core + Android BLE + UI | **In Progress** (core + Android app done) |
| 2 — Multi-Hop & iOS | Relay routing + iOS app + cross-platform | Not started |
| 3 — Full Messaging | Groups, voice msgs, images, receipts | Not started |
| 4 — Security & Distribution | Duress PIN, camouflage, offline install | Not started |
| 5 — Launch | Optimization, audit, localization, release | Not started |
