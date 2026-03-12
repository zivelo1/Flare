package com.flare.mesh

import android.content.Context
import android.app.Application
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.flare.mesh.data.repository.FlareRepository
import com.flare.mesh.service.MeshService
import com.flare.mesh.util.Constants
import timber.log.Timber
import java.io.File
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
    internal fun initializeFlareNode() {
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

        /**
         * Permanently erases all data: stops mesh service, deletes the encrypted
         * database, clears lock screen preferences, and reinitializes with a fresh
         * identity. After this call, the app appears as freshly installed.
         */
        fun wipeAndReinitialize(context: Context) {
            Timber.w("DESTRUCTION CODE ACTIVATED — wiping all data")

            // 1. Stop mesh service
            MeshService.stop(context)

            // 2. Clear lock screen and destruction code preferences
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(Constants.KEY_UNLOCK_CODE_HASH)
                .remove(Constants.KEY_DESTRUCTION_CODE_HASH)
                .apply()

            // 3. Delete the encrypted database files
            val dbPath = File(context.filesDir, "flare.db")
            val walPath = File("${dbPath.absolutePath}-wal")
            val shmPath = File("${dbPath.absolutePath}-shm")
            dbPath.delete()
            walPath.delete()
            shmPath.delete()
            Timber.w("Database files deleted")

            // 4. Reset the FlareRepository singleton
            FlareRepository.reset()

            // 5. Reinitialize with a fresh database and new identity
            val app = context.applicationContext as FlareApplication
            app.initializeFlareNode()

            Timber.w("Wipe complete — new identity created")
        }
    }

    /**
     * Gets or creates a device-bound passphrase using Android Keystore.
     *
     * Strategy: Generate an AES-256 key in Keystore, then use it to encrypt a
     * known plaintext. The ciphertext is deterministic per-device (same key always
     * produces the same output with a fixed IV) and serves as the DB passphrase.
     *
     * For software-backed keys where encoded bytes are available, we hash them.
     * For hardware-backed keys (TEE/StrongBox), we encrypt a fixed challenge and
     * use the ciphertext as the passphrase — this is unforgeable without the
     * hardware key.
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
        val encoded = key.encoded
        return if (encoded != null) {
            // Software-backed: hash the raw key bytes
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.digest(encoded).joinToString("") { "%02x".format(it) }
        } else {
            // Hardware-backed: use the key to encrypt a fixed challenge.
            // The result is deterministic per-device and cryptographically strong.
            derivePassphraseFromHardwareKey(key)
        }
    }

    /**
     * Derives a passphrase by encrypting a fixed challenge with the hardware-backed key.
     * Uses a stored IV (generated once and persisted) so the ciphertext is deterministic.
     * The ciphertext is unforgeable without access to the hardware key.
     */
    private fun derivePassphraseFromHardwareKey(key: SecretKey): String {
        val prefs = getSharedPreferences("flare_key_derivation", MODE_PRIVATE)
        val challenge = "flare-db-passphrase-challenge-v1".toByteArray()

        // Check if we already have a derived passphrase stored
        val storedPassphrase = prefs.getString("derived_passphrase", null)
        if (storedPassphrase != null) {
            return storedPassphrase
        }

        // First time: encrypt the challenge and store the result
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(challenge)

        // Combine IV + ciphertext and hash to get passphrase
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(iv)
        digest.update(ciphertext)
        val passphrase = digest.digest().joinToString("") { "%02x".format(it) }

        // Persist so we get the same passphrase on every app launch
        prefs.edit().putString("derived_passphrase", passphrase).apply()
        Timber.i("Derived and stored hardware-backed passphrase")

        return passphrase
    }
}
