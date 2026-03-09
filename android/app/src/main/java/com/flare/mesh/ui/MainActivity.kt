package com.flare.mesh.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.flare.mesh.service.MeshService
import com.flare.mesh.ui.navigation.FlareNavHost
import com.flare.mesh.ui.theme.FlareTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {

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
                    FlareNavHost(
                        onRequestPermissions = { requestBluetoothPermissions() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestBluetoothPermissions()
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

        permissionLauncher.launch(permissions.toTypedArray())
    }
}
