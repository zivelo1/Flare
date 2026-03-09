# Flare — Project Status

## Current Phase: Phase 1 — Foundation (Rust Core)
**Goal:** Two phones exchange encrypted text messages over BLE

## What's Done
- [x] Feasibility research and architecture document
- [x] GitHub repo created (github.com/zivelo1/Flare)
- [x] Project structure scaffolded
- [x] .gitignore configured
- [x] Rust core: Identity generation (Ed25519 signing + X25519 key agreement)
- [x] Rust core: Diffie-Hellman key agreement between devices
- [x] Rust core: HKDF key derivation (per-message keys from shared secrets)
- [x] Rust core: AES-256-GCM authenticated encryption/decryption
- [x] Rust core: Message protocol (wire format, builder, serialization)
- [x] Rust core: Spray-and-Wait mesh router
- [x] Rust core: Bloom filter message deduplication
- [x] Rust core: Peer table with RSSI-based distance estimation
- [x] Rust core: BLE message chunking/reassembly for MTU constraints
- [x] Rust core: SQLCipher encrypted database (identity, contacts, messages, outbox)
- [x] Rust core: Transport event model and abstraction layer
- [x] All 57 unit tests passing

## What's Next (Phase 1 Remaining)
- [ ] Android: BLE GATT service and client (Kotlin)
- [ ] Android: Peer discovery and connection management
- [ ] Android: Basic chat UI (Jetpack Compose)
- [ ] UniFFI bindings: Rust → Kotlin
- [ ] Integration test: Two Android devices, encrypted chat over BLE

## Phase Overview
| Phase | Scope | Status |
|---|---|---|
| 1 — Foundation | Rust core + Android BLE + encrypted chat | **In Progress** (core complete) |
| 2 — Multi-Hop & iOS | Relay routing + iOS app + cross-platform | Not started |
| 3 — Full Messaging | Groups, voice msgs, images, receipts | Not started |
| 4 — Security & Distribution | Duress PIN, camouflage, offline install | Not started |
| 5 — Launch | Optimization, audit, localization, release | Not started |

## Test Results (57/57 passing)
```
Crypto:    identity (8), encryption (6), keys (5) = 19 tests
Protocol:  message builder, serialization, wire size = 6 tests
Routing:   dedup (6), peer_table (5), router (6) = 17 tests
Transport: chunking, reassembly, headers = 5 tests
Storage:   schema, identity, contacts, outbox = 7 tests
```
