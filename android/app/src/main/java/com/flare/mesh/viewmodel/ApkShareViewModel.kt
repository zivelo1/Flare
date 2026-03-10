package com.flare.mesh.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * ViewModel managing APK sharing state.
 * Computes APK metadata (path, size, SHA-256 hash) and tracks transfer progress.
 */
class ApkShareViewModel(application: Application) : AndroidViewModel(application) {

    data class ApkInfo(
        val versionName: String,
        val versionCode: Long,
        val filePath: String,
        val sizeBytes: Long,
        val sha256Hash: String,
    )

    data class TransferRequest(
        val deviceId: String,
        val deviceName: String?,
        val progress: Float,
        val isComplete: Boolean,
    )

    data class ApkOffer(
        val deviceId: String,
        val deviceName: String?,
        val versionName: String,
        val sizeBytes: Long,
        val isVerified: Boolean,
        val downloadProgress: Float,
        val isDownloaded: Boolean,
    )

    private val _apkInfo = MutableStateFlow<ApkInfo?>(null)
    val apkInfo: StateFlow<ApkInfo?> = _apkInfo.asStateFlow()

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private val _transferRequests = MutableStateFlow<List<TransferRequest>>(emptyList())
    val transferRequests: StateFlow<List<TransferRequest>> = _transferRequests.asStateFlow()

    private val _shareProgress = MutableStateFlow(0f)
    val shareProgress: StateFlow<Float> = _shareProgress.asStateFlow()

    private val _availableOffers = MutableStateFlow<List<ApkOffer>>(emptyList())
    val availableOffers: StateFlow<List<ApkOffer>> = _availableOffers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    init {
        loadApkInfo()
    }

    private fun loadApkInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }

                val appInfo = context.applicationInfo
                val apkPath = appInfo.sourceDir
                val apkFile = File(apkPath)
                val sizeBytes = apkFile.length()
                val sha256 = computeSha256(apkFile)

                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }

                _apkInfo.value = ApkInfo(
                    versionName = packageInfo.versionName ?: "unknown",
                    versionCode = versionCode,
                    filePath = apkPath,
                    sizeBytes = sizeBytes,
                    sha256Hash = sha256,
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load APK info")
            }
        }
    }

    fun startSharing() {
        _isSharing.value = true
        _transferRequests.value = emptyList()
        _shareProgress.value = 0f
        Timber.i("Started APK sharing via Bluetooth advertising")
        // Actual BLE advertising would be triggered through MeshService
    }

    fun stopSharing() {
        _isSharing.value = false
        _shareProgress.value = 0f
        Timber.i("Stopped APK sharing")
    }

    fun startScanning() {
        _isScanning.value = true
        _availableOffers.value = emptyList()
        Timber.i("Started scanning for APK offers")
    }

    fun stopScanning() {
        _isScanning.value = false
        Timber.i("Stopped scanning for APK offers")
    }

    fun requestDownload(offer: ApkOffer) {
        _availableOffers.value = _availableOffers.value.map { existing ->
            if (existing.deviceId == offer.deviceId) {
                existing.copy(downloadProgress = 0.01f)
            } else existing
        }
        Timber.i("Requested APK download from %s", offer.deviceId.take(12))
    }

    /**
     * Called by the BLE layer when a new device requests the APK.
     */
    fun onTransferRequested(deviceId: String, deviceName: String?) {
        val request = TransferRequest(
            deviceId = deviceId,
            deviceName = deviceName,
            progress = 0f,
            isComplete = false,
        )
        _transferRequests.value = _transferRequests.value + request
    }

    /**
     * Called by the BLE layer when transfer progress updates.
     */
    fun onTransferProgress(deviceId: String, progress: Float) {
        _transferRequests.value = _transferRequests.value.map { req ->
            if (req.deviceId == deviceId) {
                req.copy(
                    progress = progress,
                    isComplete = progress >= 1f,
                )
            } else req
        }
        // Update overall progress as average
        val requests = _transferRequests.value
        if (requests.isNotEmpty()) {
            _shareProgress.value = requests.map { it.progress }.average().toFloat()
        }
    }

    /**
     * Called by the BLE layer when an APK offer is discovered.
     */
    fun onOfferDiscovered(
        deviceId: String,
        deviceName: String?,
        versionName: String,
        sizeBytes: Long,
        isVerified: Boolean,
    ) {
        val existing = _availableOffers.value.find { it.deviceId == deviceId }
        if (existing == null) {
            _availableOffers.value = _availableOffers.value + ApkOffer(
                deviceId = deviceId,
                deviceName = deviceName,
                versionName = versionName,
                sizeBytes = sizeBytes,
                isVerified = isVerified,
                downloadProgress = 0f,
                isDownloaded = false,
            )
        }
    }

    /**
     * Called by the BLE layer when download progress updates.
     */
    fun onDownloadProgress(deviceId: String, progress: Float) {
        _availableOffers.value = _availableOffers.value.map { offer ->
            if (offer.deviceId == deviceId) {
                offer.copy(
                    downloadProgress = progress,
                    isDownloaded = progress >= 1f,
                )
            } else offer
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { fis ->
            var bytesRead = fis.read(buffer)
            while (bytesRead != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesRead = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun formatFileSize(bytes: Long): String = when {
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
