package com.flare.mesh.ui.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flare.mesh.R
import com.flare.mesh.util.Constants
import kotlinx.coroutines.delay

/**
 * Splash screen displayed at app launch.
 * Shows an animated flame icon with the Flare brand name over a gradient background.
 * Auto-navigates after [Constants.SPLASH_DURATION_MS].
 */
@Composable
fun SplashScreen(
    onSplashComplete: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val background = MaterialTheme.colorScheme.background

    // Flame flicker animation
    val infiniteTransition = rememberInfiniteTransition(label = "splashFlame")
    val flameScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flameScale",
    )
    val flameTipOffset by infiniteTransition.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flameTipOffset",
    )

    // Fade-in for text
    val alpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 800, delayMillis = 300),
        label = "textAlpha",
    )

    LaunchedEffect(Unit) {
        delay(Constants.SPLASH_DURATION_MS)
        onSplashComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(primary, primaryContainer),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(
                modifier = Modifier.size(120.dp),
            ) {
                drawFlame(
                    scale = flameScale,
                    tipOffsetX = flameTipOffset,
                    flameColor = onPrimary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.splash_app_name),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                ),
                color = onPrimary.copy(alpha = alpha),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = onPrimary.copy(alpha = alpha * 0.8f),
            )
        }
    }
}

/**
 * Draws a stylized flame shape on the Canvas.
 */
private fun DrawScope.drawFlame(
    scale: Float,
    tipOffsetX: Float,
    flameColor: androidx.compose.ui.graphics.Color,
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f

    val scaleW = w * scale
    val scaleH = h * scale
    val offsetX = (w - scaleW) / 2f
    val offsetY = (h - scaleH) / 2f

    // Outer flame
    val outerPath = Path().apply {
        moveTo(offsetX + scaleW * 0.5f + tipOffsetX * 0.3f, offsetY + scaleH * 0.05f)
        cubicTo(
            offsetX + scaleW * 0.15f, offsetY + scaleH * 0.35f,
            offsetX + scaleW * 0.05f, offsetY + scaleH * 0.6f,
            offsetX + scaleW * 0.2f, offsetY + scaleH * 0.85f,
        )
        cubicTo(
            offsetX + scaleW * 0.3f, offsetY + scaleH * 0.95f,
            offsetX + scaleW * 0.7f, offsetY + scaleH * 0.95f,
            offsetX + scaleW * 0.8f, offsetY + scaleH * 0.85f,
        )
        cubicTo(
            offsetX + scaleW * 0.95f, offsetY + scaleH * 0.6f,
            offsetX + scaleW * 0.85f, offsetY + scaleH * 0.35f,
            offsetX + scaleW * 0.5f + tipOffsetX * 0.3f, offsetY + scaleH * 0.05f,
        )
        close()
    }
    drawPath(outerPath, color = flameColor.copy(alpha = 0.9f), style = Fill)

    // Inner flame (slightly smaller, brighter)
    val innerPath = Path().apply {
        moveTo(offsetX + scaleW * 0.5f + tipOffsetX * 0.2f, offsetY + scaleH * 0.25f)
        cubicTo(
            offsetX + scaleW * 0.3f, offsetY + scaleH * 0.45f,
            offsetX + scaleW * 0.25f, offsetY + scaleH * 0.6f,
            offsetX + scaleW * 0.35f, offsetY + scaleH * 0.8f,
        )
        cubicTo(
            offsetX + scaleW * 0.4f, offsetY + scaleH * 0.88f,
            offsetX + scaleW * 0.6f, offsetY + scaleH * 0.88f,
            offsetX + scaleW * 0.65f, offsetY + scaleH * 0.8f,
        )
        cubicTo(
            offsetX + scaleW * 0.75f, offsetY + scaleH * 0.6f,
            offsetX + scaleW * 0.7f, offsetY + scaleH * 0.45f,
            offsetX + scaleW * 0.5f + tipOffsetX * 0.2f, offsetY + scaleH * 0.25f,
        )
        close()
    }
    drawPath(innerPath, color = flameColor.copy(alpha = 0.5f), style = Fill)
}
