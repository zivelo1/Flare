package com.flare.mesh.util

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
}
