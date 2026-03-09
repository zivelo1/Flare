package com.flare.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.flare.mesh.util.Constants
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * BLE Peripheral (GATT Server) that advertises the Flare service
 * and accepts connections from nearby mesh peers.
 *
 * Each connected peer can:
 * - Write messages to us via CHAR_MESSAGE_WRITE
 * - Subscribe to notifications on CHAR_MESSAGE_NOTIFY
 * - Read our peer info via CHAR_PEER_INFO
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

    /** Our local peer info bytes (set by the application layer). */
    var localPeerInfoBytes: ByteArray = ByteArray(0)

    /** Flow of incoming message data from connected peers. */
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

        startAdvertising()
    }

    /**
     * Starts BLE advertising with the Flare service UUID.
     */
    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Timber.e("BLE advertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
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
     */
    @SuppressLint("MissingPermission")
    fun sendToPeer(address: String, data: ByteArray): Boolean {
        val server = gattServer ?: return false
        val device = connectedDevices[address] ?: return false

        val service = server.getService(Constants.SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(Constants.CHAR_MESSAGE_NOTIFY_UUID) ?: return false

        return try {
            server.notifyCharacteristicChanged(device, characteristic, false, data)
            BluetoothStatusCodes.SUCCESS == BluetoothStatusCodes.SUCCESS
        } catch (e: SecurityException) {
            Timber.e(e, "Failed to notify peer %s", address)
            false
        }
    }

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
        Timber.i("GATT server stopped")
    }

    /** Returns the number of currently connected peers. */
    fun connectedCount(): Int = connectedDevices.size

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
                        _incomingMessages.tryEmit(IncomingBleMessage(device.address, data))
                        Timber.d("Received %d bytes from %s", data.size, device.address)
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

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            Timber.d("MTU changed to %d for %s", mtu, device.address)
        }
    }
}
