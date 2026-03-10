package com.flare.mesh.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flare.mesh.R
import com.flare.mesh.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDuress: () -> Unit,
    onNavigateToPower: () -> Unit,
    onNavigateToApkShare: () -> Unit = {},
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val hasDuressPin by settingsViewModel.hasDuressPin.collectAsState()
    val currentPowerTier by settingsViewModel.currentPowerTier.collectAsState()
    val storeStats by settingsViewModel.storeStats.collectAsState()

    LaunchedEffect(Unit) {
        settingsViewModel.refreshStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Security Section ─────────────────────────────────────
            SettingsSectionHeader("Security")

            SettingsItem(
                icon = Icons.Filled.Shield,
                title = "Duress PIN",
                subtitle = if (hasDuressPin) "Configured — decoy database active"
                else "Not configured — tap to set up",
                onClick = onNavigateToDuress,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Battery & Performance Section ────────────────────────
            SettingsSectionHeader("Battery & Performance")

            SettingsItem(
                icon = Icons.Filled.BatteryChargingFull,
                title = "Power Management",
                subtitle = "Current tier: ${formatTierName(currentPowerTier)}",
                onClick = onNavigateToPower,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Storage Section ──────────────────────────────────────
            SettingsSectionHeader("Storage")

            storeStats?.let { stats ->
                StorageStatsCard(stats)
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Device Info Section ──────────────────────────────────
            SettingsSectionHeader("Device")

            DeviceInfoCard(
                deviceId = settingsViewModel.deviceId,
                safetyNumber = settingsViewModel.safetyNumber,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Sharing Section ───────────────────────────────────────
            SettingsSectionHeader("Sharing")

            SettingsItem(
                icon = Icons.Filled.Share,
                title = stringResource(R.string.apk_settings_share),
                subtitle = stringResource(R.string.apk_settings_share_subtitle),
                onClick = onNavigateToApkShare,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── About Section ────────────────────────────────────────
            SettingsSectionHeader("About")

            SettingsItem(
                icon = Icons.Filled.Info,
                title = "Flare",
                subtitle = "Encrypted mesh messaging — no internet required",
                onClick = {},
            )

            SettingsItem(
                icon = Icons.Filled.Code,
                title = "Open Source",
                subtitle = "Licensed under GPLv3",
                onClick = {},
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun StorageStatsCard(
    stats: com.flare.mesh.data.repository.StoreStats,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StorageStat("Own", stats.ownMessages.toString())
                StorageStat("Relay", stats.activeRelayMessages.toString())
                StorageStat("Waiting", stats.waitingRelayMessages.toString())
                StorageStat("Total", stats.totalMessages.toString())
            }
            Spacer(Modifier.height(12.dp))

            val usedMB = stats.totalBytes / (1024.0 * 1024.0)
            val budgetMB = stats.budgetBytes / (1024.0 * 1024.0)
            val progress = if (stats.budgetBytes > 0)
                (stats.totalBytes.toFloat() / stats.budgetBytes.toFloat()).coerceIn(0f, 1f)
            else 0f

            Text(
                text = String.format("%.1f MB / %.0f MB used", usedMB, budgetMB),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (progress > 0.8f) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun StorageStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeviceInfoCard(
    deviceId: String,
    safetyNumber: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Device ID",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = deviceId.take(24) + "…",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Safety Number",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = safetyNumber,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun formatTierName(tier: String): String = when (tier) {
    "high" -> "High Performance"
    "balanced" -> "Balanced"
    "low_power" -> "Low Power"
    "ultra_low" -> "Ultra Low"
    else -> tier.replaceFirstChar { it.uppercase() }
}
