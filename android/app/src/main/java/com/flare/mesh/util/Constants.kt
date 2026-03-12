package com.flare.mesh.util

import com.flare.mesh.R
import java.util.UUID

/**
 * Central constants for the Flare application.
 * Single source of truth — no hardcoded values elsewhere in the codebase.
 */
object Constants {

    /** Flare protocol version. Must match flare-core PROTOCOL_VERSION. */
    const val PROTOCOL_VERSION: Byte = 1

    // ── BLE GATT Service ──────────────────────────────────────────────

    /** Custom BLE service UUID for Flare mesh discovery. */
    val SERVICE_UUID: UUID = UUID.fromString("7A8B0001-F5A0-4D6B-8C3E-1B2A3C4D5E6F")

    /** Characteristic UUID for writing messages to a peer. */
    val CHAR_MESSAGE_WRITE_UUID: UUID = UUID.fromString("7A8B0002-F5A0-4D6B-8C3E-1B2A3C4D5E6F")

    /** Characteristic UUID for receiving message notifications. */
    val CHAR_MESSAGE_NOTIFY_UUID: UUID = UUID.fromString("7A8B0003-F5A0-4D6B-8C3E-1B2A3C4D5E6F")

    /** Characteristic UUID for reading peer info. */
    val CHAR_PEER_INFO_UUID: UUID = UUID.fromString("7A8B0004-F5A0-4D6B-8C3E-1B2A3C4D5E6F")

    /** Standard Client Characteristic Configuration Descriptor for notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ── BLE Scanning ──────────────────────────────────────────────────

    /** BLE scan interval in milliseconds. Balance between discovery speed and battery. */
    const val BLE_SCAN_INTERVAL_MS: Long = 5000L

    /** BLE scan window in milliseconds (must be <= scan interval). */
    const val BLE_SCAN_WINDOW_MS: Long = 2000L

    /** How long to consider a peer "recent" before marking stale (milliseconds). */
    const val PEER_STALE_TIMEOUT_MS: Long = 300_000L  // 5 minutes

    // ── Adaptive Power Management ──────────────────────────────────────
    // See flare-core/src/power/mod.rs for the full tier-based power manager.
    // These constants mirror the Rust PowerConfig defaults for the Android BLE layer.

    /**
     * Power tier scan parameters: [scanWindowMs, scanIntervalMs, burstScanMs, burstSleepMs]
     * High:      near-continuous scanning for active data exchange (max 30s)
     * Balanced:  25% duty cycle when peers are present
     * LowPower:  burst mode — 5s scan every 30s (~17% active)
     * UltraLow:  burst mode — 3s scan every 60s (~5% active)
     */
    val POWER_TIER_HIGH_SCAN_WINDOW_MS: Long = 4096L
    val POWER_TIER_HIGH_SCAN_INTERVAL_MS: Long = 4096L

    val POWER_TIER_BALANCED_SCAN_WINDOW_MS: Long = 1024L
    val POWER_TIER_BALANCED_SCAN_INTERVAL_MS: Long = 4096L

    val POWER_TIER_LOW_BURST_SCAN_MS: Long = 5000L
    val POWER_TIER_LOW_BURST_SLEEP_MS: Long = 25000L

    val POWER_TIER_ULTRALOW_BURST_SCAN_MS: Long = 3000L
    val POWER_TIER_ULTRALOW_BURST_SLEEP_MS: Long = 57000L

    /** Seconds of inactivity before downgrading from High to Balanced. */
    const val POWER_HIGH_INACTIVITY_THRESHOLD_SECS: Int = 10

    /** Seconds without peer discovery before downgrading to LowPower. */
    const val POWER_BALANCED_NO_PEERS_THRESHOLD_SECS: Int = 60

    /** Maximum continuous seconds in High tier. */
    const val POWER_HIGH_DURATION_LIMIT_SECS: Int = 30

    /** Battery % below which UltraLow is forced. */
    const val POWER_CRITICAL_BATTERY_PERCENT: Int = 15

    /** Battery % below which High tier is disabled (cap at Balanced). */
    const val POWER_LOW_BATTERY_PERCENT: Int = 30

    // ── Messages ──────────────────────────────────────────────────────

    /** Default maximum hops for a message. */
    const val DEFAULT_MAX_HOPS: Int = 10

    /** Default TTL for messages in seconds (24 hours). */
    const val DEFAULT_TTL_SECONDS: Int = 86400

    /** Number of Spray-and-Wait copies. */
    const val SPRAY_COPIES: Int = 8

    /** Maximum BLE MTU we request during negotiation. */
    const val REQUESTED_MTU: Int = 517

    /** Minimum usable MTU (default BLE MTU minus ATT overhead). */
    const val MIN_MTU: Int = 20

    // ── BLE Chunking ─────────────────────────────────────────────────

    /** Maximum number of chunks per message (1 byte index = 255). */
    const val BLE_CHUNK_MAX_COUNT: Int = 255

    /** Timeout for incomplete chunk reassembly buffers (milliseconds). */
    const val BLE_CHUNK_REASSEMBLY_TIMEOUT_MS: Long = 30_000L

    /** Timeout waiting for a single BLE write/notify callback (milliseconds). */
    const val BLE_CHUNK_WRITE_TIMEOUT_MS: Long = 5_000L

    // ── Notifications ─────────────────────────────────────────────────

    /** Notification channel ID for the mesh foreground service. */
    const val MESH_SERVICE_CHANNEL_ID: String = "flare_mesh_service"

    /** Notification ID for the mesh foreground service. */
    const val MESH_SERVICE_NOTIFICATION_ID: Int = 1001

    /** Notification channel ID for incoming messages. */
    const val MESSAGE_CHANNEL_ID: String = "flare_messages"

    // ── QR Code ──────────────────────────────────────────────────────

    /** Separator character used in Flare QR code data strings. */
    const val QR_DATA_SEPARATOR: String = "|"

    /** Minimum number of fields in a valid Flare QR code. */
    const val QR_MIN_FIELDS: Int = 3

    /** Expected length of hex-encoded public keys (32 bytes = 64 hex chars). */
    const val HEX_PUBLIC_KEY_LENGTH: Int = 64

    // ── Deep Link (Identity Sharing) ─────────────────────────────────
    // URI format: flare://add?id=<deviceId>&sk=<signingKey>&ak=<agreementKey>&name=<displayName>
    // Used for sharing identity via SMS, WhatsApp, email, or any out-of-band channel.

    /** Custom URL scheme for Flare deep links. */
    const val DEEP_LINK_SCHEME: String = "flare"

    /** Host component for the add-contact deep link. */
    const val DEEP_LINK_HOST_ADD: String = "add"

    /** Query parameter key for device ID. */
    const val DEEP_LINK_PARAM_ID: String = "id"

    /** Query parameter key for hex-encoded signing public key. */
    const val DEEP_LINK_PARAM_SIGNING_KEY: String = "sk"

    /** Query parameter key for hex-encoded agreement public key. */
    const val DEEP_LINK_PARAM_AGREEMENT_KEY: String = "ak"

    /** Query parameter key for optional display name. */
    const val DEEP_LINK_PARAM_NAME: String = "name"

    // ── Crypto ────────────────────────────────────────────────────────

    /** Length of the device ID in bytes. */
    const val DEVICE_ID_LENGTH: Int = 16

    /** Length of Ed25519 public keys in bytes. */
    const val PUBLIC_KEY_LENGTH: Int = 32

    /** Length of Ed25519 signatures in bytes. */
    const val SIGNATURE_LENGTH: Int = 64

    // ── Wi-Fi Direct ──────────────────────────────────────────────────

    /** TCP port for Wi-Fi Direct data exchange. */
    const val WIFI_DIRECT_TRANSFER_PORT: Int = 8778

    /** Socket connection timeout (milliseconds). */
    const val WIFI_DIRECT_CONNECT_TIMEOUT_MS: Int = 10_000

    /** Maximum receive buffer size for Wi-Fi Direct (10 MB). */
    const val WIFI_DIRECT_MAX_RECEIVE_BYTES: Int = 10 * 1024 * 1024

    /** Interval for checking Wi-Fi Direct transfer queue (milliseconds). */
    const val WIFI_DIRECT_QUEUE_CHECK_INTERVAL_MS: Long = 5000L

    /** How long a Wi-Fi Direct transfer can wait before being dropped (seconds). */
    const val WIFI_DIRECT_TRANSFER_TIMEOUT_SECS: Long = 300L

    // ── Message Size Tiers ────────────────────────────────────────────

    /** Maximum payload size for BLE mesh relay (bytes). Mirrors Rust SizeTierConfig. */
    const val MESH_RELAY_MAX_BYTES: Int = 15 * 1024

    /** Maximum payload size for direct-preferred tier (bytes). */
    const val DIRECT_PREFERRED_MAX_BYTES: Int = 64 * 1024

    /** Absolute maximum payload size (bytes). */
    const val ABSOLUTE_MAX_PAYLOAD_BYTES: Int = 10 * 1024 * 1024

    // ── UI ──────────────────────────────────────────────────────────

    /** Duration the splash screen is displayed in milliseconds. */
    const val SPLASH_DURATION_MS: Long = 1500L

    /** Haptic vibration pattern for incoming messages [delay, vibrate, sleep, vibrate]. */
    val HAPTIC_INCOMING_PATTERN: LongArray = longArrayOf(0L, 80L, 120L, 100L)

    /** Amplitude values for incoming message haptic pattern. -1 = default. */
    val HAPTIC_INCOMING_AMPLITUDES: IntArray = intArrayOf(0, 120, 0, 180)

    // ── Media Message Prefixes ────────────────────────────────────────
    // These prefixes identify media type in the encrypted payload.
    // Format: prefix + Base64-encoded binary data.

    /** Prefix for voice message payloads (followed by Base64-encoded .m4a data). */
    const val MEDIA_PREFIX_VOICE: String = "flare:voice:"

    /** Prefix for image message payloads (followed by Base64-encoded JPEG data). */
    const val MEDIA_PREFIX_IMAGE: String = "flare:image:"

    /** Maximum image dimension (pixels) before compression for mesh transfer. */
    const val IMAGE_MAX_DIMENSION: Int = 800

    /** JPEG compression quality for mesh transfer (0-100). */
    const val IMAGE_COMPRESS_QUALITY: Int = 60

    /** Polling interval for voice waveform amplitude in milliseconds. */
    const val VOICE_WAVEFORM_POLL_INTERVAL_MS: Long = 60L

    /** Minimum recording duration in milliseconds to be considered valid. */
    const val VOICE_MIN_RECORDING_DURATION_MS: Long = 500L

    /** Number of waveform bars displayed during recording. */
    const val VOICE_WAVEFORM_BAR_COUNT: Int = 32

    /** Maximum number of nodes shown in mesh visualization. */
    const val MESH_VIS_MAX_NODES: Int = 12

    /** Animation duration for mesh node pulses in milliseconds. */
    const val MESH_VIS_PULSE_DURATION_MS: Int = 2000

    /** Minimum line width for weak signal in mesh visualization. */
    const val MESH_VIS_MIN_LINE_WIDTH_DP: Float = 1f

    /** Maximum line width for strong signal in mesh visualization. */
    const val MESH_VIS_MAX_LINE_WIDTH_DP: Float = 4f

    // ── Language & Preferences ────────────────────────────────────────

    /** SharedPreferences file name. */
    const val PREFS_NAME: String = "flare_prefs"

    /** Key for persisting language preference. */
    const val KEY_LANGUAGE: String = "app_language"

    /** Key for persisting onboarding completion. */
    const val KEY_ONBOARDING_COMPLETE: String = "onboarding_complete"

    /** Key for persisting the user's display name. */
    const val KEY_DISPLAY_NAME: String = "user_display_name"

    /** Key for persisting dark mode preference. */
    const val KEY_DARK_MODE: String = "dark_mode"

    /** Dark mode option: follow system setting. */
    const val DARK_MODE_SYSTEM: String = "system"

    /** Dark mode option: always light. */
    const val DARK_MODE_LIGHT: String = "light"

    /** Dark mode option: always dark. */
    const val DARK_MODE_DARK: String = "dark"

    /** Language code for system default. */
    const val LANGUAGE_SYSTEM_DEFAULT: String = "system"

    /** Broadcast recipient constant — all 0xFF (matches Rust BROADCAST_DEVICE_ID). */
    const val BROADCAST_DEVICE_ID: String = "ffffffffffffffffffffffffffffffff"

    // ── Destruction Code (Lock Screen) ────────────────────────────────

    /** SharedPreferences key for the SHA-256 hash of the unlock code. */
    const val KEY_UNLOCK_CODE_HASH: String = "unlock_code_hash"

    /** SharedPreferences key for the SHA-256 hash of the destruction code. */
    const val KEY_DESTRUCTION_CODE_HASH: String = "destruction_code_hash"

    /** Minimum length for unlock and destruction codes. */
    const val MIN_CODE_LENGTH: Int = 4

    // ── APK Sharing ──────────────────────────────────────────────────

    // ── Key Exchange ──────────────────────────────────────────────────

    /** Content type for KeyExchange messages. */
    const val CONTENT_TYPE_KEY_EXCHANGE: UByte = 4u

    /** Separator in key exchange payload: deviceId|signingKeyHex|agreementKeyHex|displayName */
    const val KEY_EXCHANGE_SEPARATOR: String = "|"

    /** Minimum fields in a key exchange payload. */
    const val KEY_EXCHANGE_MIN_FIELDS: Int = 3

    // ── APK Sharing ──────────────────────────────────────────────────

    /** Filename used when copying APK to cache for sharing via FileProvider. */
    const val APK_SHARE_CACHE_FILENAME: String = "Flare.apk"

    /** MIME type for Android APK files. */
    const val APK_MIME_TYPE: String = "application/vnd.android.package-archive"

    /** GitHub repository releases URL for download link sharing. */
    const val GITHUB_RELEASES_URL: String = "https://github.com/zivelo1/Flare/releases/latest"

    /** Share message template key — used with string resource for share text. */
    const val APK_SHARE_SUBDIRECTORY: String = "apk_share"

    /** Supported languages with their resource IDs and native names. */
    data class LanguageOption(
        val code: String,
        val nameRes: Int,
        val nativeName: String? = null,
    )

    val SUPPORTED_LANGUAGES = listOf(
        LanguageOption(LANGUAGE_SYSTEM_DEFAULT, R.string.language_system_default),
        LanguageOption("en", R.string.language_english, "English"),
        LanguageOption("fa", R.string.language_farsi, "فارسی"),
        LanguageOption("ar", R.string.language_arabic, "العربية"),
        LanguageOption("es", R.string.language_spanish, "Español"),
        LanguageOption("ru", R.string.language_russian, "Русский"),
        LanguageOption("zh", R.string.language_chinese, "中文"),
        LanguageOption("ko", R.string.language_korean, "한국어"),
    )
}
