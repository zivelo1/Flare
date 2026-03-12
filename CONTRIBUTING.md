# Contributing to Flare

Thank you for your interest in contributing to Flare! This project exists to help people communicate safely when the internet is down, and every contribution makes a difference.

## How to Contribute

### Reporting Bugs

1. Check [existing issues](https://github.com/zivelo1/Flare/issues) to avoid duplicates
2. Open a new issue with:
   - Device model and Android version
   - Steps to reproduce
   - Expected vs actual behavior
   - Logcat output if available (`adb logcat -s Flare:* AndroidRuntime:*`)

### Suggesting Features

Open an issue with the `enhancement` label. Describe:
- The problem you're trying to solve
- Your proposed solution
- How it fits Flare's mission (offline-first, privacy-preserving, accessible)

### Submitting Code

1. Fork the repository
2. Create a feature branch from `main`
3. Make your changes
4. Ensure all tests pass:
   ```bash
   cd flare-core && cargo test
   cd android && ./gradlew assembleDebug
   ```
5. Submit a pull request with a clear description

### Code Style

- **Rust** — follow `cargo fmt` and `cargo clippy`
- **Kotlin** — follow standard Kotlin conventions and Jetpack Compose best practices
- **Constants** — all magic numbers and strings go in `Constants.kt` (Android) or `constants.rs` (Rust). No hardcoded values in UI or logic code
- **Strings** — all user-facing text must be in `strings.xml` (Android) for localization. Never hardcode UI text

### Architecture Overview

```
flare-core/     — Rust core library (crypto, routing, storage, protocol)
android/        — Android app (Kotlin + Jetpack Compose)
ios/            — iOS app (Swift + SwiftUI) — deferred, Android-first
docs/           — Architecture docs, project status
scripts/        — Build scripts for cross-compilation
```

The Rust core handles all cryptography, mesh routing, and data storage. Platform apps (Android/iOS) handle BLE transport, UI, and call into the Rust core via UniFFI bindings.

### Building

#### Prerequisites
- Rust 1.70+ (`curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`)
- Android Studio with NDK installed
- Android SDK (API 34+)

#### Rust Core
```bash
cd flare-core
cargo build
cargo test
```

#### Android
```bash
# Cross-compile Rust for Android (requires NDK)
./scripts/build-android.sh

# Build APK
cd android
./gradlew assembleDebug
```

### Translation

Flare supports 6 languages beyond English. Translation files are in:
- `android/app/src/main/res/values-fa/` (Farsi)
- `android/app/src/main/res/values-ar/` (Arabic)
- `android/app/src/main/res/values-es/` (Spanish)
- `android/app/src/main/res/values-ru/` (Russian)
- `android/app/src/main/res/values-zh-rCN/` (Chinese Simplified)
- `android/app/src/main/res/values-ko/` (Korean)

To add a new language:
1. Create `values-<code>/strings.xml`
2. Translate all strings from `values/strings.xml`
3. Add the language to `Constants.SUPPORTED_LANGUAGES` in `Constants.kt`
4. Add the locale to `android:localeConfig` in `AndroidManifest.xml`

### Security

If you find a security vulnerability, **do not** open a public issue. Instead, see [SECURITY.md](SECURITY.md) for responsible disclosure instructions.

## Code of Conduct

Be respectful, inclusive, and constructive. Flare is built for people in crisis situations — keep that perspective in mind. Harassment, discrimination, and hostile behavior will not be tolerated.

## License

By contributing, you agree that your contributions will be licensed under the [GNU General Public License v3.0](LICENSE).
