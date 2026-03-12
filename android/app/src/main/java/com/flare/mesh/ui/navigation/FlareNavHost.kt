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
import com.flare.mesh.ui.chat.BroadcastScreen
import com.flare.mesh.ui.chat.ConversationListScreen
import com.flare.mesh.ui.chat.ChatScreen
import com.flare.mesh.ui.contacts.ContactsScreen
import com.flare.mesh.ui.contacts.FindContactScreen
import com.flare.mesh.ui.contacts.PhoneSearchScreen
import com.flare.mesh.ui.contacts.QrDisplayScreen
import com.flare.mesh.ui.contacts.QrScannerScreen
import com.flare.mesh.ui.contacts.SharedPhraseSearchScreen
import com.flare.mesh.ui.groups.CreateGroupScreen
import com.flare.mesh.ui.groups.GroupListScreen
import com.flare.mesh.ui.onboarding.OnboardingScreen
import com.flare.mesh.ui.settings.DuressSettingsScreen
import com.flare.mesh.ui.settings.NetworkScreen
import com.flare.mesh.ui.settings.PowerSettingsScreen
import com.flare.mesh.ui.settings.LanguageSettingsScreen
import com.flare.mesh.ui.settings.SettingsScreen
import com.flare.mesh.util.Constants
import com.flare.mesh.ui.sharing.ApkShareScreen
import com.flare.mesh.ui.splash.SplashScreen
import com.flare.mesh.viewmodel.ContactsViewModel
import com.flare.mesh.viewmodel.DiscoveryViewModel

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

/** Key for persisting onboarding completion in SharedPreferences. */
private val PREFS_NAME = Constants.PREFS_NAME
private val KEY_ONBOARDING_COMPLETE = Constants.KEY_ONBOARDING_COMPLETE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlareNavHost(
    onRequestPermissions: () -> Unit,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val onboardingComplete = remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)) }

    // Always start with splash; it will navigate to onboarding or chats
    val startDest = "splash"

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
            startDestination = startDest,
            modifier = Modifier.padding(innerPadding),
        ) {
            // ── Splash ────────────────────────────────────────────────
            composable("splash") {
                SplashScreen(
                    onSplashComplete = {
                        val nextRoute = if (onboardingComplete.value) {
                            Screen.Chats.route
                        } else {
                            "onboarding"
                        }
                        navController.navigate(nextRoute) {
                            popUpTo("splash") { inclusive = true }
                        }
                    },
                )
            }

            // ── Onboarding ──────────────────────────────────────────
            composable("onboarding") {
                OnboardingScreen(
                    onComplete = {
                        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
                        onboardingComplete.value = true
                        navController.navigate(Screen.Chats.route) {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    },
                )
            }

            // ── Chats ───────────────────────────────────────────────
            composable(Screen.Chats.route) {
                ConversationListScreen(
                    onConversationClick = { conversationId ->
                        navController.navigate("chat/$conversationId")
                    },
                    onNavigateToSettings = {
                        navController.navigate("settings")
                    },
                    onNavigateToGroups = {
                        navController.navigate("groups")
                    },
                    onNavigateToBroadcast = {
                        navController.navigate("broadcast")
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

            // ── Contacts ────────────────────────────────────────────
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
                    onNavigateToFindContact = {
                        navController.navigate("find-contact")
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
                    shareLink = contactsViewModel.generateShareLink(),
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable("find-contact") {
                FindContactScreen(
                    onNavigateToPhrase = { navController.navigate("phrase-search") },
                    onNavigateToQrScanner = { navController.navigate("qr-scanner") },
                    onNavigateToPhone = { navController.navigate("phone-search") },
                    onNavigateToImport = { /* TODO: contact import screen */ },
                    onNavigateToShareLink = { navController.navigate("qr-display") },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable("phrase-search") {
                val discoveryViewModel: DiscoveryViewModel = viewModel()
                SharedPhraseSearchScreen(
                    viewModel = discoveryViewModel,
                    onContactFound = { navController.popBackStack("contacts", false) },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable("phone-search") {
                val discoveryViewModel: DiscoveryViewModel = viewModel()
                PhoneSearchScreen(
                    viewModel = discoveryViewModel,
                    onContactFound = { navController.popBackStack("contacts", false) },
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── Network ─────────────────────────────────────────────
            composable(Screen.Network.route) {
                NetworkScreen()
            }

            // ── Settings ────────────────────────────────────────────
            composable("settings") {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDuress = { navController.navigate("settings-duress") },
                    onNavigateToPower = { navController.navigate("settings-power") },
                    onNavigateToApkShare = { navController.navigate("apk-share") },
                    onNavigateToLanguage = { navController.navigate("settings-language") },
                )
            }

            composable("settings-duress") {
                DuressSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable("settings-power") {
                PowerSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            composable("settings-language") {
                LanguageSettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── Groups ──────────────────────────────────────────────
            composable("groups") {
                GroupListScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onCreateGroup = { navController.navigate("create-group") },
                    onGroupClick = { groupId ->
                        navController.navigate("chat/$groupId")
                    },
                )
            }

            composable("create-group") {
                CreateGroupScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onGroupCreated = { groupId ->
                        navController.popBackStack("groups", false)
                    },
                )
            }

            // ── Broadcast ──────────────────────────────────────────
            composable("broadcast") {
                BroadcastScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── APK Sharing ─────────────────────────────────────────
            composable("apk-share") {
                ApkShareScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }

        }
    }
}
