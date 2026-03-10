package com.flare.mesh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flare.mesh.data.model.Contact
import com.flare.mesh.data.model.Group
import com.flare.mesh.data.repository.FlareRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Status of group creation operation.
 */
sealed class CreateGroupStatus {
    object Idle : CreateGroupStatus()
    object Creating : CreateGroupStatus()
    data class Success(val groupId: String) : CreateGroupStatus()
    data class Error(val message: String) : CreateGroupStatus()
}

/**
 * ViewModel for the group messaging screen.
 * Manages group creation, member selection, and group lifecycle.
 */
class GroupViewModel : ViewModel() {

    private val repository: FlareRepository by lazy { FlareRepository.getInstance() }

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val _selectedGroupMembers = MutableStateFlow<List<String>>(emptyList())
    val selectedGroupMembers: StateFlow<List<String>> = _selectedGroupMembers.asStateFlow()

    private val _availableContacts = MutableStateFlow<List<Contact>>(emptyList())
    val availableContacts: StateFlow<List<Contact>> = _availableContacts.asStateFlow()

    private val _createGroupStatus = MutableStateFlow<CreateGroupStatus>(CreateGroupStatus.Idle)
    val createGroupStatus: StateFlow<CreateGroupStatus> = _createGroupStatus.asStateFlow()

    init {
        refreshGroups()
        loadAvailableContacts()
    }

    /**
     * Fetches the current list of groups from the repository.
     */
    fun refreshGroups() {
        viewModelScope.launch {
            try {
                _groups.value = repository.listGroups()
                Timber.d("Refreshed groups: %d groups loaded", _groups.value.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh groups")
            }
        }
    }

    /**
     * Creates a new group with the given name and adds the currently selected members.
     */
    fun createGroup(groupName: String) {
        viewModelScope.launch {
            _createGroupStatus.value = CreateGroupStatus.Creating
            try {
                val groupId = UUID.randomUUID().toString()
                repository.createGroup(groupId, groupName)
                Timber.i("Created group '%s' with id %s", groupName, groupId.take(12))

                for (deviceId in _selectedGroupMembers.value) {
                    repository.addGroupMember(groupId, deviceId)
                    Timber.d("Added member %s to group %s", deviceId.take(12), groupId.take(12))
                }

                _createGroupStatus.value = CreateGroupStatus.Success(groupId)
                refreshGroups()
            } catch (e: Exception) {
                Timber.e(e, "Failed to create group '%s'", groupName)
                _createGroupStatus.value = CreateGroupStatus.Error(
                    e.message ?: "Unknown error creating group"
                )
            }
        }
    }

    /**
     * Toggles a device ID in the selected members list.
     * If already selected, removes it; otherwise adds it.
     */
    fun toggleMemberSelection(deviceId: String) {
        val current = _selectedGroupMembers.value.toMutableList()
        if (current.contains(deviceId)) {
            current.remove(deviceId)
            Timber.d("Deselected member %s", deviceId.take(12))
        } else {
            current.add(deviceId)
            Timber.d("Selected member %s", deviceId.take(12))
        }
        _selectedGroupMembers.value = current
    }

    /**
     * Clears the member selection and resets the creation status.
     */
    fun clearSelection() {
        _selectedGroupMembers.value = emptyList()
        _createGroupStatus.value = CreateGroupStatus.Idle
    }

    /**
     * Returns the member device IDs for a given group.
     */
    fun getGroupMembers(groupId: String) {
        viewModelScope.launch {
            try {
                val members = repository.getGroupMembers(groupId)
                Timber.d("Group %s has %d members", groupId.take(12), members.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to get members for group %s", groupId.take(12))
            }
        }
    }

    /**
     * Removes a member from a group and refreshes the group list.
     */
    fun removeGroupMember(groupId: String, deviceId: String) {
        viewModelScope.launch {
            try {
                repository.removeGroupMember(groupId, deviceId)
                Timber.i("Removed member %s from group %s", deviceId.take(12), groupId.take(12))
                refreshGroups()
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove member %s from group %s", deviceId.take(12), groupId.take(12))
            }
        }
    }

    private fun loadAvailableContacts() {
        viewModelScope.launch {
            try {
                repository.refreshContacts()
                _availableContacts.value = repository.contacts.value
                Timber.d("Loaded %d available contacts", _availableContacts.value.size)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load available contacts")
            }
        }
    }
}
