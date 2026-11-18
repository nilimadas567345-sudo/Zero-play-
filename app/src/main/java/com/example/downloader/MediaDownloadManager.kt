package com.example.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID

class MediaDownloadManager(
    private val context: Context,
    private val repository: EchoRepository
) {
    private val resolver = MediaStreamResolver()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = mutableMapOf<String, Job>()

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
        val trimmed = rawUrl.trim()
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid URL"))
        if (uri.scheme != "http" && uri.scheme != "https") {
            return Result.failure(IllegalArgumentException("Enter a valid http:// or https:// media URL"))
        }
        if (wifiOnly && !isOnUnmeteredOrWifi()) {
            return Result.failure(IllegalArgumentException("Wi-Fi only downloads enabled in Settings"))
        }

        val initialRawBase = customTitle?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "ZeroPlay_${System.currentTimeMillis() / 1000}"

        val initialName = formatDisplayName(
            base = initialRawBase,
            restrictFilenames = restrictFilenames,
            filenameTemplate = filenameTemplate
        )

        val taskId = UUID.randomUUID().toString()
        val pendingTask = DownloadTaskUi(
            id = taskId,
            downloadManagerId = System.currentTimeMillis(),
            url = trimmed,
            title = initialName,
            preset = preset,
            progress = 0.03f,
            status = JobStatus.RUNNING
        )
        _downloads.update { listOf(pendingTask) + it }

        val job = scope.launch {
            val cacheFiles = mutableListOf<File>()
            try {
                val maxHeight = maxHeightForPreset(preset)
                val resolvedResult = resolver.resolve(
                    rawUrl = trimmed,
                    preferAudio = !preset.isVideo,
                    maxHeightPx = maxHeight
                )
                if (resolvedResult.isFailure) {
                    val msg = resolvedResult.exceptionOrNull()?.message
                        ?: "Unable to extract a playable stream from this URL"
                    markTaskFailed(taskId, msg)
                    onError?.invoke(msg)
                    return@launch
                }

                val resolved = resolvedResult.getOrThrow()
                val effectiveBase = customTitle?.takeIf { it.isNotBlank() }
                    ?: resolved.title?.takeIf { it.isNotBlank() }
                    ?: initialRawBase

                val finalName = formatDisplayName(
                    base = effectiveBase,
                    restrictFilenames = restrictFilenames,
                    filenameTemplate = filenameTemplate
                )

                _downloads.update { list ->
                    list.map {
                        if (it.id == taskId) {
                            it.copy(title = finalName, url = resolved.primaryUrl, progress = 0.08f)
                        } else it
                    }
                }

                val tempDir = File(context.cacheDir, "dl_work").apply { mkdirs() }
                val primaryTemp = File(tempDir, "primary_${taskId}.tmp").also { cacheFiles += it }

                if (resolved.secondaryAudioUrl != null && preset.isVideo) {
                    // Download adaptive Video + Audio in sequence, then mux into single MP4
                    val audioTemp = File(tempDir, "audio_${taskId}.tmp").also { cacheFiles += it }
                    val muxedTemp = File(tempDir, "muxed_${taskId}.mp4").also { cacheFiles += it }

                    downloadStreamToFile(
                        streamUrl = resolved.primaryUrl,
                        headers = resolved.requestHeaders,
                        destFile = primaryTemp
                    ) { frac ->
                        updateProgress(taskId, 0.08f + frac * 0.54f)
                    }.getOrThrow()

                    downloadStreamToFile(
                        streamUrl = resolved.secondaryAudioUrl,
                        headers = resolved.secondaryHeaders,
                        destFile = audioTemp
                    ) { frac ->
                        updateProgress(taskId, 0.62f + frac * 0.23f)
                    }.getOrThrow()

                    updateProgress(taskId, 0.88f)
                    val muxedOk = muxVideoAndAudioMp4(primaryTemp, audioTemp, muxedTemp)
                    val finalSource = if (muxedOk && muxedTemp.length() > 1024L) muxedTemp else primaryTemp
                    finishAndExport(
                        taskId = taskId,
                        sourceFile = finalSource,
                        fileBaseName = finalName,
                        preset = preset,
                        forceExtension = "mp4",
                        forceVideo = true,
                        splitSubfolders = splitSubfolders,
                        autoImportToLibrary = autoImportToLibrary,
                        sourcePlatform = resolved.sourcePlatform
                    )
                } else {
                    downloadStreamToFile(
                        streamUrl = resolved.primaryUrl,
                        headers = resolved.requestHeaders,
                        destFile = primaryTemp
                    ) { frac ->
                        updateProgress(taskId, 0.08f + frac * 0.78f)
                    }.getOrThrow()

                    val finalSource: File
                    val finalExt: String
                    if (resolved.extractAudioFromVideo && !preset.isVideo) {
                        val audioOut = File(tempDir, "audio_ext_${taskId}.m4a").also { cacheFiles += it }
                        updateProgress(taskId, 0.89f)
                        val extracted = extractAudioTrackToM4a(primaryTemp, audioOut)
                        if (extracted && audioOut.length() > 512L) {
                            finalSource = audioOut
                            finalExt = "m4a"
                        } else {
                            finalSource = primaryTemp
                            finalExt = "mp4"
                        }
                    } else {
                        finalSource = primaryTemp
                        finalExt = inferExtension(resolved.primaryUrl, preset)
                    }

                    finishAndExport(
                        taskId = taskId,
                        sourceFile = finalSource,
                        fileBaseName = finalName,
                        preset = preset,
                        forceExtension = finalExt,
                        forceVideo = preset.isVideo,
                        splitSubfolders = splitSubfolders,
                        autoImportToLibrary = autoImportToLibrary,
                        sourcePlatform = resolved.sourcePlatform
                    )
                }
            } catch (t: Throwable) {
                val msg = t.message ?: "Download failed"
                markTaskFailed(taskId, msg)
                onError?.invoke(msg)
            } finally {
                cacheFiles.forEach { runCatching { it.delete() } }
                activeJobs.remove(taskId)
            }
        }
        activeJobs[taskId] = job
        return Result.success(pendingTask)
    }

    private suspend fun finishAndExport(
        taskId: String,
        sourceFile: File,
        fileBaseName: String,
        preset: DownloadFormatPreset,
        forceExtension: String,
        forceVideo: Boolean,
        splitSubfolders: Boolean,
        autoImportToLibrary: Boolean,
        sourcePlatform: String
    ) {
        updateProgress(taskId, 0.94f)
        val mimeType = mimeForExtension(forceExtension, forceVideo)
        val fileName = "${fileBaseName}.${forceExtension}"
        val subfolder = if (splitSubfolders) {
            if (forceVideo) "ZeroPlay/Video" else "ZeroPlay/Audio"
        } else {
            "ZeroPlay"
        }

        val exportedUri = exportToDownloads(
            sourceFile = sourceFile,
            fileName = fileName,
            mimeType = mimeType,
            relativeSubfolder = subfolder
        )
        val sizeBytes = sourceFile.length()

        if (autoImportToLibrary) {
            repository.markDownloaded(
                trackId = "dl_${taskId.take(12)}",
                title = fileBaseName,
                artist = "$sourcePlatform • ${forceExtension.uppercase()}",
                uriString = exportedUri.toString(),
                mediaType = if (forceVideo) MediaType.VIDEO else MediaType.AUDIO,
                mimeType = mimeType,
                sizeBytes = sizeBytes
            )
        }

        _downloads.update { list ->
            list.map {
                if (it.id == taskId) {
                    it.copy(
                        progress = 1f,
                        status = JobStatus.COMPLETED,
                        localUriString = exportedUri.toString()
                    )
                } else it
            }
        }
    }

    private fun downloadStreamToFile(
        streamUrl: String,
        headers: Map<String, String>,
        destFile: File,
        onProgress: (Float) -> Unit
    ): Result<File> {
        if (destFile.exists()) destFile.delete()
        var currentUrl = streamUrl
        var downloaded = 0L
        var totalExpected = -1L
        var retries = 0

        while (retries < 6) {
            var conn: HttpURLConnection? = null
            try {
                // Manually follow redirects so custom headers aren't lost across domains
                var redirectCount = 0
                while (redirectCount < 6) {
                    conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 15000
                        readTimeout = 30000
                        requestMethod = "GET"
                        headers.forEach { (k, v) -> setRequestProperty(k, v) }
                        if (downloaded > 0L) {
                            setRequestProperty("Range", "bytes=$downloaded-")
                        }
                    }
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val nextLoc = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (nextLoc.isNullOrBlank()) break
                        currentUrl = URL(URL(currentUrl), nextLoc).toString()
                        redirectCount++
                    } else {
                        break
                    }
                }

                val active = conn ?: return Result.failure(IllegalStateException("Unable to open stream connection"))
                val code = active.responseCode
                if (code !in 200..299 && code != 206) {
                    return Result.failure(IllegalStateException("Media server returned HTTP $code"))
                }

                val cType = active.contentType.orEmpty().lowercase()
                if (cType.contains("text/html") || cType.contains("application/xhtml")) {
                    return Result.failure(IllegalStateException("Stream URL returned HTML instead of media"))
                }

                if (totalExpected <= 0L) {
                    val contentRange = active.getHeaderField("Content-Range")
                    val rangeTotal = contentRange?.substringAfterLast('/')?.toLongOrNull()
                    totalExpected = rangeTotal ?: active.contentLengthLong.takeIf { it > 0L }?.let { downloaded + it } ?: -1L
                }

                val append = downloaded > 0L && code == 206
                if (!append && downloaded > 0L) {
                    downloaded = 0L
                }

                val buf = ByteArray(32 * 1024)
                var checkedMagic = downloaded > 0L
                active.inputStream.use { input ->
                    FileOutputStream(destFile, append).use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            if (!checkedMagic) {
                                checkedMagic = true
                                val head = String(buf, 0, minOf(read, 64), Charsets.UTF_8).trimStart().lowercase()
                                if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
                                    return Result.failure(IllegalStateException("Stream payload contained HTML webpage"))
                                }
                            }
                            output.write(buf, 0, read)
                            downloaded += read
                            val frac = if (totalExpected > 0L) {
                                (downloaded.toFloat() / totalExpected.toFloat()).coerceIn(0f, 0.99f)
                            } else {
                                0.45f
                            }
                            onProgress(frac)
                        }
                    }
                }

                if (totalExpected > 0L && downloaded < totalExpected) {
                    retries++
                    continue
                }
                if (destFile.length() <= 0L) {
                    return Result.failure(IllegalStateException("Downloaded media file was empty"))
                }
                onProgress(1f)
                return Result.success(destFile)
            } catch (t: Throwable) {
                retries++
                if (retries >= 6) {
                    return Result.failure(t)
                }
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        return Result.failure(IllegalStateException("Download interrupted before completion"))
    }

    private fun muxVideoAndAudioMp4(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Boolean {
        val vExtractor = MediaExtractor()
        val aExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            vExtractor.setDataSource(videoFile.absolutePath)
            aExtractor.setDataSource(audioFile.absolutePath)

            var vTrackIdx = -1
            var vFormat: MediaFormat? = null
            for (i in 0 until vExtractor.trackCount) {
                val f = vExtractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    vTrackIdx = i
                    vFormat = f
                    break
                }
            }

            var aTrackIdx = -1
            var aFormat: MediaFormat? = null
            for (i in 0 until aExtractor.trackCount) {
                val f = aExtractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    aTrackIdx = i
                    aFormat = f
                    break
                }
            }

            if (vFormat == null || aFormat == null) return false

            vExtractor.selectTrack(vTrackIdx)
            aExtractor.selectTrack(aTrackIdx)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = muxer.addTrack(vFormat)
            val outAudioTrack = muxer.addTrack(aFormat)
            muxer.start()

            val bufferSize = maxOf(
                if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 512 * 1024,
                256 * 1024
            )
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val sampleSize = vExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = vExtractor.sampleTime
                @Suppress("WrongConstant")
                info.flags = vExtractor.sampleFlags
                muxer.writeSampleData(outVideoTrack, buffer, info)
                vExtractor.advance()
            }

            while (true) {
                val sampleSize = aExtractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = aExtractor.sampleTime
                @Suppress("WrongConstant")
                info.flags = aExtractor.sampleFlags
                muxer.writeSampleData(outAudioTrack, buffer, info)
                aExtractor.advance()
            }

            true
        } catch (_: Throwable) {
            runCatching { outputFile.delete() }
            false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { vExtractor.release() }
            runCatching { aExtractor.release() }
        }
    }

    private fun extractAudioTrackToM4a(
        videoFile: File,
        outputAudioFile: File
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            var audioIdx = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioIdx = i
                    audioFormat = f
                    break
                }
            }
            if (audioFormat == null) return false

            extractor.selectTrack(audioIdx)
            muxer = MediaMuxer(outputAudioFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocate(256 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime
                @Suppress("WrongConstant")
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
            true
        } catch (_: Throwable) {
            runCatching { outputAudioFile.delete() }
            false
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun exportToDownloads(
        sourceFile: File,
        fileName: String,
        mimeType: String,
        relativeSubfolder: String
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$relativeSubfolder")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            }
        }

        val appDownloadDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            relativeSubfolder
        ).apply { mkdirs() }
        val outFile = File(appDownloadDir, fileName)
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        }
        return Uri.fromFile(outFile)
    }

    private fun formatDisplayName(
        base: String,
        restrictFilenames: Boolean,
        filenameTemplate: SealFilenameTemplatePref
    ): String {
        val templated = when (filenameTemplate) {
            SealFilenameTemplatePref.TITLE_ONLY -> base
            SealFilenameTemplatePref.TITLE_ARTIST -> "$base - ZeroPlay"
            SealFilenameTemplatePref.ARTIST_TITLE -> "ZeroPlay - $base"
            SealFilenameTemplatePref.TITLE_TIMESTAMP -> "${base}_${System.currentTimeMillis() / 1000}"
        }
        return if (restrictFilenames) {
            templated.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").replace(Regex("_+"), "_").trim('_').take(56)
                .ifBlank { "ZeroPlay_${System.currentTimeMillis() / 1000}" }
        } else {
            templated.replace("/", "_").take(56)
        }
    }

    private fun maxHeightForPreset(preset: DownloadFormatPreset): Int = when (preset) {
        DownloadFormatPreset.VIDEO_MP4_4K -> 2160
        DownloadFormatPreset.VIDEO_MP4_HIGH -> 1080
        DownloadFormatPreset.VIDEO_MP4_STANDARD -> 720
        DownloadFormatPreset.VIDEO_MP4_COMPACT -> 480
        else -> 1080
    }

    private fun inferExtension(url: String, preset: DownloadFormatPreset): String {
        val cleanPath = url.substringBefore('?').lowercase()
        return when {
            !preset.isVideo -> {
                when {
                    cleanPath.endsWith(".mp3") -> "mp3"
                    cleanPath.endsWith(".flac") -> "flac"
                    cleanPath.endsWith(".ogg") -> "ogg"
                    cleanPath.endsWith(".opus") -> "opus"
                    cleanPath.endsWith(".webm") -> "webm"
                    else -> "m4a"
                }
            }
            cleanPath.endsWith(".webm") -> "webm"
            cleanPath.endsWith(".mkv") -> "mkv"
            else -> preset.extension
        }
    }

    private fun mimeForExtension(ext: String, isVideo: Boolean): String = when (ext.lowercase()) {
        "mp4" -> if (isVideo) "video/mp4" else "audio/mp4"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "webm" -> if (isVideo) "video/webm" else "audio/webm"
        "ogg", "opus" -> "audio/ogg"
        else -> if (isVideo) "video/mp4" else "audio/mp4"
    }

    private fun isOnUnmeteredOrWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun updateProgress(taskId: String, progress: Float) {
        _downloads.update { list ->
            list.map {
                if (it.id == taskId && it.status == JobStatus.RUNNING) {
                    it.copy(progress = progress.coerceIn(0f, 0.99f))
                } else it
            }
        }
    }

    private fun markTaskFailed(taskId: String, message: String) {
        _downloads.update { list ->
            list.map {
                if (it.id == taskId) it.copy(status = JobStatus.FAILED, progress = 0f, errorMessage = message)
                else it
            }
        }
    }
}
