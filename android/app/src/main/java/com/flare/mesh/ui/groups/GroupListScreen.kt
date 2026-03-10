package com.flare.mesh.ui.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flare.mesh.data.model.Group
import com.flare.mesh.util.IdenticonGenerator
import com.flare.mesh.viewmodel.GroupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupListScreen(
    onNavigateBack: () -> Unit,
    onCreateGroup: () -> Unit,
    onGroupClick: (String) -> Unit,
    groupViewModel: GroupViewModel = viewModel(),
) {
    val groups by groupViewModel.groups.collectAsState()

    LaunchedEffect(Unit) {
        groupViewModel.refreshGroups()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Groups") },
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
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateGroup,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Create group",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) { padding ->
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Group,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "No groups yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Create a group to message multiple contacts at once.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 48.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(groups, key = { it.groupId }) { group ->
                    GroupItem(
                        group = group,
                        onClick = { onGroupClick(group.groupId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupItem(
    group: Group,
    onClick: () -> Unit,
) {
    val (bgColor, _) = IdenticonGenerator.getColors(group.groupId)

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Surface(
                shape = CircleShape,
                color = bgColor.copy(alpha = 0.25f),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Group,
                        contentDescription = null,
                        tint = bgColor,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        headlineContent = {
            Text(
                text = group.groupName,
                fontWeight = FontWeight.Medium,
            )
        },
        supportingContent = {
            Text(
                text = "${group.memberCount} member${if (group.memberCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
