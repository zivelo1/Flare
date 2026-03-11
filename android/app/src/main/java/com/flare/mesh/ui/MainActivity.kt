package com.flare.mesh.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.flare.mesh.FlareApplication
import com.flare.mesh.R
import com.flare.mesh.service.MeshService
import com.flare.mesh.ui.navigation.FlareNavHost
import com.flare.mesh.ui.theme.FlareTheme
import com.flare.mesh.util.Constants
import com.flare.mesh.viewmodel.ContactsViewModel
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val contactsViewModel by lazy { ContactsViewModel() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Timber.i("All permissions granted, starting mesh service")
            MeshService.start(this)
        } else {
            Timber.w("Some permissions denied: %s",
                permissions.filter { !it.value }.keys.joinToString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FlareTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val error = FlareApplication.initError
                    if (error != null) {
                        InitErrorScreen(error)
                    } else {
                        FlareNavHost(
                            onRequestPermissions = { requestBluetoothPermissions() },
                        )
                    }
                }
            }
        }

        // Handle deep link if the activity was launched with one
        handleDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * Processes an incoming deep link intent (flare://add?id=...&sk=...&ak=...).
     * Parses the URI, adds the contact, and shows a toast confirmation.
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != Constants.DEEP_LINK_SCHEME || uri.host != Constants.DEEP_LINK_HOST_ADD) return

        Timber.i("Received deep link: %s", uri)

        // Defer processing until FlareNode is initialized
        if (FlareApplication.initError != null) {
            Timber.w("Cannot process deep link — FlareNode not initialized")
            return
        }

        val parsed = contactsViewModel.parseDeepLink(uri)
        if (parsed != null) {
            contactsViewModel.addContactFromLink(parsed)
            Toast.makeText(this, R.string.deep_link_contact_added, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.deep_link_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        requestBluetoothPermissions()
    }

    @Composable
    private fun InitErrorScreen(error: String) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = "Flare — Init Error",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Take a screenshot and report this:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        // Only request permissions that haven't been granted yet
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            // All permissions already granted — start mesh service directly
            Timber.i("All permissions already granted, starting mesh service")
            MeshService.start(this)
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
