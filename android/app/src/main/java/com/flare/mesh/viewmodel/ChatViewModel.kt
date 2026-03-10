package com.flare.mesh.viewmodel

import android.app.Application
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
