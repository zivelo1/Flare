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

    // ── Crypto ────────────────────────────────────────────────────────

    /** Length of the device ID in bytes. */
    const val DEVICE_ID_LENGTH: Int = 16

    /** Length of Ed25519 public keys in bytes. */
    const val PUBLIC_KEY_LENGTH: Int = 32

    /** Length of Ed25519 signatures in bytes. */
    const val SIGNATURE_LENGTH: Int = 64
}
