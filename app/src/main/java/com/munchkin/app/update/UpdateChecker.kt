package com.munchkin.app.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL

/**
 * Handles checking for updates from GitHub releases and installing APKs.
 */
class UpdateChecker(private val context: Context) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API_URL = "https://api.github.com/repos/adm-adi/munchkin/releases/latest"
    }
    
    private val json = Json { 
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    
    /**
     * Check for available updates.
     * @return UpdateInfo if update available, null otherwise
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            // Get actual version from package info (NOT hardcoded)
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersion = packageInfo.versionName ?: "0.0.0"
            
            Log.d(TAG, "Checking for updates... Current installed: $currentVersion")
            
            // Fetch latest release from GitHub
            val response = URL(GITHUB_API_URL).readText()
            val release = json.decodeFromString<GitHubRelease>(response)
            val latestVersion = release.tagName.removePrefix("v")
            
            Log.d(TAG, "Current: $currentVersion, Latest: $latestVersion")
            
            if (isNewerVersion(latestVersion, currentVersion)) {
                // Find APK asset
                val apkAsset = release.assets.find { it.name.endsWith(".apk") }
                
                if (apkAsset != null) {
                    UpdateResult.UpdateAvailable(
                        UpdateInfo(
                            version = latestVersion,
                            releaseNotes = release.body ?: "Nueva versión disponible",
                            downloadUrl = apkAsset.browserDownloadUrl,
                            fileSize = apkAsset.size
                        )
                    )
                } else {
                    UpdateResult.NoUpdate
                }
            } else {
                UpdateResult.NoUpdate
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            UpdateResult.Error(e.message ?: "Error desconocido")
        }
    }
    
    /**
     * Download and install APK update.
     */
    fun downloadAndInstall(updateInfo: UpdateInfo, onProgress: (Int) -> Unit, onComplete: () -> Unit) {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "munchkin-${updateInfo.version}.apk"
        )
        
        // Delete old file if exists
        if (apkFile.exists()) {
            apkFile.delete()
        }
        
        val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl))
            .setTitle("Munchkin v${updateInfo.version}")
            .setDescription("Descargando actualización...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(apkFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        
        val downloadId = downloadManager.enqueue(request)
        
        // Register receiver for download complete
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    context.unregisterReceiver(this)
                    onComplete()
                    installApk(apkFile)
                }
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }
    
    private fun installApk(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
        }
    }
    
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        
        for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}

// ============== Data Classes ==============

data class UpdateInfo(
    val version: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileSize: Long
)

sealed class UpdateResult {
    data class UpdateAvailable(val info: UpdateInfo) : UpdateResult()
    data object NoUpdate : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0
)
