package com.flare.mesh.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flare.mesh.data.model.MeshPeer
import com.flare.mesh.data.model.MeshStatus
import com.flare.mesh.service.MeshService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the network dashboard screen.
 * Exposes mesh status and nearby peers from the BLE scanner.
 */
class NetworkViewModel : ViewModel() {

    val meshStatus: StateFlow<MeshStatus> = MeshService.meshStatus

    val isRunning: StateFlow<Boolean> = MeshService.isRunning

    val nearbyPeers: StateFlow<Map<String, MeshPeer>> = MeshService.discoveredPeers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
}
