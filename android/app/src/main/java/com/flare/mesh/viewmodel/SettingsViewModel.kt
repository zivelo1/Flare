package com.flare.mesh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flare.mesh.data.model.PowerTierInfo
import com.flare.mesh.data.repository.FlareRepository
import com.flare.mesh.data.repository.StoreStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * ViewModel for the Settings screen.
 * Manages power management and store statistics via the Rust core (FlareRepository).
 */
class SettingsViewModel : ViewModel() {

    private val repository: FlareRepository by lazy { FlareRepository.getInstance() }

    val deviceId: String get() = repository.getDeviceId()
    val safetyNumber: String get() = repository.getSafetyNumber()

    private val _currentPowerTier = MutableStateFlow("")
    val currentPowerTier: StateFlow<String> = _currentPowerTier.asStateFlow()

    private val _batterySaverEnabled = MutableStateFlow(false)
    val batterySaverEnabled: StateFlow<Boolean> = _batterySaverEnabled.asStateFlow()

    private val _storeStats = MutableStateFlow<StoreStats?>(null)
    val storeStats: StateFlow<StoreStats?> = _storeStats.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _currentPowerTier.value = repository.powerCurrentTier()
            } catch (e: Exception) {
                Timber.e(e, "Failed to load current power tier")
            }
        }

        viewModelScope.launch {
            try {
                _storeStats.value = repository.getStoreStats()
            } catch (e: Exception) {
                Timber.e(e, "Failed to load store stats")
            }
        }
    }

    fun toggleBatterySaver() {
        val newValue = !_batterySaverEnabled.value
        try {
            repository.powerSetBatterySaver(newValue)
            _batterySaverEnabled.value = newValue
            _currentPowerTier.value = repository.powerCurrentTier()
            Timber.i("Battery saver %s", if (newValue) "enabled" else "disabled")
        } catch (e: Exception) {
            Timber.e(e, "Failed to toggle battery saver")
        }
    }

    fun refreshStats() {
        viewModelScope.launch {
            try {
                _storeStats.value = repository.getStoreStats()
                _currentPowerTier.value = repository.powerCurrentTier()
            } catch (e: Exception) {
                Timber.e(e, "Failed to refresh stats")
            }
        }
    }
}
