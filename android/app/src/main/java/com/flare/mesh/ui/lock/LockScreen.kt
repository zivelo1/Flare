package com.flare.mesh.ui.lock

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.flare.mesh.R
import com.flare.mesh.util.Constants
import timber.log.Timber
import java.security.MessageDigest

/**
 * Lock screen shown on app launch when a destruction code is configured.
 *
 * Offers biometric authentication (fingerprint/face) as the primary unlock,
 * with manual code entry as fallback. Entering the destruction code
 * permanently erases all data.
 */
@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    onDestructionTriggered: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }

    var code by remember { mutableStateOf("") }
    var showCode by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showCodeEntry by remember { mutableStateOf(false) }

    val wrongCodeText = stringResource(R.string.lock_screen_wrong_code)

    // Check if biometric is available
    val biometricAvailable = remember {
        val biometricManager = BiometricManager.from(context)
        biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    // Show biometric prompt on first load
    LaunchedEffect(Unit) {
        if (biometricAvailable) {
            showBiometricPrompt(
                activity = context as FragmentActivity,
                onSuccess = onUnlocked,
                onFallback = { showCodeEntry = true },
            )
        } else {
            showCodeEntry = true
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.lock_screen_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.lock_screen_enter_code),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            if (showCodeEntry || !biometricAvailable) {
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.lock_screen_code_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showCode) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showCode = !showCode }) {
                            Icon(
                                if (showCode) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                    singleLine = true,
                    isError = errorMessage != null,
                )

                AnimatedVisibility(visible = errorMessage != null) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        val enteredHash = sha256(code)
                        val destructionHash = prefs.getString(Constants.KEY_DESTRUCTION_CODE_HASH, null)
                        val unlockHash = prefs.getString(Constants.KEY_UNLOCK_CODE_HASH, null)

                        when (enteredHash) {
                            destructionHash -> {
                                Timber.w("DESTRUCTION CODE ENTERED — wiping all data")
                                onDestructionTriggered()
                            }
                            unlockHash -> {
                                onUnlocked()
                            }
                            else -> {
                                errorMessage = wrongCodeText
                                code = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = code.length >= Constants.MIN_CODE_LENGTH,
                ) {
                    Text(stringResource(R.string.lock_screen_unlock))
                }
            }

            // Biometric retry button
            if (biometricAvailable) {
                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        showBiometricPrompt(
                            activity = context as FragmentActivity,
                            onSuccess = onUnlocked,
                            onFallback = { showCodeEntry = true },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Fingerprint,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock with biometric")
                }
            }
        }
    }
}

/**
 * Shows the Android biometric prompt (fingerprint/face).
 */
private fun showBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onFallback: () -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)

    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            Timber.i("Biometric authentication succeeded")
            onSuccess()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            Timber.d("Biometric error: %d %s", errorCode, errString)
            onFallback()
        }

        override fun onAuthenticationFailed() {
            Timber.d("Biometric authentication failed (wrong fingerprint)")
            // Don't fall back — let the user retry
        }
    }

    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Flare")
        .setSubtitle("Use your fingerprint or face to unlock")
        .setNegativeButtonText("Enter code")
        .build()

    try {
        BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
    } catch (e: Exception) {
        Timber.e(e, "Failed to show biometric prompt")
        onFallback()
    }
}

/**
 * Computes SHA-256 hash of a string, returned as hex.
 */
fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/**
 * Checks if a destruction code is configured (lock screen should be shown).
 */
fun isLockScreenEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(Constants.KEY_DESTRUCTION_CODE_HASH, null) != null
}
