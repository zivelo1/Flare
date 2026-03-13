package com.flare.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import com.flare.mesh.util.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Peripheral (GATT Server) that advertises the Flare service
 * and accepts connections from nearby mesh peers.
 *
 * Handles BLE chunking transparently: large outbound notifications are split
 * into MTU-sized chunks, and incoming writes are reassembled from chunks.
 */
class GattServer(private val context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /** Connected devices tracked by address. */
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()

    /** Negotiated MTU per connected device. */
    private val mtuMap = ConcurrentHashMap<String, Int>()

    /** Per-device notification completion signals for sequential chunk sends. */
    private val notifyCompletions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Per-device notification mutex to prevent interleaved chunk notifications. */
    private val notifyMutexes = ConcurrentHashMap<String, Mutex>()

    /** Reassembler for incoming chunked characteristic writes. */
    private val reassembler = ChunkReassembler()

    /** Our local peer info bytes (set by the application layer). */
    var localPeerInfoBytes: ByteArray = ByteArray(0)

    /** Flow of complete (reassembled) incoming messages from connected peers. */
    private val _incomingMessages = MutableSharedFlow<IncomingBleMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<IncomingBleMessage> = _incomingMessages.asSharedFlow()

    /** Flow of connection events. */
    private val _connectionEvents = MutableSharedFlow<BleConnectionEvent>(extraBufferCapacity = 16)
    val connectionEvents: SharedFlow<BleConnectionEvent> = _connectionEvents.asSharedFlow()

    data class IncomingBleMessage(
        val fromAddress: String,
        val data: ByteArray,
    )

    sealed class BleConnectionEvent {
        data class Connected(val address: String) : BleConnectionEvent()
        data class Disconnected(val address: String) : BleConnectionEvent()
    }

    /**
     * Starts the GATT server and begins BLE advertising.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (gattServer != null) {
            Timber.d("GATT server already running")
            return
        }

        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        val server = gattServer ?: run {
            Timber.e("Failed to open GATT server")
            return
        }

        // Create the Flare GATT service
        val service = BluetoothGattService(
            Constants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // Message Write characteristic (peers write messages to us)
        val messageWriteChar = BluetoothGattCharacteristic(
            Constants.CHAR_MESSAGE_WRITE_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        // Message Notify characteristic (we notify peers of outgoing messages)
        val messageNotifyChar = BluetoothGattCharacteristic(
            Constants.CHAR_MESSAGE_NOTIFY_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        // Add CCCD for notification subscription
        val cccd = BluetoothGattDescriptor(
            Constants.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        messageNotifyChar.addDescriptor(cccd)

        // Peer Info characteristic (peers read our identity)
        val peerInfoChar = BluetoothGattCharacteristic(
            Constants.CHAR_PEER_INFO_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )

        service.addCharacteristic(messageWriteChar)
        service.addCharacteristic(messageNotifyChar)
        service.addCharacteristic(peerInfoChar)

        server.addService(service)
        Timber.i("GATT server started with Flare service")

        startAdvertising(AdvertisePowerTier.BALANCED)
    }

    /**
     * Advertise mode tiers for adaptive power management.
     * Maps to Android AdvertiseSettings modes.
     */
    enum class AdvertisePowerTier {
        HIGH,      // ADVERTISE_MODE_LOW_LATENCY — fastest discovery by peers
        BALANCED,  // ADVERTISE_MODE_BALANCED — moderate interval
        LOW_POWER, // ADVERTISE_MODE_LOW_POWER — least battery, slowest discovery
    }

    private var currentAdvertiseTier: AdvertisePowerTier = AdvertisePowerTier.BALANCED

    /**
     * Starts BLE advertising with the Flare service UUID.
     * Tier controls the advertising interval for power management.
     */
    @SuppressLint("MissingPermission")
    fun startAdvertising(tier: AdvertisePowerTier = AdvertisePowerTier.BALANCED) {
        // Stop current advertising if changing tier
        if (advertiseCallback != null && tier != currentAdvertiseTier) {
            try {
                advertiseCallback?.let { advertiser?.stopAdvertising(it) }
            } catch (_: SecurityException) { }
            advertiseCallback = null
        }

        currentAdvertiseTier = tier
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Timber.e("BLE advertiser not available")
            return
        }

        val advertiseMode = when (tier) {
            AdvertisePowerTier.HIGH -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            AdvertisePowerTier.BALANCED -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            AdvertisePowerTier.LOW_POWER -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }

        val txPower = when (tier) {
            AdvertisePowerTier.HIGH -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
            AdvertisePowerTier.BALANCED -> AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM
            AdvertisePowerTier.LOW_POWER -> AdvertiseSettings.ADVERTISE_TX_POWER_LOW
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advertiseMode)
            .setTxPowerLevel(txPower)
            .setConnectable(true)
            .setTimeout(0) // Advertise indefinitely
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // Save space; don't leak device name
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(Constants.SERVICE_UUID))
            .build()

        // Scan response with additional data
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceData(
                ParcelUuid(Constants.SERVICE_UUID),
                localPeerInfoBytes.take(4).toByteArray(), // Short device ID in scan response
            )
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Timber.i("BLE advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                Timber.e("BLE advertising failed with error code: %d", errorCode)
            }
        }

        advertiseCallback = callback

        try {
            advertiser?.startAdvertising(settings, data, scanResponse, callback)
        } catch (e: SecurityException) {
            Timber.e(e, "BLE advertise permission denied")
        }
    }

    /**
     * Sends data to a connected peer via notification on the message characteristic.
     *
     * Transparently handles BLE chunking: if the data exceeds the peer's MTU,
     * it is split into chunks and sent sequentially, waiting for each
     * onNotificationSent callback before sending the next chunk.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendToPeer(address: String, data: ByteArray): Boolean {
        val server = gattServer ?: return false
        val device = connectedDevices[address] ?: return false

        val service = server.getService(Constants.SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(Constants.CHAR_MESSAGE_NOTIFY_UUID)
            ?: return false

        val mtu = getMtu(address)
        Timber.d("BLE_SEND_SERVER: %d bytes MTU=%d", data.size, mtu)
        val chunks = BleChunker.chunk(data, mtu)
        if (chunks.isEmpty()) {
            Timber.e("BLE_SEND_SERVER: CHUNK FAILED %d bytes for %s (MTU=%d, need %d chunks but max=%d)",
                data.size, address, mtu, (data.size / (mtu - 5).coerceAtLeast(1)) + 1, Constants.BLE_CHUNK_MAX_COUNT)
            return false
        }

        if (chunks.size > 1) {
            Timber.d("Chunking %d bytes into %d chunks for %s (MTU=%d)",
                data.size, chunks.size, address, mtu)
        }

        val mutex = notifyMutexes.getOrPut(address) { Mutex() }

        return mutex.withLock {
            for ((index, chunk) in chunks.withIndex()) {
                val deferred = CompletableDeferred<Boolean>()
                notifyCompletions[address] = deferred

                val notifyInitiated = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        server.notifyCharacteristicChanged(device, characteristic, false, chunk) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = chunk
                        @Suppress("DEPRECATION")
                        server.notifyCharacteristicChanged(device, characteristic, false)
                    }
                } catch (e: SecurityException) {
                    Timber.e(e, "Failed to notify peer %s", address)
                    false
                }

                if (!notifyInitiated) {
                    notifyCompletions.remove(address)
                    Timber.w("Notification failed at chunk %d/%d for %s",
                        index + 1, chunks.size, address)
                    return@withLock false
                }

                // Wait for onNotificationSent callback
                val success = withTimeoutOrNull(Constants.BLE_CHUNK_WRITE_TIMEOUT_MS) {
                    deferred.await()
                } ?: false

                notifyCompletions.remove(address)

                if (!success) {
                    Timber.w("Notification callback failed/timed out at chunk %d/%d for %s",
                        index + 1, chunks.size, address)
                    return@withLock false
                }
            }
            true
        }
    }

    /**
     * External MTU provider — used to fall back to GattClient's negotiated MTU
     * when the server hasn't received its own onMtuChanged callback yet.
     * Set by MeshService after constructing both GattServer and GattClient.
     */
    var externalMtuProvider: ((String) -> Int?)? = null

    /**
     * Returns the negotiated MTU for a connected device.
     * Falls back to external provider (GattClient MTU) before using MIN_MTU.
     */
    fun getMtu(address: String): Int =
        mtuMap[address]
            ?: externalMtuProvider?.invoke(address)
            ?: Constants.MIN_MTU

    /** Returns only the locally negotiated MTU (no fallback). Used by cross-link to avoid recursion. */
    fun getMtuDirect(address: String): Int? = mtuMap[address]

    /**
     * Stops the GATT server and advertising.
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiseCallback?.let { advertiser?.stopAdvertising(it) }
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to stop advertising")
        }

        try {
            gattServer?.close()
        } catch (e: Exception) {
            Timber.e(e, "Failed to close GATT server")
        }

        gattServer = null
        advertiser = null
        advertiseCallback = null
        connectedDevices.clear()
        mtuMap.clear()
        notifyMutexes.clear()
        Timber.i("GATT server stopped")
    }

    /** Returns the number of currently connected peers. */
    fun connectedCount(): Int = connectedDevices.size

    /** Returns addresses of all currently connected devices. */
    fun connectedDeviceAddresses(): Set<String> = connectedDevices.keys.toSet()

    /** Cleans up stale incomplete chunk reassembly buffers. */
    fun pruneChunkBuffers() = reassembler.pruneStale()

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices[device.address] = device
                    _connectionEvents.tryEmit(BleConnectionEvent.Connected(device.address))
                    Timber.i("Peer connected: %s", device.address)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device.address)
                    mtuMap.remove(device.address)
                    notifyMutexes.remove(device.address)
                    _connectionEvents.tryEmit(BleConnectionEvent.Disconnected(device.address))
                    Timber.i("Peer disconnected: %s", device.address)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            when (characteristic.uuid) {
                Constants.CHAR_PEER_INFO_UUID -> {
                    val responseData = if (offset < localPeerInfoBytes.size) {
                        localPeerInfoBytes.copyOfRange(offset, localPeerInfoBytes.size)
                    } else {
                        ByteArray(0)
                    }
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset, responseData,
                    )
                }
                else -> {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null,
                    )
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            when (characteristic.uuid) {
                Constants.CHAR_MESSAGE_WRITE_UUID -> {
                    value?.let { data ->
                        Timber.d("BLE_RECV_SERVER: write %d bytes from %s preparedWrite=%s offset=%d",
                            data.size, device.address, preparedWrite, offset)
                        val reassembled = reassembler.onDataReceived(data)
                        if (reassembled != null) {
                            _incomingMessages.tryEmit(
                                IncomingBleMessage(device.address, reassembled),
                            )
                            Timber.i("BLE_RECV_SERVER: complete message %d bytes from %s",
                                reassembled.size, device.address)
                        }
                    }
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null,
                        )
                    }
                }
                else -> {
                    if (responseNeeded) {
                        gattServer?.sendResponse(
                            device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null,
                        )
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid == Constants.CCCD_UUID) {
                // Client enabling/disabling notifications
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null,
                    )
                }
                Timber.d("Notifications %s for %s",
                    if (value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true) "enabled" else "disabled",
                    device.address,
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            // Android caps characteristic values at 512 bytes regardless of ATT_MTU.
            // Use min(ATT_MTU - 3, 512) to avoid silent truncation of notifications.
            val usable = (mtu - 3).coerceAtMost(512)
            mtuMap[device.address] = usable
            Timber.d("MTU changed to %d (usable: %d, capped from %d) for %s",
                mtu, usable, mtu - 3, device.address)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyCompletions[device.address]?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }
}
