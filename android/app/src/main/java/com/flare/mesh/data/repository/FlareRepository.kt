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

        // Build the mesh message envelope (content_type 0x01 = Text)
        val meshMsg = node.buildMeshMessage(recipientDeviceId, encrypted, 1u)

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
                            messageId = parsed.messageId,
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
     * Prepares a raw mesh message for relay by incrementing its hop count.
     * Returns the updated serialized message, or null if hop limit reached.
     */
    suspend fun prepareForRelay(rawData: ByteArray): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                node.prepareForRelay(rawData)
            } catch (e: Exception) {
                Timber.d("Message reached hop limit, not relaying")
                null
            }
        }

    /**
     * Creates a delivery ACK for a received message, to propagate back to sender.
     * Returns serialized mesh message bytes.
     */
    suspend fun createDeliveryAck(originalMessageId: String, senderDeviceId: String): ByteArray =
        withContext(Dispatchers.IO) {
            node.createDeliveryAck(originalMessageId, senderDeviceId)
        }

    /**
     * Creates a read receipt for a message the user has viewed.
     * Returns serialized mesh message bytes.
     */
    suspend fun createReadReceipt(originalMessageId: String, senderDeviceId: String): ByteArray =
        withContext(Dispatchers.IO) {
            node.createReadReceipt(originalMessageId, senderDeviceId)
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

    /**
     * Processes a remote peer's neighborhood bitmap and tags the peer
     * for neighborhood-aware routing. Bridge peers are prioritized in routing.
     */
    fun processRemoteNeighborhoodForPeer(peerDeviceId: String, remoteBitmap: ByteArray): String =
        node.processRemoteNeighborhoodForPeer(peerDeviceId, remoteBitmap)

    // ── Transfer Strategy ──────────────────────────────────────────────

    /**
     * Returns the recommended transfer strategy for a message.
     * Used by the service layer to decide BLE mesh vs Wi-Fi Direct.
     */
    fun recommendTransferStrategy(contentType: UByte, payloadBytes: UInt): TransferRecommendation {
        val ffi = node.recommendTransferStrategy(contentType, payloadBytes)
        return TransferRecommendation(
            strategy = ffi.strategy.toString().lowercase(),
            sizeTier = ffi.sizeTier,
            estimatedBleChunks = ffi.estimatedBleChunks.toInt(),
            isOversized = ffi.isOversized,
        )
    }

    // ── Wi-Fi Direct Transfer Queue ────────────────────────────────────

    fun wifiDirectEnqueue(
        transferIdHex: String,
        recipientDeviceId: String,
        payload: ByteArray,
        contentType: UByte,
        nowSecs: ULong,
    ): Boolean = try {
        node.wifiDirectEnqueue(transferIdHex, recipientDeviceId, payload, contentType, nowSecs)
    } catch (e: Exception) {
        Timber.w(e, "Wi-Fi Direct enqueue failed")
        false
    }

    fun wifiDirectNextTransfer(peerDeviceId: String): ByteArray? = try {
        node.wifiDirectNextTransfer(peerDeviceId)
    } catch (e: Exception) {
        Timber.w(e, "Wi-Fi Direct next transfer failed")
        null
    }

    fun wifiDirectCompleteTransfer(transferIdHex: String): Boolean = try {
        node.wifiDirectCompleteTransfer(transferIdHex)
    } catch (e: Exception) {
        false
    }

    fun wifiDirectFailTransfer(transferIdHex: String): Boolean = try {
        node.wifiDirectFailTransfer(transferIdHex)
    } catch (e: Exception) {
        false
    }

    fun wifiDirectConnectionChanged(state: String, peerDeviceId: String?) {
        try {
            node.wifiDirectConnectionChanged(state, peerDeviceId)
        } catch (e: Exception) {
            Timber.w(e, "Wi-Fi Direct connection state update failed")
        }
    }

    fun wifiDirectMostNeededPeer(): String? = node.wifiDirectMostNeededPeer()

    fun wifiDirectHasPending(): Boolean = node.wifiDirectHasPending()

    fun wifiDirectPruneExpired(nowSecs: ULong): Int = node.wifiDirectPruneExpired(nowSecs).toInt()

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

    // ── Rendezvous Discovery ─────────────────────────────────────────

    /**
     * Starts a shared-phrase rendezvous search.
     * Returns serialized broadcast RouteRequest message for BLE transmission.
     */
    suspend fun startPassphraseSearch(passphrase: String): ByteArray =
        withContext(Dispatchers.IO) {
            node.startPassphraseSearch(passphrase)
        }

    /**
     * Starts a phone-number rendezvous search.
     * Returns serialized broadcast RouteRequest message for BLE transmission.
     */
    suspend fun startPhoneSearch(myPhone: String, theirPhone: String): ByteArray =
        withContext(Dispatchers.IO) {
            node.startPhoneSearch(myPhone, theirPhone)
        }

    /**
     * Registers the user's phone number for incoming rendezvous searches.
     * Only a hash is stored — the phone number itself is never persisted.
     */
    suspend fun registerMyPhone(phoneNumber: String) =
        withContext(Dispatchers.IO) {
            node.registerMyPhone(phoneNumber)
        }

    /**
     * Imports phone contacts and pre-computes bilateral rendezvous tokens.
     * Returns the number of tokens generated.
     */
    suspend fun importPhoneContacts(myPhone: String, contacts: List<String>): UInt =
        withContext(Dispatchers.IO) {
            node.importPhoneContacts(myPhone, contacts)
        }

    /**
     * Cancels an active rendezvous search.
     */
    suspend fun cancelSearch(tokenHex: String) =
        withContext(Dispatchers.IO) {
            node.cancelSearch(tokenHex)
        }

    /**
     * Builds broadcast messages for all active rendezvous searches.
     * Called periodically by MeshService.
     */
    suspend fun buildRendezvousBroadcasts(): List<ByteArray> =
        withContext(Dispatchers.IO) {
            node.buildRendezvousBroadcasts()
        }

    /**
     * Processes an incoming RouteRequest — checks for token match and returns reply bytes.
     */
    suspend fun processRendezvousRequest(rawPayload: ByteArray, senderDeviceId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            node.processRendezvousRequest(rawPayload, senderDeviceId)
        }

    /**
     * Processes an incoming RouteReply — decrypts discovered contact identity.
     */
    suspend fun processRendezvousReply(rawPayload: ByteArray): uniffi.flare_core.FfiDiscoveredContact? =
        withContext(Dispatchers.IO) {
            node.processRendezvousMessage(rawPayload, 0x11u)
        }

    /**
     * Returns the number of active rendezvous searches.
     */
    fun activeSearchCount(): Int = node.activeSearchCount().toInt()

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
    val messageId: String? = null,
)

data class TransferRecommendation(
    val strategy: String,   // "meshrelay", "directpreferred", "directrequired"
    val sizeTier: String,   // "small", "medium", "large"
    val estimatedBleChunks: Int,
    val isOversized: Boolean,
)
