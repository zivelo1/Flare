package com.flare.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.flare.mesh.R
import com.flare.mesh.ble.BleScanner
import com.flare.mesh.ble.GattClient
import com.flare.mesh.ble.GattServer
import com.flare.mesh.data.model.MeshPeer
import com.flare.mesh.data.model.MeshStatus
import com.flare.mesh.data.repository.FlareRepository
import com.flare.mesh.data.repository.RouteDecisionType
import com.flare.mesh.ui.MainActivity
import com.flare.mesh.util.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Foreground service that maintains the Flare mesh network.
 *
 * Runs BLE scanning and advertising continuously, manages peer connections,
 * and handles message routing via the Rust core (FlareNode).
 */
class MeshService : LifecycleService() {

    private lateinit var bleScanner: BleScanner
    private lateinit var gattServer: GattServer
    private lateinit var gattClient: GattClient

    private var scanJob: Job? = null
    private var pruneJob: Job? = null
    private var rendezvousJob: Job? = null
    private var powerJob: Job? = null

    /** Current adaptive power tier. Updated by the power evaluation loop. */
    private var currentScanTier: BleScanner.ScanPowerTier = BleScanner.ScanPowerTier.BALANCED
    private var currentAdvertiseTier: GattServer.AdvertisePowerTier = GattServer.AdvertisePowerTier.BALANCED

    /** Tracks last data activity timestamp for power tier evaluation. */
    private var lastDataActivityMs: Long = 0L
    private var lastPeerSeenMs: Long = 0L
    private var highTierEnteredMs: Long = 0L

    /** Whether a burst scan is currently sleeping (LowPower/UltraLow tiers). */
    private var burstSleeping: Boolean = false

    companion object {
        private val _meshStatus = MutableStateFlow(MeshStatus())
        val meshStatus: StateFlow<MeshStatus> = _meshStatus.asStateFlow()

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _discoveredPeers = MutableStateFlow<Map<String, MeshPeer>>(emptyMap())
        val discoveredPeers: StateFlow<Map<String, MeshPeer>> = _discoveredPeers.asStateFlow()

        private val _outboundQueue = MutableSharedFlow<OutboundMessage>(extraBufferCapacity = 64)

        private val _incomingDelivered = MutableSharedFlow<DeliveredMessage>(extraBufferCapacity = 64)
        val incomingDelivered: SharedFlow<DeliveredMessage> = _incomingDelivered.asSharedFlow()

        fun start(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshService::class.java)
            context.stopService(intent)
        }

        /**
         * Enqueues a serialized mesh message for BLE transmission to a specific peer.
         */
        fun enqueueOutbound(recipientDeviceId: String, serializedMessage: ByteArray) {
            _outboundQueue.tryEmit(OutboundMessage(recipientDeviceId, serializedMessage))
        }
    }

    data class OutboundMessage(val recipientDeviceId: String, val data: ByteArray)
    data class DeliveredMessage(val senderId: String, val plaintext: String)

    override fun onCreate() {
        super.onCreate()
        Timber.i("MeshService created")

        bleScanner = BleScanner(this)
        gattServer = GattServer(this)
        gattClient = GattClient(this)

        createNotificationChannels()

        // Set peer info bytes from FlareNode on the GATT server
        try {
            val repo = FlareRepository.getInstance()
            gattServer.localPeerInfoBytes = repo.getPeerInfoBytes()
        } catch (e: Exception) {
            Timber.w(e, "FlareRepository not yet initialized")
        }
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

        // Start BLE scanning at default Balanced tier
        bleScanner.startScanning(BleScanner.ScanPowerTier.BALANCED)

        // Periodic scan cycle: update status, discovered peers, and neighborhood filter
        scanJob = lifecycleScope.launch {
            while (isActive) {
                delay(Constants.BLE_SCAN_INTERVAL_MS)
                val peers = bleScanner.discoveredPeers.value
                _discoveredPeers.value = peers

                if (peers.isNotEmpty()) {
                    lastPeerSeenMs = System.currentTimeMillis()
                }

                // Record discovered peers' BLE addresses as short IDs in neighborhood filter
                try {
                    val repo = FlareRepository.getInstance()
                    peers.keys.forEach { address ->
                        val shortId = addressToShortId(address)
                        repo.recordNeighborhoodPeer(shortId)
                    }
                } catch (_: Exception) { }

                updateMeshStatus()
            }
        }

        // Adaptive power management loop — evaluates tier every scan interval
        powerJob = lifecycleScope.launch {
            while (isActive) {
                delay(Constants.BLE_SCAN_INTERVAL_MS)
                evaluateAndApplyPowerTier()
            }
        }

        // Periodic stale peer cleanup + message pruning
        pruneJob = lifecycleScope.launch {
            while (isActive) {
                delay(Constants.PEER_STALE_TIMEOUT_MS / 2)
                bleScanner.pruneStale()
                try {
                    val pruned = FlareRepository.getInstance().pruneExpiredMessages()
                    if (pruned > 0) {
                        Timber.d("Pruned %d expired messages from routing store", pruned)
                    }
                } catch (_: Exception) { }
            }
        }

        // Auto-connect to newly discovered peers via GATT client
        lifecycleScope.launch {
            bleScanner.newPeerDevices.collect { device ->
                val address = device.address
                if (address !in gattClient.connectedAddresses() &&
                    address !in gattServer.connectedDeviceAddresses()
                ) {
                    Timber.i("Auto-connecting to discovered peer: %s", address)
                    gattClient.connectToPeer(device)
                }
            }
        }

        // Collect incoming messages from GATT server
        lifecycleScope.launch {
            gattServer.incomingMessages.collect { message ->
                handleIncomingMessage(message)
            }
        }

        // Collect connection events from GATT server
        lifecycleScope.launch {
            gattServer.connectionEvents.collect { event ->
                when (event) {
                    is GattServer.BleConnectionEvent.Connected -> {
                        Timber.i("Peer connected via GATT server: %s", event.address)
                        try {
                            val repo = FlareRepository.getInstance()
                            repo.notifyPeerConnected(event.address)

                            lifecycleScope.launch(Dispatchers.IO) {
                                // Exchange neighborhood bitmaps for bridge detection
                                val localBitmap = repo.exportNeighborhoodBitmap()
                                gattServer.sendToPeer(event.address, localBitmap)

                                // Forward stored messages to newly connected peer
                                val messages = repo.getMessagesForPeer(event.address)
                                messages.forEach { data ->
                                    gattServer.sendToPeer(event.address, data)
                                }
                            }
                        } catch (_: Exception) { }
                        updateMeshStatus()
                    }
                    is GattServer.BleConnectionEvent.Disconnected -> {
                        Timber.i("Peer disconnected: %s", event.address)
                        updateMeshStatus()
                    }
                }
            }
        }

        // Collect GATT client connection state changes
        lifecycleScope.launch {
            gattClient.connectionState.collect { event ->
                if (event.connected) {
                    Timber.i("GATT client connected to peer: %s", event.address)
                    try {
                        val repo = FlareRepository.getInstance()
                        repo.notifyPeerConnected(event.address)

                        lifecycleScope.launch(Dispatchers.IO) {
                            // Forward stored messages to newly connected peer
                            val messages = repo.getMessagesForPeer(event.address)
                            messages.forEach { data ->
                                gattClient.writeMessage(event.address, data)
                            }
                            if (messages.isNotEmpty()) {
                                Timber.d("Sent %d stored messages to %s via client", messages.size, event.address)
                            }
                        }
                    } catch (_: Exception) { }
                    updateMeshStatus()
                } else {
                    Timber.i("GATT client disconnected from peer: %s", event.address)
                    updateMeshStatus()
                }
            }
        }

        // Collect data from GATT client connections
        lifecycleScope.launch {
            gattClient.receivedData.collect { data ->
                Timber.d("Received %d bytes from client connection %s", data.data.size, data.fromAddress)
                handleIncomingMessage(
                    GattServer.IncomingBleMessage(data.fromAddress, data.data)
                )
            }
        }

        // Process outbound message queue
        lifecycleScope.launch {
            _outboundQueue.collect { outbound ->
                sendToMesh(outbound)
            }
        }

        // Periodic rendezvous broadcast (every 30 seconds while searches are active)
        rendezvousJob = lifecycleScope.launch {
            while (isActive) {
                delay(30_000L)
                try {
                    val repo = FlareRepository.getInstance()
                    if (repo.activeSearchCount() > 0) {
                        val broadcasts = repo.buildRendezvousBroadcasts()
                        broadcasts.forEach { data ->
                            sendToMesh(OutboundMessage("broadcast", data))
                        }
                        if (broadcasts.isNotEmpty()) {
                            Timber.d("Broadcast %d rendezvous tokens", broadcasts.size)
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private fun stopMesh() {
        Timber.i("Stopping mesh network")
        scanJob?.cancel()
        pruneJob?.cancel()
        rendezvousJob?.cancel()
        powerJob?.cancel()
        bleScanner.stopScanning()
        gattServer.stop()
        gattClient.disconnectAll()
    }

    /**
     * Evaluates network activity and battery state to determine the optimal
     * BLE power tier, then applies it to scanner and advertiser.
     *
     * Tier transitions:
     * - High:      active data exchange or recent activity (max 30s, disabled below 30% battery)
     * - Balanced:  peers present or recently seen
     * - LowPower:  no peers nearby, burst mode scanning
     * - UltraLow:  critical battery (<15%) or user battery saver
     *
     * See flare-core/src/power/mod.rs for the canonical tier logic.
     */
    private fun evaluateAndApplyPowerTier() {
        val now = System.currentTimeMillis()
        val batteryPercent = getBatteryPercent()

        val connectedCount = gattServer.connectedCount() + gattClient.connectedAddresses().size
        val hasPendingOutbound = try {
            FlareRepository.getInstance().getStoreStats().totalMessages > 0
        } catch (_: Exception) { false }

        // ── Force UltraLow on critical battery ──
        if (batteryPercent <= Constants.POWER_CRITICAL_BATTERY_PERCENT) {
            applyPowerTier(PowerTierResult.ULTRA_LOW)
            return
        }

        // ── Determine candidate tier ──
        val secsSinceData = (now - lastDataActivityMs) / 1000
        val secsSincePeer = (now - lastPeerSeenMs) / 1000
        val secsInHigh = (now - highTierEnteredMs) / 1000

        val highDurationOk = if (currentScanTier == BleScanner.ScanPowerTier.HIGH) {
            secsInHigh < Constants.POWER_HIGH_DURATION_LIMIT_SECS
        } else {
            true
        }

        val candidate = when {
            hasPendingOutbound && connectedCount > 0 ->
                PowerTierResult.HIGH
            secsSinceData < Constants.POWER_HIGH_INACTIVITY_THRESHOLD_SECS && highDurationOk ->
                PowerTierResult.HIGH
            connectedCount > 0 || secsSincePeer < Constants.POWER_BALANCED_NO_PEERS_THRESHOLD_SECS ->
                PowerTierResult.BALANCED
            else ->
                PowerTierResult.LOW_POWER
        }

        // ── Cap at Balanced when battery is low ──
        val capped = if (batteryPercent <= Constants.POWER_LOW_BATTERY_PERCENT &&
            candidate == PowerTierResult.HIGH
        ) {
            PowerTierResult.BALANCED
        } else {
            candidate
        }

        applyPowerTier(capped)
    }

    /**
     * Applies the evaluated power tier to BLE scanner and GATT server advertiser.
     * Handles burst mode for LowPower/UltraLow tiers.
     */
    private fun applyPowerTier(tier: PowerTierResult) {
        val newScanTier = tier.toScanTier()
        val newAdvertiseTier = tier.toAdvertiseTier()

        // Track High tier entry
        if (newScanTier == BleScanner.ScanPowerTier.HIGH &&
            currentScanTier != BleScanner.ScanPowerTier.HIGH
        ) {
            highTierEnteredMs = System.currentTimeMillis()
        }

        // Only reconfigure if tier actually changed
        if (newScanTier != currentScanTier) {
            Timber.i("Power tier changed: %s -> %s", currentScanTier, newScanTier)
            currentScanTier = newScanTier
            currentAdvertiseTier = newAdvertiseTier

            // Apply new scan tier (handles burst mode internally via Android scan modes)
            bleScanner.startScanning(newScanTier)
            gattServer.startAdvertising(newAdvertiseTier)

            // Manage burst mode for low-power tiers
            manageBurstMode(tier)
        }
    }

    /**
     * Manages burst-mode scanning for LowPower and UltraLow tiers.
     * In burst mode, scanning runs for a short window then pauses to save battery.
     */
    private fun manageBurstMode(tier: PowerTierResult) {
        // Cancel any existing burst job
        burstSleeping = false

        if (tier == PowerTierResult.LOW_POWER || tier == PowerTierResult.ULTRA_LOW) {
            val burstScanMs = if (tier == PowerTierResult.LOW_POWER) {
                Constants.POWER_TIER_LOW_BURST_SCAN_MS
            } else {
                Constants.POWER_TIER_ULTRALOW_BURST_SCAN_MS
            }
            val burstSleepMs = if (tier == PowerTierResult.LOW_POWER) {
                Constants.POWER_TIER_LOW_BURST_SLEEP_MS
            } else {
                Constants.POWER_TIER_ULTRALOW_BURST_SLEEP_MS
            }

            lifecycleScope.launch {
                while (isActive && currentScanTier == tier.toScanTier()) {
                    // Scan phase
                    burstSleeping = false
                    bleScanner.startScanning(tier.toScanTier())
                    delay(burstScanMs)

                    // Sleep phase — stop scanning to save power
                    burstSleeping = true
                    bleScanner.stopScanning()
                    delay(burstSleepMs)
                }
            }
        }
    }

    /** Reads the current battery percentage from the system. */
    private fun getBatteryPercent(): Int {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            100 // Assume full if unavailable
        }
    }

    /** Notifies the power manager that data was sent or received. */
    fun notifyDataActivity() {
        lastDataActivityMs = System.currentTimeMillis()
    }

    /**
     * Internal enum representing evaluated power tier result.
     * Maps to both BleScanner.ScanPowerTier and GattServer.AdvertisePowerTier.
     */
    private enum class PowerTierResult {
        HIGH, BALANCED, LOW_POWER, ULTRA_LOW;

        fun toScanTier(): BleScanner.ScanPowerTier = when (this) {
            HIGH -> BleScanner.ScanPowerTier.HIGH
            BALANCED -> BleScanner.ScanPowerTier.BALANCED
            LOW_POWER -> BleScanner.ScanPowerTier.LOW_POWER
            ULTRA_LOW -> BleScanner.ScanPowerTier.ULTRA_LOW
        }

        fun toAdvertiseTier(): GattServer.AdvertisePowerTier = when (this) {
            HIGH -> GattServer.AdvertisePowerTier.HIGH
            BALANCED -> GattServer.AdvertisePowerTier.BALANCED
            LOW_POWER -> GattServer.AdvertisePowerTier.LOW_POWER
            ULTRA_LOW -> GattServer.AdvertisePowerTier.LOW_POWER
        }
    }

    private suspend fun handleIncomingMessage(message: GattServer.IncomingBleMessage) {
        Timber.d("Processing incoming message from %s (%d bytes)",
            message.fromAddress, message.data.size)

        notifyDataActivity()

        try {
            val repo = FlareRepository.getInstance()
            val result = repo.processIncomingMessage(message.data)

            when (result.decision) {
                RouteDecisionType.DELIVER_LOCALLY -> {
                    Timber.i("Message delivered locally from %s", result.senderId)
                    if (result.senderId != null && result.plaintext != null) {
                        _incomingDelivered.tryEmit(
                            DeliveredMessage(result.senderId, result.plaintext)
                        )

                        // Send delivery ACK back through the mesh
                        if (result.messageId != null) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val ackBytes = repo.createDeliveryAck(
                                        result.messageId, result.senderId
                                    )
                                    sendToMesh(OutboundMessage(result.senderId, ackBytes))
                                } catch (e: Exception) {
                                    Timber.w(e, "Failed to send delivery ACK")
                                }
                            }
                        }
                    }
                }

                RouteDecisionType.FORWARD -> {
                    // Prepare for relay (increment hop count)
                    val relayData = repo.prepareForRelay(message.data)
                    if (relayData != null) {
                        // Forward to all connected peers except sender
                        val connectedAddresses = gattClient.connectedAddresses() +
                            gattServer.connectedDeviceAddresses()
                        connectedAddresses
                            .filter { it != message.fromAddress }
                            .forEach { address ->
                                gattServer.sendToPeer(address, relayData)
                                gattClient.writeMessage(address, relayData)
                            }
                        Timber.d("Forwarded message to %d peers",
                            connectedAddresses.size - 1)
                    } else {
                        Timber.d("Message reached hop limit, not relaying")
                    }
                }

                RouteDecisionType.STORE -> {
                    Timber.d("Message stored for later forwarding")
                }

                RouteDecisionType.DROP -> {
                    Timber.d("Message dropped (duplicate/expired/hop limit)")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing incoming message")
        }

        updateMeshStatus()
    }

    private fun sendToMesh(outbound: OutboundMessage) {
        notifyDataActivity()

        // Send to all connected peers (Spray-and-Wait)
        val serverPeers = gattServer.connectedDeviceAddresses()
        val clientPeers = gattClient.connectedAddresses()

        var sent = 0
        serverPeers.forEach { address ->
            if (gattServer.sendToPeer(address, outbound.data)) sent++
        }
        clientPeers.forEach { address ->
            if (gattClient.writeMessage(address, outbound.data)) sent++
        }

        Timber.d("Sent outbound message to %d/%d peers",
            sent, serverPeers.size + clientPeers.size)
    }

    /**
     * Derives a deterministic 4-byte short ID from a BLE MAC address.
     * Uses the last 4 bytes of the address (stripped of colons).
     */
    private fun addressToShortId(address: String): ByteArray {
        val bytes = address.replace(":", "")
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        // Take last 4 bytes, or pad with zeros if shorter
        return when {
            bytes.size >= 4 -> bytes.takeLast(4).toByteArray()
            else -> ByteArray(4 - bytes.size) + bytes
        }
    }

    private fun updateMeshStatus() {
        val discoveredCount = bleScanner.discoveredPeers.value.size
        val connectedCount = gattServer.connectedCount() + gattClient.connectedAddresses().size

        val storedCount = try {
            FlareRepository.getInstance().getStoreStats().totalMessages
        } catch (_: Exception) { 0 }

        _meshStatus.value = MeshStatus(
            isActive = true,
            connectedPeerCount = connectedCount,
            discoveredPeerCount = discoveredCount,
            storedMessageCount = storedCount,
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
