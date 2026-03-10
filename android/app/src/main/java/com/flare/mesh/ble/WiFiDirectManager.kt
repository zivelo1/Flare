package com.flare.mesh.ble

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Manages Wi-Fi Direct (Wi-Fi P2P) transport for high-bandwidth peer transfers.
 *
 * Wi-Fi Direct provides ~250m range and ~50 Mbps throughput, used for:
 * - Voice messages that exceed BLE chunk limits
 * - Image transfers
 * - APK sharing
 *
 * Operates alongside BLE — BLE handles discovery and small messages,
 * Wi-Fi Direct handles large payloads on demand.
 */
class WiFiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WiFiDirectManager"

        /** TCP port for Wi-Fi Direct data exchange. */
        private const val TRANSFER_PORT = 8778

        /** Socket connection timeout (milliseconds). */
        private const val CONNECT_TIMEOUT_MS = 10_000

        /** Maximum receive buffer size (10 MB). */
        private const val MAX_RECEIVE_BYTES = 10 * 1024 * 1024
    }

    /** Incoming data received from a Wi-Fi Direct peer. */
    data class IncomingTransfer(val senderAddress: String, val data: ByteArray)

    /** Connection state changes. */
    data class ConnectionEvent(val deviceAddress: String, val connected: Boolean)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _connectionState = MutableStateFlow("disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val _connectedDevice = MutableStateFlow<String?>(null)
    val connectedDevice: StateFlow<String?> = _connectedDevice

    private val _incomingData = MutableSharedFlow<IncomingTransfer>(extraBufferCapacity = 16)
    val incomingData: SharedFlow<IncomingTransfer> = _incomingData

    private val _connectionEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 8)
    val connectionEvents: SharedFlow<ConnectionEvent> = _connectionEvents

    private var serverJob: Job? = null
    private var isGroupOwner = false
    private var groupOwnerAddress: String? = null
    private var isActive = false

    /**
     * Initializes Wi-Fi Direct. Call from a Context that has ACCESS_FINE_LOCATION permission.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isActive) return
        Log.i(TAG, "Starting Wi-Fi Direct transport")

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = wifiP2pManager?.initialize(context, context.mainLooper, null)

        if (wifiP2pManager == null || channel == null) {
            Log.e(TAG, "Wi-Fi P2P not supported on this device")
            return
        }

        registerReceiver()
        startDiscovery()
        isActive = true
    }

    fun stop() {
        if (!isActive) return
        Log.i(TAG, "Stopping Wi-Fi Direct transport")

        serverJob?.cancel()
        wifiP2pManager?.removeGroup(channel, null)

        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered
        }

        _connectionState.value = "disconnected"
        _connectedDevice.value = null
        isActive = false
    }

    /**
     * Sends data to the connected Wi-Fi Direct peer.
     * Returns true if send was initiated successfully.
     */
    fun sendData(data: ByteArray): Boolean {
        val address = if (isGroupOwner) {
            // Group owner: connected client's address (simplified — uses group owner address)
            groupOwnerAddress
        } else {
            groupOwnerAddress
        }

        if (address == null) {
            Log.w(TAG, "No connected peer address for Wi-Fi Direct send")
            return false
        }

        scope.launch {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, TRANSFER_PORT), CONNECT_TIMEOUT_MS)
                    val outputStream = socket.getOutputStream()

                    // Length-prefixed protocol: 4-byte big-endian length + payload
                    val length = data.size
                    outputStream.write(byteArrayOf(
                        (length shr 24).toByte(),
                        (length shr 16).toByte(),
                        (length shr 8).toByte(),
                        length.toByte()
                    ))
                    outputStream.write(data)
                    outputStream.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Wi-Fi Direct send failed: ${e.message}")
            }
        }

        return true
    }

    /**
     * Connects to a discovered Wi-Fi Direct peer by device address.
     */
    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String) {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }

        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "Wi-Fi Direct connection initiated to $deviceAddress")
                _connectionState.value = "connecting"
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Wi-Fi Direct connection failed: reason=$reason")
                _connectionState.value = "failed"
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Wi-Fi Direct peer discovery started")
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "Wi-Fi Direct discovery failed: reason=$reason")
            }
        })
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<WifiP2pInfo>(
                            WifiP2pManager.EXTRA_WIFI_P2P_INFO
                        )
                        handleConnectionChanged(info)
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // Peers changed — could request updated peer list here
                        Log.d(TAG, "Wi-Fi Direct peers changed")
                    }

                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            Log.w(TAG, "Wi-Fi P2P is disabled")
                            _connectionState.value = "disconnected"
                        }
                    }
                }
            }
        }

        context.registerReceiver(receiver, intentFilter)
    }

    private fun handleConnectionChanged(info: WifiP2pInfo?) {
        if (info == null) return

        if (info.groupFormed) {
            isGroupOwner = info.isGroupOwner
            groupOwnerAddress = info.groupOwnerAddress?.hostAddress

            _connectionState.value = "connected"
            _connectedDevice.value = groupOwnerAddress

            Log.i(TAG, "Wi-Fi Direct group formed. Owner=$isGroupOwner, address=$groupOwnerAddress")

            scope.launch {
                _connectionEvents.emit(ConnectionEvent(
                    deviceAddress = groupOwnerAddress ?: "",
                    connected = true
                ))
            }

            // Group owner starts a server socket for receiving data
            if (isGroupOwner) {
                startServerSocket()
            }
        } else {
            Log.i(TAG, "Wi-Fi Direct group dissolved")
            _connectionState.value = "disconnected"

            val previousDevice = _connectedDevice.value
            _connectedDevice.value = null
            serverJob?.cancel()

            if (previousDevice != null) {
                scope.launch {
                    _connectionEvents.emit(ConnectionEvent(
                        deviceAddress = previousDevice,
                        connected = false
                    ))
                }
            }
        }
    }

    /**
     * Starts a server socket for receiving Wi-Fi Direct data transfers.
     * Only runs on the group owner.
     */
    private fun startServerSocket() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                ServerSocket(TRANSFER_PORT).use { serverSocket ->
                    serverSocket.soTimeout = 0 // Block indefinitely
                    Log.i(TAG, "Wi-Fi Direct server listening on port $TRANSFER_PORT")

                    while (isActive) {
                        try {
                            val client = serverSocket.accept()
                            launch {
                                handleClientConnection(client)
                            }
                        } catch (e: IOException) {
                            if (isActive) {
                                Log.e(TAG, "Server accept failed: ${e.message}")
                            }
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start server socket: ${e.message}")
            }
        }
    }

    private suspend fun handleClientConnection(socket: Socket) {
        try {
            socket.use { s ->
                val inputStream = s.getInputStream()

                // Read 4-byte length prefix (big-endian)
                val lengthBytes = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = inputStream.read(lengthBytes, read, 4 - read)
                    if (n < 0) return
                    read += n
                }

                val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                        ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                        ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                        (lengthBytes[3].toInt() and 0xFF)

                if (length <= 0 || length > MAX_RECEIVE_BYTES) {
                    Log.w(TAG, "Invalid payload length: $length")
                    return
                }

                // Read payload
                val payload = ByteArray(length)
                read = 0
                while (read < length) {
                    val n = inputStream.read(payload, read, length - read)
                    if (n < 0) break
                    read += n
                }

                if (read == length) {
                    val senderAddress = s.inetAddress?.hostAddress ?: "unknown"
                    Log.d(TAG, "Received $length bytes from $senderAddress")
                    _incomingData.emit(IncomingTransfer(senderAddress, payload))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Client connection handling failed: ${e.message}")
        }
    }
}
