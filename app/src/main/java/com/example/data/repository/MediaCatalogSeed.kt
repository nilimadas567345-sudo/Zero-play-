package com.example.data.repository

import android.content.Context
import android.net.Uri
import com.example.R
import com.example.data.local.TrackRecordEntity
import com.example.data.model.ArtistChartItem
import com.example.data.model.LyricLine
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object MediaCatalogSeed {

    data class SeedSpec(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationSec: Int,
        val artworkResId: Int,
        val moodTag: String,
        val playCount: Int,
        val isLiked: Boolean,
        val source: String,
        val rootFreqHz: Double,
        val lyrics: List<LyricLine>
    )

    val trendingArtists: List<ArtistChartItem> = listOf(
        ArtistChartItem(1, "Kaelen Vance", "Synthwave • Tokyo", "14.8M monthly", R.drawable.img_cover_midnight_1794995218900),
        ArtistChartItem(2, "Soraia Lune", "Alt R&B • Velvet", "11.2M monthly", R.drawable.img_cover_velvet_1794995218473),
        ArtistChartItem(3, "Vespera", "Electronic • Chrome", "9.6M monthly", R.drawable.img_cover_aurora_1794995218098),
        ArtistChartItem(4, "Crimson Vale", "Indie Rock • Horizon", "7.4M monthly", R.drawable.img_cover_horizon_1794995219984),
        ArtistChartItem(5, "Nori Takahashi", "Ambient • Echo", "5.9M monthly", R.drawable.img_cover_midnight_1794995218900),
        ArtistChartItem(6, "Mireille", "Dream Pop • Dusk", "4.3M monthly", R.drawable.img_cover_velvet_1794995218473)
    )

    val seeds: List<SeedSpec> = listOf(
        SeedSpec(
            id = "seed_midnight_rain",
            title = "Neon Rain Over Shibuya",
            artist = "Kaelen Vance",
            album = "Midnight Frequencies",
            durationSec = 24,
            artworkResId = R.drawable.img_cover_midnight_1794995218900,
            moodTag = "Relax",
            playCount = 1284000,
            isLiked = true,
            source = "CURATED",
            rootFreqHz = 220.0,
            lyrics = listOf(
                LyricLine(0L, "Streetlights flicker through the midnight glass"),
                LyricLine(3500L, "Footsteps echo where the neon shadows pass"),
                LyricLine(7000L, "Static humming on an open frequency"),
                LyricLine(10500L, "Every chord is pulling you back home to me"),
                LyricLine(14500L, "Hold the rhythm till the city fades to blue"),
                LyricLine(18500L, "In the silence I can still hear only you")
            )
        ),
        SeedSpec(
            id = "seed_velvet_prism",
            title = "Amber Velvet Prism",
            artist = "Soraia Lune",
            album = "Golden Hour Tapes",
            durationSec = 24,
            artworkResId = R.drawable.img_cover_velvet_1794995218473,
            moodTag = "Romance",
            playCount = 982000,
            isLiked = true,
            source = "CURATED",
            rootFreqHz = 261.63,
            lyrics = listOf(
                LyricLine(0L, "Sunlight spills across the velvet curtain fold"),
                LyricLine(3800L, "Turning every quiet memory into gold"),
                LyricLine(7500L, "Slow tempo breathing in the amber haze"),
                LyricLine(11200L, "Caught inside the glow of late November days"),
                LyricLine(15200L, "Soft harmonics floating through the windowpane"),
                LyricLine(19000L, "Whispering your name again and again")
            )
        ),
        SeedSpec(
            id = "seed_aurora_chrome",
            title = "Iridescent Wave",
            artist = "Vespera",
            album = "Liquid Chrome EP",
            durationSec = 24,
            artworkResId = R.drawable.img_cover_aurora_1794995218098,
            moodTag = "Energy",
            playCount = 864000,
            isLiked = false,
            source = "CURATED",
            rootFreqHz = 293.66,
            lyrics = listOf(
                LyricLine(0L, "Liquid silver rising on a tidal beat"),
                LyricLine(3500L, "Northern lights colliding where the oceans meet"),
                LyricLine(7200L, "Pulse accelerates beneath the crystal dome"),
                LyricLine(11000L, "Chasing signals far beyond the monochrome"),
                LyricLine(14800L, "Every wavelength shimmers in the midnight air"),
                LyricLine(18800L, "Weightless motion taking us from here to there")
            )
        ),
        SeedSpec(
            id = "seed_crimson_horizon",
            title = "Solaris Horizon",
            artist = "Crimson Vale",
            album = "Desert Sunsets",
            durationSec = 24,
            artworkResId = R.drawable.img_cover_horizon_1794995219984,
            moodTag = "Feel good",
            playCount = 745000,
            isLiked = true,
            source = "CACHED",
            rootFreqHz = 196.0,
            lyrics = listOf(
                LyricLine(0L, "Crimson sun is sinking past the canyon ridge"),
                LyricLine(3600L, "Open highway burning underneath the bridge"),
                LyricLine(7400L, "Radio is singing out a fearless sound"),
                LyricLine(11400L, "Leaving all the heavy static on the ground"),
                LyricLine(15400L, "Hands up to the sky before the embers cool"),
                LyricLine(19200L, "Running wild and free without a single rule")
            )
        ),
        SeedSpec(
            id = "seed_afterglow_drive",
            title = "Afterglow Drive",
            artist = "Kaelen Vance & Mireille",
            album = "Late Night Signals",
            durationSec = 22,
            artworkResId = R.drawable.img_cover_midnight_1794995218900,
            moodTag = "Party",
            playCount = 631000,
            isLiked = false,
            source = "CURATED",
            rootFreqHz = 329.63,
            lyrics = listOf(
                LyricLine(0L, "Headlights cutting through the mist along the bay"),
                LyricLine(4000L, "Bassline kicking all the quiet doubts away"),
                LyricLine(8000L, "Dancing silhouettes beneath the harbor lights"),
                LyricLine(12000L, "Made for endless summers and electric nights")
            )
        ),
        SeedSpec(
            id = "seed_silk_echoes",
            title = "Silk & Echoes",
            artist = "Soraia Lune",
            album = "Velvet Sessions",
            durationSec = 22,
            artworkResId = R.drawable.img_cover_velvet_1794995218473,
            moodTag = "Romance",
            playCount = 589000,
            isLiked = true,
            source = "CACHED",
            rootFreqHz = 246.94,
            lyrics = listOf(
                LyricLine(0L, "Velvet shadows drifting on the studio floor"),
                LyricLine(4200L, "Every gentle echo leaves us wanting more"),
                LyricLine(8400L, "Warm analog vinyl spinning soft and slow"),
                LyricLine(12600L, "Nowhere else on earth we ever need to go")
            )
        ),
        SeedSpec(
            id = "seed_prism_pulse",
            title = "Prism Pulse",
            artist = "Vespera",
            album = "Chromatic",
            durationSec = 22,
            artworkResId = R.drawable.img_cover_aurora_1794995218098,
            moodTag = "Party",
            playCount = 512000,
            isLiked = false,
            source = "CURATED",
            rootFreqHz = 349.23,
            lyrics = listOf(
                LyricLine(0L, "Fractured colors flashing on the clubroom wall"),
                LyricLine(4200L, "Feel the synthesizer lifting up us all"),
                LyricLine(8400L, "Drop the tempo then we bring it right back in"),
                LyricLine(12600L, "Let the iridescent symphony begin")
            )
        ),
        SeedSpec(
            id = "seed_desert_bloom",
            title = "Desert Bloom",
            artist = "Crimson Vale",
            album = "Red Mesa",
            durationSec = 22,
            artworkResId = R.drawable.img_cover_horizon_1794995219984,
            moodTag = "Feel good",
            playCount = 478000,
            isLiked = false,
            source = "CURATED",
            rootFreqHz = 220.0,
            lyrics = listOf(
                LyricLine(0L, "Morning breeze across the open desert plain"),
                LyricLine(4500L, "Golden petals waking after summer rain"),
                LyricLine(9000L, "Every chord rings bright under the endless blue"),
                LyricLine(13500L, "Everything feels brand new when I walk with you")
            )
        ),
        SeedSpec(
            id = "seed_moonlit_vinyl",
            title = "Moonlit Vinyl",
            artist = "Nori Takahashi",
            album = "Kyoto Lo-Fi",
            durationSec = 20,
            artworkResId = R.drawable.img_cover_midnight_1794995218900,
            moodTag = "Relax",
            playCount = 419000,
            isLiked = true,
            source = "CACHED",
            rootFreqHz = 174.61,
            lyrics = listOf(
                LyricLine(0L, "Soft crackle on the needle as the night winds down"),
                LyricLine(5000L, "Quiet lanterns glowing in a sleeping town"),
                LyricLine(10000L, "Breathe in calm and let the distant worries drift"),
                LyricLine(15000L, "Midnight melodies become a quiet gift")
            )
        ),
        SeedSpec(
            id = "seed_kinetic_sky",
            title = "Kinetic Sky",
            artist = "Vespera & Kaelen Vance",
            album = "Velocity",
            durationSec = 20,
            artworkResId = R.drawable.img_cover_aurora_1794995218098,
            moodTag = "Energy",
            playCount = 394000,
            isLiked = false,
            source = "CURATED",
            rootFreqHz = 392.0,
            lyrics = listOf(
                LyricLine(0L, "Engines hum before the neon starting line"),
                LyricLine(4800L, "Chasing thunder where the silver stars align"),
                LyricLine(9600L, "Faster than the shadow of the setting sun"),
                LyricLine(14400L, "We are only getting started, watch us run")
            )
        ),
        SeedSpec(
            id = "seed_saffron_haze",
            title = "Saffron Haze",
            artist = "Mireille",
            album = "Paper Lanterns",
            durationSec = 20,
            artworkResId = R.drawable.img_cover_velvet_1794995218473,
            moodTag = "Feel good",
            playCount = 342000,
            isLiked = false,
            source = "CURATED",
            rootFreqHz = 277.18,
            lyrics = listOf(
                LyricLine(0L, "Warm saffron sky fading into pastel pink"),
                LyricLine(5000L, "Skipping stones along the quiet river brink"),
                LyricLine(10000L, "Easy laughter carrying across the breeze"),
                LyricLine(15000L, "Floating gently through the afternoon with ease")
            )
        ),
        SeedSpec(
            id = "seed_redline_echo",
            title = "Redline Echo",
            artist = "Crimson Vale",
            album = "Analog Dust",
            durationSec = 20,
            artworkResId = R.drawable.img_cover_horizon_1794995219984,
            moodTag = "Energy",
            playCount = 301000,
            isLiked = false,
            source = "CURATED",
            rootFreqHz = 246.94,
            lyrics = listOf(
                LyricLine(0L, "Feedback ringing through the desert canyon air"),
                LyricLine(5000L, "Dust clouds rising up without a single care"),
                LyricLine(10000L, "Turn the amplifiers all the way to ten"),
                LyricLine(15000L, "Hear the echo rolling back to us again")
            )
        )
    )

    private val lyricsMap: Map<String, List<LyricLine>> = seeds.associate { it.id to it.lyrics }

    fun getLyricsForTrack(trackId: String): List<LyricLine> {
        return lyricsMap[trackId] ?: listOf(
            LyricLine(0L, "Instrumental & Local Audio Playback"),
            LyricLine(4000L, "Playing directly from device media library"),
            LyricLine(8000L, "Powered by Zero Play Media3 Engine")
        )
    }

    fun ensureSeedTracksAndAudio(context: Context): List<TrackRecordEntity> {
        val synthDir = File(context.filesDir, "echo_synth_tracks").apply { mkdirs() }
        return seeds.mapIndexed { index, spec ->
            val wavFile = File(synthDir, "${spec.id}.wav")
            if (!wavFile.exists()) {
                generateAmbientWav(
                    file = wavFile,
                    durationSeconds = spec.durationSec,
                    baseFreq = spec.rootFreqHz
                )
            }
            TrackRecordEntity(
                trackId = spec.id,
                title = spec.title,
                artist = spec.artist,
                album = spec.album,
                durationMs = spec.durationSec * 1000L,
                uriString = Uri.fromFile(wavFile).toString(),
                artworkResId = spec.artworkResId,
                artworkUriString = null,
                mediaType = "AUDIO",
                source = spec.source,
                moodTag = spec.moodTag,
                playCount = spec.playCount,
                isLiked = spec.isLiked,
                dateAdded = System.currentTimeMillis() - (index * 3_600_000L),
                mimeType = "audio/wav",
                sizeBytes = wavFile.length()
            )
        }
    }

    private fun generateAmbientWav(file: File, durationSeconds: Int, baseFreq: Double) {
        val sampleRate = 22050
        val totalSamples = sampleRate * durationSeconds
        val dataSize = totalSamples * 2
        val headerSize = 44

        val pcmBytes = ByteArray(headerSize + dataSize)

        // RIFF/WAVE header
        writeAscii(pcmBytes, 0, "RIFF")
        writeIntLE(pcmBytes, 4, 36 + dataSize)
        writeAscii(pcmBytes, 8, "WAVE")
        writeAscii(pcmBytes, 12, "fmt ")
        writeIntLE(pcmBytes, 16, 16)
        writeShortLE(pcmBytes, 20, 1) // PCM
        writeShortLE(pcmBytes, 22, 1) // Mono
        writeIntLE(pcmBytes, 24, sampleRate)
        writeIntLE(pcmBytes, 28, sampleRate * 2)
        writeShortLE(pcmBytes, 32, 2)
        writeShortLE(pcmBytes, 34, 16)
        writeAscii(pcmBytes, 36, "data")
        writeIntLE(pcmBytes, 40, dataSize)

        // Warm ambient 4-chord synth progression
        val chordRatios = doubleArrayOf(1.0, 1.25, 1.5, 1.125)
        val chordSeconds = 4.0

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val chordIdx = ((t / chordSeconds).toInt()) % chordRatios.size
            val localT = t % chordSeconds
            val root = baseFreq * chordRatios[chordIdx]

            // Soft pad envelope per chord
            val attack = (localT / 0.35).coerceAtMost(1.0)
            val decay = exp(-localT * 0.25)
            val fadeOut = if (t > durationSeconds - 1.5) ((durationSeconds - t) / 1.5).coerceIn(0.0, 1.0) else 1.0
            val env = attack * decay * fadeOut

            val tone = 0.50 * sin(2.0 * PI * root * t) +
                0.30 * sin(2.0 * PI * (root * 1.5) * t) +
                0.20 * sin(2.0 * PI * (root * 2.0) * t)
            val sampleVal = (tone * env * 9000.0).toInt().coerceIn(-32767, 32767)

            val offset = headerSize + i * 2
            pcmBytes[offset] = (sampleVal and 0xFF).toByte()
            pcmBytes[offset + 1] = ((sampleVal shr 8) and 0xFF).toByte()
        }

        FileOutputStream(file).use { it.write(pcmBytes) }
    }

    private fun writeAscii(buf: ByteArray, offset: Int, str: String) {
        str.forEachIndexed { idx, c -> buf[offset + idx] = c.code.toByte() }
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
