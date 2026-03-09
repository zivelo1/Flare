package com.flare.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.flare.mesh.R
import com.flare.mesh.ble.BleScanner
import com.flare.mesh.ble.GattClient
import com.flare.mesh.ble.GattServer
import com.flare.mesh.data.model.MeshStatus
import com.flare.mesh.ui.MainActivity
import com.flare.mesh.util.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Foreground service that maintains the Flare mesh network.
 *
 * Runs BLE scanning and advertising continuously, manages peer connections,
 * and handles message routing in the background.
 */
class MeshService : LifecycleService() {

    private lateinit var bleScanner: BleScanner
    private lateinit var gattServer: GattServer
    private lateinit var gattClient: GattClient

    private var scanJob: Job? = null
    private var pruneJob: Job? = null

    companion object {
        private val _meshStatus = MutableStateFlow(MeshStatus())
        val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("MeshService created")

        bleScanner = BleScanner(this)
        gattServer = GattServer(this)
        gattClient = GattClient(this)

        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(
            Constants.MESH_SERVICE_NOTIFICATION_ID,
            buildNotification(0),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        startMesh()
        _isRunning.value = true

        return START_STICKY // Restart if killed by OS
    }

    override fun onDestroy() {
        stopMesh()
        _isRunning.value = false
        Timber.i("MeshService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startMesh() {
        Timber.i("Starting mesh network")

        // Start GATT server (advertise + accept connections)
        gattServer.start()

        // Start BLE scanning
        bleScanner.startScanning()

        // Periodic scan cycle: scan → pause → scan (saves battery)
        scanJob = lifecycleScope.launch {
            while (isActive) {
                delay(Constants.BLE_SCAN_INTERVAL_MS)
                updateMeshStatus()
            }
        }

        // Periodic stale peer cleanup
        pruneJob = lifecycleScope.launch {
            while (isActive) {
                delay(Constants.PEER_STALE_TIMEOUT_MS / 2)
                bleScanner.pruneStale()
            }
        }

        // Collect incoming messages from GATT server
        lifecycleScope.launch {
            gattServer.incomingMessages.collect { message ->
                handleIncomingMessage(message)
            }
        }

        // Collect connection events
        lifecycleScope.launch {
            gattServer.connectionEvents.collect { event ->
                when (event) {
                    is GattServer.BleConnectionEvent.Connected -> {
                        Timber.i("Peer connected via GATT server: %s", event.address)
                        updateMeshStatus()
                    }
                    is GattServer.BleConnectionEvent.Disconnected -> {
                        Timber.i("Peer disconnected: %s", event.address)
                        updateMeshStatus()
                    }
                }
            }
        }

        // Collect data from GATT client connections
        lifecycleScope.launch {
            gattClient.receivedData.collect { data ->
                Timber.d("Received %d bytes from client connection %s", data.data.size, data.fromAddress)
                // TODO: Route through mesh router
            }
        }
    }

    private fun stopMesh() {
        Timber.i("Stopping mesh network")
        scanJob?.cancel()
        pruneJob?.cancel()
        bleScanner.stopScanning()
        gattServer.stop()
        gattClient.disconnectAll()
    }

    private fun handleIncomingMessage(message: GattServer.IncomingBleMessage) {
        Timber.d("Processing incoming message from %s (%d bytes)",
            message.fromAddress, message.data.size)

        // TODO: Deserialize message, pass to Rust router, handle routing decision
        // For now, count it
        _meshStatus.value = _meshStatus.value.copy(
            messagesRelayed = _meshStatus.value.messagesRelayed + 1,
        )
    }

    private fun updateMeshStatus() {
        val discoveredCount = bleScanner.discoveredPeers.value.size
        val connectedCount = gattServer.connectedCount() + gattClient.connectedAddresses().size

        _meshStatus.value = MeshStatus(
            isActive = true,
            connectedPeerCount = connectedCount,
            discoveredPeerCount = discoveredCount,
            storedMessageCount = 0, // TODO: From Rust router
            messagesRelayed = _meshStatus.value.messagesRelayed,
        )

        // Update notification with current peer count
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(
            Constants.MESH_SERVICE_NOTIFICATION_ID,
            buildNotification(connectedCount),
        )
    }

    private fun buildNotification(peerCount: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (peerCount > 0) {
            getString(R.string.mesh_notification_text_format, peerCount)
        } else {
            getString(R.string.mesh_notification_text_no_peers)
        }

        return NotificationCompat.Builder(this, Constants.MESH_SERVICE_CHANNEL_ID)
            .setContentTitle(getString(R.string.mesh_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannels() {
        val meshChannel = NotificationChannel(
            Constants.MESH_SERVICE_CHANNEL_ID,
            getString(R.string.mesh_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.mesh_service_channel_description)
            setShowBadge(false)
        }

        val messageChannel = NotificationChannel(
            Constants.MESSAGE_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming Flare messages"
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(meshChannel)
        notificationManager.createNotificationChannel(messageChannel)
    }
}
