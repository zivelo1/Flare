package com.flare.mesh.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Settings
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
import com.flare.mesh.data.model.Conversation
import com.flare.mesh.data.model.MeshStatus
import com.flare.mesh.util.IdenticonGenerator
import com.flare.mesh.viewmodel.ChatViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onConversationClick: (String) -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToGroups: () -> Unit = {},
    onNavigateToBroadcast: () -> Unit = {},
    chatViewModel: ChatViewModel = viewModel(),
) {
    val meshStatus by chatViewModel.meshStatus.collectAsState()
    val isServiceRunning by chatViewModel.isServiceRunning.collectAsState()
    val conversations by chatViewModel.conversations.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        MeshStatusIndicator(meshStatus, isServiceRunning)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToBroadcast) {
                        Icon(Icons.Default.Campaign, contentDescription = stringResource(R.string.broadcast_title))
                    }
                    IconButton(onClick = onNavigateToGroups) {
                        Icon(Icons.Default.Group, contentDescription = stringResource(R.string.conversation_groups))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        if (conversations.isEmpty()) {
            EmptyConversationsView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                meshStatus = meshStatus,
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationItem(
                        conversation = conversation,
                        onClick = { onConversationClick(conversation.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshStatusIndicator(meshStatus: MeshStatus, isRunning: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.animateContentSize(),
    ) {
        val statusColor = when {
            !isRunning -> MaterialTheme.colorScheme.error
            meshStatus.connectedPeerCount > 0 -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        }

        Icon(
            Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = statusColor,
            modifier = Modifier.size(8.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = when {
                !isRunning -> stringResource(R.string.network_status_inactive)
                meshStatus.connectedPeerCount > 0 ->
                    stringResource(R.string.peer_count_format, meshStatus.connectedPeerCount)
                else -> stringResource(R.string.mesh_notification_text_no_peers)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyConversationsView(
    modifier: Modifier = Modifier,
    meshStatus: MeshStatus,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "🔥",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize * 2),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.no_conversations),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 48.dp),
        )

        if (meshStatus.discoveredPeerCount > 0) {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.conversation_devices_nearby_format, meshStatus.discoveredPeerCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            val (bgColor, fgColor) = IdenticonGenerator.getColors(conversation.contact.identity.deviceId)
            val initials = IdenticonGenerator.getInitials(
                conversation.contact.displayName,
                conversation.contact.identity.deviceId,
            )
            Surface(
                shape = CircleShape,
                color = bgColor.copy(alpha = 0.25f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleMedium,
                        color = bgColor,
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = conversation.contact.displayName ?: conversation.contact.identity.deviceId.take(12),
                fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            conversation.lastMessage?.let { msg ->
                Text(
                    text = msg.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                conversation.lastMessage?.let { msg ->
                    Text(
                        text = timeFormatter.format(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (conversation.unreadCount > 0)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Badge {
                        Text(conversation.unreadCount.toString())
                    }
                }
            }
        },
    )
}
