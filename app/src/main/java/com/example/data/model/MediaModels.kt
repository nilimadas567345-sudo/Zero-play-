package com.example.data.model

import androidx.annotation.DrawableRes

enum class MediaType {
    AUDIO,
    VIDEO
}

enum class MediaSource {
    CURATED,
    LOCAL,
    DOWNLOADED,
    EXPORTED,
    CACHED
}

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class MediaTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uriString: String,
    @DrawableRes val artworkResId: Int? = null,
    val artworkUriString: String? = null,
    val mediaType: MediaType = MediaType.AUDIO,
    val source: MediaSource = MediaSource.CURATED,
    val moodTag: String = "Feel good",
    val playCount: Int = 0,
    val isLiked: Boolean = false,
    val dateAdded: Long = System.currentTimeMillis(),
    val mimeType: String = "audio/wav",
    val sizeBytes: Long = 0L,
    val lyrics: List<LyricLine> = emptyList()
)

data class ArtistChartItem(
    val rank: Int,
    val name: String,
    val subtitle: String,
    val monthlyListeners: String,
    @DrawableRes val artworkResId: Int
)

data class PlaylistUi(
    val id: Long,
    val name: String,
    val tracks: List<MediaTrack>,
    @DrawableRes val coverResId: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ConversionType(val label: String) {
    PHOTO_COMPRESS("Photo Convert & Compress"),
    VIDEO_COMPRESS("Video Compressor"),
    AUDIO_EXTRACT("Audio Extractor")
}

enum class JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
}

data class ConversionJobUi(
    val id: String,
    val title: String,
    val type: ConversionType,
    val detailLabel: String,
    val progress: Float,
    val status: JobStatus,
    val inputUriString: String? = null,
    val outputUriString: String? = null,
    val sizeBeforeBytes: Long = 0L,
    val sizeAfterBytes: Long = 0L,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class DownloadFormatPreset(
    val label: String,
    val subtitle: String,
    val extension: String,
    val isVideo: Boolean
) {
    VIDEO_MP4_4K("Video MP4 • 2160p 4K", "Ultra HD H.264 / HEVC • .mp4", "mp4", true),
    VIDEO_MP4_HIGH("Video MP4 • 1080p HD", "1080p H.264 / AAC • .mp4", "mp4", true),
    VIDEO_MP4_STANDARD("Video MP4 • 720p", "720p Balanced • .mp4", "mp4", true),
    VIDEO_MP4_COMPACT("Video MP4 • 480p", "480p Data Saver • .mp4", "mp4", true),
    AUDIO_M4A_HIGH("Audio M4A • Studio", "320 kbps AAC • .m4a", "m4a", false),
    AUDIO_MP3_STANDARD("Audio MP3 • Portable", "192 kbps MP3 • .mp3", "mp3", false),
    AUDIO_FLAC_LOSSLESS("Audio FLAC • Lossless", "24-bit Lossless • .flac", "flac", false)
}

data class DownloadTaskUi(
    val id: String,
    val downloadManagerId: Long,
    val url: String,
    val title: String,
    val preset: DownloadFormatPreset,
    val progress: Float,
    val status: JobStatus,
    val localUriString: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class LibraryQuickCategory(val title: String) {
    LIKED("Liked"),
    DOWNLOADED("Downloaded"),
    EXPORTED("Exported"),
    CACHED("Cached"),
    TOP_50("My top 50"),
    BOTTOM_50("My bottom 50"),
    LOCAL("Local")
}

enum class LibrarySortOption(val label: String) {
    DATE_ADDED("Date added"),
    TITLE("Title"),
    PLAY_COUNT("Most played")
}
