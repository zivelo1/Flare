# Flare

**Encrypted messaging that works without internet. Phone to phone, through Bluetooth.**

## What Is Flare?

Flare lets you send encrypted messages to anyone — even when there is no internet, no cell service, and no Wi-Fi. Your messages travel from phone to phone using Bluetooth, hopping through other Flare users until they reach your contact, even if they are dozens of kilometers away.

No servers. No accounts. No phone number required. Just install and start messaging.

## Who Is This For?

- **People during internet shutdowns** — governments blocking communication during protests or unrest
- **People during natural disasters** — earthquakes, hurricanes, floods that destroy cell towers, in areas where survivors are gathered
- **Journalists and aid workers** — operating in conflict zones where infrastructure is destroyed but people are present
- **Protesters and activists** — communicating in crowded streets when networks are shut down
- **Festival and event attendees** — crowded venues where cell networks are overwhelmed
- **Refugee camps and shelters** — dense populations with no telecom infrastructure
- **Anyone who values privacy** — messages never touch a server, ever

## How It Works

```
You ←──BLE──→ Stranger's ←──BLE──→ Another ←──BLE──→ Your
                phone               phone           friend
```

1. **Install Flare** on your phone (Android or iPhone)
2. **Find your friend** by entering a shared phrase you both know — a memory, a place, an inside joke. Flare searches the mesh network and connects you securely.
3. **Send messages.** Your messages are encrypted on your phone and hop through other Flare users' phones until they reach your friend. Nobody in between can read them — not even the people whose phones relay them.

Messages can travel across a city or even between cities, as long as there are enough Flare users along the way. The more people using Flare, the further messages can reach.

## Finding Your Contacts Without Internet

Since there are no servers, Flare uses a **Blind Rendezvous** protocol to help you find people you know:

| Method | How It Works | Best For |
|---|---|---|
| **Shared Phrase** | Both you and your friend type the same phrase (a shared memory). Flare matches you securely. | Most situations — secure and private |
| **QR Code** | Scan each other's QR code when you meet in person | Maximum security |
| **Phone Number** | Enter each other's phone numbers. Both must do it. | Convenience (with privacy tradeoff) |
| **Contact Import** | Import your phone contacts to find friends already on Flare | Quick setup |

Your phone number, passphrase, and contacts **never leave your device**. Only a mathematical fingerprint is shared — it cannot be reversed.

## Key Features

- **Works offline** — no internet, no Wi-Fi, no cell service needed
- **End-to-end encrypted** — only you and your recipient can read messages
- **No accounts or registration** — no phone number, no email, no sign-up
- **Store and forward** — messages wait on relay phones until they can be delivered, even if it takes days
- **Multi-hop routing** — messages travel through many phones to reach distant recipients
- **Duress protection** — a panic PIN opens a decoy app with fake messages if you are forced to unlock
- **Phone-to-phone install** — share Flare with others via Bluetooth, no app store needed
- **Open source** — GPLv3, auditable, community-maintained

## Architecture

```
┌───────────────────────────────────┐
│   Shared Core (Rust)               │
│   Crypto · Routing · Discovery     │
│   Storage · Protocol               │
├────────────────┬──────────────────┤
│ Android        │ iOS               │
│ Kotlin +       │ Swift +           │
│ Jetpack        │ SwiftUI           │
│ Compose        │                   │
└────────────────┴──────────────────┘
```

- **Rust core** — cryptography, mesh routing, Blind Rendezvous discovery, encrypted storage
- **Android** — BLE GATT, Wi-Fi Direct, Material 3 UI
- **iOS** — CoreBluetooth, Multipeer Connectivity, SwiftUI

## Current Status

**Rust Core** (109 tests passing):
- Ed25519/X25519 identity and key agreement
- AES-256-GCM encryption, HKDF key derivation
- Spray-and-Wait mesh routing with adaptive TTL (48h → 72h → 7d)
- Blind Rendezvous discovery — shared phrase (Argon2id-hardened), phone number (bilateral hash), contact import
- Multi-hop relay with hop count increment (signature excludes mutable fields)
- Neighborhood Bloom Filter for privacy-preserving bridge detection (no GPS)
- Priority message store with 50MB budget and 3-tier eviction
- Delivery ACK and read receipt processing for relay cleanup
- Group messaging, duress PIN, APK sharing protocol
- SQLCipher encrypted database, BLE chunking, UniFFI FFI layer

**Android App** (Kotlin + Jetpack Compose):
- BLE GATT server + client with full mesh routing
- Material 3 UI: chat bubbles, contacts, network dashboard
- Find Contact screen: shared phrase, QR code, phone number discovery
- Full message pipeline: encrypt → send → relay → deliver → ACK
- Foreground service, neighborhood detection, ProGuard rules

**iOS** — Swift bindings generated, app implementation pending

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

- [Architecture Decisions](docs/ARCHITECTURE_DECISIONS.md) — ADR log
- [Development Setup](docs/DEVELOPMENT_SETUP.md) — build instructions
- [Project Status](docs/PROJECT_STATUS.md) — current progress

## Security

Flare uses established, audited cryptographic primitives:
- **Ed25519** — digital signatures (identity, message authentication)
- **X25519** — Diffie-Hellman key agreement
- **AES-256-GCM** — authenticated encryption
- **HKDF-SHA256** — key derivation
- **Argon2id** — passphrase-based key derivation (database encryption + rendezvous tokens)
- **SQLCipher** — encrypted SQLite for data at rest

**Privacy by design:**
- No servers, no accounts, no tracking
- Messages never touch the internet
- Phone numbers and passphrases never leave your device
- Rendezvous tokens rotate weekly and cannot be reversed
- Duress PIN opens a decoy database if you are coerced

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE) for details.
