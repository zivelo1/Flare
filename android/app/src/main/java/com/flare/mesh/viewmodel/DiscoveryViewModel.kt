package com.flare.mesh.viewmodel

import android.content.ContentResolver
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flare.mesh.data.model.DeviceIdentity
import com.flare.mesh.data.repository.FlareRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel for the Blind Rendezvous discovery protocol.
 * Handles shared phrase search, phone number search, and contact import.
 */
class DiscoveryViewModel : ViewModel() {

    private val repository by lazy { FlareRepository.getInstance() }

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _discoveredContact = MutableStateFlow<DiscoveredContact?>(null)
    val discoveredContact: StateFlow<DiscoveredContact?> = _discoveredContact.asStateFlow()

    private val _importedCount = MutableStateFlow(0)
    val importedCount: StateFlow<Int> = _importedCount.asStateFlow()

    /**
     * Starts a shared passphrase rendezvous search.
     * Both parties must enter the same phrase.
     */
    fun startPhraseSearch(phrase: String) {
        if (phrase.isBlank()) return

        viewModelScope.launch {
            try {
                _searchState.value = SearchState.Searching(
                    mode = DiscoveryMode.SHARED_PHRASE,
                    queryHint = "Phrase: ${phrase.take(20)}...",
                )
                val broadcastBytes = repository.startPassphraseSearch(phrase)
                // Enqueue the broadcast for BLE transmission
                com.flare.mesh.service.MeshService.enqueueOutbound("broadcast", broadcastBytes)
                Timber.i("Passphrase search started")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start passphrase search")
                _searchState.value = SearchState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Starts a phone number rendezvous search.
     * Both parties compute a bilateral token from both phone numbers.
     */
    fun startPhoneSearch(myPhone: String, theirPhone: String) {
        if (myPhone.isBlank() || theirPhone.isBlank()) return

        viewModelScope.launch {
            try {
                _searchState.value = SearchState.Searching(
                    mode = DiscoveryMode.PHONE_NUMBER,
                    queryHint = "Phone: ${theirPhone.takeLast(4)}",
                )
                val broadcastBytes = repository.startPhoneSearch(myPhone, theirPhone)
                com.flare.mesh.service.MeshService.enqueueOutbound("broadcast", broadcastBytes)
                Timber.i("Phone search started")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start phone search")
                _searchState.value = SearchState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Imports phone contacts and pre-computes bilateral rendezvous tokens.
     */
    fun importContacts(contentResolver: ContentResolver, myPhone: String) {
        viewModelScope.launch {
            try {
                val phoneNumbers = readPhoneContacts(contentResolver)
                Timber.i("Read %d phone contacts", phoneNumbers.size)

                if (phoneNumbers.isNotEmpty()) {
                    // Register our phone number first
                    repository.registerMyPhone(myPhone)

                    // Import contacts and pre-compute tokens
                    val count = repository.importPhoneContacts(myPhone, phoneNumbers)
                    _importedCount.value = count.toInt()
                    Timber.i("Imported %d contact tokens", count)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to import contacts")
            }
        }
    }

    /**
     * Called when a rendezvous match is found (from MeshService).
     */
    fun onContactDiscovered(contact: DiscoveredContact) {
        _discoveredContact.value = contact
        _searchState.value = SearchState.Found(contact)
    }

    /**
     * Cancels the current search.
     */
    fun cancelSearch() {
        _searchState.value = SearchState.Idle
        _discoveredContact.value = null
    }

    /**
     * Resets state after adding the discovered contact.
     */
    fun clearDiscovery() {
        _discoveredContact.value = null
        _searchState.value = SearchState.Idle
    }

    /**
     * Reads phone numbers from the device's contact list.
     */
    private fun readPhoneContacts(contentResolver: ContentResolver): List<String> {
        val phoneNumbers = mutableSetOf<String>()

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        )

        cursor?.use {
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val number = it.getString(numberIndex)
                if (!number.isNullOrBlank()) {
                    phoneNumbers.add(number.trim())
                }
            }
        }

        return phoneNumbers.toList()
    }
}

sealed class SearchState {
    data object Idle : SearchState()
    data class Searching(val mode: DiscoveryMode, val queryHint: String) : SearchState()
    data class Found(val contact: DiscoveredContact) : SearchState()
    data class Error(val message: String) : SearchState()
}

enum class DiscoveryMode {
    SHARED_PHRASE,
    PHONE_NUMBER,
    CONTACT_IMPORT,
}

data class DiscoveredContact(
    val identity: DeviceIdentity,
    val discoveryMethod: String,
)
