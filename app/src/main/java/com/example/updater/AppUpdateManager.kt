package com.example.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.data.local.UpdateChannelPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long,
    val releasePageUrl: String,
    val isPrerelease: Boolean,
    val isUpdateAvailable: Boolean
)

sealed class UpdateCheckOutcome {
    data class NeedsConfiguration(val message: String) : UpdateCheckOutcome()
    data class UpToDate(
        val installedVersion: String,
        val latestTag: String?,
        val message: String
    ) : UpdateCheckOutcome()

    data class UpdateAvailable(
        val installedVersion: String,
        val release: AppReleaseInfo
    ) : UpdateCheckOutcome()

    data class Error(val message: String) : UpdateCheckOutcome()
}

class AppUpdateManager(private val context: Context) {

    fun getInstalledVersionLabel(): String {
        val pkgInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val vName = pkgInfo?.versionName ?: "1.0"
        val vCode = pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 1L
        return "v$vName ($vCode)"
    }

    private fun getInstalledVersionPair(): Pair<String, Long> {
        val pkgInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val vName = pkgInfo?.versionName ?: "1.0"
        val vCode = pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 1L
        return vName to vCode
    }

    suspend fun checkForUpdate(
        repoInput: String,
        directFeedUrl: String,
        channel: UpdateChannelPref
    ): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        val (installedName, installedCode) = getInstalledVersionPair()
        val installedLabel = "v$installedName ($installedCode)"

        val cleanFeedUrl = directFeedUrl.trim()
        if (cleanFeedUrl.isNotBlank()) {
            return@withContext checkDirectFeedOrApk(cleanFeedUrl, installedName, installedCode, installedLabel)
        }

        val slug = parseGitHubSlug(repoInput)
        if (slug == null) {
            return@withContext UpdateCheckOutcome.NeedsConfiguration(
                "Enter a public GitHub repository (e.g. nilimadas567345-sudo/Zero-play-) or a direct APK / manifest URL below."
            )
        }

        try {
            val apiUrl = if (channel == UpdateChannelPref.STABLE) {
                "https://api.github.com/repos/$slug/releases/latest"
            } else {
                "https://api.github.com/repos/$slug/releases?per_page=10"
            }

            val (code, body) = httpGetString(apiUrl)
            if (code == 404) {
                if (channel == UpdateChannelPref.STABLE) {
                    val (listCode, listBody) = httpGetString("https://api.github.com/repos/$slug/releases?per_page=5")
                    if (listCode == 200) {
                        val arr = JSONArray(listBody)
                        if (arr.length() > 0) {
                            return@withContext UpdateCheckOutcome.UpToDate(
                                installedVersion = installedLabel,
                                latestTag = null,
                                message = "No Stable release published yet in $slug. Switch to Preview channel to see pre-releases."
                            )
                        }
                    }
                }
                return@withContext UpdateCheckOutcome.UpToDate(
                    installedVersion = installedLabel,
                    latestTag = null,
                    message = "Repository $slug is reachable, but no GitHub Releases have been published yet. Push a version from Google AI Studio to trigger the release workflow."
                )
            }
            if (code !in 200..299) {
                return@withContext UpdateCheckOutcome.Error(
                    "GitHub returned HTTP $code for $slug. Verify the repository is public."
                )
            }

            val releaseJson: JSONObject? = if (channel == UpdateChannelPref.STABLE) {
                JSONObject(body)
            } else {
                val arr = JSONArray(body)
                if (arr.length() == 0) null else arr.getJSONObject(0)
            }

            if (releaseJson == null) {
                return@withContext UpdateCheckOutcome.UpToDate(
                    installedVersion = installedLabel,
                    latestTag = null,
                    message = "No releases found in $slug yet."
                )
            }

            val release = parseGitHubRelease(releaseJson, installedName, installedCode)
            if (release.isUpdateAvailable) {
                UpdateCheckOutcome.UpdateAvailable(installedLabel, release)
            } else {
                UpdateCheckOutcome.UpToDate(
                    installedVersion = installedLabel,
                    latestTag = release.tagName,
                    message = "Zero Play $installedLabel is up to date (Latest: ${release.tagName})."
                )
            }
        } catch (t: Throwable) {
            UpdateCheckOutcome.Error(t.message ?: "Unable to check GitHub releases")
        }
    }

    private fun checkDirectFeedOrApk(
        url: String,
        installedName: String,
        installedCode: Long,
        installedLabel: String
    ): UpdateCheckOutcome {
        return try {
            val cleanNoQuery = url.substringBefore('?').lowercase()
            if (cleanNoQuery.endsWith(".apk")) {
                val release = AppReleaseInfo(
                    tagName = "Latest APK",
                    title = "Direct APK Build",
                    releaseNotes = "Direct APK update package ready to download and install.",
                    apkDownloadUrl = url,
                    apkSizeBytes = 0L,
                    releasePageUrl = url,
                    isPrerelease = false,
                    isUpdateAvailable = true
                )
                return UpdateCheckOutcome.UpdateAvailable(installedLabel, release)
            }

            val (code, body) = httpGetString(url)
            if (code !in 200..299) {
                return UpdateCheckOutcome.Error("Update URL returned HTTP $code")
            }
            val json = JSONObject(body)
            val remoteName = json.optString("versionName", json.optString("tag_name", "1.0"))
            val remoteCode = json.optLong("versionCode", 0L)
            val apkUrl = json.optString("apkUrl", json.optString("downloadUrl", "")).takeIf { it.isNotBlank() }
            val notes = json.optString("notes", json.optString("body", "New version available."))
            val pageUrl = json.optString("releaseUrl", url)
            val newer = (remoteCode > installedCode) || isNewerVersion(remoteName, installedName, installedCode)

            val info = AppReleaseInfo(
                tagName = if (remoteName.startsWith("v")) remoteName else "v$remoteName",
                title = "Zero Play $remoteName",
                releaseNotes = notes,
                apkDownloadUrl = apkUrl,
                apkSizeBytes = json.optLong("apkSizeBytes", 0L),
                releasePageUrl = pageUrl,
                isPrerelease = json.optBoolean("prerelease", false),
                isUpdateAvailable = newer
            )
            if (newer) {
                UpdateCheckOutcome.UpdateAvailable(installedLabel, info)
            } else {
                UpdateCheckOutcome.UpToDate(
                    installedVersion = installedLabel,
                    latestTag = info.tagName,
                    message = "Zero Play $installedLabel is up to date."
                )
            }
        } catch (t: Throwable) {
            UpdateCheckOutcome.Error(t.message ?: "Failed to read update URL")
        }
    }

    private fun parseGitHubRelease(
        json: JSONObject,
        installedName: String,
        installedCode: Long
    ): AppReleaseInfo {
        val tagName = json.optString("tag_name", "v1.0")
        val name = json.optString("name", tagName).ifBlank { tagName }
        val body = json.optString("body", "").ifBlank { "New Zero Play release published on GitHub." }
        val prerelease = json.optBoolean("prerelease", false)
        val htmlUrl = json.optString("html_url", "")
        val assets = json.optJSONArray("assets")

        var apkUrl: String? = null
        var apkSize = 0L
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "").lowercase()
                if (assetName.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0L)
                    if (assetName.contains("release") || assetName.contains("zero")) break
                }
            }
        }

        val isNewer = isNewerVersion(tagName, installedName, installedCode)
        return AppReleaseInfo(
            tagName = tagName,
            title = name,
            releaseNotes = body,
            apkDownloadUrl = apkUrl,
            apkSizeBytes = apkSize,
            releasePageUrl = htmlUrl,
            isPrerelease = prerelease,
            isUpdateAvailable = isNewer
        )
    }

    suspend fun downloadApk(
        apkUrl: String,
        versionTag: String,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val updatesDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.cacheDir, "updates")
            updatesDir.mkdirs()
            val safeTag = versionTag.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val destFile = File(updatesDir, "ZeroPlay-$safeTag.apk")
            if (destFile.exists()) destFile.delete()

            var currentUrl = apkUrl
            var redirects = 0
            while (redirects < 6) {
                conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "ZeroPlay-Updater/1.0")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) break
                    currentUrl = loc
                    redirects++
                } else {
                    break
                }
            }

            val activeConn = conn ?: return@withContext Result.failure(IllegalStateException("Connection failed"))
            if (activeConn.responseCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Download failed (HTTP ${activeConn.responseCode})"))
            }

            val totalBytes = activeConn.contentLengthLong.coerceAtLeast(1L)
            var downloadedBytes = 0L
            val buf = ByteArray(16 * 1024)
            activeConn.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        downloadedBytes += read
                        onProgress((downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f))
                    }
                }
            }

            // Validate APK ZIP magic header ('P', 'K')
            val isValidZip = destFile.length() > 1024L && destFile.inputStream().use { s ->
                val header = ByteArray(2)
                s.read(header) == 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
            }
            if (!isValidZip) {
                destFile.delete()
                return@withContext Result.failure(
                    IllegalArgumentException("Downloaded file is not a valid APK package.")
                )
            }

            onProgress(1f)
            Result.success(destFile)
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun installApk(apkFile: File): Result<Unit> {
        return runCatching {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun parseGitHubSlug(raw: String): String? {
        val trimmed = raw.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("github.com/")
            .removeSuffix(".git")
            .trim('/')
        val parts = trimmed.split("/").filter { it.isNotBlank() }
        return if (parts.size >= 2) "${parts[0]}/${parts[1]}" else null
    }

    private fun isNewerVersion(remoteTag: String, installedName: String, installedCode: Long): Boolean {
        val remoteClean = remoteTag.trim().removePrefix("v").removePrefix("V").substringBefore("-")
        val localClean = installedName.trim().removePrefix("v").removePrefix("V").substringBefore("-")

        val rParts = remoteClean.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        val lParts = localClean.split(".").mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
        if (rParts.isEmpty()) return false

        // Support tags formatted as v<versionName>.<versionCode> (one extra segment over versionName)
        if (rParts.size == lParts.size + 1 && rParts.size >= 2) {
            val remoteCodePart = rParts.last().toLong()
            val baseRemote = rParts.dropLast(1)
            val cmp = compareSegments(baseRemote, lParts)
            return if (cmp != 0) cmp > 0 else remoteCodePart > installedCode
        }
        return compareSegments(rParts, lParts) > 0
    }

    private fun compareSegments(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun httpGetString(urlString: String): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "ZeroPlay-Updater/1.0")
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to body
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
