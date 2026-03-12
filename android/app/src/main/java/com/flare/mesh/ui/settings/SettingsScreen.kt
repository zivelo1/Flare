package com.flare.mesh.ui.settings

import android.app.Activity
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Context
import com.flare.mesh.BuildConfig
import com.flare.mesh.R
import com.flare.mesh.util.Constants
import com.flare.mesh.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDuress: () -> Unit,
    onNavigateToPower: () -> Unit,
    onNavigateToApkShare: () -> Unit = {},
    onNavigateToLanguage: () -> Unit = {},
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }
    val hasDestructionCode = remember { mutableStateOf(prefs.getString(Constants.KEY_DESTRUCTION_CODE_HASH, null) != null) }
    val currentPowerTier by settingsViewModel.currentPowerTier.collectAsState()
    val storeStats by settingsViewModel.storeStats.collectAsState()

    LaunchedEffect(Unit) {
        settingsViewModel.refreshStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Profile Section ───────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_profile))

            ProfileNameItem(
                onNameChanged = { /* name saved via SharedPreferences in the item */ },
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Security Section ─────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_security))

            SettingsItem(
                icon = Icons.Filled.Shield,
                title = stringResource(R.string.destruction_title),
                subtitle = if (hasDestructionCode.value) stringResource(R.string.settings_duress_subtitle_configured)
                else stringResource(R.string.settings_duress_subtitle_not_configured),
                onClick = onNavigateToDuress,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Battery & Performance Section ────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_battery))

            SettingsItem(
                icon = Icons.Filled.BatteryChargingFull,
                title = stringResource(R.string.settings_power_title),
                subtitle = stringResource(R.string.settings_power_subtitle_format, formatTierName(currentPowerTier)),
                onClick = onNavigateToPower,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Storage Section ──────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_storage))

            storeStats?.let { stats ->
                StorageStatsCard(stats)
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Device Info Section ──────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_device))

            DeviceInfoCard(
                deviceId = settingsViewModel.deviceId,
                safetyNumber = settingsViewModel.safetyNumber,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Sharing Section ───────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_sharing))

            SettingsItem(
                icon = Icons.Filled.Share,
                title = stringResource(R.string.apk_settings_share),
                subtitle = stringResource(R.string.apk_settings_share_subtitle),
                onClick = onNavigateToApkShare,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Language Section ──────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_language))

            SettingsItem(
                icon = Icons.Filled.Language,
                title = stringResource(R.string.settings_language_title),
                subtitle = stringResource(R.string.settings_language_subtitle, getCurrentLanguageName()),
                onClick = onNavigateToLanguage,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── Display Section ──────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_display))

            DarkModeSettingsItem()

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // ── About Section ────────────────────────────────────────
            SettingsSectionHeader(stringResource(R.string.settings_section_about))

            SettingsItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.app_name),
                subtitle = "Version ${BuildConfig.VERSION_NAME} — ${stringResource(R.string.settings_about_subtitle)}",
                onClick = {},
            )

            SettingsItem(
                icon = Icons.Filled.Code,
                title = stringResource(R.string.settings_open_source),
                subtitle = stringResource(R.string.settings_license),
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
private fun ProfileNameItem(
    onNameChanged: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString(Constants.KEY_DISPLAY_NAME, "") ?: "") }

    ListItem(
        leadingContent = {
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    prefs.edit().putString(Constants.KEY_DISPLAY_NAME, it).apply()
                    onNameChanged(it)
                },
                label = { Text(stringResource(R.string.profile_name_label)) },
                placeholder = { Text(stringResource(R.string.profile_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
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
                StorageStat(stringResource(R.string.settings_storage_own), stats.ownMessages.toString())
                StorageStat(stringResource(R.string.settings_storage_relay), stats.activeRelayMessages.toString())
                StorageStat(stringResource(R.string.settings_storage_waiting), stats.waitingRelayMessages.toString())
                StorageStat(stringResource(R.string.settings_storage_total), stats.totalMessages.toString())
            }
            Spacer(Modifier.height(12.dp))

            val usedMB = stats.totalBytes / (1024.0 * 1024.0)
            val budgetMB = stats.budgetBytes / (1024.0 * 1024.0)
            val progress = if (stats.budgetBytes > 0)
                (stats.totalBytes.toFloat() / stats.budgetBytes.toFloat()).coerceIn(0f, 1f)
            else 0f

            Text(
                text = stringResource(R.string.settings_storage_usage_format, usedMB, budgetMB),
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
                text = stringResource(R.string.settings_device_id),
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
                text = stringResource(R.string.settings_safety_number),
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

@Composable
private fun DarkModeSettingsItem() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var currentMode by remember { mutableStateOf(prefs.getString(Constants.KEY_DARK_MODE, Constants.DARK_MODE_SYSTEM) ?: Constants.DARK_MODE_SYSTEM) }
    var showDialog by remember { mutableStateOf(false) }

    val subtitle = when (currentMode) {
        Constants.DARK_MODE_LIGHT -> stringResource(R.string.settings_dark_mode_light)
        Constants.DARK_MODE_DARK -> stringResource(R.string.settings_dark_mode_dark)
        else -> stringResource(R.string.settings_dark_mode_system)
    }

    SettingsItem(
        icon = Icons.Filled.DarkMode,
        title = stringResource(R.string.settings_dark_mode_title),
        subtitle = subtitle,
        onClick = { showDialog = true },
    )

    if (showDialog) {
        val options = listOf(
            Constants.DARK_MODE_SYSTEM to stringResource(R.string.settings_dark_mode_system),
            Constants.DARK_MODE_LIGHT to stringResource(R.string.settings_dark_mode_light),
            Constants.DARK_MODE_DARK to stringResource(R.string.settings_dark_mode_dark),
        )

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_dark_mode_title)) },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (value != currentMode) {
                                        currentMode = value
                                        prefs.edit().putString(Constants.KEY_DARK_MODE, value).apply()
                                        showDialog = false
                                        // Recreate activity to apply theme change across the entire app
                                        (context as? Activity)?.recreate()
                                    } else {
                                        showDialog = false
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = currentMode == value,
                                onClick = {
                                    if (value != currentMode) {
                                        currentMode = value
                                        prefs.edit().putString(Constants.KEY_DARK_MODE, value).apply()
                                        showDialog = false
                                        (context as? Activity)?.recreate()
                                    } else {
                                        showDialog = false
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun formatTierName(tier: String): String = when (tier) {
    "high" -> stringResource(R.string.settings_tier_high)
    "balanced" -> stringResource(R.string.settings_tier_balanced)
    "low_power" -> stringResource(R.string.settings_tier_low)
    "ultra_low" -> stringResource(R.string.settings_tier_ultra_low)
    else -> tier.replaceFirstChar { it.uppercase() }
}

@Composable
private fun getCurrentLanguageName(): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return when (locale.language) {
        "fa" -> stringResource(R.string.language_farsi)
        "ar" -> stringResource(R.string.language_arabic)
        "es" -> stringResource(R.string.language_spanish)
        "ru" -> stringResource(R.string.language_russian)
        "zh" -> stringResource(R.string.language_chinese)
        "ko" -> stringResource(R.string.language_korean)
        else -> stringResource(R.string.language_english)
    }
}
