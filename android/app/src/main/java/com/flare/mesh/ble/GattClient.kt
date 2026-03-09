package com.flare.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import com.flare.mesh.util.Constants
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Central (GATT Client) that connects to discovered Flare peers
 * and exchanges messages via GATT characteristics.
 *
 * Manages connections to multiple peers simultaneously.
 */
class GattClient(private val context: Context) {

    /** Active GATT connections by device address. */
    private val connections = ConcurrentHashMap<String, BluetoothGatt>()

    /** Negotiated MTU per connection (default BLE MTU = 23, ATT overhead = 3). */
    private val mtuMap = ConcurrentHashMap<String, Int>()

    /** Flow of data received from peers via notifications. */
    private val _receivedData = MutableSharedFlow<ReceivedData>(extraBufferCapacity = 64)
    val receivedData: SharedFlow<ReceivedData> = _receivedData.asSharedFlow()

    /** Flow of connection state changes. */
    private val _connectionState = MutableSharedFlow<ConnectionStateChange>(extraBufferCapacity = 16)
    val connectionState: SharedFlow<ConnectionStateChange> = _connectionState.asSharedFlow()

    /** Flow of peer info read from remote devices. */
    private val _peerInfo = MutableSharedFlow<PeerInfoReceived>(extraBufferCapacity = 16)
    val peerInfo: SharedFlow<PeerInfoReceived> = _peerInfo.asSharedFlow()

    data class ReceivedData(val fromAddress: String, val data: ByteArray)
    data class ConnectionStateChange(val address: String, val connected: Boolean)
    data class PeerInfoReceived(val address: String, val peerInfoBytes: ByteArray)

    /**
     * Initiates a GATT connection to a discovered peer.
     */
    @SuppressLint("MissingPermission")
    fun connectToPeer(device: BluetoothDevice) {
        if (connections.containsKey(device.address)) {
            Timber.d("Already connected to %s", device.address)
            return
        }

        Timber.i("Connecting to peer: %s", device.address)
        device.connectGatt(
            context,
            false, // Don't auto-connect; we want immediate connection
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )
    }

    /**
     * Writes message data to a connected peer's message write characteristic.
     */
    @SuppressLint("MissingPermission")
    fun writeMessage(address: String, data: ByteArray): Boolean {
        val gatt = connections[address] ?: run {
            Timber.w("Cannot write to %s: not connected", address)
            return false
        }

        val service = gatt.getService(Constants.SERVICE_UUID) ?: run {
            Timber.w("Flare service not found on %s", address)
            return false
        }

        val characteristic = service.getCharacteristic(Constants.CHAR_MESSAGE_WRITE_UUID) ?: run {
            Timber.w("Message write characteristic not found on %s", address)
            return false
        }

        return try {
            val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val statusCode = gatt.writeCharacteristic(characteristic, data, writeType)
            val success = statusCode == BluetoothStatusCodes.SUCCESS
            if (!success) {
                Timber.w("Write to %s returned status %d", address, statusCode)
            }
            success
        } catch (e: SecurityException) {
            Timber.e(e, "Write permission denied for %s", address)
            false
        }
    }

    /**
     * Disconnects from a specific peer.
     */
    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        connections[address]?.let { gatt ->
            try {
                gatt.disconnect()
                gatt.close()
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to disconnect from %s", address)
            }
        }
        connections.remove(address)
        mtuMap.remove(address)
    }

    /**
     * Disconnects from all peers.
     */
    @SuppressLint("MissingPermission")
    fun disconnectAll() {
        connections.keys.toList().forEach { disconnect(it) }
    }

    /**
     * Returns the negotiated MTU for a peer, or the minimum MTU if not negotiated.
     */
    fun getMtu(address: String): Int = mtuMap[address] ?: Constants.MIN_MTU

    /**
     * Returns addresses of all connected peers.
     */
    fun connectedAddresses(): Set<String> = connections.keys.toSet()

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connections[address] = gatt
                    Timber.i("Connected to peer: %s, requesting MTU", address)

                    // Request larger MTU for bigger message chunks
                    try {
                        gatt.requestMtu(Constants.REQUESTED_MTU)
                    } catch (e: SecurityException) {
                        Timber.e(e, "MTU request failed for %s", address)
                        // Still discover services even without MTU negotiation
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    connections.remove(address)
                    mtuMap.remove(address)
                    _connectionState.tryEmit(ConnectionStateChange(address, false))
                    Timber.i("Disconnected from peer: %s (status=%d)", address, status)

                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to close GATT for %s", address)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuMap[address] = mtu - 3 // Subtract ATT header overhead
                Timber.d("MTU negotiated to %d (usable: %d) for %s", mtu, mtu - 3, address)
            }

            // Discover services after MTU negotiation
            try {
                gatt.discoverServices()
            } catch (e: SecurityException) {
                Timber.e(e, "Service discovery failed for %s", address)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("Service discovery failed for %s (status=%d)", address, status)
                return
            }

            val service = gatt.getService(Constants.SERVICE_UUID)
            if (service == null) {
                Timber.w("Flare service not found on %s", address)
                return
            }

            Timber.i("Flare service discovered on %s", address)

            // 1. Subscribe to message notifications
            val notifyChar = service.getCharacteristic(Constants.CHAR_MESSAGE_NOTIFY_UUID)
            if (notifyChar != null) {
                try {
                    gatt.setCharacteristicNotification(notifyChar, true)

                    val cccd = notifyChar.getDescriptor(Constants.CCCD_UUID)
                    if (cccd != null) {
                        gatt.writeDescriptor(
                            cccd,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                        )
                    }
                } catch (e: SecurityException) {
                    Timber.e(e, "Failed to enable notifications on %s", address)
                }
            }

            // 2. Read peer info
            val peerInfoChar = service.getCharacteristic(Constants.CHAR_PEER_INFO_UUID)
            if (peerInfoChar != null) {
                try {
                    gatt.readCharacteristic(peerInfoChar)
                } catch (e: SecurityException) {
                    Timber.e(e, "Failed to read peer info from %s", address)
                }
            }

            _connectionState.tryEmit(ConnectionStateChange(address, true))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val address = gatt.device.address
            when (characteristic.uuid) {
                Constants.CHAR_MESSAGE_NOTIFY_UUID -> {
                    _receivedData.tryEmit(ReceivedData(address, value))
                    Timber.d("Received %d bytes notification from %s", value.size, address)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val address = gatt.device.address

            when (characteristic.uuid) {
                Constants.CHAR_PEER_INFO_UUID -> {
                    _peerInfo.tryEmit(PeerInfoReceived(address, value))
                    Timber.d("Read peer info (%d bytes) from %s", value.size, address)
                }
            }
        }
    }
}
