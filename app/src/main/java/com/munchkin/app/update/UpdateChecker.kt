package com.munchkin.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.munchkin.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * Handles checking for updates from GitHub releases, securely downloading APKs over HTTPS,
 * verifying optional SHA-256 digests and APK signing certificates/version codes, and launching install.
 */
class UpdateChecker(private val context: Context) {
    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API_URL = "https://api.github.com/repos/adm-adi/munchkin/releases/latest"
        private const val MAX_REDIRECTS = 5

        private val ALLOWED_DOWNLOAD_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com"
        )

        private val SHA256_HEX_REGEX = Regex("^[a-f0-9]{64}$")

        /**
         * Returns true only when [url] uses `https` and targets an allowlisted GitHub release host.
         */
        internal fun isAllowedDownloadUrl(url: String): Boolean {
            return try {
                val uri = URI(url.trim())
                if (!uri.scheme.equals("https", ignoreCase = true)) return false
                val host = uri.host?.lowercase() ?: return false
                host in ALLOWED_DOWNLOAD_HOSTS
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Parses a `.sha256` checksum asset (either raw 64-char hex or `<hex>  <filename>`).
         */
        internal fun parseSha256Text(text: String): String? {
            val token = text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return null
            return token.takeIf { SHA256_HEX_REGEX.matches(it) }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    internal fun isAllowedDownloadUrl(url: String): Boolean = Companion.isAllowedDownloadUrl(url)

    internal fun parseSha256Text(text: String): String? = Companion.parseSha256Text(text)

    /**
     * Check for available updates from GitHub Releases.
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val packageInfo = getInstalledPackageInfo(includeSignatures = false)
            val currentVersion = packageInfo.versionName ?: "0.0.0"

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Checking for updates... Current installed: $currentVersion")
            }

            conn = openAllowedConnection(GITHUB_API_URL)
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val release = json.decodeFromString<GitHubRelease>(response)
            val latestVersion = release.tagName.removePrefix("v")

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Current: $currentVersion, Latest: $latestVersion")
            }

            if (isNewerVersion(latestVersion, currentVersion)) {
                val apkAsset = release.assets.find { it.name.endsWith(".apk", ignoreCase = true) }
                if (apkAsset != null) {
                    val sha256Asset = release.assets.find {
                        it.name.equals("${apkAsset.name}.sha256", ignoreCase = true)
                    } ?: release.assets.find {
                        it.name.endsWith(".sha256", ignoreCase = true)
                    }

                    UpdateResult.UpdateAvailable(
                        UpdateInfo(
                            version = latestVersion,
                            releaseNotes = release.body ?: "Nueva versión disponible",
                            downloadUrl = apkAsset.browserDownloadUrl,
                            fileSize = apkAsset.size,
                            sha256Url = sha256Asset?.browserDownloadUrl
                        )
                    )
                } else {
                    UpdateResult.NoUpdate
                }
            } else {
                UpdateResult.NoUpdate
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            UpdateResult.Error(e.message ?: "Error desconocido")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Securely downloads, verifies (SHA-256 if published, package name, anti-rollback version code,
     * and signing certificate match), and starts the package installer for [updateInfo].
     */
    suspend fun downloadAndInstall(updateInfo: UpdateInfo): Result<Unit> = withContext(Dispatchers.IO) {
        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        val safeVersion = updateInfo.version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val apkFile = File(updatesDir, "munchkin-$safeVersion.apk")
        val tempFile = File(updatesDir, "munchkin-$safeVersion.apk.tmp")

        var downloadConn: HttpURLConnection? = null
        var checksumConn: HttpURLConnection? = null

        try {
            if (tempFile.exists()) tempFile.delete()
            if (apkFile.exists()) apkFile.delete()

            downloadConn = openAllowedConnection(updateInfo.downloadUrl)
            val digest = MessageDigest.getInstance("SHA-256")
            downloadConn.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }

            if (!tempFile.renameTo(apkFile)) {
                tempFile.copyTo(apkFile, overwrite = true)
                tempFile.delete()
            }

            val sha256Url = updateInfo.sha256Url
            if (sha256Url != null) {
                checksumConn = openAllowedConnection(sha256Url)
                val checksumBody = checksumConn.inputStream.bufferedReader().use { it.readText() }
                val expectedSha256 = parseSha256Text(checksumBody)
                    ?: throw SecurityException("Invalid SHA-256 checksum file format")
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw SecurityException("Downloaded APK SHA-256 checksum mismatch")
                }
            }

            verifyDownloadedApk(apkFile)

            withContext(Dispatchers.Main) {
                installApk(apkFile)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            tempFile.delete()
            apkFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Update download/verification failed", e)
            tempFile.delete()
            apkFile.delete()
            Result.failure(e)
        } finally {
            downloadConn?.disconnect()
            checksumConn?.disconnect()
        }
    }

    private fun openAllowedConnection(startUrl: String): HttpURLConnection {
        var currentUrl = startUrl
        var redirects = 0
        while (true) {
            if (!isAllowedDownloadUrl(currentUrl)) {
                throw SecurityException("Disallowed update URL: $currentUrl")
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 30_000
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect without Location header")
                }
                if (redirects >= MAX_REDIRECTS) {
                    throw IOException("Too many redirects")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("Unexpected HTTP status $code")
            }
            return conn
        }
    }

    private fun verifyDownloadedApk(apkFile: File) {
        val archiveInfo = getArchivePackageInfo(apkFile)
            ?: throw SecurityException("Downloaded file is not a valid APK archive")

        if (archiveInfo.packageName != context.packageName) {
            throw SecurityException("APK package mismatch: ${archiveInfo.packageName}")
        }

        val installedInfo = getInstalledPackageInfo(includeSignatures = true)
        val installedVersionCode = PackageInfoCompat.getLongVersionCode(installedInfo)
        val archiveVersionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
        if (archiveVersionCode <= installedVersionCode) {
            throw SecurityException(
                "APK version code ($archiveVersionCode) is not newer than installed ($installedVersionCode)"
            )
        }

        val installedCerts = extractCertDigests(installedInfo)
        val archiveCerts = extractCertDigests(archiveInfo)
        if (installedCerts.isEmpty() || archiveCerts.isEmpty() || installedCerts.intersect(archiveCerts).isEmpty()) {
            throw SecurityException("APK signing certificate does not match installed app")
        }
    }

    private fun getInstalledPackageInfo(includeSignatures: Boolean): PackageInfo {
        val pm = context.packageManager
        return if (!includeSignatures) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageInfo(context.packageName, 0)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
    }

    private fun getArchivePackageInfo(apkFile: File): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNATURES)
        }
    }

    private fun extractCertDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            signingInfo?.apkContentsSigners ?: signingInfo?.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        if (signatures.isNullOrEmpty()) return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            md.reset()
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun installApk(apkFile: File) {
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
    }

    /**
     * Compare semantic versions. Returns true if latest > current.
     * Handles versions like "2.17.3", "v2.17.3", "2.17.3-beta", etc.
     */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        val cleanLatest = cleanVersion(latest)
        val cleanCurrent = cleanVersion(current)

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Version comparison: cleanLatest='$cleanLatest', cleanCurrent='$cleanCurrent'")
        }

        if (cleanLatest == cleanCurrent) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Versions are equal, no update needed")
            return false
        }

        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }

        if (latestParts.isEmpty() || currentParts.isEmpty()) {
            Log.w(TAG, "Failed to parse versions, comparing as strings")
            return cleanLatest > cleanCurrent
        }

        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (BuildConfig.DEBUG) Log.d(TAG, "Comparing part $i: latest=$l, current=$c")
            if (l > c) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Latest is newer at part $i")
                return true
            }
            if (l < c) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Current is newer at part $i, no update needed")
                return false
            }
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "All parts equal, no update needed")
        return false
    }

    private fun cleanVersion(version: String): String {
        return version
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .split("-")[0]
            .trim()
    }
}

// ============== Data Classes ==============

data class UpdateInfo(
    val version: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileSize: Long,
    val sha256Url: String? = null
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
