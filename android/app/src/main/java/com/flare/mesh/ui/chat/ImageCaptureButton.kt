package com.flare.mesh.ui.chat

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.flare.mesh.R
import timber.log.Timber
import java.io.File

/**
 * Camera icon button in the message input bar.
 * Uses [ActivityResultContracts.TakePicture] to capture a photo,
 * then calls [onImageCaptured] with the URI of the captured image.
 */
@Composable
fun ImageCaptureButton(
    onImageCaptured: (Uri) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    var hasPermission by remember { mutableStateOf(false) }

    val errorMessage = stringResource(R.string.image_capture_error)

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = photoUri
        if (success && uri != null) {
            onImageCaptured(uri)
        } else if (!success) {
            // User cancelled or capture failed
            Timber.d("Camera capture cancelled or failed")
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) {
            launchCamera(context, cameraLauncher) { uri ->
                photoUri = uri
            }
        } else {
            onError(context.getString(R.string.image_permission_needed))
        }
    }

    IconButton(
        onClick = {
            if (!hasPermission) {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchCamera(context, cameraLauncher) { uri ->
                    photoUri = uri
                }
            }
        },
        modifier = modifier,
    ) {
        Icon(
            Icons.Filled.CameraAlt,
            contentDescription = stringResource(R.string.image_capture),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun launchCamera(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Uri>,
    onUriCreated: (Uri) -> Unit,
) {
    try {
        val photoFile = File.createTempFile(
            "photo_",
            ".jpg",
            context.cacheDir,
        )
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile,
        )
        onUriCreated(uri)
        launcher.launch(uri)
    } catch (e: Exception) {
        Timber.e(e, "Failed to create temp file for camera")
    }
}
