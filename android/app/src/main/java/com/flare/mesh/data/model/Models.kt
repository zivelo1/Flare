package com.flare.mesh.data.model

import java.time.Instant

/**
 * Represents a device's identity in the Flare mesh.
 * The device ID is derived from the Ed25519 signing public key.
 */
data class DeviceIdentity(
    val deviceId: String,
    val signingPublicKey: ByteArray,
    val agreementPublicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentity) return false
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}

/**
 * A contact in the user's contact list.
 */
data class Contact(
    val identity: DeviceIdentity,
    val displayName: String?,
    val isVerified: Boolean = false,
    val lastSeen: Instant = Instant.now(),
)

/**
 * A conversation thread with a contact.
 */
data class Conversation(
    val id: String,
    val contact: Contact,
    val lastMessage: ChatMessage? = null,
    val unreadCount: Int = 0,
)

/**
 * A single chat message.
 */
data class ChatMessage(
    val messageId: String,
    val conversationId: String,
    val senderDeviceId: String,
    val content: String,
    val timestamp: Instant,
    val isOutgoing: Boolean,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING,
)

/**
 * Message delivery status indicators.
 */
enum class DeliveryStatus {
    /** Message created, not yet sent to mesh. */
    PENDING,
    /** Message sent to at least one relay node. */
    SENT,
    /** Message confirmed delivered to recipient's device. */
    DELIVERED,
    /** Message read by recipient. */
    READ,
    /** Message delivery failed. */
    FAILED,
}

/**
 * Information about a discovered peer in the mesh network.
 */
data class MeshPeer(
    val deviceId: String,
    val displayName: String? = null,
    val rssi: Int? = null,
    val estimatedDistanceMeters: Float? = null,
    val isConnected: Boolean = false,
    val transportType: TransportType = TransportType.BLUETOOTH_LE,
    val lastSeen: Instant = Instant.now(),
)

/**
 * Type of wireless transport used to communicate with a peer.
 */
enum class TransportType {
    BLUETOOTH_LE,
    WIFI_DIRECT,
    WIFI_AWARE,
}

/**
 * Overall mesh network status.
 */
data class MeshStatus(
    val isActive: Boolean = false,
    val connectedPeerCount: Int = 0,
    val discoveredPeerCount: Int = 0,
    val storedMessageCount: Int = 0,
    val messagesRelayed: Long = 0,
)

/**
 * A group chat with multiple members.
 */
data class Group(
    val groupId: String,
    val groupName: String,
    val createdAt: String,
    val creatorDeviceId: String,
    val memberCount: Int = 0,
)

/**
 * Power management tier recommendation from the Rust core.
 */
data class PowerTierInfo(
    val tier: String,
    val scanWindowMs: Long,
    val scanIntervalMs: Long,
    val advertiseIntervalMs: Long,
    val burstScanDurationMs: Long,
    val burstSleepDurationMs: Long,
    val useBurstMode: Boolean,
)
