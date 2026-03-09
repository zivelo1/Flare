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
- Android SDK API 34+
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
├── flare-core/          # Rust shared library
│   ├── src/
│   │   ├── crypto/      # Signal Protocol, key generation, signatures
│   │   ├── routing/     # Spray-and-Wait, AODV, deduplication
│   │   ├── storage/     # SQLCipher database layer
│   │   ├── protocol/    # Message serialization, wire format
│   │   └── transport/   # Transport abstraction layer
│   ├── tests/           # Integration tests
│   └── Cargo.toml
├── android/             # Android app (Kotlin + Jetpack Compose)
│   └── app/src/main/
│       └── java/com/flare/mesh/
│           ├── ble/         # BLE GATT service/client
│           ├── transport/   # Transport providers
│           ├── ui/          # Jetpack Compose screens
│           ├── service/     # Foreground service
│           └── util/        # Utilities
├── ios/                 # iOS app (Swift + SwiftUI)
│   └── Flare/Sources/
│       ├── BLE/         # CoreBluetooth implementation
│       ├── Transport/   # Transport providers
│       ├── UI/          # SwiftUI views
│       └── Util/        # Utilities
├── docs/                # Project documentation
├── scripts/             # Build and deployment scripts
├── venv/                # Python virtual environment (gitignored)
└── FEASIBILITY_AND_ARCHITECTURE.md
```

## Building

### Rust Core
```bash
cd flare-core
cargo build
cargo test
```

### Android
```bash
cd android
./gradlew assembleDebug
```

### iOS
```bash
cd ios
xcodebuild -scheme Flare -sdk iphoneos build
```
