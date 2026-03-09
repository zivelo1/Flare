# MeshLink: Decentralized Mesh Messaging Application
## Feasibility Assessment & Architecture Document

**Author:** Systems Architect
**Date:** March 9, 2026
**Version:** 1.0
**Classification:** Open Source — Public

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Problem Statement](#2-problem-statement)
3. [Feasibility Assessment — Honest Verdict](#3-feasibility-assessment)
4. [Use Cases](#4-use-cases)
5. [Technical Foundation — What Actually Works](#5-technical-foundation)
6. [Existing Projects — Lessons Learned](#6-existing-projects)
7. [Architecture Design](#7-architecture-design)
8. [Security Architecture](#8-security-architecture)
9. [Cross-Platform Interoperability (Android ↔ iOS)](#9-cross-platform-interoperability)
10. [Offline Distribution Strategy](#10-offline-distribution-strategy)
11. [Implementation Roadmap](#11-implementation-roadmap)
12. [Risk Matrix](#12-risk-matrix)
13. [What This App Cannot Do — Managing Expectations](#13-what-this-app-cannot-do)
14. [Appendix: Technical References](#14-appendix)

---

## 1. Executive Summary

### The Question
Can we build a modern, user-friendly, fully decentralized messaging application that works without internet, cellular networks, or any central infrastructure — using only nearby phones as relay nodes?

### The Answer
**Yes, with significant caveats.**

Text messaging over a phone-based mesh network is **technically feasible** using Bluetooth Low Energy (BLE) and Wi-Fi Direct/Multipeer Connectivity. Several working implementations exist (Briar, Bridgefy, Serval). However, the honest reality involves hard physical and platform constraints that must be communicated to users:

| Capability | Verdict | Constraint |
|---|---|---|
| Text messaging (nearby) | ✅ Feasible | 10-30m BLE range in urban settings |
| Text messaging (multi-hop relay) | ✅ Feasible with delays | Requires chain of phones; minutes to hours delivery |
| Store-and-forward messaging | ✅ Feasible | Delivery not guaranteed; depends on physical movement |
| Voice messages (async) | ✅ Feasible | Compressed audio, store-and-forward delivery |
| Real-time voice calls | ❌ Not feasible over mesh | BLE throughput < 1.5 kbps at 6 hops |
| Real-time voice (direct, 1-hop) | ⚠️ Marginal | Codec2 at 1200bps, walkie-talkie style only |
| iOS background mesh operation | ⚠️ Severely limited | 10-second background windows; reduced BLE scanning |
| Large mesh (1000+ nodes) | ❌ Not feasible over BLE | Flooding storms, packet collisions |
| Mesh in sparse/rural areas | ⚠️ Unreliable | Network partitions when phones too far apart |
| Cross-platform Android ↔ iOS | ✅ Feasible over BLE | BLE GATT is the common protocol |
| End-to-end encryption | ✅ Feasible | Signal Protocol adaptable for DTN |
| Offline app distribution | ✅ Feasible on Android; ⚠️ Hard on iOS | APK sideloading vs iOS restrictions |

### Recommendation
**Proceed with development**, focusing on text messaging and async voice messages as the core features. Design for the constraints rather than against them. The application fills a real gap — no existing solution combines modern UX, full decentralization, strong encryption, cross-platform support, and offline distribution.

---

## 2. Problem Statement

### Primary Scenarios

**Internet Shutdowns by Authoritarian Regimes**
- Iran (2019, 2022, 2026): Complete internet blackouts — in 2026, 85-92 million citizens cut off from all internet, calls, and SMS
- Myanmar (2021-present): Prolonged shutdowns and throttling
- Sudan (2019, 2023): Shutdowns during military conflicts
- 283 internet shutdowns documented globally in 2024 alone (Access Now reports)

**Natural Disasters and Infrastructure Collapse**
- Earthquakes destroying cell towers
- Hurricanes/typhoons flooding telecom infrastructure
- Wildfires burning fiber routes
- Any event where cellular/internet is down but people still have charged phones

### Core Requirements
1. **Zero infrastructure dependency** — no servers, no internet, no cell towers
2. **Modern UX** — must look and feel like Signal/WhatsApp, not a terminal
3. **End-to-end encryption** — messages unreadable by any intermediary
4. **Cross-platform** — Android and iPhone must interoperate seamlessly
5. **Offline installable** — distributable without internet access
6. **Open source** — auditable, trustworthy, community-maintained

---

## 3. Feasibility Assessment

### 3.1 The Physics — What Radio Can and Cannot Do

**Bluetooth Low Energy (BLE) — The Primary Transport**

| Parameter | Specification | Real-World Urban |
|---|---|---|
| Theoretical range | Up to 300m (BT 5.0) | 10-30m through walls/crowds |
| Indoor range | 50-100m (open) | 5-15m through concrete |
| Data rate (spec) | 2 Mbps (BT 5.0) | ~236 kbps effective |
| Mesh throughput (6 hops) | — | < 1.5 kbps |
| Power consumption | ~6.6% additional drain/day | 10-20% with active scanning |

**Wi-Fi Direct (Android) / Multipeer Connectivity (iOS)**

| Parameter | Android Wi-Fi Direct | iOS Multipeer Connectivity |
|---|---|---|
| Range | 50-200m | 30-100m |
| Throughput | Up to 250 Mbps | Up to 250 Mbps |
| Multi-hop | Not natively; requires app-level relay | Not natively |
| Background operation | Possible with foreground service | Limited; connections drop in background |
| Cross-platform | ❌ Not compatible with iOS | ❌ Not compatible with Android |

**Critical insight:** Wi-Fi Direct (Android) and Multipeer Connectivity (iOS) are **not interoperable**. They use different discovery and connection protocols. The ONLY cross-platform wireless protocol available on both platforms is **BLE GATT**. This is the foundation of cross-platform mesh.

### 3.2 Platform Constraints — The iOS Problem

iOS is the harder platform. Apple severely restricts background Bluetooth operations:

| iOS Constraint | Impact |
|---|---|
| Background task window: ~10 seconds | Cannot maintain continuous mesh participation |
| Extended background task: ~15 minutes max | App suspended after timeout |
| Background BLE scanning: filtered only | Only sees specific service UUIDs; reduced frequency |
| Background BLE advertising: reduced | Advertising interval increased; some ad types suppressed |
| Two backgrounded apps exchanging BLE | Only works if receiver's screen is ON |
| State restoration (CoreBluetooth) | Event-driven reactivation possible but not continuous |
| System can terminate background apps | All connections lost unpredictably |

**What this means in practice:** An iOS device is a **partial mesh participant** when the app is in the background. It can respond to BLE events but cannot actively scan or relay messages reliably. The mesh is strongest when iOS users have the app in the foreground.

**Android is significantly better:** Foreground services can maintain continuous BLE scanning and advertising. Wi-Fi Direct groups can persist. Battery optimization can be managed by the user.

### 3.3 Cross-Platform Interoperability — The BLE Bridge

Despite Wi-Fi Direct and Multipeer Connectivity being incompatible, **BLE GATT is the universal bridge**:

```
┌──────────────┐     BLE GATT      ┌──────────────┐
│   Android    │◄──────────────────►│    iPhone     │
│              │   (Cross-platform) │              │
│  Wi-Fi Direct│                    │  Multipeer   │
│  (Android ↔  │                    │  Connectivity│
│   Android)   │                    │  (iOS ↔ iOS) │
└──────────────┘                    └──────────────┘
```

- **Android ↔ Android**: Can use Wi-Fi Direct (high bandwidth) OR BLE
- **iOS ↔ iOS**: Can use Multipeer Connectivity (high bandwidth) OR BLE
- **Android ↔ iOS**: BLE GATT only (lower bandwidth, but it works)

The application must abstract the transport layer so that the routing/messaging layer doesn't care which physical transport is in use. Messages are messages regardless of whether they travel over BLE, Wi-Fi Direct, or Multipeer Connectivity.

---

### 3.4 Real-World Deployment Evidence

| Event | App Used | Outcome | Lesson |
|---|---|---|---|
| Hong Kong 2019 | Bridgefy | 60,000+ installs in one week | Proved demand — but security was catastrophically broken |
| Iran 2022 (Mahsa Amini) | Briar | Lifeline for activists | Bluetooth mesh worked in dense gatherings; Android-only limited reach |
| Iran 2026 (current) | Briar + others | 85-92M cut off; Briar documented as communication tool | Validates the entire premise of this project |
| Myanmar 2021-present | Various | Prolonged shutdowns | Need for pre-installed, offline-distributable apps confirmed |

**Critical lesson from Hong Kong:** Bridgefy was marketed as secure but had zero encryption. Users thought they were protected while authorities could read every message. **This is why our security architecture uses battle-tested Signal Protocol and must undergo independent audit before launch.**

---

## 4. Use Cases

### 4.1 Authoritarian Internet Shutdown
- Citizens coordinate during protests
- Journalists transmit reports
- Families communicate during crises
- Human rights documentation and evidence sharing

### 4.2 Natural Disaster / Emergency
- Survivors communicate when cell towers are down
- Search and rescue coordination
- Emergency broadcasts to nearby devices
- Medical information relay

### 4.3 Remote / Off-Grid Areas
- Rural communities with no cell coverage
- Hiking/camping groups
- Maritime/island communities
- Music festivals or large events where cell networks are congested

---

## 5. Technical Foundation

### 5.1 Transport Layer Technologies

#### BLE GATT (Primary — Cross-Platform)
Bluetooth Low Energy using Generic Attribute Profile (GATT) is the only wireless protocol available on both Android and iOS that allows third-party app-level data exchange.

**How it works for mesh:**
1. Each device acts as both a **GATT Server** (peripheral) and a **GATT Client** (central)
2. The GATT Server advertises a custom service UUID identifying the mesh network
3. Nearby devices discover each other through BLE scanning
4. Once connected, devices exchange messages through custom GATT characteristics
5. Messages include routing metadata for multi-hop forwarding

**BLE 5.0 Long Range (Coded PHY) — Extended Range Option:**
Some modern phones support BLE 5.0 Coded PHY, which trades throughput for range:
- Indoor: 50-100 meters
- Outdoor line-of-sight: up to 1,300 meters (tested by Nordic Semiconductor)
- Throughput: 125-500 kbps (raw), significantly lower after overhead
- **Caveat:** Not all phones support Coded PHY. The app must detect and negotiate the best available PHY.

**BLE GATT Characteristics for Mesh Messaging:**

| Characteristic | UUID | Purpose |
|---|---|---|
| Message Write | Custom | Incoming messages from peers |
| Message Notify | Custom | Outgoing message notifications |
| Peer Discovery | Custom | Exchange peer routing tables |
| Handshake | Custom | Key exchange initiation |

#### Wi-Fi Aware / NAN (Android-Only — Newer Alternative)
Available on Android 8.0+ (API 26). Purpose-built for mesh networking on smartphones:
- Supports device discovery and direct connections without infrastructure
- Android 13+ adds "instant communication mode" for faster discovery
- Supports up to 5 simultaneous device connections
- Range: ~15m indoor (tested), standard Wi-Fi range outdoor (~100m)
- Throughput: 300+ Mbps (comparable to standard Wi-Fi)
- **Advantage over Wi-Fi Direct:** Designed for mesh scenarios, lower discovery latency
- **Limitation:** Only available on newer Android devices with Wi-Fi Aware hardware

#### Wi-Fi Direct (Android-Only Enhancement)
When two Android devices need to exchange larger payloads (voice messages, files), Wi-Fi Direct provides the bandwidth:
- Discovery via BLE, bulk transfer via Wi-Fi Direct
- Group Owner negotiation handled automatically
- Survives in background via Android foreground service

#### Multipeer Connectivity Framework (iOS-Only Enhancement)
Apple's proprietary framework for iOS-to-iOS communication:
- Uses a combination of Bluetooth, Wi-Fi, and infrastructure Wi-Fi
- Higher bandwidth than BLE GATT alone
- Limited to iOS devices; cannot communicate with Android
- Drops connections when app is backgrounded (can be partially mitigated)

### 5.2 Delay-Tolerant Networking (DTN)

Traditional internet protocols (TCP/IP) assume an end-to-end path exists. In a mesh of phones, this assumption fails. DTN solves this:

**Store-Carry-Forward Paradigm:**
```
Alice's Phone → [no path to Bob] → stores message locally
    ↓
Alice walks near Carol → message forwarded to Carol
    ↓
Carol walks near Bob → message delivered to Bob
```

**Routing Strategy: Hybrid AODV + Spray-and-Wait**

1. **When mesh is connected** (enough nearby nodes): Use AODV (Ad-hoc On-Demand Distance Vector)
   - Discovers routes only when needed (saves battery)
   - Good performance under intermittent connectivity
   - Lower overhead than proactive protocols

2. **When mesh is fragmented** (sparse nodes): Use Spray-and-Wait
   - Create L copies of the message (e.g., L=6)
   - "Spray" copies to the first L/2 encountered nodes
   - Each holder "waits" until they encounter the destination
   - Bounded overhead (exactly L copies), good delivery ratio

**Message Deduplication:**
- Each message has a globally unique ID: `SHA-256(sender_pubkey + timestamp + sequence_number)`
- Each node maintains a Bloom filter of recently seen message IDs
- Bloom filter is memory-efficient (~1.2 bytes per entry at 1% false positive rate)
- Messages seen before are not re-forwarded

### 5.3 Message Structure

```
MeshMessage {
    version:        uint8           // Protocol version
    message_id:     bytes[32]       // SHA-256 unique ID
    sender_id:      bytes[32]       // Sender's public key fingerprint
    recipient_id:   bytes[32]       // Recipient's public key fingerprint (or broadcast)
    hop_count:      uint8           // Current hop count
    max_hops:       uint8           // Maximum allowed hops (default: 10)
    ttl:            uint32          // Time-to-live in seconds
    created_at:     uint64          // Unix timestamp (milliseconds)
    content_type:   uint8           // 0x01=text, 0x02=voice, 0x03=image, 0x04=key_exchange
    payload_size:   uint16          // Encrypted payload size in bytes
    payload:        bytes[]         // Encrypted content (Signal Protocol ciphertext)
    signature:      bytes[64]       // Ed25519 signature over all above fields
}
```

Total overhead per message: ~137 bytes + payload. For a typical text message (200 chars UTF-8), total size is ~337 bytes — well within a single BLE GATT write (max 512 bytes per write with MTU negotiation).

---

## 6. Existing Projects — Lessons Learned

### 6.1 Briar Messenger (briarproject.org)
**Status:** Active, mature, Android-only
**Architecture:** Tor for internet, Bluetooth/Wi-Fi for local mesh
**Encryption:** Custom protocol based on Bramble transport
**Lessons:**
- ✅ Designed specifically for activists and journalists
- ✅ Strong security model; peer-reviewed
- ✅ Works without internet via Bluetooth and Wi-Fi
- ❌ Android only — no iOS version (cited as "extremely difficult" due to iOS background BLE limits)
- ❌ Terminal-like UX; not user-friendly for average person
- ❌ Bluetooth mesh is limited to direct contacts (no multi-hop relay)
- **Key takeaway:** Even the Briar team, with years of experience, chose not to tackle iOS due to background operation constraints. Our approach must accept iOS limitations rather than fight them.

### 6.2 Bridgefy
**Status:** Active (pivoted to SDK/enterprise)
**Security Record:** **Catastrophic failures** documented in 2021 security audit:
- Users could be tracked (user IDs transmitted in plaintext)
- No effective authentication
- No real confidentiality (encryption was broken)
- A single malicious message could crash the entire network
- Was actively marketed to Hong Kong protesters despite these flaws
**Lessons:**
- ❌ Security was marketing, not reality
- ❌ Closed-source during critical period (audited externally)
- **Key takeaway:** Security claims must be backed by open-source code and independent audits. Never ship crypto you haven't had reviewed.

### 6.3 Serval Mesh
**Status:** Largely dormant (last significant activity ~2018)
**Architecture:** Android app creating Wi-Fi mesh
**Lessons:**
- Was ahead of its time but lost momentum
- Relied on Wi-Fi ad-hoc mode (requires root on most Android devices)
- **Key takeaway:** Don't require root access. Use standard APIs only.

### 6.4 Meshtastic
**Status:** Active, thriving community
**Architecture:** LoRa hardware devices with phone companion app
**Range:** 1-10+ km per hop (LoRa)
**Lessons:**
- ✅ Excellent community and documentation
- ✅ Proves mesh messaging works at scale
- ❌ Requires separate hardware (LoRa radio ~$25-35)
- ❌ Very low bandwidth (text only, ~11 kbps max)
- **Key takeaway:** Meshtastic proves the concept works. Our challenge is doing it phone-only without LoRa hardware.

### 6.5 BitChat (July 2025 — Jack Dorsey)
**Status:** New, early stage
**Architecture:** BLE mesh networking, no internet/servers/accounts/phone numbers
**Lessons:**
- ✅ Validates market demand — high-profile founder chose this exact problem
- ✅ BLE-only approach (same as our primary transport)
- ⚠️ Very new; limited real-world testing
- **Key takeaway:** The space is actively attracting serious attention. Our project has more comprehensive goals (multi-transport, voice messages, offline distribution).

### 6.6 Other Notable Projects
- **Jami:** Fully P2P, open source, works on all platforms including iOS. Uses OpenDHT for distributed peer discovery. Can communicate on local network without internet. Worth studying for iOS approach.
- **Meshrabiya (UstadMobile):** Virtual mesh network for Android over WiFi. Open source. Useful reference implementation.

### 6.7 Comparative Analysis

| Feature | Briar | Bridgefy | Serval | Meshtastic | **MeshLink (Ours)** |
|---|---|---|---|---|---|
| Android | ✅ | ✅ | ✅ | ✅ (companion) | ✅ |
| iOS | ❌ | ✅ | ❌ | ✅ (companion) | ✅ (with caveats) |
| Cross-platform mesh | N/A | ❌ (broken) | ❌ | ✅ (via LoRa) | ✅ (via BLE) |
| Multi-hop relay | ❌ | ✅ | ✅ | ✅ | ✅ |
| No extra hardware | ✅ | ✅ | ✅ | ❌ | ✅ |
| Strong encryption | ✅ | ❌ | ✅ | ⚠️ (AES-128) | ✅ (Signal Protocol) |
| Modern UX | ❌ | ✅ | ❌ | ❌ | ✅ |
| Open source | ✅ | ❌ | ✅ | ✅ | ✅ |
| Offline installable | ✅ (APK) | ❌ | ✅ (APK) | ✅ (APK) | ✅ |

---

## 7. Architecture Design

### 7.1 High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    USER INTERFACE                         │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────────┐  │
│  │   Chat UI    │ │  Contacts    │ │  Network Status  │  │
│  │  (Messages,  │ │  (Discovery, │ │  (Peer count,    │  │
│  │   Groups)    │ │   QR keys)   │ │   mesh health)   │  │
│  └──────┬──────┘ └──────┬───────┘ └────────┬─────────┘  │
├─────────┴───────────────┴──────────────────┴─────────────┤
│                  APPLICATION LAYER                        │
│  ┌──────────────────────────────────────────────────┐    │
│  │  Message Manager (compose, queue, deliver, ack)   │    │
│  │  Contact Manager (identity, trust, key exchange)  │    │
│  │  Group Manager (membership, broadcast, sync)      │    │
│  └──────────────────────┬───────────────────────────┘    │
├─────────────────────────┴────────────────────────────────┤
│                   CRYPTO LAYER                            │
│  ┌──────────────────────────────────────────────────┐    │
│  │  Signal Protocol (X3DH + Double Ratchet)          │    │
│  │  Ed25519 Signatures │ X25519 Key Agreement        │    │
│  │  AES-256-GCM Payload Encryption                   │    │
│  │  Noise Protocol Framework (transport encryption)  │    │
│  └──────────────────────┬───────────────────────────┘    │
├─────────────────────────┴────────────────────────────────┤
│                  ROUTING LAYER                            │
│  ┌──────────────────────────────────────────────────┐    │
│  │  Hybrid Router (AODV + Spray-and-Wait)            │    │
│  │  Message Store (pending delivery, TTL mgmt)       │    │
│  │  Deduplication Engine (Bloom filter)              │    │
│  │  Peer Table (nearby nodes, link quality)          │    │
│  └──────────────────────┬───────────────────────────┘    │
├─────────────────────────┴────────────────────────────────┤
│                 TRANSPORT LAYER                           │
│  ┌────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ │
│  │  BLE GATT   │ │  Wi-Fi Direct│ │  Wi-Fi Aware │ │  Multipeer   │ │
│  │  (Android + │ │  (Android    │ │  (Android 8+ │ │  Connectivity│ │
│  │   iOS)      │ │   only)      │ │   only)      │ │  (iOS only)  │ │
│  │  [PRIMARY]  │ │  [ENHANCED]  │ │  [ENHANCED]  │ │  [ENHANCED]  │ │
│  └────────────┘ └──────────────┘ └──────────────┘ └──────────────┘ │
│  ┌──────────────────────────────────────────────────┐    │
│  │  Transport Abstraction Layer                      │    │
│  │  (Unified interface regardless of physical link)  │    │
│  └──────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

### 7.2 Transport Abstraction Layer

The critical architectural decision: **the routing and messaging layers must be transport-agnostic.** A message doesn't care if it travels over BLE, Wi-Fi Direct, or Multipeer Connectivity.

```
Interface: TransportProvider {
    // Discovery
    startDiscovery() → Stream<PeerInfo>
    stopDiscovery()

    // Connection
    connectToPeer(peerId: PeerIdentity) → Connection
    acceptConnections() → Stream<Connection>

    // Data
    sendData(connection: Connection, data: bytes) → Result
    receiveData(connection: Connection) → Stream<bytes>

    // Metadata
    getTransportType() → TransportType  // BLE, WIFI_DIRECT, MULTIPEER
    getMaxPayloadSize() → int
    getEstimatedBandwidth() → int
    isAvailable() → bool
}
```

**Transport Selection Priority:**
1. **Same-platform high-bandwidth** (Wi-Fi Direct or Multipeer): For large payloads between same-OS devices
2. **BLE GATT**: For cross-platform communication and text messages
3. **Multiple simultaneous**: A node can use BLE + Wi-Fi Direct simultaneously

### 7.3 Peer Discovery and Identity

**Discovery Flow:**
```
1. Device generates identity on first launch:
   - Ed25519 signing keypair (permanent identity)
   - X25519 key agreement keypair (for Signal Protocol)
   - Device ID = SHA-256(public_signing_key)[0:16] (truncated to 16 bytes)

2. BLE Advertising:
   - Advertise custom service UUID: "MeshLink-v1"
   - Advertisement data includes: protocol version + device_id[0:4] (first 4 bytes)
   - Full identity exchanged after connection

3. Peer Connection:
   - Central scans for "MeshLink-v1" service UUID
   - Connects to discovered peripheral
   - Exchanges full public keys via GATT characteristics
   - Establishes encrypted transport using Noise_XX pattern
   - Exchanges routing tables (known peers and distances)

4. Contact Verification:
   - Users verify contacts via QR code scan (in person)
   - QR contains: public_signing_key + public_agreement_key
   - Verified contacts marked as "trusted" (green checkmark)
   - Unverified contacts can still message but shown as "unverified"
```

### 7.4 Message Routing

**AODV Route Discovery (when mesh is connected):**
```
Alice wants to send to Bob (6 hops away):

1. Alice broadcasts ROUTE_REQUEST(destination=Bob_ID, sequence=1, hop_count=0)
2. Each intermediate node:
   - Checks if it knows a route to Bob
   - If yes: sends ROUTE_REPLY back to Alice with the path
   - If no: increments hop_count, rebroadcasts ROUTE_REQUEST
3. Bob receives ROUTE_REQUEST, sends ROUTE_REPLY back along reverse path
4. Alice now has a route: Alice → C → E → G → J → M → Bob
5. Messages flow along this route until it breaks
6. If route breaks: ROUTE_ERROR propagated, new discovery initiated
```

**Spray-and-Wait (when mesh is fragmented):**
```
Alice wants to send to Bob (no connected path):

SPRAY PHASE:
1. Alice creates L=8 copies of the message
2. Alice encounters Carol → gives Carol 4 copies, keeps 4
3. Alice encounters Dave → gives Dave 2 copies, keeps 2
4. Carol encounters Eve → gives Eve 2 copies, keeps 2

WAIT PHASE:
5. Each holder (Alice, Carol, Dave, Eve) with 1 copy waits
6. When any holder encounters Bob → delivers message
7. Bob sends ACK, which propagates back to delete remaining copies
```

**Hop Limit:** Maximum 10 hops (configurable). At 20m average BLE range, this covers ~200m radius. In dense areas with many phones, effective coverage extends as nodes relay through paths.

### 7.5 Data Storage Architecture

**Local Database (per device):**
```
SQLCipher (encrypted SQLite)
├── identities          // Own keypairs, contact public keys
├── contacts            // Name, device_id, trust_level, last_seen
├── conversations       // Thread metadata
├── messages            // Decrypted messages for user's own conversations
├── pending_outbox      // Messages awaiting delivery (encrypted)
├── relay_store         // Messages being relayed for others (encrypted, TTL-managed)
├── routing_table       // Known peers, distances, last_updated
├── seen_messages       // Bloom filter data for deduplication
└── key_sessions        // Signal Protocol session state (ratchet state)
```

**All data encrypted at rest** using SQLCipher with a key derived from user's passphrase via Argon2id.

### 7.6 Network Visualization

What a real-world mesh might look like during an Iranian internet shutdown in Tehran:

```
Urban density: ~12,000 people/km² in central Tehran
Assume 2% mesh app adoption = 240 devices/km²
Average BLE range in urban: 15m
Coverage area per device: ~707m² (π × 15²)

At 240 devices/km² (1,000,000m²):
- Average distance between mesh nodes: ~64m
- This is GREATER than BLE range (15m)
- The mesh would be FRAGMENTED at 2% adoption

Required for connected mesh at 15m BLE range:
- ~1,415 devices/km² = ~0.6% of population
- In practice: need ~5-10% adoption in a neighborhood
  for reliable connected mesh

REALISTIC SCENARIO: mesh works well in:
- Protest gatherings (hundreds of phones in close proximity)
- University campuses
- Apartment buildings
- Bazaars/marketplaces
Mesh struggles in:
- Spread-out residential areas
- Between neighborhoods (store-carry-forward needed)
- Rural areas
```

---

## 8. Security Architecture

### 8.1 Threat Model

**Adversary capabilities (state-level):**
- Can seize devices at checkpoints
- Can deploy BLE/Wi-Fi scanners in public areas
- Can deploy IMSI catchers (but these target cellular, not BLE)
- Can compel individuals to unlock devices
- Can monitor electromagnetic emissions passively
- Cannot break modern cryptography (AES-256, Ed25519, X25519)

**What we protect against:**
| Threat | Mitigation |
|---|---|
| Message interception | End-to-end encryption (Signal Protocol) |
| Message tampering | Ed25519 signatures on every message |
| Traffic analysis (who talks to whom) | Onion-style header encryption for relay messages |
| Device seizure — message content | SQLCipher encryption, Argon2id key derivation |
| Device seizure — social graph | Duress PIN shows decoy data; timer-based auto-wipe |
| BLE scanning for app detection | Rotating BLE MAC (automatic on modern OS); generic service name |
| Relay node reading messages | Relay nodes see only encrypted blobs; cannot read content |
| Impersonation | Contact verification via QR code; signed messages |
| Sybil attack (fake nodes) | Proof-of-proximity challenge; reputation scoring |
| Network-wide DoS | Hop limits, TTL, rate limiting per peer |

### 8.2 Cryptographic Protocols

**Identity Keys (generated once, on device):**
- **Signing:** Ed25519 (256-bit) — signs all messages, proves identity
- **Key Agreement:** X25519 (Curve25519) — Diffie-Hellman for Signal Protocol

**Signal Protocol Adaptation for DTN:**

The standard Signal Protocol uses X3DH (Extended Triple Diffie-Hellman) for initial key exchange, which normally requires a server to host "prekey bundles." In our serverless design:

```
Standard Signal:
  Alice → Server: "Give me Bob's prekey bundle"
  Server → Alice: {Bob's identity key, signed prekey, one-time prekey}
  Alice computes shared secret, sends first message

MeshLink Adaptation:
  Option A — In-person exchange (most secure):
    Alice and Bob meet → scan each other's QR codes
    QR contains: identity_key + signed_prekey + one-time_prekeys[0..5]
    Both parties have full prekey bundle; session established immediately

  Option B — Mesh-relayed key exchange (less secure, more convenient):
    Alice broadcasts: KEY_REQUEST(recipient=Bob_ID)
    Request propagates through mesh (encrypted to Bob's identity key)
    Bob receives request, sends back: PREKEY_BUNDLE via mesh
    Alice establishes session
    RISK: First message vulnerable to MITM until QR verification
    MITIGATION: "Safety number" displayed; users should verify in person
```

**ASMesh Protocol (2023 — Academic Research):**
IACR ePrint 2023/1053 specifically addresses the Signal Protocol in mesh networks. It proposes "Anonymous and Secure Messaging in Mesh Networks" using a stronger, anonymous Double Ratchet that accounts for delayed communication and distributed adversaries. This is the most relevant academic work for our design and should be studied during implementation.

**Double Ratchet (per-message forward secrecy):**
- Every message uses a new symmetric key
- Compromising one message key does not reveal past or future messages
- Ratchet state stored in SQLCipher database
- If device seized, only messages already decrypted in the database are exposed; future messages using new ratchet keys remain safe

**Transport Encryption (Noise Protocol):**
- Every BLE/Wi-Fi Direct link encrypted using Noise_XX handshake
- Provides confidentiality between adjacent nodes (relay nodes cannot read headers of messages not addressed to them)
- This is in ADDITION to end-to-end Signal Protocol encryption

### 8.3 Plausible Deniability Features

**Duress Mode:**
- User sets a "duress PIN" in addition to their normal PIN
- Entering duress PIN:
  - Shows a fake, innocuous message history
  - Real data remains hidden in an encrypted partition
  - Optionally triggers silent alert to trusted contacts ("I've been compromised")
- App appears identical in both modes; no way to detect hidden partition without the real PIN

**Disappearing Messages:**
- Timer-based auto-delete (configurable: 1 hour, 1 day, 1 week)
- Secure deletion: overwrite message data before removing database entry
- Option to auto-wipe entire app after N failed PIN attempts

**Camouflage Mode:**
- App icon and name can be changed to look like a calculator, weather app, etc.
- Launching the camouflage app shows a functional calculator/weather app
- Entering a specific sequence reveals the real messaging app

### 8.4 Anti-Surveillance Measures

**BLE MAC Rotation:**
- Modern Android (8+) and iOS automatically rotate BLE MAC addresses
- Advertising address changes periodically; tracking via MAC is mitigated by the OS
- Application-level identity uses cryptographic keys, not hardware identifiers

**Metadata Minimization:**
- Relay messages carry only: encrypted blob + message_id + hop_count + TTL
- Relay nodes do NOT see: sender, recipient, content type, or timestamps
- Sender/recipient IDs encrypted inside the payload using the Noise transport key

**Traffic Padding:**
- Optional: generate dummy encrypted messages at random intervals
- Makes it harder for a passive observer to determine when real communication occurs
- Configurable based on battery trade-off

---

## 9. Cross-Platform Interoperability (Android ↔ iOS)

### 9.1 The Interoperability Challenge

Android and iOS have fundamentally different Bluetooth and Wi-Fi mesh capabilities. The application **must not care** which platform a peer is running on. A message from an Android phone must reach an iPhone seamlessly and vice versa.

### 9.2 Solution: BLE GATT as Universal Protocol

**Custom GATT Service Definition:**

```
Service: MeshLink Messaging Service
UUID: 7A8B0001-XXXX-XXXX-XXXX-XXXXXXXXXXXX  (registered custom UUID)

Characteristics:
  1. Message Write (Write Without Response)
     UUID: 7A8B0002-...
     Client writes message chunks to this characteristic

  2. Message Notify (Notify)
     UUID: 7A8B0003-...
     Server notifies client of incoming messages

  3. Peer Info (Read)
     UUID: 7A8B0004-...
     Returns: protocol_version, device_id, platform (Android/iOS), capabilities

  4. MTU Exchange (Read)
     UUID: 7A8B0005-...
     Returns: maximum supported payload size
```

**Why this works cross-platform:**
- BLE GATT is a standard Bluetooth protocol supported identically on both platforms
- Both Android (BluetoothGattServer/BluetoothGattClient) and iOS (CBPeripheralManager/CBCentralManager) can implement the same GATT service
- The service UUID is the same on both platforms — devices discover each other regardless of OS
- Data format is platform-independent (binary protocol defined above)

### 9.3 Simultaneous Role Operation

Each device operates as BOTH a BLE Central and Peripheral simultaneously:

```
┌──────────────────────────────────┐
│           Device A               │
│  ┌────────────┐ ┌─────────────┐  │
│  │  Central    │ │ Peripheral  │  │
│  │  (Scanner)  │ │ (Advertiser)│  │
│  │  Connects   │ │ Accepts     │  │
│  │  to others  │ │ connections │  │
│  └────────────┘ └─────────────┘  │
└──────────────────────────────────┘

Android: Both roles supported simultaneously ✅
iOS: Both roles supported simultaneously ✅ (with background caveats)
```

### 9.4 Platform-Specific Transport Upgrades

When two same-platform devices connect via BLE, they can negotiate an upgrade:

```
1. Device A (Android) discovers Device B (Android) via BLE
2. They exchange Peer Info → both are Android
3. Negotiation: "Let's upgrade to Wi-Fi Direct for higher bandwidth"
4. Wi-Fi Direct group formed → bulk data transferred
5. BLE connection maintained for mesh routing

Same process for iOS ↔ iOS with Multipeer Connectivity

Android ↔ iOS: Stays on BLE (no upgrade available)
```

### 9.5 Message Chunking for BLE

BLE has limited MTU (Maximum Transmission Unit). After MTU negotiation:
- Android: typically 247-517 bytes per write
- iOS: typically 185-512 bytes per write

**Chunking Protocol for messages larger than MTU:**
```
Chunk Header (4 bytes):
  message_id_short: uint16  // Short ID for reassembly
  chunk_index:      uint8   // 0-255
  total_chunks:     uint8   // 1-255

Max message size: 255 × (MTU - 4) bytes
  At MTU=247: max ~62 KB per message
  At MTU=512: max ~130 KB per message
```

---

## 10. Offline Distribution Strategy

### 10.1 The Distribution Problem

If the internet is shut down, users cannot download the app from Google Play or the App Store. The app must be distributable phone-to-phone without any internet.

### 10.2 Android Distribution (Straightforward)

**APK Sideloading:**
- Android allows installing apps from APK files outside the Play Store
- User enables "Install from unknown sources" in settings
- APK file can be shared via:
  - **Bluetooth file transfer** (built into Android)
  - **NFC tap** (Android Beam or similar)
  - **Wi-Fi Direct file sharing**
  - **USB cable** (phone-to-phone via OTG)
  - **microSD card**
  - **QR code** linking to a local file server (one phone hosts a Wi-Fi Direct hotspot + HTTP server)

**F-Droid Repository:**
- F-Droid is an alternative app store for open-source Android apps
- F-Droid itself can be distributed offline via APK
- F-Droid supports "nearby" sharing — share apps with nearby devices over Wi-Fi/Bluetooth
- MeshLink published on F-Droid ensures reproducible builds

**Built-in App Sharing:**
The MeshLink app itself includes an "App Sharing" feature:
```
1. User A opens MeshLink → Settings → Share App
2. MeshLink creates a Wi-Fi Direct hotspot + lightweight HTTP server
3. User B connects to the hotspot (shown as QR code or manual SSID)
4. User B's browser opens automatically → downloads APK
5. User B installs the APK
6. Both users now have MeshLink and can join the mesh
```

### 10.3 iOS Distribution (Challenging but Possible)

iOS is significantly more restrictive about app installation:

**Option 1: AltStore / Sideloading (requires a computer)**
- AltStore allows sideloading IPA files on iOS without jailbreak
- Requires a computer (Mac or Windows) with AltServer running
- Apps must be re-signed every 7 days (free Apple ID) or 365 days (paid developer account)
- Not practical for mass distribution in a crisis

**Option 2: Enterprise Certificate Distribution**
- An Apple Enterprise Developer account ($299/year) can distribute apps outside the App Store
- Users visit a URL (can be local, served from a phone's hotspot) to install
- Apple can revoke the certificate remotely — but this requires internet
- During an internet shutdown, revocation checks fail; existing installations continue to work
- **Risk:** If Apple is pressured by the regime, they could revoke the certificate once internet returns

**Option 3: TestFlight (pre-crisis)**
- Distribute via TestFlight before the internet shutdown
- TestFlight apps last 90 days
- Requires internet for initial install but works offline after

**Option 4: Progressive Web App (PWA) with BLE**
- Web Bluetooth API allows BLE from browsers (Chrome on Android, limited on iOS Safari)
- Could serve as a minimal fallback on iOS
- Significant limitations: no background operation, limited BLE functionality on iOS Safari

**Recommended iOS Strategy:**
1. **Primary:** Publish on App Store (available during peacetime)
2. **Pre-crisis:** Distribute via TestFlight to activist networks
3. **During crisis:** Enterprise certificate via local hotspot for new installs
4. **Fallback:** AltStore distribution with instructions (for technical users)

### 10.4 App Integrity Verification

Since sideloaded APKs could be tampered with (malware-injected), the app includes:

```
Verification Chain:
1. APK/IPA is signed with project's release signing key
2. Public key fingerprint published widely (social media, print, QR on posters)
3. App displays its own signing key fingerprint at: Settings → About → Verify Integrity
4. Users compare the fingerprint with the known-good value
5. The app can also verify OTHER copies' integrity via Bluetooth:
   - "Scan to verify" feature: scan another phone's MeshLink app
   - Compares signing key fingerprint
   - Warns if the other phone has a modified/fake version
```

---

## 11. Implementation Roadmap

### 11.1 Technology Stack Decision

**Chosen approach: Native development with shared core**

```
┌─────────────────────────────────────────┐
│         Shared Core (Rust)               │
│  ┌────────────────────────────────────┐  │
│  │  Cryptography (Signal Protocol)     │  │
│  │  Routing Engine (AODV + S&W)        │  │
│  │  Message Store (SQLCipher)          │  │
│  │  Protocol Serialization             │  │
│  │  Deduplication Engine               │  │
│  └────────────────────────────────────┘  │
├─────────────────────────────────────────┤
│    FFI Layer (UniFFI - Mozilla's tool)   │
├──────────────────┬──────────────────────┤
│  Android (Kotlin) │    iOS (Swift)       │
│  ┌──────────────┐ │ ┌────────────────┐  │
│  │ BLE (Android  │ │ │ BLE (Core      │  │
│  │  Bluetooth API)│ │ │  Bluetooth)    │  │
│  │ Wi-Fi Direct  │ │ │ Multipeer      │  │
│  │ UI (Jetpack   │ │ │  Connectivity  │  │
│  │  Compose)     │ │ │ UI (SwiftUI)   │  │
│  │ Foreground    │ │ │ Background     │  │
│  │  Service      │ │ │  Task mgmt     │  │
│  └──────────────┘ │ └────────────────┘  │
└──────────────────┴──────────────────────┘
```

**Why Rust for the core:**
- Memory-safe without garbage collection (critical for crypto code)
- Excellent cross-platform support (compiles to Android NDK and iOS via C ABI)
- Mozilla's UniFFI generates Kotlin and Swift bindings automatically
- libsignal (Signal's official crypto library) is written in Rust
- Single implementation of routing, crypto, and storage — no bugs from reimplementing in two languages

**Why native UI (not Flutter/React Native):**
- BLE and Wi-Fi Direct require deep platform integration
- iOS background BLE requires CoreBluetooth state restoration (not available in Flutter)
- Android foreground services require native service components
- Native UI frameworks (Jetpack Compose / SwiftUI) provide the modern UX needed
- Performance-critical mesh operations benefit from native execution

### 11.2 Phase Plan

#### Phase 1: Foundation (Weeks 1-8)
**Goal:** Two Android phones exchange encrypted text messages over BLE

Deliverables:
- [ ] Rust core library: identity generation (Ed25519 + X25519)
- [ ] Rust core library: message serialization/deserialization
- [ ] Rust core library: Signal Protocol integration (using libsignal-protocol-rust)
- [ ] Rust core library: SQLCipher storage layer
- [ ] Android: BLE GATT service and client implementation
- [ ] Android: Peer discovery and connection management
- [ ] Android: Basic chat UI (Jetpack Compose) — single conversation
- [ ] Android: QR code key exchange for contact verification
- [ ] UniFFI bindings: Rust → Kotlin
- [ ] Integration test: Two Android devices, encrypted chat over BLE

#### Phase 2: Multi-Hop & iOS (Weeks 9-16)
**Goal:** Messages relay through intermediate phones; iOS joins the mesh

Deliverables:
- [ ] Rust core: AODV routing implementation
- [ ] Rust core: Spray-and-Wait fallback routing
- [ ] Rust core: Message deduplication (Bloom filter)
- [ ] Rust core: Store-and-forward message queue with TTL
- [ ] Android: Multi-hop message relay via BLE
- [ ] Android: Wi-Fi Direct transport for Android ↔ Android
- [ ] Android: Foreground service for background mesh operation
- [ ] iOS: CoreBluetooth BLE GATT service and client
- [ ] iOS: Multipeer Connectivity transport for iOS ↔ iOS
- [ ] iOS: Background BLE state restoration
- [ ] iOS: Basic chat UI (SwiftUI)
- [ ] UniFFI bindings: Rust → Swift
- [ ] Integration test: Android ↔ iOS encrypted chat over BLE
- [ ] Integration test: 3-device relay (A → B → C)

#### Phase 3: Full Messaging Features (Weeks 17-22)
**Goal:** Production-quality messaging experience

Deliverables:
- [ ] Group messaging (with shared group key, managed by group creator)
- [ ] Voice messages (Codec2 encoding, store-and-forward)
- [ ] Image messages (compressed, chunked transfer)
- [ ] Disappearing messages (configurable timer)
- [ ] Message delivery receipts (✓ sent to mesh, ✓✓ delivered, blue = read)
- [ ] Typing indicators (when peer is directly connected)
- [ ] Contact list management with trust levels
- [ ] Network status dashboard (peer count, mesh topology visualization)
- [ ] Notification system (Android: standard, iOS: local notifications)

#### Phase 4: Security Hardening & Distribution (Weeks 23-28)
**Goal:** Anti-surveillance features and offline distribution

Deliverables:
- [ ] Duress PIN with decoy data
- [ ] Camouflage mode (alternate app icon/name)
- [ ] Auto-wipe after N failed PINs
- [ ] Traffic padding (configurable dummy messages)
- [ ] Noise Protocol transport encryption between peers
- [ ] Rate limiting and anti-DoS measures
- [ ] Android: Built-in app sharing (Wi-Fi Direct hotspot + HTTP)
- [ ] Android: F-Droid repository configuration
- [ ] iOS: Enterprise certificate distribution setup
- [ ] App integrity verification (signing key fingerprint check)
- [ ] Security audit preparation (documentation for auditors)

#### Phase 5: Optimization & Launch (Weeks 29-34)
**Goal:** Performance optimization, testing, and public release

Deliverables:
- [ ] Battery optimization (adaptive scan intervals based on movement/context)
- [ ] BLE connection pooling and management
- [ ] Large-scale mesh testing (50+ devices simulation)
- [ ] Edge case testing (network partitions, rapid topology changes)
- [ ] Localization (Farsi, Arabic, Burmese, Chinese, Russian, Spanish, French, English)
- [ ] Accessibility compliance
- [ ] Open-source release on GitHub with build instructions
- [ ] Independent security audit (engage external firm)
- [ ] Documentation: user guide, developer guide, security whitepaper

### 11.3 Dependency Map

```
libsignal-protocol-rust  →  Rust Core  →  UniFFI  →  Kotlin Bindings → Android App
                                                   →  Swift Bindings  → iOS App

External Dependencies (all open-source):
├── libsignal-protocol-rust (Signal Foundation, GPLv3)
├── rusqlite + SQLCipher (SQLite encryption, MIT/BSD)
├── snow (Noise Protocol in Rust, Apache-2.0)
├── ed25519-dalek (Ed25519 signatures, BSD)
├── x25519-dalek (X25519 key agreement, BSD)
├── uniffi (Mozilla, MPL-2.0)
├── blake3 (hashing, Apache-2.0/CC0)
├── codec2 (voice codec, LGPL-2.1)
├── zxing (QR code, Apache-2.0)
└── argon2 (key derivation, Apache-2.0/MIT)
```

---

## 12. Risk Matrix

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| iOS background BLE too limited for mesh | HIGH | HIGH | Accept limitation; document clearly; iOS users told "keep app open for best mesh participation"; use state restoration for partial operation |
| Low adoption density breaks mesh | HIGH | HIGH | Store-carry-forward as fallback; clear user expectations; encourage neighborhood-level adoption drives |
| Apple revokes enterprise certificate | MEDIUM | MEDIUM | Pre-install via App Store; TestFlight backup; AltStore instructions |
| State-level attacker deploys BLE scanners | MEDIUM | MEDIUM | Rotating MAC addresses; generic service UUID; traffic padding; camouflage mode |
| Device seizure reveals contacts | MEDIUM | HIGH | Duress PIN; auto-wipe; disappearing messages; verify users understand the risks |
| Bridgefy-style security failure | LOW (if audited) | CRITICAL | Use battle-tested Signal Protocol; open-source everything; fund external audit before launch |
| Battery drain unacceptable for users | MEDIUM | MEDIUM | Adaptive scan intervals; "Battery Saver" mode that reduces mesh participation; clear battery impact in settings |
| BLE throughput too low for voice messages | LOW | LOW | Pre-compress with Codec2; accept multi-minute delivery time for audio; set size limits |
| Protocol bugs in multi-hop routing | MEDIUM | HIGH | Extensive simulation testing; formal verification of routing logic; gradual rollout |
| App blocked by regime in app store | HIGH | LOW | F-Droid, sideloading, and offline sharing are the primary distribution channels anyway |

---

## 13. What This App Cannot Do — Managing Expectations

**It is critical that users understand the limitations.** Overselling capabilities (like Bridgefy did) is dangerous when people's lives depend on it.

### Cannot:
1. **Replace the internet.** This is a local mesh. You can message people within range of the mesh (directly or via relay). You cannot access websites, send emails, or reach someone on another continent.

2. **Guarantee message delivery.** In a mesh network, messages may take minutes, hours, or never arrive — depending on node density and physical movement. This is not WhatsApp; delivery is probabilistic.

3. **Support real-time voice calls over multi-hop mesh.** Bandwidth is too low. Push-to-talk walkie-talkie between directly connected phones (1 hop) is possible. Full phone calls over the mesh are not.

4. **Work in sparse areas without enough phones.** If you're alone in a field, there's no mesh. You need other phones within 10-30 meters, and enough of them to form a chain to your recipient.

5. **Protect against device seizure if you haven't set up protections.** If your phone is seized unlocked, your messages are visible. Users MUST set up a PIN and should enable auto-wipe.

6. **Make iOS a full mesh participant in the background.** When the MeshLink app is in the background on an iPhone, it has very limited ability to relay messages. iPhone users should keep the app open during active use.

7. **Scale to tens of thousands of nodes in a single mesh.** BLE mesh practically supports hundreds of nodes. Larger deployments naturally partition into sub-meshes connected by mobile relay nodes.

### The App's Promise:
- In a crisis, when you have nothing else, MeshLink lets you communicate with people around you — encrypted, private, and without any infrastructure.
- It works best in dense areas: protest crowds, apartment buildings, university campuses, refugee camps, disaster shelters.
- It is honest about its limitations and helps users understand what to expect.

---

## 14. Appendix: Technical References

### Standards and Protocols
- Bluetooth Core Specification v5.3 — bluetooth.com/specifications
- BLE GATT Protocol — bluetooth.com/specifications/gatt
- Signal Protocol — signal.org/docs
- X3DH Key Agreement — signal.org/docs/specifications/x3dh
- Double Ratchet Algorithm — signal.org/docs/specifications/doubleratchet
- Noise Protocol Framework — noiseprotocol.org
- AODV (RFC 3561) — ietf.org/rfc/rfc3561
- Delay-Tolerant Networking Architecture (RFC 4838) — ietf.org/rfc/rfc4838
- Codec2 Voice Codec — codec2.org

### Key Research Papers
- "Breaking Bridgefy" (2021) — Security analysis showing catastrophic failures in Bridgefy's mesh messaging. IACR ePrint 2021/214.
- "Spray and Wait: An Efficient Routing Scheme for Intermittently Connected Mobile Networks" — Spyropoulos et al., ACM SIGCOMM 2005.
- "Performance Analysis of Bluetooth Mesh Networking" — arXiv:2106.04230.
- "AODV, OLSR, DSR Comparison in Mobile Ad-Hoc Networks" — ResearchGate, 2022.

### Open-Source References
- Briar Project — briarproject.org (architecture and security model)
- Meshtastic — meshtastic.org (mesh messaging reference implementation)
- libsignal — github.com/signalapp/libsignal (Signal Protocol in Rust)
- UniFFI — github.com/mozilla/uniffi-rs (Rust → Kotlin/Swift bindings)
- Meshrabiya — github.com/UstadMobile/Meshrabiya (Wi-Fi mesh library for Android)

### Platform Documentation
- Android Bluetooth LE — developer.android.com/develop/connectivity/bluetooth/ble
- Android Wi-Fi Direct — developer.android.com/develop/connectivity/wifi/wifip2p
- iOS Core Bluetooth — developer.apple.com/documentation/corebluetooth
- iOS Multipeer Connectivity — developer.apple.com/documentation/multipeerconnectivity
- iOS Background Execution — developer.apple.com/documentation/backgroundtasks

---

## License

This document and all associated source code are released under the **GNU General Public License v3.0 (GPLv3)**, ensuring:
- Anyone can use, modify, and distribute the software
- All derivative works must also be open source
- No entity can create a proprietary fork

---

*"The internet is not a luxury. It is a lifeline. When that lifeline is cut, people must have alternatives."*
