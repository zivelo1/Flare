package com.flare.mesh.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
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
 * BLE Central (GATT Client) that connects to discovered Flare peers
 * and exchanges messages via GATT characteristics.
 *
 * Handles BLE chunking transparently: large messages are split into
 * MTU-sized chunks on write, and reassembled from chunks on receive.
 */
class GattClient(private val context: Context) {

    /** Active GATT connections by device address. */
    private val connections = ConcurrentHashMap<String, BluetoothGatt>()

    /** Negotiated MTU per connection (default BLE MTU = 23, ATT overhead = 3). */
    private val mtuMap = ConcurrentHashMap<String, Int>()

    /** Per-address write completion signals for sequential chunk writes. */
    private val writeCompletions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Per-address write mutex to prevent interleaved chunk writes. */
    private val writeMutexes = ConcurrentHashMap<String, Mutex>()

    /** Reassembler for incoming chunked notifications. */
    private val reassembler = ChunkReassembler()

    /** Flow of complete (reassembled) message data from peers. */
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
     *
     * Transparently handles BLE chunking: if the data exceeds the negotiated MTU,
     * it is split into chunks and sent sequentially, waiting for each write callback
     * before sending the next chunk.
     */
    @SuppressLint("MissingPermission")
    suspend fun writeMessage(address: String, data: ByteArray): Boolean {
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

        val mtu = getMtu(address)
        val chunks = BleChunker.chunk(data, mtu)
        if (chunks.isEmpty()) {
            Timber.e("Failed to chunk %d bytes for %s (MTU=%d)", data.size, address, mtu)
            return false
        }

        if (chunks.size > 1) {
            Timber.d("Chunking %d bytes into %d chunks for %s (MTU=%d)",
                data.size, chunks.size, address, mtu)
        }

        val mutex = writeMutexes.getOrPut(address) { Mutex() }

        return mutex.withLock {
            for ((index, chunk) in chunks.withIndex()) {
                val deferred = CompletableDeferred<Boolean>()
                writeCompletions[address] = deferred

                val writeInitiated = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        gatt.writeCharacteristic(characteristic, chunk, writeType) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.value = chunk
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    }
                } catch (e: SecurityException) {
                    Timber.e(e, "Write permission denied for %s", address)
                    false
                }

                if (!writeInitiated) {
                    writeCompletions.remove(address)
                    Timber.w("Write initiation failed at chunk %d/%d for %s",
                        index + 1, chunks.size, address)
                    return@withLock false
                }

                // Wait for onCharacteristicWrite callback
                val success = withTimeoutOrNull(Constants.BLE_CHUNK_WRITE_TIMEOUT_MS) {
                    deferred.await()
                } ?: false

                writeCompletions.remove(address)

                if (!success) {
                    Timber.w("Write callback failed/timed out at chunk %d/%d for %s",
                        index + 1, chunks.size, address)
                    return@withLock false
                }
            }
            true
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
        writeMutexes.remove(address)
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

    /**
     * Cleans up stale incomplete chunk reassembly buffers.
     */
    fun pruneChunkBuffers() = reassembler.pruneStale()

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
                    writeMutexes.remove(address)
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(
                                cccd,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(cccd)
                        }
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

        // ── Write callback for sequential chunk sending ──

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val address = gatt.device.address
            writeCompletions[address]?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // ── Notification receive (with chunk reassembly) ──

        // API 33+ callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        // Pre-API 33 callback (deprecated but required for older devices)
        @Deprecated("Required for API < 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
        }

        private fun handleCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val address = gatt.device.address
            when (characteristic.uuid) {
                Constants.CHAR_MESSAGE_NOTIFY_UUID -> {
                    val reassembled = reassembler.onDataReceived(value)
                    if (reassembled != null) {
                        _receivedData.tryEmit(ReceivedData(address, reassembled))
                        Timber.d("Received complete message (%d bytes) from %s",
                            reassembled.size, address)
                    }
                }
            }
        }

        // ── Characteristic read callbacks ──

        // API 33+ callback
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicRead(gatt, characteristic, value, status)
        }

        // Pre-API 33 callback (deprecated but required for older devices)
        @Deprecated("Required for API < 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            handleCharacteristicRead(gatt, characteristic, characteristic.value ?: return, status)
        }

        private fun handleCharacteristicRead(
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
