package com.flare.mesh.ui.contacts

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flare.mesh.viewmodel.DiscoveryViewModel
import com.flare.mesh.viewmodel.SearchState

/**
 * Screen for discovering contacts via phone number.
 *
 * Uses bilateral hashing: both parties' phone numbers are combined
 * to derive the rendezvous token. This means both parties must enter
 * each other's number for the match to succeed.
 *
 * Includes a prominent security warning — this method is less secure
 * than shared phrase against nation-state adversaries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSearchScreen(
    viewModel: DiscoveryViewModel,
    onContactFound: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val searchState by viewModel.searchState.collectAsState()
    var myPhone by remember { mutableStateOf("") }
    var theirPhone by remember { mutableStateOf("") }
    var acceptedRisk by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone Number Search") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (searchState) {
                is SearchState.Idle, is SearchState.Error -> {
                    // Security warning
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Security Notice",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "A well-funded adversary who knows YOUR phone number " +
                                        "could potentially determine who you are trying to reach. " +
                                        "Use \"Shared Phrase\" instead if you are concerned about " +
                                        "government surveillance.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = myPhone,
                        onValueChange = { myPhone = it },
                        label = { Text("Your phone number") },
                        placeholder = { Text("+1 555 123 4567") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = theirPhone,
                        onValueChange = { theirPhone = it },
                        label = { Text("Their phone number") },
                        placeholder = { Text("+1 555 987 6543") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = acceptedRisk,
                            onCheckedChange = { acceptedRisk = it },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "I understand the security limitations",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (searchState is SearchState.Error) {
                        Text(
                            text = (searchState as SearchState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.startPhoneSearch(myPhone, theirPhone) },
                        enabled = myPhone.isNotBlank() && theirPhone.isNotBlank() && acceptedRisk,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search Mesh")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Your friend must also enter YOUR phone number in their app. " +
                            "Neither phone number is ever sent over the network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                is SearchState.Searching -> {
                    Spacer(modifier = Modifier.height(48.dp))
                    CircularProgressIndicator(modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Searching the mesh network...", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Both phone numbers are combined into a single token. " +
                            "Only someone who knows both numbers can match it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    OutlinedButton(onClick = { viewModel.cancelSearch() }) {
                        Text("Cancel Search")
                    }
                }

                is SearchState.Found -> {
                    // Reuse the same found state UI as SharedPhraseSearchScreen
                    val found = (searchState as SearchState.Found).contact
                    Spacer(modifier = Modifier.height(48.dp))
                    Text("Contact Found!", style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Device ID", style = MaterialTheme.typography.labelSmall)
                            Text(found.identity.deviceId.take(24) + "...")
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            onContactFound()
                            viewModel.clearDiscovery()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Add Contact")
                    }
                }
            }
        }
    }
}
