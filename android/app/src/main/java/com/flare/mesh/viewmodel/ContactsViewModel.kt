package com.flare.mesh.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flare.mesh.data.model.Contact
import com.flare.mesh.data.model.DeviceIdentity
import com.flare.mesh.data.repository.FlareRepository
import com.flare.mesh.service.MeshService
import com.flare.mesh.util.Constants
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
                val parts = qrData.split(Constants.QR_DATA_SEPARATOR)
                if (parts.size < Constants.QR_MIN_FIELDS) {
                    Timber.w("Invalid QR data format: expected at least %d fields", Constants.QR_MIN_FIELDS)
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
        ).joinToString(Constants.QR_DATA_SEPARATOR)
    }

    /**
     * Generates a shareable deep link URI for the local device identity.
     * Format: flare://add?id=<deviceId>&sk=<signingKey>&ak=<agreementKey>
     * Can be shared via SMS, WhatsApp, email, or any messaging channel.
     */
    fun generateShareLink(): String {
        val identity = repository.getPublicIdentity()
        return Uri.Builder()
            .scheme(Constants.DEEP_LINK_SCHEME)
            .authority(Constants.DEEP_LINK_HOST_ADD)
            .appendQueryParameter(Constants.DEEP_LINK_PARAM_ID, identity.deviceId)
            .appendQueryParameter(Constants.DEEP_LINK_PARAM_SIGNING_KEY, bytesToHex(identity.signingPublicKey))
            .appendQueryParameter(Constants.DEEP_LINK_PARAM_AGREEMENT_KEY, bytesToHex(identity.agreementPublicKey))
            .build()
            .toString()
    }

    /**
     * Parses and validates a deep link URI, returning extracted parameters.
     * Returns null if the URI is invalid or missing required fields.
     */
    fun parseDeepLink(uri: Uri): DeepLinkContact? {
        if (uri.scheme != Constants.DEEP_LINK_SCHEME || uri.host != Constants.DEEP_LINK_HOST_ADD) {
            Timber.w("Invalid deep link scheme/host: %s", uri)
            return null
        }

        val deviceId = uri.getQueryParameter(Constants.DEEP_LINK_PARAM_ID)
        val signingKeyHex = uri.getQueryParameter(Constants.DEEP_LINK_PARAM_SIGNING_KEY)
        val agreementKeyHex = uri.getQueryParameter(Constants.DEEP_LINK_PARAM_AGREEMENT_KEY)
        val displayName = uri.getQueryParameter(Constants.DEEP_LINK_PARAM_NAME)

        if (deviceId.isNullOrEmpty() || signingKeyHex.isNullOrEmpty() || agreementKeyHex.isNullOrEmpty()) {
            Timber.w("Deep link missing required parameters: %s", uri)
            return null
        }

        if (signingKeyHex.length != Constants.HEX_PUBLIC_KEY_LENGTH ||
            agreementKeyHex.length != Constants.HEX_PUBLIC_KEY_LENGTH) {
            Timber.w("Deep link has invalid key lengths: sk=%d ak=%d",
                signingKeyHex.length, agreementKeyHex.length)
            return null
        }

        return DeepLinkContact(
            deviceId = deviceId,
            signingPublicKey = hexToBytes(signingKeyHex),
            agreementPublicKey = hexToBytes(agreementKeyHex),
            displayName = displayName,
        )
    }

    /**
     * Adds a contact from a parsed deep link. Must be called from a coroutine.
     */
    fun addContactFromLink(contact: DeepLinkContact) {
        viewModelScope.launch {
            try {
                repository.addContact(
                    deviceId = contact.deviceId,
                    signingPublicKey = contact.signingPublicKey,
                    agreementPublicKey = contact.agreementPublicKey,
                    displayName = contact.displayName,
                    isVerified = false, // Link-based = not verified (no in-person confirmation)
                )
                Timber.i("Contact added via deep link: %s", contact.deviceId.take(12))
            } catch (e: Exception) {
                Timber.e(e, "Failed to add contact from deep link")
            }
        }
    }

    /**
     * Validated contact data extracted from a deep link URI.
     */
    data class DeepLinkContact(
        val deviceId: String,
        val signingPublicKey: ByteArray,
        val agreementPublicKey: ByteArray,
        val displayName: String?,
    )

    /**
     * Renames a contact by updating their display name.
     */
    fun renameContact(deviceId: String, newName: String) {
        viewModelScope.launch {
            try {
                val success = repository.updateContactDisplayName(deviceId, newName)
                if (success) {
                    Timber.i("Contact renamed: %s -> %s", deviceId.take(12), newName)
                    refreshContacts()
                } else {
                    Timber.w("Contact not found for rename: %s", deviceId.take(12))
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to rename contact: %s", deviceId.take(12))
            }
        }
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
