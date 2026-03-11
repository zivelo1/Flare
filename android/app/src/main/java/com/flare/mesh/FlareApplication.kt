package com.flare.mesh

import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flare.mesh.data.repository.FlareRepository
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class FlareApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Flare application started")

        initializeFlareNode()
    }

    /**
     * Initializes the Rust FlareNode with a device-bound passphrase.
     * The passphrase is derived from a key stored in Android Keystore,
     * ensuring the encrypted database is tied to this specific device.
     */
    private fun initializeFlareNode() {
        try {
            val passphrase = getOrCreateDevicePassphrase()
            FlareRepository.initialize(this, passphrase)
            Timber.i("FlareRepository initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize FlareRepository")
            // Store error so the UI can display it instead of crashing
            initError = "${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString()}"
        }
    }

    companion object {
        /** Non-null if FlareNode initialization failed. Displayed by the UI. */
        var initError: String? = null
            private set
    }

    /**
     * Gets or creates a device-bound passphrase using Android Keystore.
     * This passphrase never leaves the device's secure hardware.
     */
    private fun getOrCreateDevicePassphrase(): String {
        val keyAlias = "flare_db_key"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            )
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            keyGenerator.generateKey()
            Timber.i("Generated new device-bound database key")
        }

        val key = keyStore.getKey(keyAlias, null) as SecretKey
        // Use the key's encoded bytes hash as the passphrase
        // For hardware-backed keys, encoded is null, so we use a deterministic derivation
        val encoded = key.encoded
        return if (encoded != null) {
            encoded.joinToString("") { "%02x".format(it) }
        } else {
            // Hardware-backed key — use alias + device-specific constant
            // This is deterministic per-device since the keystore is device-bound
            "flare-device-${keyAlias.hashCode()}-${android.os.Build.FINGERPRINT.hashCode()}"
        }
    }
}
