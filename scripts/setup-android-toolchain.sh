#!/usr/bin/env bash
#
# Setup Android build toolchain for Flare on macOS.
#
# Installs (only if missing):
#   - JDK 17 (Eclipse Temurin via Homebrew)
#   - Android command-line tools
#   - Android SDK platform + build tools
#   - Android NDK (version matched to CI)
#   - Rust Android cross-compilation targets
#
# Idempotent: safe to run multiple times.
#
# Usage:
#   ./scripts/setup-android-toolchain.sh
#
# After running, source the printed env exports or add them to your shell profile.
#

set -euo pipefail

# ── Configuration (single source of truth — matches CI workflow) ──────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Read versions from CI workflow to stay in sync
CI_WORKFLOW="${PROJECT_ROOT}/.github/workflows/build.yml"
if [ -f "${CI_WORKFLOW}" ]; then
    NDK_VERSION=$(grep 'NDK_VERSION:' "${CI_WORKFLOW}" | head -1 | sed 's/.*"\(.*\)".*/\1/')
    JAVA_VERSION=$(grep 'JAVA_VERSION:' "${CI_WORKFLOW}" | head -1 | sed 's/.*"\(.*\)".*/\1/')
else
    echo "WARNING: CI workflow not found at ${CI_WORKFLOW}, using defaults"
    NDK_VERSION="27.1.12297006"
    JAVA_VERSION="17"
fi

# Android SDK components to install
ANDROID_PLATFORM="android-35"
ANDROID_BUILD_TOOLS="35.0.0"
ANDROID_CMDLINE_TOOLS_VERSION="latest"

# Rust targets for Android cross-compilation
RUST_ANDROID_TARGETS=(
    "aarch64-linux-android"
    "armv7-linux-androideabi"
    "x86_64-linux-android"
)

# Default SDK location (standard macOS convention)
DEFAULT_ANDROID_HOME="${HOME}/Library/Android/sdk"

# Package tracking file (so user knows what to remove later)
PACKAGE_LOG="${PROJECT_ROOT}/mac-installed-packages.txt"
TODAY=$(date +%Y-%m-%d)

# Append a tracked install entry to the package log
log_install() {
    local name="$1"
    local method="$2"
    local remove_cmd="$3"
    local location="${4:-}"

    cat >> "${PACKAGE_LOG}" << EOF

$(( $(grep -c '^[0-9]\+\.' "${PACKAGE_LOG}" 2>/dev/null || echo "0") + 1 )). ${name}
   Installed via: ${method}
   Remove with:  ${remove_cmd}
   Date:         ${TODAY}
   Reason:       Android build toolchain for Flare
   Location:     ${location}
EOF
}

echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Flare Android Toolchain Setup                          ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  NDK version : ${NDK_VERSION}                       ║"
echo "║  JDK version : ${JAVA_VERSION}                                 ║"
echo "║  Platform    : ${ANDROID_PLATFORM}                          ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

ERRORS=()
INSTALLED=()
SKIPPED=()

# ── Helper functions ──────────────────────────────────────────────────

check_command() {
    command -v "$1" &>/dev/null
}

# ── Step 1: Homebrew ──────────────────────────────────────────────────
echo "▸ Checking Homebrew..."
if ! check_command brew; then
    ERRORS+=("Homebrew is required but not installed. Install from https://brew.sh")
    echo "  ✗ Homebrew not found — cannot continue"
    printf '\n%s\n' "${ERRORS[@]}"
    exit 1
fi
SKIPPED+=("Homebrew (already installed)")

# ── Step 2: JDK ──────────────────────────────────────────────────────
echo "▸ Checking JDK ${JAVA_VERSION}..."
JAVA_INSTALLED=false

if check_command java; then
    CURRENT_JAVA=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/' || echo "0")
    if [ "${CURRENT_JAVA}" = "${JAVA_VERSION}" ]; then
        JAVA_INSTALLED=true
        SKIPPED+=("JDK ${JAVA_VERSION} (already installed)")
    fi
fi

if [ "${JAVA_INSTALLED}" = false ]; then
    echo "  Installing JDK ${JAVA_VERSION} (Eclipse Temurin)..."
    brew install --cask "temurin@${JAVA_VERSION}"
    INSTALLED+=("JDK ${JAVA_VERSION}")
    TEMURIN_LOC=$(/usr/libexec/java_home -v "${JAVA_VERSION}" 2>/dev/null || echo "unknown")
    log_install "JDK ${JAVA_VERSION} (Eclipse Temurin)" \
        "brew install --cask temurin@${JAVA_VERSION}" \
        "brew uninstall --cask temurin@${JAVA_VERSION}" \
        "${TEMURIN_LOC}"
fi

# Verify Java is now available
if ! check_command java; then
    # Temurin may need PATH update
    TEMURIN_HOME=$(/usr/libexec/java_home -v "${JAVA_VERSION}" 2>/dev/null || echo "")
    if [ -n "${TEMURIN_HOME}" ]; then
        export JAVA_HOME="${TEMURIN_HOME}"
        export PATH="${JAVA_HOME}/bin:${PATH}"
    else
        ERRORS+=("JDK ${JAVA_VERSION} installation may have failed — java not found in PATH")
    fi
fi

# ── Step 3: Android SDK ──────────────────────────────────────────────
echo "▸ Checking Android SDK..."

# Determine ANDROID_HOME
if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
    echo "  Using existing ANDROID_HOME: ${ANDROID_HOME}"
elif [ -d "${DEFAULT_ANDROID_HOME}" ]; then
    ANDROID_HOME="${DEFAULT_ANDROID_HOME}"
    echo "  Found SDK at default location: ${ANDROID_HOME}"
else
    ANDROID_HOME="${DEFAULT_ANDROID_HOME}"
    echo "  Will install SDK to: ${ANDROID_HOME}"
fi

export ANDROID_HOME

# Install Android command-line tools if sdkmanager is not available
SDKMANAGER=""
for candidate in \
    "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" \
    "${ANDROID_HOME}/cmdline-tools/bin/sdkmanager" \
    "$(which sdkmanager 2>/dev/null || echo '')"; do
    if [ -n "${candidate}" ] && [ -x "${candidate}" ]; then
        SDKMANAGER="${candidate}"
        break
    fi
done

if [ -z "${SDKMANAGER}" ]; then
    echo "  Installing Android command-line tools..."

    # Download command-line tools
    CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip"
    TEMP_DIR=$(mktemp -d)
    CMDLINE_ZIP="${TEMP_DIR}/cmdline-tools.zip"

    echo "  Downloading command-line tools..."
    curl -fsSL -o "${CMDLINE_ZIP}" "${CMDLINE_TOOLS_URL}"

    # Extract and place in correct directory structure
    mkdir -p "${ANDROID_HOME}/cmdline-tools"
    unzip -q "${CMDLINE_ZIP}" -d "${TEMP_DIR}"
    mv "${TEMP_DIR}/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest"

    rm -rf "${TEMP_DIR}"
    SDKMANAGER="${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager"
    INSTALLED+=("Android command-line tools")
    log_install "Android SDK + command-line tools" \
        "setup-android-toolchain.sh (downloaded from dl.google.com)" \
        "rm -rf ${ANDROID_HOME}" \
        "${ANDROID_HOME}"
else
    SKIPPED+=("Android command-line tools (already installed)")
fi

# Accept licenses non-interactively
echo "  Accepting SDK licenses..."
yes 2>/dev/null | "${SDKMANAGER}" --licenses >/dev/null 2>&1 || true

# Install required SDK components
echo "  Installing SDK components..."

SDK_PACKAGES=(
    "platform-tools"
    "platforms;${ANDROID_PLATFORM}"
    "build-tools;${ANDROID_BUILD_TOOLS}"
    "ndk;${NDK_VERSION}"
)

for pkg in "${SDK_PACKAGES[@]}"; do
    # Check if already installed
    if "${SDKMANAGER}" --list_installed 2>/dev/null | grep -q "${pkg}" 2>/dev/null; then
        SKIPPED+=("SDK: ${pkg}")
    else
        echo "  Installing ${pkg}..."
        "${SDKMANAGER}" --install "${pkg}" >/dev/null 2>&1
        INSTALLED+=("SDK: ${pkg}")
        # NDK is the only large component worth tracking individually
        if [[ "${pkg}" == ndk* ]]; then
            log_install "Android NDK ${NDK_VERSION}" \
                "sdkmanager \"${pkg}\"" \
                "sdkmanager --uninstall \"${pkg}\" (or rm -rf ${ANDROID_HOME}/ndk/${NDK_VERSION})" \
                "${ANDROID_HOME}/ndk/${NDK_VERSION}"
        fi
    fi
done

# Set NDK home
ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/${NDK_VERSION}"
export ANDROID_NDK_HOME

# ── Step 4: Rust Android targets ─────────────────────────────────────
echo "▸ Checking Rust Android targets..."

if ! check_command rustup; then
    ERRORS+=("Rust toolchain (rustup) is required but not found. Install from https://rustup.rs")
else
    INSTALLED_TARGETS=$(rustup target list --installed)
    for target in "${RUST_ANDROID_TARGETS[@]}"; do
        if echo "${INSTALLED_TARGETS}" | grep -q "${target}"; then
            SKIPPED+=("Rust target: ${target}")
        else
            echo "  Installing Rust target: ${target}..."
            rustup target add "${target}"
            INSTALLED+=("Rust target: ${target}")
            log_install "Rust target: ${target}" \
                "rustup target add ${target}" \
                "rustup target remove ${target}" \
                "~/.rustup/toolchains/stable-aarch64-apple-darwin/lib/rustlib/${target}/"
        fi
    done
fi

# ── Step 5: Create local.properties for Gradle ───────────────────────
echo "▸ Setting up local.properties..."
LOCAL_PROPS="${PROJECT_ROOT}/android/local.properties"

if [ -f "${LOCAL_PROPS}" ]; then
    # Check if it has correct sdk.dir
    if grep -q "sdk.dir=${ANDROID_HOME}" "${LOCAL_PROPS}" 2>/dev/null; then
        SKIPPED+=("local.properties (already correct)")
    else
        echo "  Updating local.properties with current SDK path..."
        # Preserve any custom entries, update sdk.dir and ndk.dir
        grep -v '^sdk\.dir=' "${LOCAL_PROPS}" | grep -v '^ndk\.dir=' > "${LOCAL_PROPS}.tmp" || true
        echo "sdk.dir=${ANDROID_HOME}" >> "${LOCAL_PROPS}.tmp"
        echo "ndk.dir=${ANDROID_NDK_HOME}" >> "${LOCAL_PROPS}.tmp"
        mv "${LOCAL_PROPS}.tmp" "${LOCAL_PROPS}"
        INSTALLED+=("local.properties (updated)")
    fi
else
    cat > "${LOCAL_PROPS}" << EOF
# Auto-generated by setup-android-toolchain.sh
# This file should NOT be committed to version control.
sdk.dir=${ANDROID_HOME}
ndk.dir=${ANDROID_NDK_HOME}
EOF
    INSTALLED+=("local.properties (created)")
fi

# ── Step 6: Validate NDK toolchain ───────────────────────────────────
echo "▸ Validating NDK toolchain..."

# Detect correct host tag for this machine
HOST_OS=$(uname -s | tr '[:upper:]' '[:lower:]')
HOST_ARCH=$(uname -m)

# NDK 27+ uses just "darwin" or "linux" for the prebuilt path on some versions,
# but most still use "darwin-x86_64". Check both.
TOOLCHAIN_DIR=""
for tag in "${HOST_OS}-${HOST_ARCH}" "${HOST_OS}-x86_64" "${HOST_OS}"; do
    candidate="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${tag}"
    if [ -d "${candidate}" ]; then
        TOOLCHAIN_DIR="${candidate}"
        break
    fi
done

if [ -z "${TOOLCHAIN_DIR}" ]; then
    ERRORS+=("NDK toolchain not found in ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/")
else
    # Verify the clang compiler exists
    if [ -x "${TOOLCHAIN_DIR}/bin/clang" ]; then
        echo "  ✓ NDK toolchain verified at ${TOOLCHAIN_DIR}"
    else
        ERRORS+=("NDK clang not found at ${TOOLCHAIN_DIR}/bin/clang")
    fi
fi

# ── Summary ───────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════════"
echo "  SETUP SUMMARY"
echo "════════════════════════════════════════════════════════════"

if [ ${#INSTALLED[@]} -gt 0 ]; then
    echo ""
    echo "  Installed:"
    for item in "${INSTALLED[@]}"; do
        echo "    ✓ ${item}"
    done
fi

if [ ${#SKIPPED[@]} -gt 0 ]; then
    echo ""
    echo "  Already present:"
    for item in "${SKIPPED[@]}"; do
        echo "    · ${item}"
    done
fi

if [ ${#ERRORS[@]} -gt 0 ]; then
    echo ""
    echo "  ✗ ERRORS:"
    for err in "${ERRORS[@]}"; do
        echo "    ✗ ${err}"
    done
    echo ""
    exit 1
fi

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  ENVIRONMENT VARIABLES"
echo ""
echo "  Add these to your ~/.zshrc (or run them now):"
echo ""
echo "    export ANDROID_HOME=\"${ANDROID_HOME}\""
echo "    export ANDROID_NDK_HOME=\"${ANDROID_NDK_HOME}\""
echo "    export PATH=\"\${ANDROID_HOME}/platform-tools:\${ANDROID_HOME}/cmdline-tools/latest/bin:\${PATH}\""
echo ""
echo "════════════════════════════════════════════════════════════"
echo "  NEXT STEPS"
echo ""
echo "  1. Export the environment variables above (or restart shell)"
echo "  2. Run: ./scripts/build-android.sh"
echo "  3. Run: cd android && ./gradlew assembleDebug"
echo "  4. Connect phone via USB (enable USB Debugging in Developer Options)"
echo "  5. Run: adb install android/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "  Setup complete ✓"
