#!/usr/bin/env bash
#
# Build Flare Rust core for Android targets and place in jniLibs.
#
# Prerequisites:
#   - Rust toolchain: https://rustup.rs
#   - Android NDK: install via Android Studio SDK Manager or `sdkmanager "ndk;27.1.12297006"`
#   - Rust Android targets: rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#
# Usage:
#   ./scripts/build-android.sh              # Build all targets (release)
#   ./scripts/build-android.sh --debug      # Build all targets (debug)
#   ./scripts/build-android.sh arm64        # Build only arm64-v8a (release)
#
# Environment variables:
#   ANDROID_NDK_HOME  — Path to Android NDK (auto-detected from ANDROID_HOME if not set)
#   MIN_API_LEVEL     — Minimum Android API level (default: 26, matching minSdk)
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CORE_DIR="${PROJECT_ROOT}/flare-core"
JNILIBS_DIR="${PROJECT_ROOT}/android/app/src/main/jniLibs"

MIN_API_LEVEL="${MIN_API_LEVEL:-26}"
BUILD_MODE="release"
BUILD_FLAG="--release"

# Parse arguments
TARGETS_FILTER=""
for arg in "$@"; do
    case "$arg" in
        --debug)
            BUILD_MODE="debug"
            BUILD_FLAG=""
            ;;
        arm64)   TARGETS_FILTER="aarch64" ;;
        armv7)   TARGETS_FILTER="armv7" ;;
        x86_64)  TARGETS_FILTER="x86_64" ;;
        *)
            echo "Unknown argument: $arg"
            echo "Usage: $0 [--debug] [arm64|armv7|x86_64]"
            exit 1
            ;;
    esac
done

# ── Detect NDK ──────────────────────────────────────────────────────
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    if [ -n "${ANDROID_HOME:-}" ]; then
        # Find the latest installed NDK
        NDK_DIR=$(find "${ANDROID_HOME}/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)
        if [ -n "${NDK_DIR}" ]; then
            ANDROID_NDK_HOME="${NDK_DIR}"
        fi
    fi
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ERROR: Android NDK not found."
    echo ""
    echo "Set ANDROID_NDK_HOME or install NDK via:"
    echo "  Android Studio → SDK Manager → SDK Tools → NDK"
    echo "  or: sdkmanager \"ndk;27.1.12297006\""
    exit 1
fi

echo "Using NDK: ${ANDROID_NDK_HOME}"

# Detect host OS and architecture for toolchain path
# NDK 27+ on Apple Silicon still uses "darwin-x86_64" for the prebuilt dir,
# but we check multiple candidates to be resilient across NDK versions.
HOST_OS=$(uname -s | tr '[:upper:]' '[:lower:]')
HOST_ARCH=$(uname -m)

TOOLCHAIN=""
for tag in "${HOST_OS}-${HOST_ARCH}" "${HOST_OS}-x86_64" "${HOST_OS}"; do
    candidate="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${tag}"
    if [ -d "${candidate}" ]; then
        TOOLCHAIN="${candidate}"
        break
    fi
done

if [ -z "${TOOLCHAIN}" ]; then
    echo "ERROR: NDK toolchain not found in ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/"
    echo "Tried: ${HOST_OS}-${HOST_ARCH}, ${HOST_OS}-x86_64, ${HOST_OS}"
    exit 1
fi

# ── Target definitions ──────────────────────────────────────────────
# Format: rust_target|android_abi|cc_prefix
TARGETS=(
    "aarch64-linux-android|arm64-v8a|aarch64-linux-android${MIN_API_LEVEL}"
    "armv7-linux-androideabi|armeabi-v7a|armv7a-linux-androideabi${MIN_API_LEVEL}"
    "x86_64-linux-android|x86_64|x86_64-linux-android${MIN_API_LEVEL}"
)

# ── Install Rust targets if needed ──────────────────────────────────
echo "Checking Rust targets..."
INSTALLED_TARGETS=$(rustup target list --installed)
for entry in "${TARGETS[@]}"; do
    IFS='|' read -r rust_target abi cc_prefix <<< "${entry}"
    if ! echo "${INSTALLED_TARGETS}" | grep -q "${rust_target}"; then
        echo "Installing Rust target: ${rust_target}"
        rustup target add "${rust_target}"
    fi
done

# ── Build each target ───────────────────────────────────────────────
ORIGINAL_PATH="${PATH}"
echo ""
echo "Building flare-core for Android (${BUILD_MODE})..."
echo ""

LAST_BUILT_TARGET=""
LAST_BUILT_MODE="${BUILD_MODE}"

for entry in "${TARGETS[@]}"; do
    IFS='|' read -r rust_target abi cc_prefix <<< "${entry}"

    # Apply filter if specified
    if [ -n "${TARGETS_FILTER}" ] && [[ "${rust_target}" != *"${TARGETS_FILTER}"* ]]; then
        continue
    fi

    LAST_BUILT_TARGET="${rust_target}"
    echo "── ${abi} (${rust_target}) ──"

    # Set cross-compilation environment
    # Use target-specific env vars (CC_<target>) so they don't pollute host builds.
    # Also add toolchain bin/ to PATH so OpenSSL's Makefile can find the compiler
    # by short name (it records "aarch64-linux-android26-clang" not the full path).
    TARGET_ENV=$(echo "${rust_target}" | tr '-' '_')
    export CC_${TARGET_ENV}="${TOOLCHAIN}/bin/${cc_prefix}-clang"
    export AR_${TARGET_ENV}="${TOOLCHAIN}/bin/llvm-ar"
    export CC="${TOOLCHAIN}/bin/${cc_prefix}-clang"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export CARGO_TARGET_$(echo "${rust_target}" | tr 'a-z-' 'A-Z_')_LINKER="${CC}"
    export PATH="${TOOLCHAIN}/bin:${ORIGINAL_PATH}"

    # Build
    (cd "${CORE_DIR}" && cargo build --target "${rust_target}" ${BUILD_FLAG})

    # Copy to jniLibs
    mkdir -p "${JNILIBS_DIR}/${abi}"
    cp "${CORE_DIR}/target/${rust_target}/${BUILD_MODE}/libflare_core.so" \
       "${JNILIBS_DIR}/${abi}/libflare_core.so"

    # Report size
    SIZE=$(du -h "${JNILIBS_DIR}/${abi}/libflare_core.so" | cut -f1)
    echo "  → ${JNILIBS_DIR}/${abi}/libflare_core.so (${SIZE})"
    echo ""
done

# ── Generate Kotlin bindings ────────────────────────────────────────
# Restore clean host environment — uniffi-bindgen must compile for macOS,
# not Android. The cross-compiled .so is only read for metadata extraction.
unset CC AR CC_aarch64_linux_android AR_aarch64_linux_android \
      CC_armv7_linux_androideabi AR_armv7_linux_androideabi \
      CC_x86_64_linux_android AR_x86_64_linux_android
export PATH="${ORIGINAL_PATH}"

echo "Generating Kotlin bindings..."
(cd "${CORE_DIR}" && cargo run --bin uniffi-bindgen generate \
    --library "target/${LAST_BUILT_TARGET}/${LAST_BUILT_MODE}/libflare_core.so" \
    --language kotlin \
    --out-dir "${PROJECT_ROOT}/android/app/src/main/java")

echo ""
echo "Done. Native libraries placed in ${JNILIBS_DIR}"
echo "Kotlin bindings generated in android/app/src/main/java/uniffi/"
