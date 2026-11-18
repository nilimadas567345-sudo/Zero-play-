package com.example.converter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.data.model.ConversionJobUi
import com.example.data.model.ConversionType
import com.example.data.model.JobStatus
import com.example.data.model.MediaType
import com.example.data.repository.EchoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.roundToInt

enum class PhotoTargetFormat(val label: String, val mimeType: String, val ext: String) {
    JPEG("JPEG", "image/jpeg", "jpg"),
    PNG("PNG", "image/png", "png"),
    WEBP("WEBP", "image/webp", "webp")
}

enum class VideoCompressionPreset(val label: String, val heightPx: Int, val bitrateBps: Int) {
    COMPACT_480P("480p Compact", 480, 1_500_000),
    BALANCED_720P("720p Balanced", 720, 3_000_000),
    HIGH_1080P("1080p High", 1080, 6_000_000)
}

enum class VideoCodecOption(val label: String, val mimeType: String) {
    H264("H.264 (AVC)", MimeTypes.VIDEO_H264),
    HEVC("H.265 (HEVC)", MimeTypes.VIDEO_H265)
}

data class VideoFileMetrics(
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int
)

class MediaConverterEngine(
    private val context: Context,
    private val repository: EchoRepository
) {
    suspend fun inspectVideoMetrics(uri: Uri): VideoFileMetrics = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri) ?: "Selected Video"
        val size = queryFileSize(uri)
        var duration = 0L
        var width = 0
        var height = 0
        val retriever = MediaMetadataRetriever()
        runCatching {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        }
        runCatching { retriever.release() }
        VideoFileMetrics(name, size, duration, width, height)
    }

    suspend fun batchCompressPhotos(
        uris: List<Uri>,
        format: PhotoTargetFormat,
        quality: Int,
        scalePercent: Int,
        skipIfNotSmaller: Boolean = false,
        filenamePrefix: String = "ZeroPlay_"
    ) = withContext(Dispatchers.IO) {
        uris.forEachIndexed { index, uri ->
            val jobId = "photo_${UUID.randomUUID()}"
            val name = queryDisplayName(uri) ?: "Photo_${index + 1}"
            val sizeBefore = queryFileSize(uri)
            val baseJob = ConversionJobUi(
                id = jobId,
                title = "${name.substringBeforeLast('.')} -> ${format.label}",
                type = ConversionType.PHOTO_COMPRESS,
                detailLabel = "${format.label} • Q$quality • ${scalePercent}%",
                progress = 0.1f,
                status = JobStatus.RUNNING,
                inputUriString = uri.toString(),
                sizeBeforeBytes = sizeBefore
            )
            repository.upsertConversionJob(baseJob)

            runCatching {
                val inputBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to open image stream")
                val original = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size)
                    ?: error("Unsupported image format")

                val targetW = ((original.width * scalePercent) / 100f).roundToInt().coerceAtLeast(64)
                val targetH = ((original.height * scalePercent) / 100f).roundToInt().coerceAtLeast(64)
                val scaled = if (scalePercent < 100) {
                    Bitmap.createScaledBitmap(original, targetW, targetH, true)
                } else {
                    original
                }

                val outStream = ByteArrayOutputStream()
                val compressFormat = when (format) {
                    PhotoTargetFormat.JPEG -> Bitmap.CompressFormat.JPEG
                    PhotoTargetFormat.PNG -> Bitmap.CompressFormat.PNG
                    PhotoTargetFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                }
                scaled.compress(compressFormat, quality.coerceIn(10, 100), outStream)
                val compressedBytes = outStream.toByteArray()
                if (scaled !== original) scaled.recycle()
                original.recycle()

                val sizeAfter = compressedBytes.size.toLong()
                if (skipIfNotSmaller && sizeBefore > 0L && sizeAfter > (sizeBefore * 0.90).toLong()) {
                    repository.upsertConversionJob(
                        baseJob.copy(
                            progress = 1f,
                            status = JobStatus.COMPLETED,
                            detailLabel = "Skipped (<10% space savings)",
                            outputUriString = uri.toString(),
                            sizeAfterBytes = sizeBefore
                        )
                    )
                    return@runCatching
                }

                val outName = "${filenamePrefix}${name.substringBeforeLast('.')}_${System.currentTimeMillis()}.${format.ext}"
                val savedUri = saveBytesToMediaStoreImages(outName, format.mimeType, compressedBytes)

                repository.upsertConversionJob(
                    baseJob.copy(
                        progress = 1f,
                        status = JobStatus.COMPLETED,
                        outputUriString = savedUri.toString(),
                        sizeAfterBytes = sizeAfter
                    )
                )
            }.onFailure { err ->
                repository.upsertConversionJob(
                    baseJob.copy(
                        status = JobStatus.FAILED,
                        errorMessage = err.message ?: "Photo conversion failed"
                    )
                )
            }
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun compressVideo(
        uri: Uri,
        preset: VideoCompressionPreset,
        codec: VideoCodecOption = VideoCodecOption.H264,
        bitrateMbps: Float = preset.bitrateBps / 1_000_000f,
        skipIfNotSmaller: Boolean = false,
        removeAudio: Boolean = false
    ) {
        val jobId = "video_${UUID.randomUUID()}"
        val name = queryDisplayName(uri) ?: "ZeroPlayVideo"
        val sizeBefore = withContext(Dispatchers.IO) { queryFileSize(uri) }
        val targetBitrateBps = (bitrateMbps * 1_000_000f).roundToInt().coerceIn(500_000, 20_000_000)

        val baseJob = ConversionJobUi(
            id = jobId,
            title = "${name.substringBeforeLast('.')} (${preset.heightPx}p ${codec.name})",
            type = ConversionType.VIDEO_COMPRESS,
            detailLabel = buildString {
                append("${preset.label} • ${String.format("%.1f", bitrateMbps)} Mbps ${codec.name}")
                if (removeAudio) append(" • Muted")
            },
            progress = 0.05f,
            status = JobStatus.RUNNING,
            inputUriString = uri.toString(),
            sizeBeforeBytes = sizeBefore
        )
        repository.upsertConversionJob(baseJob)

        val tempFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
        try {
            runTransformerOnMain(
                inputUri = uri,
                outputFile = tempFile,
                removeVideo = false,
                removeAudio = removeAudio,
                targetHeight = preset.heightPx,
                targetBitrate = targetBitrateBps,
                videoMimeType = codec.mimeType,
                onProgress = { p ->
                    repository.upsertConversionJob(baseJob.copy(progress = p.coerceIn(0.05f, 0.96f)))
                }
            )
            val sizeAfter = tempFile.length()
            if (skipIfNotSmaller && sizeBefore > 0L && sizeAfter > (sizeBefore * 0.90).toLong()) {
                repository.upsertConversionJob(
                    baseJob.copy(
                        progress = 1f,
                        status = JobStatus.COMPLETED,
                        detailLabel = "Skipped (<10% space savings)",
                        outputUriString = uri.toString(),
                        sizeAfterBytes = sizeBefore
                    )
                )
                return
            }

            val outName = "ZeroPlay_${name.substringBeforeLast('.')}_${preset.heightPx}p.mp4"
            val savedUri = withContext(Dispatchers.IO) {
                saveFileToMediaStoreVideo(outName, tempFile)
            }
            repository.registerExportedMedia(
                trackId = "exp_vid_${System.currentTimeMillis()}",
                title = outName.substringBeforeLast('.'),
                artist = "Exported Video • ${preset.heightPx}p ${codec.name}",
                uriString = savedUri.toString(),
                mediaType = MediaType.VIDEO,
                mimeType = "video/mp4",
                sizeBytes = sizeAfter
            )
            repository.upsertConversionJob(
                baseJob.copy(
                    progress = 1f,
                    status = JobStatus.COMPLETED,
                    outputUriString = savedUri.toString(),
                    sizeAfterBytes = sizeAfter
                )
            )
        } catch (t: Throwable) {
            repository.upsertConversionJob(
                baseJob.copy(
                    status = JobStatus.FAILED,
                    errorMessage = t.message ?: "Video compression failed"
                )
            )
        } finally {
            tempFile.delete()
        }
    }

    @OptIn(UnstableApi::class)
    suspend fun extractAudioFromVideo(
        uri: Uri,
        customTitle: String? = null
    ) {
        val jobId = "audio_${UUID.randomUUID()}"
        val name = customTitle?.takeIf { it.isNotBlank() } ?: queryDisplayName(uri) ?: "ExtractedAudio"
        val sizeBefore = withContext(Dispatchers.IO) { queryFileSize(uri) }
        val baseJob = ConversionJobUi(
            id = jobId,
            title = "${name.substringBeforeLast('.')} -> M4A",
            type = ConversionType.AUDIO_EXTRACT,
            detailLabel = "AAC / M4A Audio Extraction",
            progress = 0.05f,
            status = JobStatus.RUNNING,
            inputUriString = uri.toString(),
            sizeBeforeBytes = sizeBefore
        )
        repository.upsertConversionJob(baseJob)

        val tempOut = File(context.cacheDir, "extracted_${System.currentTimeMillis()}.m4a")
        try {
            val extractedWithMuxer = withContext(Dispatchers.IO) {
                runCatching {
                    extractAudioWithMediaExtractorMuxer(uri, tempOut) { p ->
                        repository.upsertConversionJob(baseJob.copy(progress = p.coerceIn(0.05f, 0.95f)))
                    }
                    tempOut.exists() && tempOut.length() > 512L
                }.getOrDefault(false)
            }

            if (!extractedWithMuxer) {
                runTransformerOnMain(
                    inputUri = uri,
                    outputFile = tempOut,
                    removeVideo = true,
                    removeAudio = false,
                    targetHeight = 0,
                    targetBitrate = 0,
                    videoMimeType = MimeTypes.VIDEO_H264,
                    onProgress = { p ->
                        repository.upsertConversionJob(baseJob.copy(progress = p.coerceIn(0.05f, 0.96f)))
                    }
                )
            }

            val outName = "ZeroPlay_${name.substringBeforeLast('.')}_audio.m4a"
            val savedUri = withContext(Dispatchers.IO) {
                saveFileToMediaStoreAudio(outName, tempOut)
            }
            val sizeAfter = tempOut.length()
            repository.registerExportedMedia(
                trackId = "exp_aud_${System.currentTimeMillis()}",
                title = outName.substringBeforeLast('.'),
                artist = "Extracted Audio • M4A",
                uriString = savedUri.toString(),
                mediaType = MediaType.AUDIO,
                mimeType = "audio/mp4",
                sizeBytes = sizeAfter
            )
            repository.upsertConversionJob(
                baseJob.copy(
                    progress = 1f,
                    status = JobStatus.COMPLETED,
                    outputUriString = savedUri.toString(),
                    sizeAfterBytes = sizeAfter
                )
            )
        } catch (t: Throwable) {
            repository.upsertConversionJob(
                baseJob.copy(
                    status = JobStatus.FAILED,
                    errorMessage = t.message ?: "Audio extraction failed"
                )
            )
        } finally {
            tempOut.delete()
        }
    }

    private suspend fun extractAudioWithMediaExtractorMuxer(
        uri: Uri,
        outputFile: File,
        onProgress: suspend (Float) -> Unit
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) {
                error("No audio track found in video")
            }
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (!mime.contains("mp4a", ignoreCase = true) && !mime.contains("aac", ignoreCase = true)) {
                error("Non-AAC track ($mime) requires transcoding")
            }

            extractor.selectTrack(audioTrackIndex)
            val durationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                1L
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val bufferSize = 256 * 1024
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var lastReport = 0L

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()

                val now = System.currentTimeMillis()
                if (durationUs > 0 && now - lastReport > 150L) {
                    lastReport = now
                    onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0.05f, 0.95f))
                }
            }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun runTransformerOnMain(
        inputUri: Uri,
        outputFile: File,
        removeVideo: Boolean,
        removeAudio: Boolean,
        targetHeight: Int,
        targetBitrate: Int,
        videoMimeType: String,
        onProgress: suspend (Float) -> Unit
    ) = withContext(Dispatchers.Main) {
        val mediaItem = MediaItem.fromUri(inputUri)
        val editedBuilder = EditedMediaItem.Builder(mediaItem)
        if (removeAudio) {
            editedBuilder.setRemoveAudio(true)
        }
        if (removeVideo) {
            editedBuilder.setRemoveVideo(true)
        } else if (targetHeight > 0) {
            editedBuilder.setEffects(
                Effects(
                    emptyList(),
                    listOf(Presentation.createForHeight(targetHeight))
                )
            )
        }
        val editedMediaItem = editedBuilder.build()

        val transformerBuilder = Transformer.Builder(context)
        if (removeVideo) {
            transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
        } else {
            transformerBuilder.setVideoMimeType(videoMimeType)
            if (targetBitrate > 0) {
                val encoderFactory = DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder()
                            .setBitrate(targetBitrate)
                            .build()
                    )
                    .build()
                transformerBuilder.setEncoderFactory(encoderFactory)
            }
        }

        var finished = false
        var exportError: Throwable? = null

        val transformer = transformerBuilder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished = true
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    exportError = exportException
                    finished = true
                }
            })
            .build()

        transformer.start(editedMediaItem, outputFile.absolutePath)
        val progressHolder = ProgressHolder()
        while (!finished) {
            val state = transformer.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                withContext(Dispatchers.IO) {
                    onProgress(progressHolder.progress / 100f)
                }
            }
            delay(250L)
        }
        exportError?.let { throw it }
    }

    private fun saveBytesToMediaStoreImages(filename: String, mimeType: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZeroPlay")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore image entry")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun saveFileToMediaStoreVideo(filename: String, source: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ZeroPlay")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore video entry")
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun saveFileToMediaStoreAudio(filename: String, source: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/ZeroPlay")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Failed to create MediaStore audio entry")
        resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        return uri
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).name
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun queryFileSize(uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).length()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }
}
