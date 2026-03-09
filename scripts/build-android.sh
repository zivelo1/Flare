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

# Detect host OS for toolchain path
case "$(uname -s)" in
    Linux*)  HOST_TAG="linux-x86_64" ;;
    Darwin*) HOST_TAG="darwin-x86_64" ;;
    *)       echo "Unsupported host OS: $(uname -s)"; exit 1 ;;
esac

TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [ ! -d "${TOOLCHAIN}" ]; then
    echo "ERROR: NDK toolchain not found at ${TOOLCHAIN}"
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
echo ""
echo "Building flare-core for Android (${BUILD_MODE})..."
echo ""

for entry in "${TARGETS[@]}"; do
    IFS='|' read -r rust_target abi cc_prefix <<< "${entry}"

    # Apply filter if specified
    if [ -n "${TARGETS_FILTER}" ] && [[ "${rust_target}" != *"${TARGETS_FILTER}"* ]]; then
        continue
    fi

    echo "── ${abi} (${rust_target}) ──"

    # Set cross-compilation environment
    export CC="${TOOLCHAIN}/bin/${cc_prefix}-clang"
    export AR="${TOOLCHAIN}/bin/llvm-ar"
    export CARGO_TARGET_$(echo "${rust_target}" | tr 'a-z-' 'A-Z_')_LINKER="${CC}"

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
echo "Generating Kotlin bindings..."
(cd "${CORE_DIR}" && cargo run --bin uniffi-bindgen generate \
    --library "target/${rust_target}/${BUILD_MODE}/libflare_core.so" \
    --language kotlin \
    --out-dir "${PROJECT_ROOT}/android/app/src/main/java")

echo ""
echo "Done. Native libraries placed in ${JNILIBS_DIR}"
echo "Kotlin bindings generated in android/app/src/main/java/uniffi/"
