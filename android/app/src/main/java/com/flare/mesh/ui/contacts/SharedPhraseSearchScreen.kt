package com.flare.mesh.ui.contacts

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flare.mesh.viewmodel.DiscoveryViewModel
import com.flare.mesh.viewmodel.SearchState

/**
 * Screen for discovering contacts via a shared passphrase.
 *
 * Both parties enter the same phrase (a shared memory, inside joke, etc.)
 * to derive a rendezvous token. This is the most secure discovery method
 * because passphrases have high entropy — unlike phone numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedPhraseSearchScreen(
    viewModel: DiscoveryViewModel,
    onContactFound: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val searchState by viewModel.searchState.collectAsState()
    var phrase by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shared Phrase") },
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
                    Text(
                        text = "Enter a phrase that only you and your friend would know. " +
                            "It could be a shared memory, a place you both visited, " +
                            "or anything unique between you.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    OutlinedTextField(
                        value = phrase,
                        onValueChange = { phrase = it },
                        label = { Text("Shared phrase") },
                        placeholder = { Text("e.g., grandmother's house on hafez street") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Your friend must enter the EXACT same phrase in their Flare app. " +
                            "Case and extra spaces don't matter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (searchState is SearchState.Error) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = (searchState as SearchState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.startPhraseSearch(phrase) },
                        enabled = phrase.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Search Mesh")
                    }
                }

                is SearchState.Searching -> {
                    Spacer(modifier = Modifier.height(48.dp))

                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 4.dp,
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = "Searching the mesh network...",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Broadcasting your rendezvous token to nearby devices. " +
                            "This may take minutes, hours, or days depending on " +
                            "mesh connectivity to your friend.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Your phrase never leaves this device. Only a mathematical " +
                            "fingerprint is broadcast — it cannot be reversed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(32.dp))

                    OutlinedButton(onClick = {
                        viewModel.cancelSearch()
                    }) {
                        Text("Cancel Search")
                    }
                }

                is SearchState.Found -> {
                    val found = (searchState as SearchState.Found).contact

                    Spacer(modifier = Modifier.height(48.dp))

                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Contact Found!",
                        style = MaterialTheme.typography.headlineSmall,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Found via ${found.discoveryMethod}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Device ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = found.identity.deviceId.take(24) + "...",
                                style = MaterialTheme.typography.bodyMedium,
                            )
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
