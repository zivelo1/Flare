# Flare Architecture

## System Overview

```mermaid
graph TB
    subgraph "Your Phone"
        A[Flare App] --> B[Rust Core<br/>Crypto · Routing · Storage]
        B --> C[BLE GATT]
        B --> D[Wi-Fi Direct]
    end

    subgraph "Relay Phone"
        E[Flare App] --> F[Rust Core]
        F --> G[BLE GATT]
        F --> H[Wi-Fi Direct]
    end

    subgraph "Friend's Phone"
        I[Flare App] --> J[Rust Core]
        J --> K[BLE GATT]
        J --> L[Wi-Fi Direct]
    end

    C <-->|"Encrypted Message"| G
    G <-->|"Encrypted Message"| K
    D <-->|"Large Files"| H

    style A fill:#FF6B35,color:#fff
    style E fill:#555,color:#fff
    style I fill:#FF6B35,color:#fff
```

## Message Flow

```mermaid
sequenceDiagram
    participant You
    participant RustCore as Rust Core
    participant BLE as BLE Radio
    participant Relay as Relay Phone
    participant Friend as Friend's Phone

    You->>RustCore: Send "Hello"
    RustCore->>RustCore: X25519 Key Agreement
    RustCore->>RustCore: AES-256-GCM Encrypt
    RustCore->>RustCore: Ed25519 Sign
    RustCore->>RustCore: DEFLATE Compress
    RustCore->>BLE: Chunk for BLE MTU
    BLE->>Relay: BLE GATT Write
    Note over Relay: Store & Forward<br/>(can't read message)
    Relay->>Friend: BLE GATT Write
    Friend->>Friend: Verify Signature
    Friend->>Friend: Decompress
    Friend->>Friend: Decrypt
    Friend-->>You: Delivery ACK
```

## Software Architecture

```mermaid
graph TD
    subgraph "Android (Kotlin + Jetpack Compose)"
        UI[UI Layer<br/>Compose Screens]
        VM[ViewModels<br/>Chat · Contacts · Settings]
        REPO[FlareRepository<br/>Bridge Layer]
        BLE_A[BLE Scanner + GATT]
        WIFI_A[Wi-Fi Direct Manager]
        MESH[MeshService<br/>Foreground Service]
    end

    subgraph "Rust Core (via UniFFI)"
        NODE[FlareNode]
        CRYPTO[Crypto<br/>Ed25519 · X25519<br/>AES-256-GCM · HKDF]
        ROUTER[Router<br/>Spray-and-Wait<br/>Neighborhood Routing]
        STORE[Storage<br/>SQLCipher DB]
        PROTO[Protocol<br/>Wire Format · Chunking]
        RENDEZ[Rendezvous<br/>Blind Discovery]
        POWER[Power Manager<br/>4-Tier Duty Cycling]
    end

    UI --> VM
    VM --> REPO
    REPO --> NODE
    MESH --> BLE_A
    MESH --> WIFI_A
    MESH --> REPO
    NODE --> CRYPTO
    NODE --> ROUTER
    NODE --> STORE
    NODE --> PROTO
    NODE --> RENDEZ
    NODE --> POWER

    style UI fill:#FF6B35,color:#fff
    style NODE fill:#B7410E,color:#fff
    style CRYPTO fill:#8B0000,color:#fff
```

## Mesh Network Topology

```mermaid
graph LR
    A((You)) <-->|BLE| B((Peer))
    A <-->|BLE| C((Peer))
    B <-->|BLE| D((Peer))
    B <-->|BLE| E((Peer))
    C <-->|BLE| F((Peer))
    D <-->|BLE| G((Friend))
    E <-->|BLE| G
    F <-->|Wi-Fi| H((Peer))
    H <-->|BLE| G

    style A fill:#FF6B35,color:#fff,stroke:#FF6B35
    style G fill:#FF6B35,color:#fff,stroke:#FF6B35
    style B fill:#555,color:#fff
    style C fill:#555,color:#fff
    style D fill:#555,color:#fff
    style E fill:#555,color:#fff
    style F fill:#555,color:#fff
    style H fill:#555,color:#fff
```

## Contact Discovery (Blind Rendezvous)

```mermaid
flowchart TD
    START[Want to find a friend] --> CHOOSE{Choose Method}

    CHOOSE -->|Most Secure| PHRASE[Shared Phrase]
    CHOOSE -->|Remote| LINK[Identity Link]
    CHOOSE -->|In Person| QR[QR Code Scan]
    CHOOSE -->|Convenient| PHONE[Phone Number]

    PHRASE --> |"Both enter same phrase"| ARGON[Argon2id Hash<br/>+ Epoch Salt]
    ARGON --> TOKEN[Rendezvous Token]
    TOKEN --> POW[Proof of Work<br/>16-bit leading zeros]
    POW --> BROADCAST[Broadcast on Mesh]
    BROADCAST --> MATCH[Token Match!]
    MATCH --> ECDH[X25519 Key Exchange]
    ECDH --> CONTACT[Secure Contact Added]

    LINK --> SHARE[Share via SMS/WhatsApp]
    SHARE --> TAP[Friend taps link]
    TAP --> CONTACT

    QR --> SCAN[Scan QR Code]
    SCAN --> VERIFIED[Verified Contact Added]

    PHONE --> BILATERAL[Bilateral Hash<br/>sort + Argon2id]
    BILATERAL --> TOKEN

    style START fill:#FF6B35,color:#fff
    style CONTACT fill:#2E8B57,color:#fff
    style VERIFIED fill:#2E8B57,color:#fff
```

## Security Model

```mermaid
graph TD
    subgraph "Encryption Stack"
        MSG[Plaintext Message]
        MSG --> COMPRESS[DEFLATE Compress]
        COMPRESS --> ENCRYPT[AES-256-GCM Encrypt<br/>Per-message key via HKDF]
        ENCRYPT --> SIGN[Ed25519 Sign]
        SIGN --> WIRE[Wire Format<br/>+ Hop Count + TTL]
    end

    subgraph "Key Management"
        ID[Device Identity<br/>Ed25519 + X25519]
        DH[X25519 DH Agreement]
        HKDF[HKDF-SHA256<br/>Key Derivation]
        ID --> DH
        DH --> HKDF
        HKDF --> ENCRYPT
    end

    subgraph "Data at Rest"
        DB[(SQLCipher DB)]
        KS[Android Keystore<br/>Hardware-Backed Key]
        ARGON[Argon2id KDF]
        KS --> ARGON
        ARGON --> DB
    end

    subgraph "Duress Protection"
        DURESS[Duress PIN] --> FAKE[(Decoy Database<br/>Fake Messages)]
        REAL[Real PIN] --> DB
    end

    style MSG fill:#FF6B35,color:#fff
    style ENCRYPT fill:#8B0000,color:#fff
    style DB fill:#2E4053,color:#fff
    style FAKE fill:#922B21,color:#fff
```

## Power Management Tiers

```mermaid
stateDiagram-v2
    [*] --> Balanced: App Start

    High: High Performance<br/>Near-continuous scan
    Balanced: Balanced<br/>25% duty cycle
    LowPower: Low Power<br/>Burst mode ~17%
    UltraLow: Ultra Low<br/>Minimal ~5%

    Balanced --> High: Active data exchange
    High --> Balanced: Exchange complete
    Balanced --> LowPower: No peers for 60s
    LowPower --> Balanced: Peer discovered
    LowPower --> UltraLow: Battery < 15%
    UltraLow --> LowPower: Battery > 20%
    Balanced --> UltraLow: Battery saver ON

    note right of High: Max 30s duration
    note right of UltraLow: Scan 3s every 60s
```
