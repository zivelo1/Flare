package com.flare.mesh.ui.network

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flare.mesh.R
import com.flare.mesh.data.model.MeshPeer
import com.flare.mesh.util.Constants
import com.flare.mesh.util.IdenticonGenerator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Canvas-based animated mesh topology visualization.
 * Shows the device as a central node with connected peers arranged in a circle.
 * Connection lines pulse with animated opacity and thickness reflects RSSI.
 */
@Composable
fun MeshVisualizationView(
    peers: List<MeshPeer>,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    val displayPeers = peers.take(Constants.MESH_VIS_MAX_NODES)

    // Pulse animation for connection lines
    val infiniteTransition = rememberInfiniteTransition(label = "meshPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = Constants.MESH_VIS_PULSE_DURATION_MS,
                easing = EaseInOutSine,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val density = LocalDensity.current
    val minLineWidthPx = with(density) { Constants.MESH_VIS_MIN_LINE_WIDTH_DP.dp.toPx() }
    val maxLineWidthPx = with(density) { Constants.MESH_VIS_MAX_LINE_WIDTH_DP.dp.toPx() }

    val textMeasurer = rememberTextMeasurer()
    val youLabel = stringResource(R.string.mesh_visualization_you)

    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
    )

    // Compute peer colors outside the draw scope
    val peerColors = remember(displayPeers) {
        displayPeers.map { peer ->
            IdenticonGenerator.getColors(peer.deviceId).first
        }
    }

    if (displayPeers.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.network_scanning),
                style = MaterialTheme.typography.bodyMedium,
                color = onSurfaceVariantColor,
            )
        }
    } else {
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(240.dp),
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val radius = minOf(size.width, size.height) * 0.35f
            val centerNodeRadius = 24.dp.toPx()
            val peerNodeRadius = 18.dp.toPx()

            // Calculate peer positions around a circle
            val peerPositions = displayPeers.mapIndexed { index, _ ->
                val angle = (2.0 * PI * index / displayPeers.size) - PI / 2.0
                Offset(
                    x = centerX + (radius * cos(angle)).toFloat(),
                    y = centerY + (radius * sin(angle)).toFloat(),
                )
            }

            // Draw connection lines
            displayPeers.forEachIndexed { index, peer ->
                val peerPos = peerPositions[index]
                val rssi = peer.rssi ?: -90
                val signalStrength = ((rssi + 100).coerceIn(0, 70)) / 70f
                val lineWidth = minLineWidthPx + (maxLineWidthPx - minLineWidthPx) * signalStrength

                val lineAlpha = if (peer.isConnected) {
                    pulseAlpha * signalStrength.coerceAtLeast(0.4f)
                } else {
                    0.2f
                }

                val lineColor = if (peer.isConnected) primaryColor else onSurfaceVariantColor

                drawLine(
                    color = lineColor.copy(alpha = lineAlpha),
                    start = Offset(centerX, centerY),
                    end = peerPos,
                    strokeWidth = lineWidth,
                )
            }

            // Draw peer nodes
            displayPeers.forEachIndexed { index, peer ->
                val pos = peerPositions[index]
                val peerColor = peerColors[index]
                val nodeAlpha = if (peer.isConnected) 1f else 0.5f

                // Outer ring
                drawCircle(
                    color = peerColor.copy(alpha = nodeAlpha * 0.3f),
                    radius = peerNodeRadius + 4.dp.toPx(),
                    center = pos,
                )
                // Inner circle
                drawCircle(
                    color = peerColor.copy(alpha = nodeAlpha),
                    radius = peerNodeRadius,
                    center = pos,
                )

                // Peer label
                val peerLabel = peer.displayName?.take(6)
                    ?: peer.deviceId.take(4)
                val peerTextLayout = textMeasurer.measure(
                    text = AnnotatedString(peerLabel),
                    style = labelStyle.copy(color = onSurfaceColor),
                )
                drawText(
                    textLayoutResult = peerTextLayout,
                    topLeft = Offset(
                        x = pos.x - peerTextLayout.size.width / 2f,
                        y = pos.y + peerNodeRadius + 6.dp.toPx(),
                    ),
                )
            }

            // Draw center node ("You")
            drawCircle(
                color = primaryColor.copy(alpha = 0.2f),
                radius = centerNodeRadius + 6.dp.toPx(),
                center = Offset(centerX, centerY),
            )
            drawCircle(
                color = primaryColor,
                radius = centerNodeRadius,
                center = Offset(centerX, centerY),
            )

            // "You" label inside center node
            val youTextLayout = textMeasurer.measure(
                text = AnnotatedString(youLabel),
                style = labelStyle.copy(
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
            )
            drawText(
                textLayoutResult = youTextLayout,
                topLeft = Offset(
                    x = centerX - youTextLayout.size.width / 2f,
                    y = centerY - youTextLayout.size.height / 2f,
                ),
            )
        }
    }
}
