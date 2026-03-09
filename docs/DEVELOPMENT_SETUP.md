# Flare — Development Setup

## Prerequisites

### Rust Core
- Rust toolchain: `rustup` (https://rustup.rs)
- Android targets: `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android`
- iOS targets: `rustup target add aarch64-apple-ios x86_64-apple-ios aarch64-apple-ios-sim`
- Android NDK (for cross-compilation)

### Android
- Android Studio (latest stable)
- JDK 17+
- Android SDK API 35+
- Min SDK: API 26 (Android 8.0) — required for BLE features and Wi-Fi Aware

### iOS
- Xcode 15+ (macOS only)
- iOS 14.0+ deployment target
- Physical device required for BLE testing (simulators don't support Bluetooth)

### Python (tooling)
- Python 3.10+
- venv at project root: `python3 -m venv venv`

## Project Structure
```
Flare/
├── flare-core/              # Rust shared library
│   ├── src/
│   │   ├── crypto/          # Ed25519, X25519, AES-256-GCM, HKDF
│   │   ├── routing/         # Spray-and-Wait router, Bloom filter dedup
│   │   ├── storage/         # SQLCipher encrypted database
│   │   ├── protocol/        # Message wire format and serialization
│   │   ├── transport/       # BLE chunking/reassembly
│   │   └── ffi.rs           # UniFFI bindings (FlareNode entry point)
│   ├── uniffi-bindgen.rs    # UniFFI bindgen binary
│   └── Cargo.toml
├── android/                 # Android app (Kotlin + Jetpack Compose)
│   └── app/src/main/
│       └── java/
│           ├── com/flare/mesh/
│           │   ├── ble/         # BLE Scanner, GATT Server, GATT Client
│           │   ├── data/
│           │   │   ├── model/   # Data classes (DeviceIdentity, Contact, etc.)
│           │   │   └── repository/ # FlareRepository (UniFFI bridge)
│           │   ├── service/     # MeshService (foreground service)
│           │   ├── ui/          # Compose screens and navigation
│           │   ├── viewmodel/   # ChatViewModel, ContactsViewModel, NetworkViewModel
│           │   └── util/        # Constants
│           └── uniffi/flare_core/ # Auto-generated Kotlin bindings
├── docs/                    # Project documentation
├── venv/                    # Python virtual environment (gitignored)
└── FEASIBILITY_AND_ARCHITECTURE.md
```

## Building

### Rust Core
```bash
cd flare-core
cargo build
cargo test      # 57 tests
```

### Generate Kotlin Bindings
```bash
cd flare-core
cargo run --bin uniffi-bindgen generate \
  --library target/debug/libflare_core.dylib \
  --language kotlin \
  --out-dir ../android/app/src/main/java
```

### Cross-Compile for Android
```bash
# Requires Android NDK — set ANDROID_NDK_HOME
cargo build --target aarch64-linux-android --release
cargo build --target armv7-linux-androideabi --release
cargo build --target x86_64-linux-android --release
```

### Android App
```bash
cd android
./gradlew assembleDebug
```

### iOS (future)
```bash
cd ios
xcodebuild -scheme Flare -sdk iphoneos build
```

## Architecture Flow

```
User Input → ChatViewModel → FlareRepository → FlareNode (Rust/UniFFI)
                                                    ↓
BLE ← MeshService ← outbound queue ← encrypt + build mesh message
  ↓
Peer GATT → incoming data → MeshService → FlareNode.routeIncoming()
                                              ↓
                              DeliverLocally / Forward / Store / Drop
```
