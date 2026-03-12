package com.flare.mesh.ui.contacts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flare.mesh.R
import com.flare.mesh.data.model.Contact
import com.flare.mesh.viewmodel.ContactsViewModel
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onContactClick: (String) -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToMyQr: () -> Unit,
    onNavigateToFindContact: () -> Unit = {},
    contactsViewModel: ContactsViewModel = viewModel(),
) {
    val meshStatus by contactsViewModel.meshStatus.collectAsState()
    val contacts by contactsViewModel.contacts.collectAsState()

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Contact?>(null) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        contactsViewModel.refreshContacts()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tab_contacts),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToFindContact) {
                        Icon(
                            Icons.Default.PersonSearch,
                            contentDescription = stringResource(R.string.contacts_find),
                        )
                    }
                    IconButton(onClick = onNavigateToScanner) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.action_scan_qr),
                        )
                    }
                    IconButton(onClick = onNavigateToMyQr) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = stringResource(R.string.action_show_qr),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (contacts.isEmpty()) {
            EmptyContactsView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                nearbyCount = meshStatus.discoveredPeerCount,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(contacts, key = { it.identity.deviceId }) { contact ->
                    ContactItem(
                        contact = contact,
                        onClick = { onContactClick(contact.identity.deviceId) },
                        onLongClick = {
                            renameTarget = contact
                            renameText = contact.displayName ?: ""
                            showRenameDialog = true
                        },
                    )
                }
            }
        }
    }

    if (showRenameDialog && renameTarget != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameTarget?.let { contact ->
                            contactsViewModel.renameContact(contact.identity.deviceId, renameText.trim())
                        }
                        showRenameDialog = false
                    },
                    enabled = renameText.isNotBlank(),
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            title = { Text(stringResource(R.string.rename_contact_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.rename_contact_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.rename_contact_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
        )
    }
}

@Composable
private fun EmptyContactsView(
    modifier: Modifier = Modifier,
    nearbyCount: Int,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.QrCodeScanner,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.contacts_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp),
        )

        if (nearbyCount > 0) {
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.contacts_devices_nearby_format, nearbyCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactItem(
    contact: Contact,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    ListItem(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = if (contact.isVerified)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = (contact.displayName?.firstOrNull() ?: '?').uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (contact.isVerified)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = contact.displayName ?: contact.identity.deviceId.take(12),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (contact.isVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.Verified,
                        contentDescription = stringResource(R.string.verified_badge),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            val lastSeenText = formatLastSeen(contact.lastSeen)
            Text(
                text = lastSeenText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun formatLastSeen(instant: Instant): String {
    val duration = Duration.between(instant, Instant.now())
    return when {
        duration.toMinutes() < 1 -> stringResource(R.string.contacts_last_seen_now)
        duration.toMinutes() < 60 -> stringResource(R.string.contacts_last_seen_minutes, duration.toMinutes().toInt())
        duration.toHours() < 24 -> stringResource(R.string.contacts_last_seen_hours, duration.toHours().toInt())
        else -> stringResource(R.string.contacts_last_seen_days, duration.toDays().toInt())
    }
}
