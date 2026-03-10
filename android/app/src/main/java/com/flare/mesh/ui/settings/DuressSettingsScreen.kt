package com.flare.mesh.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flare.mesh.viewmodel.DuressSetupStatus
import com.flare.mesh.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuressSettingsScreen(
    onNavigateBack: () -> Unit,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    val hasDuressPin by settingsViewModel.hasDuressPin.collectAsState()
    val duressStatus by settingsViewModel.duressStatus.collectAsState()

    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Duress PIN") },
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
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            // Explanation card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Column {
                        Text(
                            text = "Plausible Deniability",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "If you are forced to unlock Flare, entering the duress PIN opens a decoy database with innocent messages. Your real conversations remain hidden and encrypted.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (hasDuressPin) {
                // Already configured — show status and clear option
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Duress PIN Active",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "A decoy database will be shown when the duress PIN is entered.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                OutlinedButton(
                    onClick = { showConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Remove Duress PIN")
                }
            } else {
                // Setup new duress PIN
                val visualTransformation = if (showPassphrase) VisualTransformation.None
                else PasswordVisualTransformation()

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Duress passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = visualTransformation,
                    trailingIcon = {
                        IconButton(onClick = { showPassphrase = !showPassphrase }) {
                            Icon(
                                if (showPassphrase) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = if (showPassphrase) "Hide" else "Show",
                            )
                        }
                    },
                    singleLine = true,
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = confirmPassphrase,
                    onValueChange = { confirmPassphrase = it },
                    label = { Text("Confirm passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = visualTransformation,
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                AnimatedVisibility(
                    visible = passphrase.isNotEmpty() && confirmPassphrase.isNotEmpty()
                            && passphrase != confirmPassphrase,
                ) {
                    Text(
                        text = "Passphrases do not match",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Warning
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "Choose a passphrase you can remember under stress. " +
                                    "It must be different from your main passphrase. " +
                                    "If you forget it, there is no way to recover it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                val canSet = passphrase.isNotBlank()
                        && passphrase == confirmPassphrase
                        && passphrase.length >= 4
                        && duressStatus != DuressSetupStatus.SETTING

                Button(
                    onClick = {
                        settingsViewModel.setDuressPassphrase(passphrase)
                        passphrase = ""
                        confirmPassphrase = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSet,
                ) {
                    if (duressStatus == DuressSetupStatus.SETTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Set Duress PIN")
                }

                AnimatedVisibility(visible = duressStatus == DuressSetupStatus.SUCCESS) {
                    Text(
                        text = "Duress PIN set successfully",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                AnimatedVisibility(visible = duressStatus == DuressSetupStatus.ERROR) {
                    Text(
                        text = "Failed to set duress PIN. Please try again.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    // Confirm removal dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsViewModel.clearDuressPassphrase()
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text("Remove Duress PIN?") },
            text = {
                Text("This will disable plausible deniability. Anyone who forces you to unlock Flare will see your real conversations.")
            },
        )
    }
}
