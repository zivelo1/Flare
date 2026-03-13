package com.flare.mesh.ui.chat

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.flare.mesh.R
import com.flare.mesh.util.Constants
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import timber.log.Timber
import java.io.File

/**
 * Hold-to-record voice message button.
 * While pressed, records audio via MediaRecorder and displays an animated waveform.
 * On release, calls [onRecordingComplete] with the path to the recorded .m4a file.
 */
@Composable
fun VoiceRecordButton(
    onRecordingComplete: (filePath: String) -> Unit,
    onRecordingError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartTime by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    var amplitudes by remember { mutableStateOf(List(Constants.VOICE_WAVEFORM_BAR_COUNT) { 0f }) }
    var recorderRef by remember { mutableStateOf<MediaRecorder?>(null) }
    var outputFileRef by remember { mutableStateOf<File?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val errorMessage = stringResource(R.string.voice_record_error)
    val tooShortMessage = stringResource(R.string.voice_record_too_short)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    // Poll amplitude while recording
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isActive) {
                delay(Constants.VOICE_WAVEFORM_POLL_INTERVAL_MS)
                val recorder = recorderRef
                if (recorder != null) {
                    try {
                        val maxAmp = recorder.maxAmplitude
                        val normalized = (maxAmp / 32767f).coerceIn(0f, 1f)
                        amplitudes = amplitudes.drop(1) + normalized
                    } catch (e: Exception) {
                        // Recorder may have been released
                    }
                }
                elapsedSeconds = ((SystemClock.elapsedRealtime() - recordingStartTime) / 1000).toInt()
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Waveform and timer (visible during recording)
        if (isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Red recording indicator
                val infiniteTransition = rememberInfiniteTransition(label = "recordPulse")
                val dotAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 600),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dotAlpha",
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(errorColor.copy(alpha = dotAlpha)),
                )

                Spacer(Modifier.width(8.dp))

                // Elapsed time
                Text(
                    text = formatRecordingTime(elapsedSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = errorColor,
                )

                Spacer(Modifier.width(12.dp))

                // Waveform bars
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                ) {
                    amplitudes.forEach { amplitude ->
                        val barHeight = (4.dp + 28.dp * amplitude)
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(barHeight)
                                .clip(RoundedCornerShape(1.dp))
                                .background(errorColor.copy(alpha = 0.7f)),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = stringResource(R.string.voice_recording),
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurfaceVariantColor,
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        // Mic button
        Surface(
            modifier = Modifier
                .size(48.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            // Check permission first
                            if (!hasPermission) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@detectTapGestures
                            }

                            // Start recording
                            val outputFile = createTempAudioFile(context)
                            outputFileRef = outputFile
                            val recorder = createMediaRecorder(context, outputFile)

                            try {
                                recorder.prepare()
                                recorder.start()
                                recorderRef = recorder
                                isRecording = true
                                recordingStartTime = SystemClock.elapsedRealtime()
                                elapsedSeconds = 0
                                amplitudes = List(Constants.VOICE_WAVEFORM_BAR_COUNT) { 0f }
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to start recording")
                                onRecordingError(errorMessage)
                                cleanupRecorder(recorder, outputFile)
                                recorderRef = null
                                outputFileRef = null
                                return@detectTapGestures
                            }

                            // Wait for release
                            val released = tryAwaitRelease()

                            // Stop recording
                            isRecording = false
                            val duration = SystemClock.elapsedRealtime() - recordingStartTime

                            try {
                                recorder.stop()
                                recorder.release()
                                recorderRef = null
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to stop recording")
                                cleanupRecorder(recorder, outputFile)
                                recorderRef = null
                                outputFileRef = null
                                onRecordingError(errorMessage)
                                return@detectTapGestures
                            }

                            if (released && duration >= Constants.VOICE_MIN_RECORDING_DURATION_MS) {
                                onRecordingComplete(outputFile.absolutePath)
                            } else {
                                outputFile.delete()
                                if (duration < Constants.VOICE_MIN_RECORDING_DURATION_MS) {
                                    onRecordingError(tooShortMessage)
                                }
                            }
                            outputFileRef = null
                        },
                    )
                },
            shape = CircleShape,
            color = if (isRecording) errorColor else primaryColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.voice_record_hold),
                    modifier = Modifier.size(20.dp),
                    tint = surfaceColor,
                )
            }
        }
    }
}

private fun createTempAudioFile(context: Context): File {
    val cacheDir = context.cacheDir
    return File.createTempFile("voice_", ".m4a", cacheDir)
}

@Suppress("DEPRECATION")
private fun createMediaRecorder(context: Context, outputFile: File): MediaRecorder {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }
    recorder.apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioEncodingBitRate(24_000)
        setAudioSamplingRate(16_000)
        setOutputFile(outputFile.absolutePath)
    }
    return recorder
}

private fun cleanupRecorder(recorder: MediaRecorder, outputFile: File) {
    try {
        recorder.reset()
        recorder.release()
    } catch (_: Exception) {
        // Best effort cleanup
    }
    outputFile.delete()
}

private fun formatRecordingTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}
