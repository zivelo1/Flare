package com.flare.mesh.ui.contacts

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.flare.mesh.R
import com.flare.mesh.util.Constants
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * QR code scanner screen using CameraX and ML Kit barcode scanning.
 *
 * Scans for Flare identity QR codes in the format:
 * deviceId|signingKey(hex)|agreementKey(hex)|displayName
 *
 * Validates the format before returning the result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerScreen(
    onQrScanned: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var scanError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qr_scanner_title)) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (hasCameraPermission) {
                CameraPreviewWithScanner(
                    onQrScanned = { rawValue ->
                        if (isValidFlareQr(rawValue)) {
                            onQrScanned(rawValue)
                        } else {
                            scanError = context.getString(R.string.qr_invalid_code)
                        }
                    },
                )

                // Instruction overlay at the bottom
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    scanError?.let { error ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Text(
                                text = error,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.qr_scanner_instruction),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                // No camera permission
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.qr_camera_permission_needed),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.action_grant_permission))
                    }
                }
            }
        }
    }
}

/**
 * Camera preview composable with ML Kit barcode analysis.
 * Uses CameraX for lifecycle-aware camera management.
 */
@Composable
private fun CameraPreviewWithScanner(
    onQrScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Prevent duplicate scans
    var hasScanned by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val barcodeOptions = BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                    .build()
                val barcodeScanner = BarcodeScanning.getClient(barcodeOptions)

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            @androidx.camera.core.ExperimentalGetImage
                            val mediaImage = imageProxy.image
                            if (mediaImage != null && !hasScanned) {
                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees,
                                )
                                barcodeScanner.process(inputImage)
                                    .addOnSuccessListener { barcodes ->
                                        barcodes.firstOrNull()?.rawValue?.let { rawValue ->
                                            if (!hasScanned) {
                                                hasScanned = true
                                                Timber.i("QR code scanned: %s", rawValue.take(20))
                                                onQrScanned(rawValue)
                                            }
                                        }
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            } else {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Failed to bind camera use cases")
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Validates that a scanned QR string matches the Flare identity format.
 * Expected: deviceId|signingKey(hex)|agreementKey(hex)|optionalDisplayName
 *
 * Validation rules:
 * - At least 3 pipe-separated parts
 * - Device ID is non-empty
 * - Signing key is 64 hex chars (32 bytes Ed25519)
 * - Agreement key is 64 hex chars (32 bytes X25519)
 */
private fun isValidFlareQr(data: String): Boolean {
    val parts = data.split(Constants.QR_DATA_SEPARATOR)
    if (parts.size < Constants.QR_MIN_FIELDS) return false

    val deviceId = parts[0]
    val signingKeyHex = parts[1]
    val agreementKeyHex = parts[2]

    if (deviceId.isBlank()) return false
    if (!isValidHexKey(signingKeyHex)) return false
    if (!isValidHexKey(agreementKeyHex)) return false

    return true
}

private fun isValidHexKey(hex: String): Boolean {
    return hex.length == Constants.HEX_PUBLIC_KEY_LENGTH &&
        hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
