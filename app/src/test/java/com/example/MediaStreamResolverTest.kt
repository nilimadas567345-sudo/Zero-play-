package com.example

import com.example.downloader.MediaStreamResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStreamResolverTest {

    private val resolver = MediaStreamResolver()

    @Test
    fun `extracts YouTube video ID from all URL formats`() {
        assertEquals("ezxqKElqUAO", resolver.extractYouTubeVideoId("https://youtu.be/ezxqKElqUAO?si=abc"))
        assertEquals("ezxqKElqUAO", resolver.extractYouTubeVideoId("https://www.youtube.com/watch?v=ezxqKElqUAO&feature=shared"))
        assertEquals("ezxqKElqUAO", resolver.extractYouTubeVideoId("https://music.youtube.com/watch?v=ezxqKElqUAO"))
        assertEquals("ezxqKElqUAO", resolver.extractYouTubeVideoId("https://www.youtube.com/shorts/ezxqKElqUAO"))
    }

    @Test
    fun `extracts Instagram shortcode from reel and post URLs`() {
        assertEquals("Df1nQiWToL1", resolver.extractInstagramShortcode("https://www.instagram.com/reel/Df1nQiWToL1/?igsh=xyz"))
        assertEquals("Df1nQiWToL1", resolver.extractInstagramShortcode("https://instagram.com/reels/Df1nQiWToL1/"))
        assertEquals("Df1nQiWToL1", resolver.extractInstagramShortcode("https://www.instagram.com/p/Df1nQiWToL1/"))
        assertEquals("Df1nQiWToL1", resolver.extractInstagramShortcode("https://www.instagram.com/tv/Df1nQiWToL1/"))
    }

    @Test
    fun `parses InnerTube JSON for video+audio pair and respects quality limit`() {
        val sampleJson = """
            {
              "playabilityStatus": { "status": "OK" },
              "videoDetails": { "title": "Zero Play Sample Video" },
              "streamingData": {
                "formats": [
                  { "itag": 18, "url": "https://cdn.example.com/muxed_360.mp4", "mimeType": "video/mp4", "height": 360, "bitrate": 400000 }
                ],
                "adaptiveFormats": [
                  { "itag": 137, "url": "https://cdn.example.com/video_1080.mp4", "mimeType": "video/mp4; codecs=\"avc1.640028\"", "height": 1080, "bitrate": 2400000 },
                  { "itag": 136, "url": "https://cdn.example.com/video_720.mp4", "mimeType": "video/mp4; codecs=\"avc1.4d401f\"", "height": 720, "bitrate": 1200000 },
                  { "itag": 140, "url": "https://cdn.example.com/audio_140.m4a", "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000 }
                ]
              }
            }
        """.trimIndent()

        val hdVideo = resolver.parseInnerTubePlayerResponse(sampleJson, preferAudio = false, maxHeightPx = 720, emptyMap()).getOrThrow()
        assertEquals("Zero Play Sample Video", hdVideo.title)
        assertEquals("https://cdn.example.com/video_720.mp4", hdVideo.primaryUrl)
        assertEquals("https://cdn.example.com/audio_140.m4a", hdVideo.secondaryAudioUrl)

        val audioOnly = resolver.parseInnerTubePlayerResponse(sampleJson, preferAudio = true, maxHeightPx = 1080, emptyMap()).getOrThrow()
        assertEquals("https://cdn.example.com/audio_140.m4a", audioOnly.primaryUrl)
        assertNull(audioOnly.secondaryAudioUrl)
    }

    @Test
    fun `parses Instagram GraphQL and escaped embed URLs cleanly`() {
        val gqlJson = """
            {
              "data": {
                "xdt_shortcode_media": {
                  "owner": { "username": "tanmay.sudo" },
                  "edge_media_to_caption": { "edges": [ { "node": { "text": "Built to Rebel" } } ] },
                  "video_url": "https:\/\/scontent.cdninstagram.com\/v\/reel.mp4?efg=123\u0026oe=456"
                }
              }
            }
        """.trimIndent()
        val parsed = resolver.parseInstagramJsonBlob(gqlJson, "Df1nQiWToL1", preferAudio = true, emptyMap())
        assertNotNull(parsed)
        assertEquals("https://scontent.cdninstagram.com/v/reel.mp4?efg=123&oe=456", parsed!!.primaryUrl)
        assertEquals("@tanmay.sudo - Built to Rebel", parsed.title)
        assertTrue(parsed.extractAudioFromVideo)

        val embedHtml = """<script>{"playable_url_quality_hd":"https:\/\/scontent-maa2-1.cdninstagram.com\/o1\/reel_hd.mp4?v=1\u0026ccb=7"}</script>"""
        val extracted = resolver.extractInstagramVideoFromText(embedHtml)
        assertEquals("https://scontent-maa2-1.cdninstagram.com/o1/reel_hd.mp4?v=1&ccb=7", extracted)
    }
}
