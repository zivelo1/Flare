package com.flare.mesh.data.repository

import android.content.Context
import com.flare.mesh.data.model.*
import com.flare.mesh.util.Constants
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
                    val node = try {
                        FlareNode(dbPath, passphrase)
                    } catch (e: Exception) {
                        // Database may be from an older version with incompatible key derivation.
                        // Delete it and start fresh — the user gets a new identity but the app works.
                        Timber.w(e, "Failed to open existing database — resetting (likely key derivation migration)")
                        val dbFile = File(dbPath)
                        val walFile = File("$dbPath-wal")
                        val shmFile = File("$dbPath-shm")
                        dbFile.delete()
                        walFile.delete()
                        shmFile.delete()
                        FlareNode(dbPath, passphrase)
                    }
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
     * Encrypts and sends a media message (voice or image).
     * Reads binary data, Base64-encodes it, encrypts, and builds a mesh message
     * with the appropriate content type.
     *
     * @param contentType 2u for voice, 3u for image
     */
    suspend fun sendMediaMessage(
        recipientDeviceId: String,
        recipientAgreementKey: ByteArray,
        mediaBytes: ByteArray,
        contentType: UByte,
        displayText: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val messageId = UUID.randomUUID().toString()

        // Base64-encode the binary data for transport through the text-based encryption pipeline
        val encoded = android.util.Base64.encodeToString(mediaBytes, android.util.Base64.NO_WRAP)

        // Prefix identifies the media type for the receiver's UI
        val prefix = when (contentType.toInt()) {
            0x02 -> Constants.MEDIA_PREFIX_VOICE
            0x03 -> Constants.MEDIA_PREFIX_IMAGE
            else -> ""
        }
        val payload = "$prefix$encoded"

        // Encrypt the payload
        val encrypted = node.createEncryptedMessage(
            recipientDeviceId, recipientAgreementKey, payload,
        )

        // Build mesh message with correct content type
        val meshMsg = node.buildMeshMessage(recipientDeviceId, encrypted, contentType)

        // Store locally with display text (not the full Base64 data)
        node.storeChatMessage(FfiChatMessage(
            messageId = messageId,
            conversationId = recipientDeviceId,
            senderDeviceId = getDeviceId(),
            content = displayText,
            timestampMs = Instant.now().toEpochMilli(),
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.PENDING.ordinal.toUByte(),
        ))

        // Queue for outbound delivery
        node.queueOutboundMessage(messageId, recipientDeviceId, encrypted)

        Timber.d("Media message queued for %s (type=%d, size=%d bytes)", recipientDeviceId.take(12), contentType.toInt(), mediaBytes.size)
        meshMsg.serialized
    }

    /**
     * Sends a KeyExchange message to a recipient, containing our public identity.
     * This allows the recipient to auto-add us and decrypt our messages
     * without needing to scan our QR code.
     *
     * Payload format: deviceId|signingKeyHex|agreementKeyHex|displayName
     * The payload is NOT encrypted — it contains only public keys.
     */
    suspend fun sendKeyExchange(
        recipientDeviceId: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val identity = getPublicIdentity()
        val signingHex = identity.signingPublicKey.joinToString("") { "%02x".format(it) }
        val agreementHex = identity.agreementPublicKey.joinToString("") { "%02x".format(it) }

        val payload = listOf(
            identity.deviceId,
            signingHex,
            agreementHex,
        ).joinToString(Constants.KEY_EXCHANGE_SEPARATOR)

        // KeyExchange is NOT encrypted — it contains only public keys
        // Build mesh message with content_type=4 (KeyExchange)
        val meshMsg = node.buildMeshMessage(
            recipientDeviceId,
            payload.toByteArray(Charsets.UTF_8),
            Constants.CONTENT_TYPE_KEY_EXCHANGE,
        )

        Timber.i("KeyExchange sent to %s", recipientDeviceId.take(12))
        meshMsg.serialized
    }

    /**
     * Processes an incoming raw mesh message from BLE.
     * Returns the routing decision and optionally the decrypted text (if for us).
     *
     * Handles KeyExchange messages by auto-adding the sender as an unverified contact.
     */
    suspend fun processIncomingMessage(rawData: ByteArray): IncomingMessageResult =
        withContext(Dispatchers.IO) {
            val decision = node.routeIncoming(rawData)

            when (decision) {
                FfiRouteDecision.DELIVER_LOCALLY -> {
                    val parsed = node.parseMeshMessage(rawData)
                    if (parsed != null) {
                        // Handle KeyExchange messages: auto-add sender as unverified contact
                        if (parsed.contentType.toInt() == Constants.CONTENT_TYPE_KEY_EXCHANGE.toInt()) {
                            handleKeyExchange(parsed)
                            return@withContext IncomingMessageResult(
                                decision = RouteDecisionType.DELIVER_LOCALLY,
                                senderId = parsed.senderId,
                                plaintext = null,
                                messageId = parsed.messageId,
                                isKeyExchange = true,
                            )
                        }

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

                        if (plaintext == null && senderContact == null) {
                            Timber.w("Message from unknown sender %s — no agreement key to decrypt", parsed.senderId.take(12))
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
     * Handles an incoming KeyExchange message by auto-adding the sender.
     * The payload contains the sender's public keys in cleartext.
     * Format: deviceId|signingKeyHex|agreementKeyHex
     */
    private suspend fun handleKeyExchange(parsed: uniffi.flare_core.FfiMeshMessage) {
        try {
            val payloadStr = String(parsed.payload, Charsets.UTF_8)
            val parts = payloadStr.split(Constants.KEY_EXCHANGE_SEPARATOR)

            if (parts.size < Constants.KEY_EXCHANGE_MIN_FIELDS) {
                Timber.w("Invalid KeyExchange payload: expected %d fields, got %d",
                    Constants.KEY_EXCHANGE_MIN_FIELDS, parts.size)
                return
            }

            val senderDeviceId = parts[0]
            val signingKeyHex = parts[1]
            val agreementKeyHex = parts[2]

            // Validate key lengths
            if (signingKeyHex.length != Constants.HEX_PUBLIC_KEY_LENGTH ||
                agreementKeyHex.length != Constants.HEX_PUBLIC_KEY_LENGTH) {
                Timber.w("Invalid key lengths in KeyExchange: sk=%d ak=%d",
                    signingKeyHex.length, agreementKeyHex.length)
                return
            }

            // Check if we already have this contact
            val existing = _contacts.value.find { it.identity.deviceId == senderDeviceId }
            if (existing != null) {
                Timber.d("KeyExchange from known contact %s — ignoring", senderDeviceId.take(12))
                return
            }

            // Auto-add as unverified contact
            val signingKey = hexToBytes(signingKeyHex)
            val agreementKey = hexToBytes(agreementKeyHex)

            node.addContact(FfiContact(
                deviceId = senderDeviceId,
                signingPublicKey = signingKey,
                agreementPublicKey = agreementKey,
                displayName = null,
                isVerified = false,
            ))

            refreshContacts()
            Timber.i("Auto-added contact via KeyExchange: %s", senderDeviceId.take(12))
        } catch (e: Exception) {
            Timber.e(e, "Failed to process KeyExchange")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
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

    /**
     * Deletes a contact and all associated data (conversation, messages).
     * Returns true if the contact was found and deleted.
     */
    suspend fun deleteContact(deviceId: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = node.deleteContact(deviceId)
            if (result) {
                refreshContacts()
            }
            result
        }

    /**
     * Updates the display name of an existing contact.
     * Returns true if the contact was found and updated.
     */
    suspend fun updateContactDisplayName(deviceId: String, displayName: String): Boolean =
        withContext(Dispatchers.IO) {
            val result = node.updateContactDisplayName(deviceId, displayName)
            if (result) {
                refreshContacts()
            }
            result
        }

    /**
     * Sends a broadcast message to all contacts.
     * Broadcasts are signed but NOT encrypted — readable by all recipients.
     * Returns the number of contacts the message was sent to.
     */
    suspend fun sendBroadcast(plaintext: String): Int = withContext(Dispatchers.IO) {
        val contacts = node.listContacts()
        if (contacts.isEmpty()) return@withContext 0

        // Build broadcast mesh message (signed, not encrypted)
        val meshMsg = node.buildBroadcastMessage(plaintext.toByteArray(Charsets.UTF_8), 1u)

        // Store locally as an outgoing message for each contact
        val messageId = UUID.randomUUID().toString()
        contacts.forEach { contact ->
            node.storeChatMessage(FfiChatMessage(
                messageId = "${messageId}-${contact.deviceId.take(8)}",
                conversationId = contact.deviceId,
                senderDeviceId = getDeviceId(),
                content = plaintext,
                timestampMs = Instant.now().toEpochMilli(),
                isOutgoing = true,
                deliveryStatus = DeliveryStatus.SENT.ordinal.toUByte(),
            ))

            // Also send individually encrypted for privacy
            try {
                val encrypted = node.createEncryptedMessage(
                    contact.deviceId, contact.agreementPublicKey, plaintext,
                )
                val individualMsg = node.buildMeshMessage(contact.deviceId, encrypted, 1u)
                node.queueOutboundMessage(
                    "${messageId}-${contact.deviceId.take(8)}",
                    contact.deviceId,
                    encrypted,
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to send broadcast to %s", contact.deviceId.take(12))
            }
        }

        contacts.size
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
     *
     * Note: Delegates to processRemoteNeighborhood (peer-specific variant not yet in FFI).
     */
    fun processRemoteNeighborhoodForPeer(peerDeviceId: String, remoteBitmap: ByteArray): String =
        node.processRemoteNeighborhood(remoteBitmap)

    // ── Transfer Strategy ──────────────────────────────────────────────

    /**
     * Returns the recommended transfer strategy for a message.
     * Used by the service layer to decide BLE mesh vs Wi-Fi Direct.
     */
    fun recommendTransferStrategy(contentType: UByte, payloadBytes: UInt): TransferRecommendation {
        // Transfer strategy recommendation not yet exposed via FFI — use heuristic defaults.
        val isLarge = payloadBytes > 50_000u
        return TransferRecommendation(
            strategy = if (isLarge) "directpreferred" else "meshrelay",
            sizeTier = if (payloadBytes < 5_000u) "small" else if (payloadBytes < 100_000u) "medium" else "large",
            estimatedBleChunks = (payloadBytes / 512u + 1u).toInt(),
            isOversized = payloadBytes > 1_000_000u,
        )
    }

    // ── Wi-Fi Direct Transfer Queue ────────────────────────────────────

    // Wi-Fi Direct transfer queue methods — not yet exposed via FFI.
    // Stubbed with no-op defaults to allow compilation; will delegate to FFI once available.

    fun wifiDirectEnqueue(
        transferIdHex: String,
        recipientDeviceId: String,
        payload: ByteArray,
        contentType: UByte,
        nowSecs: ULong,
    ): Boolean {
        Timber.d("Wi-Fi Direct enqueue stub: %s -> %s", transferIdHex, recipientDeviceId)
        return false
    }

    fun wifiDirectNextTransfer(peerDeviceId: String): ByteArray? = null

    fun wifiDirectCompleteTransfer(transferIdHex: String): Boolean = false

    fun wifiDirectFailTransfer(transferIdHex: String): Boolean = false

    fun wifiDirectConnectionChanged(state: String, peerDeviceId: String?) {
        Timber.d("Wi-Fi Direct connection changed stub: state=%s peer=%s", state, peerDeviceId)
    }

    fun wifiDirectMostNeededPeer(): String? = null

    fun wifiDirectHasPending(): Boolean = false

    fun wifiDirectPruneExpired(nowSecs: ULong): Int = 0

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

    // ── Duress PIN ──────────────────────────────────────────────────────

    /**
     * Sets a duress passphrase. When entered at login, a decoy database opens.
     */
    suspend fun setDuressPassphrase(passphrase: String) =
        withContext(Dispatchers.IO) {
            node.setDuressPassphrase(passphrase)
        }

    /**
     * Checks if a duress passphrase has been configured.
     */
    suspend fun hasDuressPassphrase(): Boolean =
        withContext(Dispatchers.IO) {
            node.hasDuressPassphrase()
        }

    /**
     * Clears the duress passphrase configuration.
     */
    suspend fun clearDuressPassphrase() =
        withContext(Dispatchers.IO) {
            node.clearDuressPassphrase()
        }

    // ── Power Management ────────────────────────────────────────────────

    // Power management methods — not yet exposed via FFI.
    // Stubbed with sensible defaults to allow compilation.

    private var _batterySaverEnabled = false

    /**
     * Enables or disables battery saver mode in the power manager.
     */
    fun powerSetBatterySaver(enabled: Boolean) {
        _batterySaverEnabled = enabled
        Timber.d("Battery saver %s (local stub)", if (enabled) "enabled" else "disabled")
    }

    /**
     * Updates the current battery level in the power manager.
     */
    fun powerUpdateBattery(percent: Int) {
        Timber.d("Battery level updated to %d%% (local stub)", percent)
    }

    /**
     * Returns the current power tier name.
     */
    fun powerCurrentTier(): String = if (_batterySaverEnabled) "low" else "normal"

    /**
     * Evaluates and returns the recommended power tier with parameters.
     */
    fun powerEvaluate(nowSecs: Long): PowerTierInfo {
        val tier = powerCurrentTier()
        return PowerTierInfo(
            tier = tier,
            scanWindowMs = if (_batterySaverEnabled) 2000L else 5000L,
            scanIntervalMs = if (_batterySaverEnabled) 10000L else 5000L,
            advertiseIntervalMs = if (_batterySaverEnabled) 1000L else 400L,
            burstScanDurationMs = 10000L,
            burstSleepDurationMs = 30000L,
            useBurstMode = _batterySaverEnabled,
        )
    }

    // ── Groups ──────────────────────────────────────────────────────────

    /**
     * Creates a new group and adds the creator as the first member.
     */
    suspend fun createGroup(groupId: String, groupName: String) =
        withContext(Dispatchers.IO) {
            node.createGroup(groupId, groupName)
        }

    /**
     * Adds a member to a group.
     */
    suspend fun addGroupMember(groupId: String, deviceId: String) =
        withContext(Dispatchers.IO) {
            node.addGroupMember(groupId, deviceId)
        }

    /**
     * Removes a member from a group.
     */
    suspend fun removeGroupMember(groupId: String, deviceId: String) =
        withContext(Dispatchers.IO) {
            node.removeGroupMember(groupId, deviceId)
        }

    /**
     * Lists all groups.
     */
    suspend fun listGroups(): List<Group> =
        withContext(Dispatchers.IO) {
            node.listGroups().map { ffi ->
                val members = node.getGroupMembers(ffi.groupId)
                Group(
                    groupId = ffi.groupId,
                    groupName = ffi.groupName,
                    createdAt = ffi.createdAt,
                    creatorDeviceId = ffi.creatorDeviceId,
                    memberCount = members.size,
                )
            }
        }

    /**
     * Gets member device IDs for a group.
     */
    suspend fun getGroupMembers(groupId: String): List<String> =
        withContext(Dispatchers.IO) {
            node.getGroupMembers(groupId)
        }

    /**
     * Builds encrypted mesh messages for all group members.
     */
    suspend fun buildGroupMessages(
        groupId: String,
        encryptedPayloads: List<ByteArray>,
        memberDeviceIds: List<String>,
    ): List<ByteArray> = withContext(Dispatchers.IO) {
        node.buildGroupMessages(groupId, encryptedPayloads, memberDeviceIds)
            .map { it.serialized }
    }
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
    val isKeyExchange: Boolean = false,
)

data class TransferRecommendation(
    val strategy: String,   // "meshrelay", "directpreferred", "directrequired"
    val sizeTier: String,   // "small", "medium", "large"
    val estimatedBleChunks: Int,
    val isOversized: Boolean,
)
