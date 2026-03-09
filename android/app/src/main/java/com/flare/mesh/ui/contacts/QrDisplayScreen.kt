package com.flare.mesh.ui.contacts

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.flare.mesh.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Displays the user's Flare identity as a QR code for contact exchange.
 * The other person scans this code to add this device as a contact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrDisplayScreen(
    qrData: String,
    safetyNumber: String,
    onNavigateBack: () -> Unit,
) {
    val foregroundColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()

    val qrBitmap = remember(qrData, foregroundColor, backgroundColor) {
        generateQrBitmap(qrData, foregroundColor, backgroundColor)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_my_code_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.qr_my_code_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(24.dp))

            // QR code image
            Card(
                modifier = Modifier.size(280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.qr_my_code_title),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Safety number for verbal verification
            Text(
                text = stringResource(R.string.qr_safety_number_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatSafetyNumber(safetyNumber),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Formats a safety number string into groups of 5 for readability.
 * E.g., "1234567890" → "12345 67890"
 */
private fun formatSafetyNumber(number: String): String {
    return number.chunked(5).joinToString("  ")
}

/**
 * Generates a QR code bitmap from the given data string.
 * Uses ZXing QRCodeWriter for deterministic encoding.
 */
private fun generateQrBitmap(
    data: String,
    foregroundColor: Int,
    backgroundColor: Int,
    sizePx: Int = 512,
): Bitmap? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val bitMatrix = QRCodeWriter().encode(
            data,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints,
        )
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(
                    x, y,
                    if (bitMatrix[x, y]) foregroundColor else backgroundColor,
                )
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
