package com.flare.mesh.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flare.mesh.R
import com.flare.mesh.data.model.ChatMessage
import com.flare.mesh.data.model.DeliveryStatus
import com.flare.mesh.util.IdenticonGenerator
import com.flare.mesh.viewmodel.ChatViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateBack: () -> Unit,
    chatViewModel: ChatViewModel = viewModel(),
) {
    var messageText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val messages by chatViewModel.currentMessages.collectAsState()
    val contact by chatViewModel.currentContact.collectAsState()

    LaunchedEffect(conversationId) {
        chatViewModel.loadConversation(conversationId)
    }

    val contactName = contact?.displayName ?: "Peer ${conversationId.take(8)}"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (avatarBg, _) = IdenticonGenerator.getColors(conversationId)
                        val avatarInitials = IdenticonGenerator.getInitials(
                            contact?.displayName,
                            conversationId,
                        )
                        Surface(
                            shape = CircleShape,
                            color = avatarBg.copy(alpha = 0.25f),
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = avatarInitials,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = avatarBg,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = contactName,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "via mesh",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
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
        bottomBar = {
            MessageInput(
                text = messageText,
                onTextChange = { messageText = it },
                onSend = {
                    if (messageText.isNotBlank()) {
                        chatViewModel.sendMessage(conversationId, messageText.trim())
                        messageText = ""
                    }
                },
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Send a message to start the conversation.\nMessages are encrypted end-to-end.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 48.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                reverseLayout = true,
            ) {
                items(messages.reversed(), key = { it.messageId }) { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }

    val isOutgoing = message.isOutgoing
    val bubbleColor by animateColorAsState(
        targetValue = if (isOutgoing)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.surfaceVariant,
        label = "bubbleColor",
    )
    val textColor = if (isOutgoing)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    val bubbleShape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (isOutgoing) 16.dp else 4.dp,
        bottomEnd = if (isOutgoing) 4.dp else 16.dp,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = timeFormatter.format(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                    if (isOutgoing) {
                        Spacer(Modifier.width(4.dp))
                        DeliveryStatusIcon(
                            status = message.deliveryStatus,
                            tint = textColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliveryStatusIcon(status: DeliveryStatus, tint: Color) {
    val icon = when (status) {
        DeliveryStatus.PENDING -> Icons.Filled.Schedule
        DeliveryStatus.SENT -> Icons.Filled.Done
        DeliveryStatus.DELIVERED -> Icons.Filled.DoneAll
        DeliveryStatus.READ -> Icons.Filled.DoneAll
        DeliveryStatus.FAILED -> Icons.Filled.ErrorOutline
    }
    val iconTint = when (status) {
        DeliveryStatus.READ -> Color(0xFF4FC3F7) // Light blue for read
        DeliveryStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> tint
    }

    Icon(
        icon,
        contentDescription = when (status) {
            DeliveryStatus.PENDING -> "Sending"
            DeliveryStatus.SENT -> "Sent"
            DeliveryStatus.DELIVERED -> "Delivered"
            DeliveryStatus.READ -> "Read"
            DeliveryStatus.FAILED -> "Failed"
        },
        modifier = Modifier.size(14.dp),
        tint = iconTint,
    )
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        stringResource(R.string.chat_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                maxLines = 4,
            )

            Spacer(Modifier.width(8.dp))

            FilledIconButton(
                onClick = onSend,
                enabled = text.isNotBlank(),
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.action_send),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
