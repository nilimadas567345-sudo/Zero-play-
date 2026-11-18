package com.example.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.R
import com.example.data.local.ConversionJobEntity
import com.example.data.local.EchoDao
import com.example.data.local.PlaylistEntity
import com.example.data.local.PlaylistTrackCrossRef
import com.example.data.local.TrackRecordEntity
import com.example.data.model.ConversionJobUi
import com.example.data.model.ConversionType
import com.example.data.model.JobStatus
import com.example.data.model.MediaSource
import com.example.data.model.MediaTrack
import com.example.data.model.MediaType
import com.example.data.model.PlaylistUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class EchoRepository(
    private val context: Context,
    private val dao: EchoDao
) {
    val allTracks: Flow<List<MediaTrack>> = dao.observeAllTracks().map { entities ->
        entities.map { it.toDomain() }
    }

    val playlists: Flow<List<PlaylistUi>> = combine(
        dao.observePlaylists(),
        dao.observePlaylistTrackRefs(),
        allTracks
    ) { playlistEntities, refs, tracks ->
        val trackMap = tracks.associateBy { it.id }
        val groupedRefs = refs.groupBy { it.playlistId }
        playlistEntities.map { entity ->
            val playlistTracks = groupedRefs[entity.id].orEmpty().mapNotNull { trackMap[it.trackId] }
            PlaylistUi(
                id = entity.id,
                name = entity.name,
                tracks = playlistTracks,
                coverResId = entity.coverResId ?: playlistTracks.firstOrNull()?.artworkResId,
                createdAt = entity.createdAt
            )
        }
    }

    val conversionJobs: Flow<List<ConversionJobUi>> = dao.observeConversionJobs().map { entities ->
        entities.map { entity ->
            ConversionJobUi(
                id = entity.id,
                title = entity.title,
                type = runCatching { ConversionType.valueOf(entity.type) }.getOrDefault(ConversionType.PHOTO_COMPRESS),
                detailLabel = entity.detailLabel,
                progress = entity.progress,
                status = runCatching { JobStatus.valueOf(entity.status) }.getOrDefault(JobStatus.COMPLETED),
                inputUriString = entity.inputUriString,
                outputUriString = entity.outputUriString,
                sizeBeforeBytes = entity.sizeBeforeBytes,
                sizeAfterBytes = entity.sizeAfterBytes,
                errorMessage = entity.errorMessage,
                createdAt = entity.createdAt
            )
        }
    }

    suspend fun initializeSeedData() = withContext(Dispatchers.IO) {
        val seededTracks = MediaCatalogSeed.ensureSeedTracksAndAudio(context)
        dao.insertTracksIfAbsent(seededTracks)

        val existingPlaylists = mutableListOf<PlaylistEntity>()
        // Check if first launch by inspecting if any curated tracks existed before; insert default playlists once
        val prefs = context.getSharedPreferences("echo_seed_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("playlists_seeded", false)) {
            val id1 = dao.insertPlaylist(
                PlaylistEntity(
                    name = "Midnight Drive",
                    coverResId = R.drawable.img_cover_midnight_1794995218900
                )
            )
            val id2 = dao.insertPlaylist(
                PlaylistEntity(
                    name = "Velvet & Chill",
                    coverResId = R.drawable.img_cover_velvet_1794995218473
                )
            )
            val id3 = dao.insertPlaylist(
                PlaylistEntity(
                    name = "Late Night Mix",
                    coverResId = R.drawable.img_cover_aurora_1794995218098
                )
            )
            listOf("seed_midnight_rain", "seed_afterglow_drive", "seed_kinetic_sky").forEach {
                dao.addTrackToPlaylist(PlaylistTrackCrossRef(id1, it))
            }
            listOf("seed_velvet_prism", "seed_silk_echoes", "seed_moonlit_vinyl").forEach {
                dao.addTrackToPlaylist(PlaylistTrackCrossRef(id2, it))
            }
            listOf("seed_aurora_chrome", "seed_crimson_horizon", "seed_prism_pulse").forEach {
                dao.addTrackToPlaylist(PlaylistTrackCrossRef(id3, it))
            }
            prefs.edit().putBoolean("playlists_seeded", true).apply()
        }
        existingPlaylists.clear()
    }

    suspend fun scanDeviceMediaStore() = withContext(Dispatchers.IO) {
        val discovered = mutableListOf<TrackRecordEntity>()

        // Scan Audio
        runCatching {
            val audioProjection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.RELATIVE_PATH
            )
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                audioProjection,
                null,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val pathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

                var count = 0
                while (cursor.moveToNext() && count < 150) {
                    val mediaId = cursor.getLong(idCol)
                    val duration = cursor.getLong(durCol)
                    if (duration < 1000L) continue
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId)
                    val albumId = cursor.getLong(albumIdCol)
                    val artUri = ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()
                    val relPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                    val source = when {
                        relPath.contains("ZeroPlay", ignoreCase = true) ||
                            relPath.contains("EchoMusic", ignoreCase = true) -> "EXPORTED"
                        relPath.contains("Download", ignoreCase = true) -> "DOWNLOADED"
                        else -> "LOCAL"
                    }
                    discovered += TrackRecordEntity(
                        trackId = "local_audio_$mediaId",
                        title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) } ?: "Audio Track $mediaId",
                        artist = cursor.getString(artistCol)?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) } ?: "Local Audio",
                        album = cursor.getString(albumCol)?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) } ?: "Device Audio",
                        durationMs = duration,
                        uriString = contentUri.toString(),
                        artworkResId = R.drawable.img_cover_aurora_1794995218098,
                        artworkUriString = artUri,
                        mediaType = "AUDIO",
                        source = source,
                        moodTag = "Feel good",
                        playCount = 0,
                        isLiked = false,
                        dateAdded = cursor.getLong(dateCol) * 1000L,
                        mimeType = cursor.getString(mimeCol) ?: "audio/mpeg",
                        sizeBytes = cursor.getLong(sizeCol)
                    )
                    count++
                }
            }
        }

        // Scan Video
        runCatching {
            val videoProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.ARTIST,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH
            )
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Video.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val pathCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)

                var count = 0
                while (cursor.moveToNext() && count < 100) {
                    val mediaId = cursor.getLong(idCol)
                    val duration = cursor.getLong(durCol)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
                    val relPath = if (pathCol >= 0) cursor.getString(pathCol).orEmpty() else ""
                    val source = when {
                        relPath.contains("ZeroPlay", ignoreCase = true) ||
                            relPath.contains("EchoMusic", ignoreCase = true) -> "EXPORTED"
                        relPath.contains("Download", ignoreCase = true) -> "DOWNLOADED"
                        else -> "LOCAL"
                    }
                    discovered += TrackRecordEntity(
                        trackId = "local_video_$mediaId",
                        title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) } ?: "Video Clip $mediaId",
                        artist = (if (artistCol >= 0) cursor.getString(artistCol) else null)
                            ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
                            ?: "Local Video",
                        album = "Video Library",
                        durationMs = duration.coerceAtLeast(1000L),
                        uriString = contentUri.toString(),
                        artworkResId = R.drawable.img_cover_horizon_1794995219984,
                        artworkUriString = contentUri.toString(),
                        mediaType = "VIDEO",
                        source = source,
                        moodTag = "Energy",
                        playCount = 0,
                        isLiked = false,
                        dateAdded = cursor.getLong(dateCol) * 1000L,
                        mimeType = cursor.getString(mimeCol) ?: "video/mp4",
                        sizeBytes = cursor.getLong(sizeCol)
                    )
                    count++
                }
            }
        }

        if (discovered.isNotEmpty()) {
            dao.insertTracksIfAbsent(discovered)
        }
    }

    suspend fun toggleLike(trackId: String) {
        val existing = dao.getTrackById(trackId) ?: return
        dao.setLiked(trackId, !existing.isLiked)
    }

    suspend fun recordPlay(trackId: String) {
        dao.incrementPlayCount(trackId)
    }

    suspend fun markDownloaded(
        trackId: String,
        title: String,
        artist: String,
        uriString: String,
        mediaType: MediaType,
        mimeType: String,
        sizeBytes: Long = 0L
    ) {
        val existing = dao.getTrackById(trackId)
        if (existing != null) {
            dao.updateTrackSource(trackId, MediaSource.DOWNLOADED.name, uriString)
        } else {
            dao.upsertTrack(
                TrackRecordEntity(
                    trackId = trackId,
                    title = title,
                    artist = artist,
                    album = "Downloads",
                    durationMs = 0L,
                    uriString = uriString,
                    artworkResId = if (mediaType == MediaType.VIDEO) R.drawable.img_cover_horizon_1794995219984 else R.drawable.img_cover_midnight_1794995218900,
                    artworkUriString = if (mediaType == MediaType.VIDEO) uriString else null,
                    mediaType = mediaType.name,
                    source = MediaSource.DOWNLOADED.name,
                    moodTag = "Feel good",
                    playCount = 0,
                    isLiked = false,
                    dateAdded = System.currentTimeMillis(),
                    mimeType = mimeType,
                    sizeBytes = sizeBytes
                )
            )
        }
    }

    suspend fun registerExportedMedia(
        trackId: String,
        title: String,
        artist: String,
        uriString: String,
        mediaType: MediaType,
        mimeType: String,
        sizeBytes: Long
    ) {
        dao.upsertTrack(
            TrackRecordEntity(
                trackId = trackId,
                title = title,
                artist = artist,
                album = "Zero Play Exported",
                durationMs = 0L,
                uriString = uriString,
                artworkResId = if (mediaType == MediaType.VIDEO) R.drawable.img_cover_aurora_1794995218098 else R.drawable.img_cover_velvet_1794995218473,
                artworkUriString = if (mediaType == MediaType.VIDEO) uriString else null,
                mediaType = mediaType.name,
                source = MediaSource.EXPORTED.name,
                moodTag = "Feel good",
                playCount = 0,
                isLiked = false,
                dateAdded = System.currentTimeMillis(),
                mimeType = mimeType,
                sizeBytes = sizeBytes
            )
        )
    }

    suspend fun createPlaylist(name: String, initialTrackId: String? = null): Long {
        val id = dao.insertPlaylist(
            PlaylistEntity(
                name = name.trim().ifEmpty { "New Playlist" },
                coverResId = R.drawable.img_cover_aurora_1794995218098
            )
        )
        if (initialTrackId != null) {
            dao.addTrackToPlaylist(PlaylistTrackCrossRef(id, initialTrackId))
        }
        return id
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: String) {
        dao.addTrackToPlaylist(PlaylistTrackCrossRef(playlistId, trackId))
    }

    suspend fun deletePlaylist(playlistId: Long) {
        dao.deletePlaylist(playlistId)
    }

    suspend fun upsertConversionJob(job: ConversionJobUi) {
        dao.upsertConversionJob(
            ConversionJobEntity(
                id = job.id,
                title = job.title,
                type = job.type.name,
                detailLabel = job.detailLabel,
                progress = job.progress,
                status = job.status.name,
                inputUriString = job.inputUriString,
                outputUriString = job.outputUriString,
                sizeBeforeBytes = job.sizeBeforeBytes,
                sizeAfterBytes = job.sizeAfterBytes,
                errorMessage = job.errorMessage,
                createdAt = job.createdAt
            )
        )
    }

    suspend fun clearConversionHistory() {
        dao.clearConversionJobs()
    }

    private fun TrackRecordEntity.toDomain(): MediaTrack {
        val cleanArtist = artist.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            ?: if (mediaType == "VIDEO") "Local Video" else "Local Audio"
        val cleanAlbum = album.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            ?: if (mediaType == "VIDEO") "Video Library" else "Audio Library"
        return MediaTrack(
            id = trackId,
            title = title,
            artist = cleanArtist,
            album = cleanAlbum,
            durationMs = durationMs,
            uriString = uriString,
            artworkResId = artworkResId,
            artworkUriString = artworkUriString,
            mediaType = runCatching { MediaType.valueOf(mediaType) }.getOrDefault(MediaType.AUDIO),
            source = runCatching { MediaSource.valueOf(source) }.getOrDefault(MediaSource.CURATED),
            moodTag = moodTag,
            playCount = playCount,
            isLiked = isLiked,
            dateAdded = dateAdded,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            lyrics = MediaCatalogSeed.getLyricsForTrack(trackId)
        )
    }
}
