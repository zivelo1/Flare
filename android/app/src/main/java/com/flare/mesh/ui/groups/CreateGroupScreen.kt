package com.flare.mesh.ui.groups

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flare.mesh.data.model.Contact
import com.flare.mesh.util.IdenticonGenerator
import com.flare.mesh.viewmodel.CreateGroupStatus
import com.flare.mesh.viewmodel.GroupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onNavigateBack: () -> Unit,
    onGroupCreated: (String) -> Unit,
    groupViewModel: GroupViewModel = viewModel(),
) {
    var groupName by remember { mutableStateOf("") }
    val selectedMembers by groupViewModel.selectedGroupMembers.collectAsState()
    val availableContacts by groupViewModel.availableContacts.collectAsState()
    val createStatus by groupViewModel.createGroupStatus.collectAsState()

    // Navigate on success
    LaunchedEffect(createStatus) {
        if (createStatus is CreateGroupStatus.Success) {
            val groupId = (createStatus as CreateGroupStatus.Success).groupId
            groupViewModel.clearSelection()
            onGroupCreated(groupId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Group") },
                navigationIcon = {
                    IconButton(onClick = {
                        groupViewModel.clearSelection()
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = groupName.isNotBlank() && selectedMembers.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { groupViewModel.createGroup(groupName) },
                    icon = { Icon(Icons.Filled.Group, contentDescription = null) },
                    text = { Text("Create (${selectedMembers.size} members)") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Group name input
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Group name") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                singleLine = true,
            )

            // Selected count
            if (selectedMembers.isNotEmpty()) {
                Text(
                    text = "${selectedMembers.size} member${if (selectedMembers.size > 1) "s" else ""} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            // Section header
            Text(
                text = "Select Members",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )

            if (availableContacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No contacts yet.\nAdd contacts first to create a group.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(availableContacts, key = { it.identity.deviceId }) { contact ->
                        val isSelected = selectedMembers.contains(contact.identity.deviceId)
                        ContactSelectionItem(
                            contact = contact,
                            isSelected = isSelected,
                            onClick = {
                                groupViewModel.toggleMemberSelection(contact.identity.deviceId)
                            },
                        )
                    }
                }
            }

            // Error state
            AnimatedVisibility(visible = createStatus is CreateGroupStatus.Error) {
                Text(
                    text = (createStatus as? CreateGroupStatus.Error)?.message ?: "Error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }

            // Loading state
            AnimatedVisibility(visible = createStatus is CreateGroupStatus.Creating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ContactSelectionItem(
    contact: Contact,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val (bgColor, _) = IdenticonGenerator.getColors(contact.identity.deviceId)
    val initials = IdenticonGenerator.getInitials(contact.displayName, contact.identity.deviceId)

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = bgColor.copy(alpha = 0.3f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleSmall,
                        color = bgColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = contact.displayName ?: contact.identity.deviceId.take(12),
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        supportingContent = {
            Text(
                text = contact.identity.deviceId.take(16) + "…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            if (isSelected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
