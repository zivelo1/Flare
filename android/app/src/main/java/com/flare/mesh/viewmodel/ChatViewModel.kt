package com.flare.mesh.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flare.mesh.data.model.*
import com.flare.mesh.data.repository.FlareRepository
import com.flare.mesh.service.MeshService
import com.flare.mesh.util.Constants
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

/**
 * ViewModel for the conversation list and individual chat screens.
 * Bridges the Rust core (via FlareRepository) to the Compose UI.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: FlareRepository by lazy { FlareRepository.getInstance() }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        application.getSystemService<VibratorManager>()?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        application.getSystemService<Vibrator>()
    }

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val currentMessages: StateFlow<List<ChatMessage>> = _currentMessages.asStateFlow()

    private val _currentContact = MutableStateFlow<Contact?>(null)
    val currentContact: StateFlow<Contact?> = _currentContact.asStateFlow()

    val meshStatus: StateFlow<MeshStatus> = MeshService.meshStatus
    val isServiceRunning: StateFlow<Boolean> = MeshService.isRunning

    val broadcastContacts: StateFlow<List<Contact>> = repository.contacts

    private val _broadcastResult = MutableStateFlow<Int?>(null)
    val broadcastResult: StateFlow<Int?> = _broadcastResult.asStateFlow()

    init {
        viewModelScope.launch {
            repository.contacts.collect { contacts ->
                updateConversationList(contacts)
            }
        }

        // Collect incoming messages delivered by MeshService
        viewModelScope.launch {
            MeshService.incomingDelivered.collect { delivered ->
                onIncomingMessage(delivered.senderId, delivered.plaintext)
            }
        }
    }

    fun loadConversation(conversationId: String) {
        val contact = repository.contacts.value.find { it.identity.deviceId == conversationId }
        _currentContact.value = contact

        // Load persisted messages from the encrypted database
        viewModelScope.launch {
            try {
                val stored = repository.getMessagesForConversation(conversationId)
                _currentMessages.value = stored
            } catch (e: Exception) {
                Timber.e(e, "Failed to load messages for %s", conversationId.take(12))
                _currentMessages.value = emptyList()
            }
        }
    }

    fun sendMessage(conversationId: String, text: String) {
        if (text.isBlank()) return

        val contact = _currentContact.value ?: run {
            Timber.w("Cannot send: no contact loaded for conversation %s", conversationId)
            return
        }

        // Optimistically add the message to the UI
        val message = ChatMessage(
            messageId = System.nanoTime().toString(),
            conversationId = conversationId,
            senderDeviceId = repository.getDeviceId(),
            content = text.trim(),
            timestamp = Instant.now(),
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.PENDING,
        )
        _currentMessages.value = _currentMessages.value + message

        viewModelScope.launch {
            try {
                val serialized = repository.sendMessage(
                    recipientDeviceId = conversationId,
                    recipientAgreementKey = contact.identity.agreementPublicKey,
                    plaintext = text.trim(),
                )

                // Hand off to MeshService for BLE transmission
                MeshService.enqueueOutbound(conversationId, serialized)

                // Update delivery status to SENT
                _currentMessages.value = _currentMessages.value.map { msg ->
                    if (msg.messageId == message.messageId) {
                        msg.copy(deliveryStatus = DeliveryStatus.SENT)
                    } else msg
                }

                updateConversationList(repository.contacts.value)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send message to %s", conversationId.take(12))
                _currentMessages.value = _currentMessages.value.map { msg ->
                    if (msg.messageId == message.messageId) {
                        msg.copy(deliveryStatus = DeliveryStatus.FAILED)
                    } else msg
                }
            }
        }
    }

    /**
     * Sends a voice message by reading the audio file, encoding it, and sending via mesh.
     */
    fun sendVoiceMessage(conversationId: String, audioFilePath: String) {
        val contact = _currentContact.value ?: run {
            Timber.w("Cannot send voice: no contact loaded for conversation %s", conversationId)
            return
        }

        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) {
            Timber.e("Voice file does not exist: %s", audioFilePath)
            return
        }

        val audioBytes = audioFile.readBytes()
        Timber.d("Sending voice message: %d bytes from %s", audioBytes.size, audioFilePath)

        // Build the full media payload for local storage and rendering
        val encoded = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
        val mediaContent = "${Constants.MEDIA_PREFIX_VOICE}$encoded"

        // Optimistically add to UI with full media content
        val message = ChatMessage(
            messageId = System.nanoTime().toString(),
            conversationId = conversationId,
            senderDeviceId = repository.getDeviceId(),
            content = mediaContent,
            timestamp = Instant.now(),
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.PENDING,
        )
        _currentMessages.value = _currentMessages.value + message

        viewModelScope.launch {
            try {
                val serialized = repository.sendMediaMessage(
                    recipientDeviceId = conversationId,
                    recipientAgreementKey = contact.identity.agreementPublicKey,
                    mediaBytes = audioBytes,
                    contentType = 2u,
                    displayText = mediaContent,
                )
                MeshService.enqueueOutbound(conversationId, serialized)
                _currentMessages.value = _currentMessages.value.map { msg ->
                    if (msg.messageId == message.messageId) msg.copy(deliveryStatus = DeliveryStatus.SENT) else msg
                }
                updateConversationList(repository.contacts.value)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send voice message to %s", conversationId.take(12))
                _currentMessages.value = _currentMessages.value.map { msg ->
                    if (msg.messageId == message.messageId) msg.copy(deliveryStatus = DeliveryStatus.FAILED) else msg
                }
            } finally {
                // Clean up the temp recording file
                audioFile.delete()
            }
        }
    }

    /**
     * Sends an image message by reading, compressing, encoding, and sending via mesh.
     */
    fun sendImageMessage(conversationId: String, imageUri: Uri) {
        val contact = _currentContact.value ?: run {
            Timber.w("Cannot send image: no contact loaded for conversation %s", conversationId)
            return
        }

        val context = getApplication<Application>()
        val imageBytes = try {
            val inputStream = context.contentResolver.openInputStream(imageUri) ?: run {
                Timber.e("Cannot open image URI: %s", imageUri)
                return
            }
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            if (original == null) {
                Timber.e("Failed to decode image from URI: %s", imageUri)
                return
            }

            // Scale down to max dimension for mesh transfer
            val scaled = scaleDown(original, Constants.IMAGE_MAX_DIMENSION)
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, Constants.IMAGE_COMPRESS_QUALITY, outputStream)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            outputStream.toByteArray()
        } catch (e: Exception) {
            Timber.e(e, "Failed to read image from URI: %s", imageUri)
            return
        }

        Timber.d("Sending image message: %d bytes (compressed)", imageBytes.size)

        // Build the full media payload for local storage and rendering
        val encoded = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        val mediaContent = "${Constants.MEDIA_PREFIX_IMAGE}$encoded"

        val message = ChatMessage(
            messageId = System.nanoTime().toString(),
            conversationId = conversationId,
            senderDeviceId = repository.getDeviceId(),
            content = mediaContent,
            timestamp = Instant.now(),
            isOutgoing = true,
            deliveryStatus = DeliveryStatus.PENDING,
        )
        _currentMessages.value = _currentMessages.value + message

        viewModelScope.launch {
            try {
                val serialized = repository.sendMediaMessage(
                    recipientDeviceId = conversationId,
                    recipientAgreementKey = contact.identity.agreementPublicKey,
                    mediaBytes = imageBytes,
                    contentType = 3u,
                    displayText = mediaContent,
                )
                MeshService.enqueueOutbound(conversationId, serialized)
                _currentMessages.value = _currentMessages.value.map { msg ->
                    if (msg.messageId == message.messageId) msg.copy(deliveryStatus = DeliveryStatus.SENT) else msg
                }
                updateConversationList(repository.contacts.value)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send image message to %s", conversationId.take(12))
                _currentMessages.value = _currentMessages.value.map { msg ->
                    if (msg.messageId == message.messageId) msg.copy(deliveryStatus = DeliveryStatus.FAILED) else msg
                }
            }
        }
    }

    /**
     * Scales a bitmap down to fit within maxDimension while maintaining aspect ratio.
     */
    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun onIncomingMessage(senderId: String, plaintext: String) {
        val message = ChatMessage(
            messageId = System.nanoTime().toString(),
            conversationId = senderId,
            senderDeviceId = senderId,
            content = plaintext,
            timestamp = Instant.now(),
            isOutgoing = false,
        )

        // If currently viewing this conversation, add to current messages
        if (_currentContact.value?.identity?.deviceId == senderId) {
            _currentMessages.value = _currentMessages.value + message
        }

        // Trigger notification-style vibration
        triggerIncomingVibration()

        updateConversationList(repository.contacts.value)
    }

    /**
     * Sends a broadcast message to all contacts.
     */
    fun sendBroadcast(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            try {
                val count = repository.sendBroadcast(text.trim())
                _broadcastResult.value = count
                updateConversationList(repository.contacts.value)
                Timber.i("Broadcast sent to %d contacts", count)
            } catch (e: Exception) {
                Timber.e(e, "Failed to send broadcast")
                _broadcastResult.value = 0
            }
        }
    }

    /**
     * Triggers a notification-style vibration pattern for incoming messages.
     * Uses the pattern defined in [Constants.HAPTIC_INCOMING_PATTERN].
     */
    private fun triggerIncomingVibration() {
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(
                    Constants.HAPTIC_INCOMING_PATTERN,
                    Constants.HAPTIC_INCOMING_AMPLITUDES,
                    -1, // No repeat
                )
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(Constants.HAPTIC_INCOMING_PATTERN, -1)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to trigger incoming message vibration")
        }
    }

    private fun updateConversationList(contacts: List<Contact>) {
        _conversations.value = contacts.map { contact ->
            Conversation(
                id = contact.identity.deviceId,
                contact = contact,
                lastMessage = _currentMessages.value
                    .filter { it.conversationId == contact.identity.deviceId }
                    .maxByOrNull { it.timestamp },
                unreadCount = 0,
            )
        }
    }
}
