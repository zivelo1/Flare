package com.flare.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.flare.mesh.data.model.MeshPeer
import com.flare.mesh.data.model.TransportType
import com.flare.mesh.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.time.Instant

/**
 * BLE Central (scanner) that discovers nearby Flare mesh devices.
 *
 * Scans for devices advertising the Flare service UUID and emits
 * discovered peers via a StateFlow.
 */
class BleScanner(context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private val _discoveredPeers = MutableStateFlow<Map<String, MeshPeer>>(emptyMap())
    val discoveredPeers: StateFlow<Map<String, MeshPeer>> = _discoveredPeers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanCallback: ScanCallback? = null

    /**
     * Starts BLE scanning for Flare mesh devices.
     * Filters by the Flare service UUID to minimize battery impact.
     */
    @SuppressLint("MissingPermission")
    fun startScanning() {
        val scanner = bleScanner
        if (scanner == null) {
            Timber.e("BLE scanner not available — Bluetooth may be off")
            return
        }

        if (_isScanning.value) {
            Timber.d("Already scanning, ignoring duplicate start request")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0) // Immediate callbacks
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { handleScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("BLE scan failed with error code: %d", errorCode)
                _isScanning.value = false
            }
        }

        scanCallback = callback

        try {
            scanner.startScan(listOf(filter), settings, callback)
            _isScanning.value = true
            Timber.i("BLE scanning started for service UUID: %s", Constants.SERVICE_UUID)
        } catch (e: SecurityException) {
            Timber.e(e, "BLE scan permission denied")
        }
    }

    /**
     * Stops BLE scanning.
     */
    @SuppressLint("MissingPermission")
    fun stopScanning() {
        scanCallback?.let { callback ->
            try {
                bleScanner?.stopScan(callback)
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to stop BLE scan")
            }
        }
        scanCallback = null
        _isScanning.value = false
        Timber.i("BLE scanning stopped")
    }

    /**
     * Processes a BLE scan result and updates the peer map.
     */
    private fun handleScanResult(result: ScanResult) {
        val device = result.device
        val rssi = result.rssi
        val address = device.address // MAC address (rotated by OS)

        // Extract device ID from scan record if available
        val serviceData = result.scanRecord?.getServiceData(ParcelUuid(Constants.SERVICE_UUID))
        val deviceId = if (serviceData != null && serviceData.size >= 4) {
            // First 4 bytes of service data = short device ID
            serviceData.take(4).joinToString("") { "%02x".format(it) }
        } else {
            // Fallback to BLE address (will change due to MAC rotation)
            address
        }

        val peer = MeshPeer(
            deviceId = deviceId,
            rssi = rssi,
            estimatedDistanceMeters = estimateDistance(rssi),
            isConnected = false,
            transportType = TransportType.BLUETOOTH_LE,
            lastSeen = Instant.now(),
        )

        val currentPeers = _discoveredPeers.value.toMutableMap()
        currentPeers[deviceId] = peer
        _discoveredPeers.value = currentPeers

        Timber.d("Discovered peer: %s (RSSI: %d, ~%.1fm)", deviceId, rssi, peer.estimatedDistanceMeters ?: -1f)
    }

    /**
     * Removes peers that haven't been seen within the stale timeout.
     */
    fun pruneStale() {
        val cutoff = Instant.now().minusMillis(Constants.PEER_STALE_TIMEOUT_MS)
        val currentPeers = _discoveredPeers.value.toMutableMap()
        val before = currentPeers.size
        currentPeers.entries.removeAll { it.value.lastSeen.isBefore(cutoff) }
        _discoveredPeers.value = currentPeers

        val pruned = before - currentPeers.size
        if (pruned > 0) {
            Timber.d("Pruned %d stale peers", pruned)
        }
    }

    /**
     * Returns whether Bluetooth is currently enabled.
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    companion object {
        /**
         * Estimates distance in meters from BLE RSSI using log-distance path loss model.
         * Calibrated for typical smartphones in urban indoor environments.
         *
         * Reference RSSI at 1 meter: -59 dBm (typical BLE)
         * Path loss exponent: 2.7 (urban indoor with obstacles)
         */
        private const val RSSI_AT_1M = -59.0
        private const val PATH_LOSS_EXPONENT = 2.7

        fun estimateDistance(rssi: Int): Float {
            return Math.pow(10.0, (RSSI_AT_1M - rssi) / (10.0 * PATH_LOSS_EXPONENT)).toFloat()
        }
    }
}
