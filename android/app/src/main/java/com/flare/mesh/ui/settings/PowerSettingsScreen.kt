package com.flare.mesh.ui.settings

import androidx.compose.foundation.layout.*
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
import com.flare.mesh.util.Constants
import com.flare.mesh.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerSettingsScreen(
    onNavigateBack: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val currentPowerTier by settingsViewModel.currentPowerTier.collectAsState()
    val batterySaverEnabled by settingsViewModel.batterySaverEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.power_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            // Current Tier Display
            CurrentTierCard(currentPowerTier)

            Spacer(Modifier.height(24.dp))

            // Battery Saver Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.BatterySaver,
                            contentDescription = null,
                            tint = if (batterySaverEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.power_battery_saver),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = stringResource(R.string.power_battery_saver_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = batterySaverEnabled,
                        onCheckedChange = { settingsViewModel.toggleBatterySaver() },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Tier Explanation
            Text(
                text = stringResource(R.string.power_how_tiers_work),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))

            TierExplanationItem(
                icon = Icons.Filled.Speed,
                name = stringResource(R.string.settings_tier_high),
                description = "Near-continuous scanning. Maximum discovery speed. " +
                        "Used during active data exchange (max ${Constants.POWER_HIGH_DURATION_LIMIT_SECS}s).",
                isActive = currentPowerTier == "high",
            )

            TierExplanationItem(
                icon = Icons.Filled.Balance,
                name = stringResource(R.string.settings_tier_balanced),
                description = "25% duty cycle scanning. Good balance of discovery and battery. " +
                        "Drops to Low Power after ${Constants.POWER_BALANCED_NO_PEERS_THRESHOLD_SECS}s without peers.",
                isActive = currentPowerTier == "balanced",
            )

            TierExplanationItem(
                icon = Icons.Filled.BatteryChargingFull,
                name = stringResource(R.string.settings_tier_low),
                description = "Burst mode — scans ${Constants.POWER_TIER_LOW_BURST_SCAN_MS / 1000}s " +
                        "every ${(Constants.POWER_TIER_LOW_BURST_SCAN_MS + Constants.POWER_TIER_LOW_BURST_SLEEP_MS) / 1000}s (~17% active).",
                isActive = currentPowerTier == "low_power",
            )

            TierExplanationItem(
                icon = Icons.Filled.BatterySaver,
                name = stringResource(R.string.settings_tier_ultra_low),
                description = "Minimal scanning — ${Constants.POWER_TIER_ULTRALOW_BURST_SCAN_MS / 1000}s " +
                        "every ${(Constants.POWER_TIER_ULTRALOW_BURST_SCAN_MS + Constants.POWER_TIER_ULTRALOW_BURST_SLEEP_MS) / 1000}s (~5% active). " +
                        "Activated below ${Constants.POWER_CRITICAL_BATTERY_PERCENT}% battery.",
                isActive = currentPowerTier == "ultra_low",
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.power_auto_adjust, Constants.POWER_LOW_BATTERY_PERCENT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CurrentTierCard(tier: String) {
    val tierColor = when (tier) {
        "high" -> MaterialTheme.colorScheme.error
        "balanced" -> MaterialTheme.colorScheme.primary
        "low_power" -> MaterialTheme.colorScheme.tertiary
        "ultra_low" -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurface
    }

    val tierIcon = when (tier) {
        "high" -> Icons.Filled.Speed
        "balanced" -> Icons.Filled.Balance
        "low_power" -> Icons.Filled.BatteryChargingFull
        "ultra_low" -> Icons.Filled.BatterySaver
        else -> Icons.Filled.BatteryUnknown
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = tierColor.copy(alpha = 0.1f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                tierIcon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = tierColor,
            )
            Column {
                Text(
                    text = stringResource(R.string.power_current_tier),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTierDisplay(tier),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = tierColor,
                )
            }
        }
    }
}

@Composable
private fun TierExplanationItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    description: String,
    isActive: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 2.dp else 0.dp,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.power_tier_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun formatTierDisplay(tier: String): String = when (tier) {
    "high" -> stringResource(R.string.settings_tier_high)
    "balanced" -> stringResource(R.string.settings_tier_balanced)
    "low_power" -> stringResource(R.string.settings_tier_low)
    "ultra_low" -> stringResource(R.string.settings_tier_ultra_low)
    else -> tier.replaceFirstChar { it.uppercase() }
}
