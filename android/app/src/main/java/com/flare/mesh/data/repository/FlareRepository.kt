package com.flare.mesh.data.repository

import android.content.Context
import com.flare.mesh.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import uniffi.flare_core.FfiChatMessage
import uniffi.flare_core.FfiContact
import uniffi.flare_core.FfiRouteDecision
import uniffi.flare_core.FlareNode
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Repository bridging the Rust FlareNode (via UniFFI) to the Android application layer.
 *
 * Provides a clean Kotlin API over the FFI boundary, converting between
 * FFI types and app-level data models. Thread-safe — all FFI calls run on IO dispatcher.
 */
class FlareRepository private constructor(private val node: FlareNode) {

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    companion object {
        @Volatile
        private var instance: FlareRepository? = null

        private const val DB_FILENAME = "flare.db"

        /**
         * Initializes the repository singleton. Must be called once from Application.onCreate().
         * The passphrase is derived per-device and stored in Android Keystore.
         */
        fun initialize(context: Context, passphrase: String): FlareRepository {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val dbPath = File(context.filesDir, DB_FILENAME).absolutePath
                    Timber.i("Initializing FlareNode with database at %s", dbPath)
                    val node = FlareNode(dbPath, passphrase)
                    val repo = FlareRepository(node)
                    instance = repo
                    Timber.i("FlareNode initialized — device ID: %s", node.getDeviceId())
                    repo
                }
            }
        }

        fun getInstance(): FlareRepository {
            return instance ?: throw IllegalStateException(
                "FlareRepository not initialized. Call initialize() in Application.onCreate()."
            )
        }
    }

    // ── Identity ──────────────────────────────────────────────────────

    fun getDeviceId(): String = node.getDeviceId()

    fun getSafetyNumber(): String = node.getSafetyNumber()

    fun getPublicIdentity(): DeviceIdentity {
        val ffi = node.getPublicIdentity()
        return DeviceIdentity(
            deviceId = ffi.deviceId,
            signingPublicKey = ffi.signingPublicKey,
            agreementPublicKey = ffi.agreementPublicKey,
        )
    }

    fun getPeerInfoBytes(): ByteArray = node.getPeerInfoBytes()

    // ── Messaging ─────────────────────────────────────────────────────

    /**
     * Encrypts and builds a mesh message for the given recipient.
     * Returns the serialized bytes ready for BLE transmission.
     */
    suspend fun sendMessage(
        recipientDeviceId: String,
        recipientAgreementKey: ByteArray,
        plaintext: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()

        // Encrypt the plaintext
        val encrypted = node.createEncryptedMessage(
            recipientDeviceId, recipientAgreementKey, plaintext,
        )

        // Build the mesh message envelope
        val meshMsg = node.buildMeshMessage(recipientDeviceId, encrypted)

        // Store locally
        node.storeChatMessage(FfiChatMessage(
            messageId = messageId,
            conversationId = recipientDeviceId,
            senderDeviceId = getDeviceId(),
            content = plaintext,
            timestampMs = Instant.now().toEpochMilli(),
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.PENDING.ordinal.toUByte(),
        ))

        // Queue for outbound delivery
        node.queueOutboundMessage(messageId, recipientDeviceId, encrypted)

        Timber.d("Message queued for %s (id=%s)", recipientDeviceId.take(12), messageId)
        meshMsg.serialized
    }

    /**
     * Processes an incoming raw mesh message from BLE.
     * Returns the routing decision and optionally the decrypted text (if for us).
     */
    suspend fun processIncomingMessage(rawData: ByteArray): IncomingMessageResult =
        withContext(Dispatchers.IO) {
            val decision = node.routeIncoming(rawData)

            when (decision) {
                FfiRouteDecision.DELIVER_LOCALLY -> {
                    val parsed = node.parseMeshMessage(rawData)
                    if (parsed != null) {
                        // Look up sender's agreement key from contacts
                        val senderContact = _contacts.value.find {
                            it.identity.deviceId == parsed.senderId
                        }
                        val plaintext = senderContact?.let {
                            node.decryptIncomingMessage(
                                parsed.senderId,
                                it.identity.agreementPublicKey,
                                parsed.payload,
                            )
                        }

                        // Store the decrypted message in the database
                        if (plaintext != null) {
                            val messageId = parsed.messageId
                            node.storeChatMessage(FfiChatMessage(
                                messageId = messageId,
                                conversationId = parsed.senderId,
                                senderDeviceId = parsed.senderId,
                                content = plaintext,
                                timestampMs = Instant.now().toEpochMilli(),
                                isOutgoing = false,
                                deliveryStatus = DeliveryStatus.DELIVERED.ordinal.toUByte(),
                            ))
                        }

                        IncomingMessageResult(
                            decision = RouteDecisionType.DELIVER_LOCALLY,
                            senderId = parsed.senderId,
                            plaintext = plaintext,
                        )
                    } else {
                        IncomingMessageResult(decision = RouteDecisionType.DROP)
                    }
                }

                FfiRouteDecision.FORWARD -> {
                    IncomingMessageResult(decision = RouteDecisionType.FORWARD)
                }

                FfiRouteDecision.STORE -> {
                    IncomingMessageResult(decision = RouteDecisionType.STORE)
                }

                else -> {
                    IncomingMessageResult(decision = RouteDecisionType.DROP)
                }
            }
        }

    /**
     * Gets messages that should be forwarded to a newly connected peer.
     */
    suspend fun getMessagesForPeer(deviceId: String): List<ByteArray> =
        withContext(Dispatchers.IO) {
            node.getMessagesForPeer(deviceId)
        }

    fun notifyPeerConnected(deviceId: String) {
        node.notifyPeerConnected(deviceId)
    }

    /**
     * Loads persisted chat messages for a conversation from the encrypted database.
     */
    suspend fun getMessagesForConversation(conversationId: String): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            node.getMessagesForConversation(conversationId).map { ffi ->
                ChatMessage(
                    messageId = ffi.messageId,
                    conversationId = ffi.conversationId,
                    senderDeviceId = ffi.senderDeviceId,
                    content = ffi.content,
                    timestamp = Instant.ofEpochMilli(ffi.timestampMs),
                    isOutgoing = ffi.isOutgoing,
                    deliveryStatus = DeliveryStatus.entries.getOrElse(ffi.deliveryStatus.toInt()) {
                        DeliveryStatus.PENDING
                    },
                )
            }
        }

    // ── Contacts ──────────────────────────────────────────────────────

    suspend fun addContact(
        deviceId: String,
        signingPublicKey: ByteArray,
        agreementPublicKey: ByteArray,
        displayName: String?,
        isVerified: Boolean,
    ) = withContext(Dispatchers.IO) {
        node.addContact(FfiContact(
            deviceId = deviceId,
            signingPublicKey = signingPublicKey,
            agreementPublicKey = agreementPublicKey,
            displayName = displayName,
            isVerified = isVerified,
        ))
        refreshContacts()
    }

    suspend fun refreshContacts() = withContext(Dispatchers.IO) {
        val ffiContacts = node.listContacts()
        _contacts.value = ffiContacts.map { ffi ->
            Contact(
                identity = DeviceIdentity(
                    deviceId = ffi.deviceId,
                    signingPublicKey = ffi.signingPublicKey,
                    agreementPublicKey = ffi.agreementPublicKey,
                ),
                displayName = ffi.displayName,
                isVerified = ffi.isVerified,
            )
        }
    }

    // ── Neighborhood Detection ──────────────────────────────────────

    /**
     * Records a peer's 4-byte short ID in the neighborhood Bloom filter.
     * Call on every BLE scan result to build local cluster awareness.
     */
    fun recordNeighborhoodPeer(shortId: ByteArray) {
        node.recordNeighborhoodPeer(shortId)
    }

    /**
     * Exports the current neighborhood bitmap for exchange with a peer.
     * Send this over BLE after connecting to enable bridge detection.
     */
    fun exportNeighborhoodBitmap(): ByteArray = node.exportNeighborhoodBitmap()

    /**
     * Processes a remote peer's neighborhood bitmap.
     * Returns the encounter type: "Local", "Bridge", or "Intermediate".
     * On bridge encounters, the Rust core automatically extends TTL on stored messages.
     */
    fun processRemoteNeighborhood(remoteBitmap: ByteArray): String =
        node.processRemoteNeighborhood(remoteBitmap)

    // ── Store Stats ────────────────────────────────────────────────────

    /**
     * Returns priority store statistics for monitoring and UI display.
     */
    fun getStoreStats(): StoreStats {
        val ffi = node.getStoreStats()
        return StoreStats(
            totalMessages = ffi.totalMessages.toInt(),
            ownMessages = ffi.ownMessages.toInt(),
            activeRelayMessages = ffi.activeRelayMessages.toInt(),
            waitingRelayMessages = ffi.waitingRelayMessages.toInt(),
            totalBytes = ffi.totalBytes.toLong(),
            budgetBytes = ffi.budgetBytes.toLong(),
        )
    }

    // ── Mesh Status ───────────────────────────────────────────────────

    fun getMeshStatus(): MeshStatus {
        val ffi = node.getMeshStatus()
        return MeshStatus(
            isActive = true,
            connectedPeerCount = ffi.connectedPeers.toInt(),
            discoveredPeerCount = ffi.discoveredPeers.toInt(),
            storedMessageCount = ffi.storedMessages.toInt(),
            messagesRelayed = ffi.messagesRelayed.toLong(),
        )
    }

    fun pruneExpiredMessages(): Int = node.pruneExpiredMessages().toInt()
}

data class StoreStats(
    val totalMessages: Int,
    val ownMessages: Int,
    val activeRelayMessages: Int,
    val waitingRelayMessages: Int,
    val totalBytes: Long,
    val budgetBytes: Long,
)

enum class RouteDecisionType {
    DELIVER_LOCALLY, FORWARD, STORE, DROP,
}

data class IncomingMessageResult(
    val decision: RouteDecisionType,
    val senderId: String? = null,
    val plaintext: String? = null,
)
