package com.flare.mesh.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val description: String,
)

private val ONBOARDING_PAGES = listOf(
    OnboardingPage(
        icon = Icons.Filled.CellTower,
        title = "No Internet Needed",
        description = "Flare sends messages phone to phone using Bluetooth. " +
                "No servers, no Wi-Fi, no cell service. " +
                "Your messages hop through other Flare users until they reach your contact.",
    ),
    OnboardingPage(
        icon = Icons.Filled.Lock,
        title = "End-to-End Encrypted",
        description = "Every message is encrypted on your phone before it leaves. " +
                "Nobody in between can read your messages — not even the people whose phones relay them.",
    ),
    OnboardingPage(
        icon = Icons.Filled.People,
        title = "Find Your Friends",
        description = "Enter a shared phrase that you and your friend both know — " +
                "a memory, a place, an inside joke. Flare searches the mesh and connects you securely.",
    ),
    OnboardingPage(
        icon = Icons.Filled.Shield,
        title = "Designed for Safety",
        description = "Duress PIN opens a fake database if you are forced to unlock. " +
                "No accounts, no phone number required, no tracking. " +
                "Your privacy is protected by design.",
    ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGES.size })
    val coroutineScope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGES.lastIndex

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Skip button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (!isLastPage) {
                    TextButton(onClick = onComplete) {
                        Text("Skip")
                    }
                }
            }

            // Pages
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                OnboardingPageContent(ONBOARDING_PAGES[page])
            }

            // Page indicator dots
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(ONBOARDING_PAGES.size) { index ->
                    val isActive = index == pagerState.currentPage
                    Surface(
                        shape = CircleShape,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isActive) 10.dp else 8.dp),
                    ) {}
                }
            }

            // Action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Back button (hidden on first page)
                if (pagerState.currentPage > 0) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        },
                    ) {
                        Text("Back")
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                if (isLastPage) {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            text = "Get Started",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        },
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
        )
    }
}
