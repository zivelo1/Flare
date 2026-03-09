package com.flare.mesh.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flare.mesh.R
import com.flare.mesh.data.model.MeshPeer
import com.flare.mesh.data.model.MeshStatus
import com.flare.mesh.data.model.TransportType
import com.flare.mesh.service.MeshService
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen() {
    val meshStatus by MeshService.meshStatus.collectAsState()
    val isRunning by MeshService.isRunning.collectAsState()

    // TODO: Replace with real peers from BleScanner
    val nearbyPeers = remember { mutableStateListOf<MeshPeer>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tab_network),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Mesh status card
            item {
                MeshStatusCard(meshStatus, isRunning)
            }

            // Stats row
            item {
                StatsRow(meshStatus)
            }

            // Nearby peers header
            item {
                Text(
                    text = "Nearby Devices",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (nearbyPeers.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "Scanning for Flare devices…",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            } else {
                items(nearbyPeers, key = { it.deviceId }) { peer ->
                    PeerCard(peer)
                }
            }
        }
    }
}

@Composable
private fun MeshStatusCard(status: MeshStatus, isRunning: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning && status.connectedPeerCount > 0)
                MaterialTheme.colorScheme.primaryContainer
            else if (isRunning)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Filled.CellTower else Icons.Filled.SignalCellularOff,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isRunning)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = if (isRunning) stringResource(R.string.network_status_active)
                    else stringResource(R.string.network_status_inactive),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (isRunning) {
                    Text(
                        text = when {
                            status.connectedPeerCount > 0 ->
                                "${status.connectedPeerCount} connected, ${status.discoveredPeerCount} discovered"
                            status.discoveredPeerCount > 0 ->
                                "${status.discoveredPeerCount} devices found, connecting…"
                            else ->
                                "Scanning for nearby devices"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsRow(status: MeshStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.People,
            value = status.connectedPeerCount.toString(),
            label = "Connected",
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Inbox,
            value = status.storedMessageCount.toString(),
            label = "Queued",
        )
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.SwapHoriz,
            value = status.messagesRelayed.toString(),
            label = "Relayed",
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PeerCard(peer: MeshPeer) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Signal strength indicator
            Surface(
                shape = CircleShape,
                color = when {
                    peer.isConnected -> MaterialTheme.colorScheme.primary
                    (peer.rssi ?: -100) > -70 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when {
                            peer.isConnected -> Icons.Filled.BluetoothConnected
                            else -> Icons.Filled.BluetoothSearching
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName ?: "Device ${peer.deviceId.take(8)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Row {
                    Text(
                        text = formatTransport(peer.transportType),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    peer.estimatedDistanceMeters?.let { distance ->
                        Text(
                            text = " • ~${"%.0f".format(distance)}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    peer.rssi?.let { rssi ->
                        Text(
                            text = " • ${rssi}dBm",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (peer.isConnected) {
                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Text("Connected", modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
}

private fun formatTransport(type: TransportType): String = when (type) {
    TransportType.BLUETOOTH_LE -> "Bluetooth LE"
    TransportType.WIFI_DIRECT -> "Wi-Fi Direct"
    TransportType.WIFI_AWARE -> "Wi-Fi Aware"
}
