package com.flare.mesh.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.flare.mesh.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.flare.mesh.ui.chat.ConversationListScreen
import com.flare.mesh.ui.chat.ChatScreen
import com.flare.mesh.ui.contacts.ContactsScreen
import com.flare.mesh.ui.contacts.QrDisplayScreen
import com.flare.mesh.ui.contacts.QrScannerScreen
import com.flare.mesh.ui.settings.NetworkScreen
import com.flare.mesh.viewmodel.ContactsViewModel

/**
 * Navigation routes for the Flare app.
 */
sealed class Screen(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    data object Chats : Screen("chats", R.string.tab_chats, Icons.Filled.Chat, Icons.Outlined.Chat)
    data object Contacts : Screen("contacts", R.string.tab_contacts, Icons.Filled.People, Icons.Outlined.People)
    data object Network : Screen("network", R.string.tab_network, Icons.Filled.CellTower, Icons.Outlined.CellTower)
}

private val bottomNavItems = listOf(Screen.Chats, Screen.Contacts, Screen.Network)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlareNavHost(
    onRequestPermissions: () -> Unit,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // Show bottom nav only on top-level screens
    val showBottomBar = bottomNavItems.any { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    tonalElevation = NavigationBarDefaults.Elevation,
                ) {
                    bottomNavItems.forEach { screen ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == screen.route
                        } == true

                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = stringResource(screen.labelRes),
                                )
                            },
                            label = { Text(stringResource(screen.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Chats.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Chats.route) {
                ConversationListScreen(
                    onConversationClick = { conversationId ->
                        navController.navigate("chat/$conversationId")
                    },
                )
            }

            composable("chat/{conversationId}") { backStackEntry ->
                val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
                ChatScreen(
                    conversationId = conversationId,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Contacts.route) {
                ContactsScreen(
                    onContactClick = { deviceId ->
                        navController.navigate("chat/$deviceId")
                    },
                    onNavigateToScanner = {
                        navController.navigate("qr-scanner")
                    },
                    onNavigateToMyQr = {
                        navController.navigate("qr-display")
                    },
                )
            }

            composable("qr-scanner") {
                val contactsViewModel: ContactsViewModel = viewModel()
                QrScannerScreen(
                    onQrScanned = { qrData ->
                        contactsViewModel.addContactFromQr(qrData)
                        navController.popBackStack()
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable("qr-display") {
                val contactsViewModel: ContactsViewModel = viewModel()
                QrDisplayScreen(
                    qrData = contactsViewModel.generateQrData(),
                    safetyNumber = contactsViewModel.getSafetyNumber(),
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable(Screen.Network.route) {
                NetworkScreen()
            }
        }
    }
}
