package com.flare.mesh.ui.settings

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flare.mesh.R
import com.flare.mesh.ui.lock.sha256
import com.flare.mesh.util.Constants

/**
 * Settings screen for configuring the Destruction Code.
 *
 * The user sets two codes:
 * - Unlock code: opens the app normally (also unlockable via biometric)
 * - Destruction code: permanently erases ALL data (messages, contacts, identity)
 *
 * Both codes are stored as SHA-256 hashes in SharedPreferences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestructionCodeScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }

    val isConfigured = remember {
        mutableStateOf(prefs.getString(Constants.KEY_DESTRUCTION_CODE_HASH, null) != null)
    }

    var unlockCode by remember { mutableStateOf("") }
    var destructionCode by remember { mutableStateOf("") }
    var confirmDestructionCode by remember { mutableStateOf("") }
    var showCodes by remember { mutableStateOf(false) }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var setupSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.destruction_title)) },
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
                        Icons.Filled.DeleteForever,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.destruction_plausible_deniability),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.destruction_explanation),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (isConfigured.value) {
                // Already configured — show status and clear option
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Filled.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.destruction_active_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.destruction_active_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
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
                    Text(stringResource(R.string.destruction_remove_button))
                }
            } else {
                // Setup new destruction code
                val visualTransformation = if (showCodes) VisualTransformation.None
                else PasswordVisualTransformation()

                // Unlock code
                OutlinedTextField(
                    value = unlockCode,
                    onValueChange = { unlockCode = it },
                    label = { Text(stringResource(R.string.destruction_unlock_code_label)) },
                    supportingText = { Text(stringResource(R.string.destruction_unlock_code_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = visualTransformation,
                    trailingIcon = {
                        IconButton(onClick = { showCodes = !showCodes }) {
                            Icon(
                                if (showCodes) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    singleLine = true,
                )

                Spacer(Modifier.height(12.dp))

                // Destruction code
                OutlinedTextField(
                    value = destructionCode,
                    onValueChange = { destructionCode = it },
                    label = { Text(stringResource(R.string.destruction_code_label)) },
                    supportingText = { Text(stringResource(R.string.destruction_code_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = visualTransformation,
                    singleLine = true,
                )

                Spacer(Modifier.height(12.dp))

                // Confirm destruction code
                OutlinedTextField(
                    value = confirmDestructionCode,
                    onValueChange = { confirmDestructionCode = it },
                    label = { Text(stringResource(R.string.destruction_confirm_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = visualTransformation,
                    singleLine = true,
                )

                Spacer(Modifier.height(8.dp))

                // Validation messages
                AnimatedVisibility(
                    visible = destructionCode.isNotEmpty()
                            && confirmDestructionCode.isNotEmpty()
                            && destructionCode != confirmDestructionCode,
                ) {
                    Text(
                        text = stringResource(R.string.destruction_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                AnimatedVisibility(
                    visible = unlockCode.isNotEmpty()
                            && destructionCode.isNotEmpty()
                            && unlockCode == destructionCode,
                ) {
                    Text(
                        text = stringResource(R.string.destruction_codes_same),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Warning card
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
                            text = stringResource(R.string.destruction_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                val canSet = unlockCode.length >= Constants.MIN_CODE_LENGTH
                        && destructionCode.length >= Constants.MIN_CODE_LENGTH
                        && destructionCode == confirmDestructionCode
                        && unlockCode != destructionCode

                Button(
                    onClick = {
                        prefs.edit()
                            .putString(Constants.KEY_UNLOCK_CODE_HASH, sha256(unlockCode))
                            .putString(Constants.KEY_DESTRUCTION_CODE_HASH, sha256(destructionCode))
                            .apply()
                        isConfigured.value = true
                        setupSuccess = true
                        unlockCode = ""
                        destructionCode = ""
                        confirmDestructionCode = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSet,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.destruction_set_button))
                }

                AnimatedVisibility(visible = setupSuccess) {
                    Text(
                        text = stringResource(R.string.destruction_set_success),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
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
                        prefs.edit()
                            .remove(Constants.KEY_UNLOCK_CODE_HASH)
                            .remove(Constants.KEY_DESTRUCTION_CODE_HASH)
                            .apply()
                        isConfigured.value = false
                        showConfirmDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.destruction_remove_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(R.string.destruction_remove_dialog_title)) },
            text = { Text(stringResource(R.string.destruction_remove_dialog_message)) },
        )
    }
}
