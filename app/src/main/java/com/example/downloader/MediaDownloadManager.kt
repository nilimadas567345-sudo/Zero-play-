package com.example.downloader

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import com.example.data.local.SealFilenameTemplatePref
import com.example.data.model.DownloadFormatPreset
import com.example.data.model.DownloadTaskUi
import com.example.data.model.JobStatus
import com.example.data.model.MediaType
import com.example.data.repository.EchoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MediaDownloadManager(
    private val context: Context,
    private val repository: EchoRepository
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    @Volatile
    private var autoImportEnabled: Boolean = true

    private val _downloads = MutableStateFlow<List<DownloadTaskUi>>(emptyList())
    val downloads: StateFlow<List<DownloadTaskUi>> = _downloads.asStateFlow()

    fun enqueueDownload(
        rawUrl: String,
        customTitle: String?,
        preset: DownloadFormatPreset,
        wifiOnly: Boolean = false,
        restrictFilenames: Boolean = true,
        splitSubfolders: Boolean = true,
        filenameTemplate: SealFilenameTemplatePref = SealFilenameTemplatePref.TITLE_ARTIST,
        autoImportToLibrary: Boolean = true,
        onError: ((String) -> Unit)? = null
    ): Result<DownloadTaskUi> {
        autoImportEnabled = autoImportToLibrary
        val trimmed = rawUrl.trim()
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid URL"))
        if (uri.scheme != "http" && uri.scheme != "https") {
            return Result.failure(IllegalArgumentException("Enter a valid http:// or https:// media URL"))
        }

        val rawBase = customTitle?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "ZeroPlay_${System.currentTimeMillis() / 1000}"

        val templatedName = when (filenameTemplate) {
            SealFilenameTemplatePref.TITLE_ONLY -> rawBase
            SealFilenameTemplatePref.TITLE_ARTIST -> "$rawBase - ZeroPlay"
            SealFilenameTemplatePref.ARTIST_TITLE -> "ZeroPlay - $rawBase"
            SealFilenameTemplatePref.TITLE_TIMESTAMP -> "${rawBase}_${System.currentTimeMillis() / 1000}"
        }

        val sanitizedBase = if (restrictFilenames) {
            templatedName.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").take(56)
        } else {
            templatedName.replace("/", "_").take(56)
        }
        val taskId = UUID.randomUUID().toString()
        val pendingTask = DownloadTaskUi(
            id = taskId,
            downloadManagerId = -1L,
            url = trimmed,
            title = sanitizedBase,
            preset = preset,
            progress = 0.02f,
            status = JobStatus.RUNNING
        )
        _downloads.update { listOf(pendingTask) + it }

        scope.launch {
            val resolved = resolveDirectMediaUrl(trimmed)
            if (resolved.isFailure) {
                val msg = resolved.exceptionOrNull()?.message
                    ?: "URL is a webpage, not a direct media stream"
                markTaskFailed(taskId, msg)
                onError?.invoke(msg)
                return@launch
            }
            val directUrl = resolved.getOrThrow()
            val directUri = Uri.parse(directUrl)
            val fileName = "${sanitizedBase}.${preset.extension}"
            val subfolder = if (splitSubfolders) {
                if (preset.isVideo) "ZeroPlay/Video" else "ZeroPlay/Audio"
            } else {
                "ZeroPlay"
            }

            runCatching {
                val request = DownloadManager.Request(directUri)
                    .setTitle(sanitizedBase)
                    .setDescription("Downloading ${preset.label}")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(!wifiOnly)
                    .setAllowedOverRoaming(!wifiOnly)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "$subfolder/$fileName")

                val dmId = downloadManager.enqueue(request)
                _downloads.update { list ->
                    list.map {
                        if (it.id == taskId) it.copy(downloadManagerId = dmId, url = directUrl, progress = 0.08f)
                        else it
                    }
                }
                startPolling()
            }.onFailure { err ->
                val msg = err.message ?: "Download failed to queue"
                markTaskFailed(taskId, msg)
                onError?.invoke(msg)
            }
        }

        return Result.success(pendingTask)
    }

    /**
     * Prevents saving HTML webpages (e.g. YouTube watch pages) as corrupt .mp4/.m4a files.
     * Attempts to extract direct og:video / <video src> / mp4 URLs from HTML when present;
     * otherwise fails cleanly with a clear error message.
     */
    private fun resolveDirectMediaUrl(urlString: String): Result<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"
                )
            }
            conn.connect()
            val finalUrl = conn.url?.toString() ?: urlString
            val contentType = (conn.contentType ?: "").lowercase()
            if (contentType.startsWith("video/") ||
                contentType.startsWith("audio/") ||
                contentType.contains("octet-stream") ||
                contentType.contains("mp4") ||
                contentType.contains("webm")
            ) {
                return Result.success(finalUrl)
            }

            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                val html = conn.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(128 * 1024)
                    val read = reader.read(buf)
                    if (read > 0) String(buf, 0, read) else ""
                }
                val extracted = extractDirectMediaFromHtml(html)
                return if (extracted != null) {
                    Result.success(extracted)
                } else {
                    Result.failure(
                        IllegalArgumentException(
                            "Webpage/YouTube link returned HTML instead of a direct video/audio file. Paste a direct .mp4/.m4a/.mp3 link."
                        )
                    )
                }
            }

            // Unknown binary content-type — allow if URL ends in known media extension
            val pathLower = finalUrl.substringBefore('?').lowercase()
            if (listOf(".mp4", ".m4a", ".mp3", ".webm", ".mkv", ".wav", ".aac", ".ogg", ".flac").any { pathLower.endsWith(it) }) {
                Result.success(finalUrl)
            } else {
                Result.failure(
                    IllegalArgumentException("URL did not return a playable audio or video stream ($contentType).")
                )
            }
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException(t.message ?: "Unable to verify media URL"))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun extractDirectMediaFromHtml(html: String): String? {
        if (html.isBlank()) return null
        val patterns = listOf(
            Regex("""<meta[^>]+property=["']og:video:secure_url["'][^>]+content=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<(?:video|source)[^>]+src=["'](https?://[^"']+\.(?:mp4|m4a|mp3|webm)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|m4a|mp3|webm)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            val match = regex.find(html)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")
            if (!match.isNullOrBlank()) return match
        }
        return null
    }

    private fun markTaskFailed(taskId: String, message: String) {
        _downloads.update { list ->
            list.map {
                if (it.id == taskId) it.copy(status = JobStatus.FAILED, progress = 0f, errorMessage = message)
                else it
            }
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                val active = _downloads.value.filter { it.status == JobStatus.RUNNING && it.downloadManagerId >= 0L }
                if (active.isEmpty()) break

                var anyStillRunning = false
                val updatedList = _downloads.value.map { task ->
                    if (task.status != JobStatus.RUNNING || task.downloadManagerId < 0L) return@map task
                    val query = DownloadManager.Query().setFilterById(task.downloadManagerId)
                    var cursor: Cursor? = null
                    try {
                        cursor = downloadManager.query(query)
                        if (cursor != null && cursor.moveToFirst()) {
                            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val bytesCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val mediaTypeCol = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)

                            val dmStatus = if (statusCol >= 0) cursor.getInt(statusCol) else DownloadManager.STATUS_RUNNING
                            val downloaded = if (bytesCol >= 0) cursor.getLong(bytesCol) else 0L
                            val total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L
                            val localUri = if (localUriCol >= 0) cursor.getString(localUriCol) else null
                            val dmMime = if (mediaTypeCol >= 0) cursor.getString(mediaTypeCol).orEmpty().lowercase() else ""

                            when (dmStatus) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    val looksLikeHtml = dmMime.contains("html") || isDownloadedFileHtml(localUri)
                                    if (looksLikeHtml) {
                                        runCatching { downloadManager.remove(task.downloadManagerId) }
                                        task.copy(
                                            progress = 0f,
                                            status = JobStatus.FAILED,
                                            errorMessage = "Downloaded webpage HTML instead of media stream"
                                        )
                                    } else {
                                        val finalUri = localUri ?: task.url
                                        val mime = if (task.preset.isVideo) "video/mp4" else "audio/${task.preset.extension}"
                                        if (autoImportEnabled) {
                                            repository.markDownloaded(
                                                trackId = "dl_${task.downloadManagerId}",
                                                title = task.title,
                                                artist = "Downloaded • ${task.preset.extension.uppercase()}",
                                                uriString = finalUri,
                                                mediaType = if (task.preset.isVideo) MediaType.VIDEO else MediaType.AUDIO,
                                                mimeType = mime,
                                                sizeBytes = downloaded.coerceAtLeast(total)
                                            )
                                        }
                                        task.copy(
                                            progress = 1f,
                                            status = JobStatus.COMPLETED,
                                            localUriString = finalUri
                                        )
                                    }
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    task.copy(status = JobStatus.FAILED, errorMessage = "Download failed")
                                }
                                else -> {
                                    anyStillRunning = true
                                    val progress = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0.05f, 0.99f) else 0.35f
                                    task.copy(progress = progress, status = JobStatus.RUNNING)
                                }
                            }
                        } else {
                            anyStillRunning = true
                            task
                        }
                    } finally {
                        cursor?.close()
                    }
                }
                _downloads.value = updatedList
                if (!anyStillRunning) break
                delay(500L)
            }
        }
    }

    private fun isDownloadedFileHtml(localUriString: String?): Boolean {
        if (localUriString.isNullOrBlank()) return false
        return runCatching {
            val uri = Uri.parse(localUriString)
            val headBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val buf = ByteArray(128)
                val n = stream.read(buf)
                if (n > 0) String(buf, 0, n).trimStart().lowercase() else ""
            }.orEmpty()
            headBytes.startsWith("<!doctype html") || headBytes.startsWith("<html")
        }.getOrDefault(false)
    }
}
