package com.flare.mesh.viewmodel

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.flare.mesh.util.Constants
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
 * ViewModel managing APK sharing.
 * Extracts APK metadata and provides share actions via system intents.
 */
class ApkShareViewModel(application: Application) : AndroidViewModel(application) {

    data class ApkInfo(
        val versionName: String,
        val versionCode: Long,
        val filePath: String,
        val sizeBytes: Long,
        val sha256Hash: String,
    )

    private val _apkInfo = MutableStateFlow<ApkInfo?>(null)
    val apkInfo: StateFlow<ApkInfo?> = _apkInfo.asStateFlow()

    private val _shareError = MutableStateFlow<String?>(null)
    val shareError: StateFlow<String?> = _shareError.asStateFlow()

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

                val apkPath = context.applicationInfo.sourceDir
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

    /**
     * Copies the installed APK to a shareable cache location and launches
     * the system share sheet. Works with Nearby Share, Bluetooth, WhatsApp,
     * email, and any app that can receive files.
     */
    fun shareApk(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sourceFile = File(context.applicationInfo.sourceDir)
                val shareDir = File(context.cacheDir, Constants.APK_SHARE_SUBDIRECTORY)
                if (!shareDir.exists()) shareDir.mkdirs()

                val cacheFile = File(shareDir, Constants.APK_SHARE_CACHE_FILENAME)
                sourceFile.copyTo(cacheFile, overwrite = true)

                val authority = "${context.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(context, authority, cacheFile)

                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = Constants.APK_MIME_TYPE
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(
                        Intent.EXTRA_TEXT,
                        context.getString(
                            com.flare.mesh.R.string.apk_share_message,
                            Constants.GITHUB_RELEASES_URL,
                        ),
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(shareIntent, context.getString(com.flare.mesh.R.string.apk_share_chooser_title))
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)

                Timber.i("APK share intent launched, size=%d bytes", cacheFile.length())
                _shareError.value = null
            } catch (e: Exception) {
                Timber.e(e, "Failed to share APK")
                _shareError.value = e.message
            }
        }
    }

    /**
     * Shares the GitHub releases download link via text share.
     * For users who prefer sharing a link instead of the APK file directly.
     */
    fun shareDownloadLink(context: android.content.Context) {
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    context.getString(
                        com.flare.mesh.R.string.apk_share_link_message,
                        Constants.GITHUB_RELEASES_URL,
                    ),
                )
            }

            val chooser = Intent.createChooser(shareIntent, context.getString(com.flare.mesh.R.string.apk_share_chooser_title))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)

            Timber.i("Download link share intent launched")
        } catch (e: Exception) {
            Timber.e(e, "Failed to share download link")
            _shareError.value = e.message
        }
    }

    fun clearError() {
        _shareError.value = null
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
