package com.flare.mesh.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flare.mesh.R

/**
 * Entry screen for finding contacts via different discovery methods.
 *
 * Three tiers of discovery:
 * 1. Shared Phrase (recommended) — highest security
 * 2. QR Code — requires in-person meeting
 * 3. Phone Number — convenience, with surveillance warning
 * 4. Import Contacts — bulk discovery from phone contacts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindContactScreen(
    onNavigateToPhrase: () -> Unit,
    onNavigateToQrScanner: () -> Unit,
    onNavigateToPhone: () -> Unit,
    onNavigateToImport: () -> Unit,
    onNavigateToShareLink: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.find_contact_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.find_contact_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Recommended: Shared Phrase
            DiscoveryOptionCard(
                icon = Icons.Default.VpnKey,
                title = "Shared Phrase",
                description = "Enter a phrase that only you and your friend know. " +
                    "A shared memory, a place, an inside joke. Most secure option.",
                recommended = true,
                onClick = onNavigateToPhrase,
            )

            // Share Identity Link
            DiscoveryOptionCard(
                icon = Icons.Default.Share,
                title = "Share Identity Link",
                description = "Send your Flare identity via SMS, WhatsApp, or any app. " +
                    "Works at any distance — no mesh needed.",
                onClick = onNavigateToShareLink,
            )

            // QR Code
            DiscoveryOptionCard(
                icon = Icons.Default.QrCodeScanner,
                title = "Scan QR Code",
                description = "Scan your contact's QR code when you meet in person. " +
                    "Maximum security — zero network exposure.",
                onClick = onNavigateToQrScanner,
            )

            // Phone Number
            DiscoveryOptionCard(
                icon = Icons.Default.Phone,
                title = "Phone Number",
                description = "Search by phone number. Both parties must enter each other's " +
                    "number. Less secure against well-funded adversaries.",
                onClick = onNavigateToPhone,
            )

            // Import Contacts
            DiscoveryOptionCard(
                icon = Icons.Default.Contacts,
                title = "Import Contacts",
                description = "Import your phone contacts to automatically find friends " +
                    "who also use Flare. Requires READ_CONTACTS permission.",
                onClick = onNavigateToImport,
            )
        }
    }
}

@Composable
private fun DiscoveryOptionCard(
    icon: ImageVector,
    title: String,
    description: String,
    recommended: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = if (recommended) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (recommended) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (recommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("Recommended", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
