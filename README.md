# Flare

**Encrypted peer-to-peer messaging over Bluetooth mesh. No internet, no servers, no cell towers.**

Flare is a fully decentralized messaging application for Android and iOS that creates mesh networks using nearby phones. Designed for internet shutdowns, natural disasters, and off-grid communication.

## How It Works

```
Phone A ←──BLE──→ Phone B ←──BLE──→ Phone C ←──BLE──→ Phone D
         15m              15m              15m
```

Each phone acts as both a messenger and a relay. Messages hop from phone to phone using Bluetooth Low Energy (BLE), reaching recipients who may be far away — as long as there are enough phones in between.

## Features (Planned)

- **No infrastructure required** — works without internet, Wi-Fi, or cell service
- **End-to-end encrypted** — AES-256-GCM with keys from X25519 Diffie-Hellman + HKDF
- **Cross-platform** — Android and iPhone communicate seamlessly over BLE GATT
- **Store-and-forward** — messages wait and travel with phones until they reach the recipient
- **Offline installable** — share the app phone-to-phone via Bluetooth, no app store needed
- **Open source** — GPLv3, auditable, community-maintained

## Architecture

```
┌─────────────────────────────┐
│   Shared Core (Rust)         │
│   Crypto · Routing · Storage │
├──────────────┬──────────────┤
│ Android      │ iOS          │
│ Kotlin +     │ Swift +      │
│ Jetpack      │ SwiftUI      │
│ Compose      │              │
└──────────────┴──────────────┘
```

- **Rust core** — cryptography, message protocol, mesh routing, encrypted storage
- **Android** — BLE GATT, Wi-Fi Direct, Jetpack Compose UI
- **iOS** — CoreBluetooth, Multipeer Connectivity, SwiftUI

## Current Status

Phase 1 (Foundation) — Rust core library complete with 57 passing tests:
- Ed25519 identity + X25519 key agreement
- AES-256-GCM authenticated encryption
- Spray-and-Wait mesh routing
- Bloom filter message deduplication
- SQLCipher encrypted local database
- BLE message chunking/reassembly

See [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for detailed progress.

## Building

### Prerequisites
- Rust 1.70+ (`curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`)
- Android Studio (for Android app)
- Xcode 15+ (for iOS app, macOS only)

### Rust Core
```bash
cd flare-core
cargo build
cargo test
```

## Documentation

- [Feasibility & Architecture](FEASIBILITY_AND_ARCHITECTURE.md) — comprehensive technical assessment
- [Architecture Decisions](docs/ARCHITECTURE_DECISIONS.md) — ADR log
- [Development Setup](docs/DEVELOPMENT_SETUP.md) — build instructions
- [Project Status](docs/PROJECT_STATUS.md) — current progress

## Security

Flare uses established, audited cryptographic primitives:
- **Ed25519** — digital signatures (identity, message authentication)
- **X25519** — Diffie-Hellman key agreement
- **AES-256-GCM** — authenticated encryption
- **HKDF-SHA256** — key derivation
- **Argon2id** — passphrase-based key derivation for database encryption
- **SQLCipher** — encrypted SQLite for data at rest

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE) for details.
