package com.flare.mesh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flare.mesh.data.model.Contact
import com.flare.mesh.data.model.DeviceIdentity
import com.flare.mesh.data.repository.FlareRepository
import com.flare.mesh.service.MeshService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel for the contacts screen.
 * Manages contact list from the Rust encrypted database.
 */
class ContactsViewModel : ViewModel() {

    private val repository: FlareRepository by lazy { FlareRepository.getInstance() }

    val contacts: StateFlow<List<Contact>> = repository.contacts
    val meshStatus = MeshService.meshStatus

    /**
     * Returns the local device's public identity for QR code display.
     */
    fun getMyPublicIdentity(): DeviceIdentity = repository.getPublicIdentity()

    /**
     * Returns the safety number for verification display.
     */
    fun getSafetyNumber(): String = repository.getSafetyNumber()

    /**
     * Adds a contact from scanned QR code data.
     * QR format: deviceId|signingKey(hex)|agreementKey(hex)|displayName
     */
    fun addContactFromQr(qrData: String) {
        viewModelScope.launch {
            try {
                val parts = qrData.split("|")
                if (parts.size < 3) {
                    Timber.w("Invalid QR data format")
                    return@launch
                }

                val deviceId = parts[0]
                val signingKey = hexToBytes(parts[1])
                val agreementKey = hexToBytes(parts[2])
                val displayName = parts.getOrNull(3)

                repository.addContact(
                    deviceId = deviceId,
                    signingPublicKey = signingKey,
                    agreementPublicKey = agreementKey,
                    displayName = displayName,
                    isVerified = true, // QR scan = verified
                )
                Timber.i("Contact added via QR: %s", deviceId.take(12))
            } catch (e: Exception) {
                Timber.e(e, "Failed to add contact from QR")
            }
        }
    }

    /**
     * Generates QR code data string for the local device identity.
     */
    fun generateQrData(): String {
        val identity = repository.getPublicIdentity()
        return listOf(
            identity.deviceId,
            bytesToHex(identity.signingPublicKey),
            bytesToHex(identity.agreementPublicKey),
        ).joinToString("|")
    }

    fun refreshContacts() {
        viewModelScope.launch {
            repository.refreshContacts()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
