package com.example.downloader

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ResolvedMediaStream(
    val primaryUrl: String,
    val secondaryAudioUrl: String? = null,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val secondaryHeaders: Map<String, String> = emptyMap(),
    val sourcePlatform: String = "Direct",
    val extractAudioFromVideo: Boolean = false
)

class MediaStreamResolver {

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val ANDROID_VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val IOS_YT_UA =
            "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"
        private const val ANDROID_YT_UA =
            "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip"
        private const val FB_CRAWLER_UA =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
        private const val WHATSAPP_UA =
            "WhatsApp/2.24.20.79 A"
        private const val IG_APP_ID = "936619743392459"
    }

    suspend fun resolve(
        rawUrl: String,
        preferAudio: Boolean,
        maxHeightPx: Int
    ): Result<ResolvedMediaStream> = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid URL"))
        val host = uri.host.orEmpty().lowercase()

        when {
            isYouTubeHost(host) -> resolveYouTube(trimmed, preferAudio, maxHeightPx)
            isInstagramHost(host) -> resolveInstagram(trimmed, preferAudio)
            else -> resolveDirectOrWebpage(trimmed, preferAudio)
        }
    }

    fun isYouTubeHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "youtu.be" ||
            h == "youtube.com" ||
            h.endsWith(".youtube.com") ||
            h == "youtube-nocookie.com" ||
            h.endsWith(".youtube-nocookie.com")
    }

    fun isInstagramHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "instagram.com" ||
            h.endsWith(".instagram.com") ||
            h == "instagr.am" ||
            h.endsWith(".instagr.am")
    }

    fun extractYouTubeVideoId(urlString: String): String? {
        val uri = runCatching { Uri.parse(urlString.trim()) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        if (host == "youtu.be") {
            val seg = uri.pathSegments.firstOrNull()?.takeIf { it.length >= 11 }
            if (seg != null) return seg.take(11)
        }
        val vParam = uri.getQueryParameter("v")
        if (!vParam.isNullOrBlank() && vParam.length >= 11) {
            return vParam.take(11)
        }
        val segments = uri.pathSegments
        for (i in 0 until segments.size - 1) {
            if (segments[i] in listOf("shorts", "live", "embed", "v", "reels")) {
                val candidate = segments[i + 1]
                if (candidate.length >= 11) return candidate.take(11)
            }
        }
        val match = Regex("""(?:v=|/|youtu\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})(?:[?&#/]|$)""")
            .find(urlString)
        return match?.groupValues?.getOrNull(1)
    }

    fun extractInstagramShortcode(urlString: String): String? {
        val match = Regex("""(?:instagram\.com|instagr\.am)/(?:[^/]+/)?(?:reel|reels|p|tv)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(urlString)
        return match?.groupValues?.getOrNull(1)
    }

    private fun resolveYouTube(
        urlString: String,
        preferAudio: Boolean,
        maxHeightPx: Int
    ): Result<ResolvedMediaStream> {
        val videoId = extractYouTubeVideoId(urlString)
            ?: return Result.failure(IllegalArgumentException("Could not find YouTube video ID in URL."))

        var lastReason: String? = null

        val clients = listOf(
            InnerTubeClientConfig(
                clientName = "ANDROID_VR",
                clientVersion = "1.60.19",
                clientIdHeader = "28",
                userAgent = ANDROID_VR_UA,
                extraClientFields = mapOf(
                    "osName" to "Android",
                    "osVersion" to "12L",
                    "deviceMake" to "Oculus",
                    "deviceModel" to "Quest 3",
                    "androidSdkVersion" to 32
                )
            ),
            InnerTubeClientConfig(
                clientName = "IOS",
                clientVersion = "20.10.4",
                clientIdHeader = "5",
                userAgent = IOS_YT_UA,
                extraClientFields = mapOf(
                    "deviceMake" to "Apple",
                    "deviceModel" to "iPhone16,2",
                    "osName" to "iPhone",
                    "osVersion" to "18.3.2.22D82"
                )
            ),
            InnerTubeClientConfig(
                clientName = "ANDROID_EMBEDDED_PLAYER",
                clientVersion = "1.0",
                clientIdHeader = "55",
                userAgent = ANDROID_YT_UA,
                extraClientFields = mapOf(
                    "osName" to "Android",
                    "osVersion" to "14",
                    "androidSdkVersion" to 34
                )
            ),
            InnerTubeClientConfig(
                clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                clientVersion = "2.0",
                clientIdHeader = "85",
                userAgent = BROWSER_UA,
                embedThirdPartyUrl = "https://www.youtube.com/watch?v=$videoId"
            )
        )

        for (client in clients) {
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", client.clientName)
                        put("clientVersion", client.clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                        client.extraClientFields.forEach { (k, v) -> put(k, v) }
                    })
                    if (client.embedThirdPartyUrl != null) {
                        put("thirdParty", JSONObject().apply {
                            put("embedUrl", client.embedThirdPartyUrl)
                        })
                    }
                })
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val resp = httpPostJson(
                urlString = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                jsonBody = payload.toString(),
                headers = mapOf(
                    "User-Agent" to client.userAgent,
                    "X-YouTube-Client-Name" to client.clientIdHeader,
                    "X-YouTube-Client-Version" to client.clientVersion,
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com/"
                )
            )
            if (resp.first in 200..299 && resp.second.isNotBlank()) {
                val parsed = parseInnerTubePlayerResponse(
                    jsonText = resp.second,
                    preferAudio = preferAudio,
                    maxHeightPx = maxHeightPx,
                    streamHeaders = mapOf(
                        "User-Agent" to client.userAgent,
                        "Origin" to "https://www.youtube.com",
                        "Referer" to "https://www.youtube.com/"
                    )
                )
                if (parsed.isSuccess) return parsed
                lastReason = parsed.exceptionOrNull()?.message
            }
        }

        // Fallback: Piped / Invidious public APIs in case YouTube blocked direct InnerTube on carrier IP
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee"
        )
        for (base in pipedInstances) {
            val resp = httpGetText(
                urlString = "$base/streams/$videoId",
                headers = mapOf("User-Agent" to BROWSER_UA, "Accept" to "application/json"),
                timeoutMs = 7000
            )
            if (resp.first in 200..299 && resp.second.startsWith("{")) {
                val parsed = parsePipedResponse(resp.second, preferAudio, maxHeightPx)
                if (parsed.isSuccess) return parsed
            }
        }

        val invidiousInstances = listOf(
            "https://inv.nadeko.net",
            "https://yewtu.be"
        )
        for (base in invidiousInstances) {
            val resp = httpGetText(
                urlString = "$base/api/v1/videos/$videoId",
                headers = mapOf("User-Agent" to BROWSER_UA, "Accept" to "application/json"),
                timeoutMs = 7000
            )
            if (resp.first in 200..299 && resp.second.startsWith("{")) {
                val parsed = parseInvidiousResponse(resp.second, preferAudio, maxHeightPx)
                if (parsed.isSuccess) return parsed
            }
        }

        return Result.failure(
            IllegalArgumentException(
                lastReason?.takeIf { it.isNotBlank() }
                    ?: "YouTube refused unauthenticated stream extraction for video $videoId. Try another link or check your network."
            )
        )
    }

    internal fun parseInnerTubePlayerResponse(
        jsonText: String,
        preferAudio: Boolean,
        maxHeightPx: Int,
        streamHeaders: Map<String, String>
    ): Result<ResolvedMediaStream> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid YouTube player JSON"))

        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status", "")
        val reason = playability?.optString("reason", "")?.takeIf { it.isNotBlank() }

        val videoDetails = root.optJSONObject("videoDetails")
        val title = videoDetails?.optString("title")?.takeIf { it.isNotBlank() }
        val thumb = videoDetails?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { arr -> if (arr.length() > 0) arr.getJSONObject(arr.length() - 1).optString("url") else null }

        val streamingData = root.optJSONObject("streamingData")
            ?: return Result.failure(IllegalArgumentException(reason ?: "YouTube playability status: $status"))

        val muxedFormats = extractYtFormats(streamingData.optJSONArray("formats"))
        val adaptiveFormats = extractYtFormats(streamingData.optJSONArray("adaptiveFormats"))

        if (preferAudio) {
            val bestM4a = adaptiveFormats
                .filter { it.mimeType.startsWith("audio/mp4") }
                .maxByOrNull { it.bitrate }
            if (bestM4a != null) {
                return Result.success(
                    ResolvedMediaStream(
                        primaryUrl = bestM4a.url,
                        title = title,
                        thumbnailUrl = thumb,
                        requestHeaders = streamHeaders,
                        sourcePlatform = "YouTube",
                        extractAudioFromVideo = false
                    )
                )
            }
            val bestAnyAudio = adaptiveFormats
                .filter { it.mimeType.startsWith("audio/") }
                .maxByOrNull { it.bitrate }
            if (bestAnyAudio != null) {
                return Result.success(
                    ResolvedMediaStream(
                        primaryUrl = bestAnyAudio.url,
                        title = title,
                        thumbnailUrl = thumb,
                        requestHeaders = streamHeaders,
                        sourcePlatform = "YouTube",
                        extractAudioFromVideo = false
                    )
                )
            }
            val fallbackMuxed = muxedFormats.maxByOrNull { it.height }
            if (fallbackMuxed != null) {
                return Result.success(
                    ResolvedMediaStream(
                        primaryUrl = fallbackMuxed.url,
                        title = title,
                        thumbnailUrl = thumb,
                        requestHeaders = streamHeaders,
                        sourcePlatform = "YouTube",
                        extractAudioFromVideo = true
                    )
                )
            }
            return Result.failure(IllegalArgumentException(reason ?: "No direct audio stream available"))
        }

        // Video mode: prefer adaptive H.264 MP4 + M4A pair for crisp HD/FHD/4K with audio
        val audioM4a = adaptiveFormats
            .filter { it.mimeType.startsWith("audio/mp4") }
            .maxByOrNull { it.bitrate }

        val adaptiveVideosMp4 = adaptiveFormats
            .filter { it.mimeType.startsWith("video/mp4") && it.height > 0 }

        if (audioM4a != null && adaptiveVideosMp4.isNotEmpty()) {
            val h264Candidates = adaptiveVideosMp4.filter {
                it.mimeType.contains("avc1", ignoreCase = true)
            }.ifEmpty { adaptiveVideosMp4 }

            val matchingLimit = h264Candidates.filter { it.height <= maxHeightPx }
            val chosenVideo = if (matchingLimit.isNotEmpty()) {
                matchingLimit.maxWithOrNull(compareBy<YtFormatCandidate> { it.height }.thenBy { it.bitrate })
            } else {
                h264Candidates.minByOrNull { it.height }
            }

            if (chosenVideo != null) {
                return Result.success(
                    ResolvedMediaStream(
                        primaryUrl = chosenVideo.url,
                        secondaryAudioUrl = audioM4a.url,
                        title = title,
                        thumbnailUrl = thumb,
                        requestHeaders = streamHeaders,
                        secondaryHeaders = streamHeaders,
                        sourcePlatform = "YouTube"
                    )
                )
            }
        }

        val muxedCandidate = muxedFormats
            .filter { it.height <= maxHeightPx }
            .maxByOrNull { it.height }
            ?: muxedFormats.maxByOrNull { it.height }

        if (muxedCandidate != null) {
            return Result.success(
                ResolvedMediaStream(
                    primaryUrl = muxedCandidate.url,
                    title = title,
                    thumbnailUrl = thumb,
                    requestHeaders = streamHeaders,
                    sourcePlatform = "YouTube"
                )
            )
        }

        // Fallback to HLS manifest if provided by iOS client
        val hlsUrl = streamingData.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
        if (hlsUrl != null) {
            return Result.failure(IllegalArgumentException("Only HLS playlist returned by YouTube for this stream; retry or update player."))
        }

        return Result.failure(IllegalArgumentException(reason ?: "No downloadable YouTube stream URLs found ($status)"))
    }

    private fun parsePipedResponse(
        jsonText: String,
        preferAudio: Boolean,
        maxHeightPx: Int
    ): Result<ResolvedMediaStream> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid Piped JSON"))
        val title = root.optString("title").takeIf { it.isNotBlank() }
        val thumb = root.optString("thumbnailUrl").takeIf { it.isNotBlank() }

        val audioStreams = root.optJSONArray("audioStreams")
        var bestAudioUrl: String? = null
        var bestAudioBitrate = -1
        if (audioStreams != null) {
            for (i in 0 until audioStreams.length()) {
                val obj = audioStreams.getJSONObject(i)
                val url = obj.optString("url")
                val mime = obj.optString("mimeType").lowercase()
                val bitrate = obj.optInt("bitrate", 0)
                if (url.startsWith("http") && (mime.contains("mp4") || mime.contains("m4a"))) {
                    if (bitrate > bestAudioBitrate) {
                        bestAudioBitrate = bitrate
                        bestAudioUrl = url
                    }
                }
            }
        }

        if (preferAudio && bestAudioUrl != null) {
            return Result.success(
                ResolvedMediaStream(
                    primaryUrl = bestAudioUrl,
                    title = title,
                    thumbnailUrl = thumb,
                    requestHeaders = mapOf("User-Agent" to BROWSER_UA),
                    sourcePlatform = "YouTube"
                )
            )
        }

        val videoStreams = root.optJSONArray("videoStreams")
        var bestMuxedUrl: String? = null
        var bestMuxedHeight = -1
        var bestAdaptiveVideoUrl: String? = null
        var bestAdaptiveHeight = -1

        if (videoStreams != null) {
            for (i in 0 until videoStreams.length()) {
                val obj = videoStreams.getJSONObject(i)
                val url = obj.optString("url")
                if (!url.startsWith("http")) continue
                val mime = obj.optString("mimeType").lowercase()
                val height = obj.optInt("height", 0)
                val videoOnly = obj.optBoolean("videoOnly", true)
                if (!mime.contains("mp4") && !obj.optString("format").equals("MPEG_4", true)) continue
                if (!videoOnly && height <= maxHeightPx && height > bestMuxedHeight) {
                    bestMuxedHeight = height
                    bestMuxedUrl = url
                } else if (videoOnly && height <= maxHeightPx && height > bestAdaptiveHeight) {
                    bestAdaptiveHeight = height
                    bestAdaptiveVideoUrl = url
                }
            }
        }

        if (!preferAudio && bestAdaptiveVideoUrl != null && bestAudioUrl != null) {
            return Result.success(
                ResolvedMediaStream(
                    primaryUrl = bestAdaptiveVideoUrl,
                    secondaryAudioUrl = bestAudioUrl,
                    title = title,
                    thumbnailUrl = thumb,
                    requestHeaders = mapOf("User-Agent" to BROWSER_UA),
                    secondaryHeaders = mapOf("User-Agent" to BROWSER_UA),
                    sourcePlatform = "YouTube"
                )
            )
        }
        if (bestMuxedUrl != null) {
            return Result.success(
                ResolvedMediaStream(
                    primaryUrl = bestMuxedUrl,
                    title = title,
                    thumbnailUrl = thumb,
                    requestHeaders = mapOf("User-Agent" to BROWSER_UA),
                    sourcePlatform = "YouTube",
                    extractAudioFromVideo = preferAudio
                )
            )
        }
        return Result.failure(IllegalArgumentException("No Piped streams matched"))
    }

    private fun parseInvidiousResponse(
        jsonText: String,
        preferAudio: Boolean,
        maxHeightPx: Int
    ): Result<ResolvedMediaStream> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid Invidious JSON"))
        val title = root.optString("title").takeIf { it.isNotBlank() }

        val adaptive = root.optJSONArray("adaptiveFormats")
        var bestAudioUrl: String? = null
        var bestAudioBitrate = -1
        var bestVideoUrl: String? = null
        var bestVideoHeight = -1

        if (adaptive != null) {
            for (i in 0 until adaptive.length()) {
                val obj = adaptive.getJSONObject(i)
                val url = obj.optString("url")
                val type = obj.optString("type").lowercase()
                if (!url.startsWith("http")) continue
                if (type.startsWith("audio/mp4")) {
                    val bitrate = obj.optString("bitrate").toIntOrNull() ?: 0
                    if (bitrate > bestAudioBitrate) {
                        bestAudioBitrate = bitrate
                        bestAudioUrl = url
                    }
                } else if (type.startsWith("video/mp4")) {
                    val height = obj.optString("resolution").removeSuffix("p").toIntOrNull() ?: 0
                    if (height <= maxHeightPx && height > bestVideoHeight) {
                        bestVideoHeight = height
                        bestVideoUrl = url
                    }
                }
            }
        }

        if (preferAudio && bestAudioUrl != null) {
            return Result.success(
                ResolvedMediaStream(
                    primaryUrl = bestAudioUrl,
                    title = title,
                    requestHeaders = mapOf("User-Agent" to BROWSER_UA),
                    sourcePlatform = "YouTube"
                )
            )
        }

        if (!preferAudio && bestVideoUrl != null && bestAudioUrl != null) {
            return Result.success(
                ResolvedMediaStream(
                    primaryUrl = bestVideoUrl,
                    secondaryAudioUrl = bestAudioUrl,
                    title = title,
                    requestHeaders = mapOf("User-Agent" to BROWSER_UA),
                    secondaryHeaders = mapOf("User-Agent" to BROWSER_UA),
                    sourcePlatform = "YouTube"
                )
            )
        }

        val formatStreams = root.optJSONArray("formatStreams")
        var muxedUrl: String? = null
        var muxedHeight = -1
        if (formatStreams != null) {
            for (i in 0 until formatStreams.length()) {
                val obj = formatStreams.getJSONObject(i)
                val url = obj.optString("url")
                val height = obj.optString("resolution").removeSuffix("p").toIntOrNull() ?: 0
                if (url.startsWith("http") && height <= maxHeightPx && height > muxedHeight) {
                    muxedHeight = height
                    muxedUrl = url
                }
            }
        }
        if (muxedUrl != null) {
            return Result.success(
                ResolvedMediaStream(
                    primaryUrl = muxedUrl,
                    title = title,
                    requestHeaders = mapOf("User-Agent" to BROWSER_UA),
                    sourcePlatform = "YouTube",
                    extractAudioFromVideo = preferAudio
                )
            )
        }
        return Result.failure(IllegalArgumentException("No Invidious streams matched"))
    }

    private fun resolveInstagram(
        rawUrl: String,
        preferAudio: Boolean
    ): Result<ResolvedMediaStream> {
        val resolvedUrl = followRedirectsOnly(rawUrl) ?: rawUrl
        val shortcode = extractInstagramShortcode(resolvedUrl)
            ?: extractInstagramShortcode(rawUrl)

        val cdnHeaders = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "https://www.instagram.com/"
        )

        if (shortcode != null) {
            // Strategy 1: Public Instagram GraphQL endpoint (PolarisPostActionLoadPostQueryQuery)
            val docIds = listOf("8845758582119845", "10015901848480474")
            for (docId in docIds) {
                val varsJson = """{"shortcode":"$shortcode","fetch_tagged_user_count":null,"hoisted_comment_id":null,"hoisted_reply_id":null}"""
                val formBody = buildString {
                    append("av=0&__d=www&__user=0&__a=1&dpr=1")
                    append("&fb_api_caller_class=RelayModern")
                    append("&fb_api_req_friendly_name=PolarisPostActionLoadPostQueryQuery")
                    append("&variables=").append(URLEncoder.encode(varsJson, "UTF-8"))
                    append("&doc_id=").append(docId)
                }
                val resp = httpPostForm(
                    urlString = "https://www.instagram.com/graphql/query",
                    formBody = formBody,
                    headers = mapOf(
                        "User-Agent" to BROWSER_UA,
                        "Accept" to "*/*",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "X-IG-App-ID" to IG_APP_ID,
                        "X-ASBD-ID" to "129477",
                        "X-FB-Friendly-Name" to "PolarisPostActionLoadPostQueryQuery",
                        "Origin" to "https://www.instagram.com",
                        "Referer" to "https://www.instagram.com/reel/$shortcode/"
                    )
                )
                if (resp.first in 200..299 && resp.second.isNotBlank()) {
                    val parsed = parseInstagramJsonBlob(resp.second, shortcode, preferAudio, cdnHeaders)
                    if (parsed != null) return Result.success(parsed)
                }
            }

            // Strategy 2: Embed endpoints (/reel/<shortcode>/embed/captioned/ and /p/<shortcode>/embed/)
            val embedUrls = listOf(
                "https://www.instagram.com/reel/$shortcode/embed/captioned/",
                "https://www.instagram.com/p/$shortcode/embed/captioned/",
                "https://www.instagram.com/p/$shortcode/embed/"
            )
            for (embedUrl in embedUrls) {
                val resp = httpGetText(
                    urlString = embedUrl,
                    headers = mapOf(
                        "User-Agent" to BROWSER_UA,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9",
                        "Referer" to "https://www.instagram.com/"
                    )
                )
                if (resp.first in 200..299 && resp.second.isNotBlank()) {
                    val mediaUrl = extractInstagramVideoFromText(resp.second)
                    if (mediaUrl != null) {
                        val title = extractHtmlTitleOrIgAuthor(resp.second) ?: "Instagram_${shortcode}"
                        return Result.success(
                            ResolvedMediaStream(
                                primaryUrl = mediaUrl,
                                title = title,
                                requestHeaders = cdnHeaders,
                                sourcePlatform = "Instagram",
                                extractAudioFromVideo = preferAudio
                            )
                        )
                    }
                }
            }

            // Strategy 3: Link-preview crawler UAs (Meta often emits og:video for Facebook/WhatsApp bots)
            for (crawlerUa in listOf(FB_CRAWLER_UA, WHATSAPP_UA)) {
                val resp = httpGetText(
                    urlString = "https://www.instagram.com/reel/$shortcode/",
                    headers = mapOf(
                        "User-Agent" to crawlerUa,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9"
                    )
                )
                if (resp.first in 200..299 && resp.second.isNotBlank()) {
                    val mediaUrl = extractInstagramVideoFromText(resp.second)
                    if (mediaUrl != null) {
                        val title = extractHtmlTitleOrIgAuthor(resp.second) ?: "Instagram_${shortcode}"
                        return Result.success(
                            ResolvedMediaStream(
                                primaryUrl = mediaUrl,
                                title = title,
                                requestHeaders = cdnHeaders,
                                sourcePlatform = "Instagram",
                                extractAudioFromVideo = preferAudio
                            )
                        )
                    }
                }
            }

            // Strategy 4: Legacy ?__a=1&__d=dis JSON API
            val legacyResp = httpGetText(
                urlString = "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis",
                headers = mapOf(
                    "User-Agent" to BROWSER_UA,
                    "Accept" to "application/json",
                    "X-IG-App-ID" to IG_APP_ID,
                    "Referer" to "https://www.instagram.com/reel/$shortcode/"
                )
            )
            if (legacyResp.first in 200..299 && legacyResp.second.trimStart().startsWith("{")) {
                val parsed = parseInstagramJsonBlob(legacyResp.second, shortcode, preferAudio, cdnHeaders)
                if (parsed != null) return Result.success(parsed)
            }
        }

        // Strategy 5: Full-document deep HTML scan on canonical URL
        val canonicalResp = httpGetText(
            urlString = resolvedUrl,
            headers = mapOf(
                "User-Agent" to BROWSER_UA,
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "en-US,en;q=0.9"
            )
        )
        if (canonicalResp.first in 200..299 && canonicalResp.second.isNotBlank()) {
            val mediaUrl = extractInstagramVideoFromText(canonicalResp.second)
            if (mediaUrl != null) {
                val title = extractHtmlTitleOrIgAuthor(canonicalResp.second)
                    ?: shortcode?.let { "Instagram_$it" }
                return Result.success(
                    ResolvedMediaStream(
                        primaryUrl = mediaUrl,
                        title = title,
                        requestHeaders = cdnHeaders,
                        sourcePlatform = "Instagram",
                        extractAudioFromVideo = preferAudio
                    )
                )
            }
        }

        return Result.failure(
            IllegalArgumentException(
                "Instagram blocked public stream extraction for this link (private post, login wall, or rate limit)."
            )
        )
    }

    internal fun parseInstagramJsonBlob(
        jsonText: String,
        shortcode: String,
        preferAudio: Boolean,
        headers: Map<String, String>
    ): ResolvedMediaStream? {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val videoUrl = findBestVideoUrlInJson(root)?.let(::unescapeUrl) ?: return null
        val caption = findFirstStringByKeys(root, setOf("text", "title"))
            ?.lineSequence()?.firstOrNull()?.trim()?.take(48)
        val owner = findFirstStringByKeys(root, setOf("username"))
        val title = when {
            !owner.isNullOrBlank() && !caption.isNullOrBlank() -> "@$owner - $caption"
            !owner.isNullOrBlank() -> "@$owner - $shortcode"
            !caption.isNullOrBlank() -> caption
            else -> "Instagram_$shortcode"
        }
        return ResolvedMediaStream(
            primaryUrl = videoUrl,
            title = title,
            requestHeaders = headers,
            sourcePlatform = "Instagram",
            extractAudioFromVideo = preferAudio
        )
    }

    internal fun extractInstagramVideoFromText(raw: String): String? {
        if (raw.isBlank()) return null
        val decoded = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")

        val patterns = listOf(
            Regex("""<meta[^>]+property=["']og:video:secure_url["'][^>]+content=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["'](https?://[^"']+)["'][^>]+property=["']og:video:secure_url["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content=["'](https?://[^"']+)["'][^>]+property=["']og:video["']""", RegexOption.IGNORE_CASE),
            Regex(""""playable_url_quality_hd"\s*:\s*"(https?://[^"]+)""""),
            Regex(""""playable_url"\s*:\s*"(https?://[^"]+)""""),
            Regex(""""video_url"\s*:\s*"(https?://[^"]+)""""),
            Regex(""""video_versions"\s*:\s*\[\s*\{[^}]*?"url"\s*:\s*"(https?://[^"]+)""""),
            Regex("""<video[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""(https?://scontent[^"'\s<>]+\.mp4(?:\?[^"'\s<>]*)?)""", RegexOption.IGNORE_CASE),
            Regex("""(https?://instagram\.[^"'\s<>]+\.mp4(?:\?[^"'\s<>]*)?)""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            val match = regex.find(decoded)?.groupValues?.getOrNull(1)
            val cleaned = match?.let(::unescapeUrl)
            if (!cleaned.isNullOrBlank() && !cleaned.contains("rsrc.php")) {
                return cleaned
            }
        }
        return null
    }

    private fun extractHtmlTitleOrIgAuthor(html: String): String? {
        val ogTitle = Regex(
            """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.getOrNull(1)?.let(::unescapeHtmlEntities)?.trim()
        if (!ogTitle.isNullOrBlank() && !ogTitle.equals("Instagram", ignoreCase = true)) {
            return ogTitle.take(64)
        }
        val titleTag = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.getOrNull(1)?.let(::unescapeHtmlEntities)?.trim()
        return titleTag?.takeIf { it.isNotBlank() && !it.equals("Instagram", ignoreCase = true) }?.take(64)
    }

    private fun resolveDirectOrWebpage(
        urlString: String,
        preferAudio: Boolean
    ): Result<ResolvedMediaStream> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 12000
                requestMethod = "GET"
                setRequestProperty("User-Agent", MOBILE_UA)
            }
            conn.connect()
            val finalUrl = conn.url?.toString() ?: urlString
            val contentType = (conn.contentType ?: "").lowercase()
            val headers = mapOf("User-Agent" to MOBILE_UA)

            if (contentType.startsWith("video/") ||
                contentType.startsWith("audio/") ||
                contentType.contains("octet-stream") ||
                contentType.contains("mp4") ||
                contentType.contains("webm")
            ) {
                return Result.success(
                    ResolvedMediaStream(
                        primaryUrl = finalUrl,
                        requestHeaders = headers,
                        sourcePlatform = "Direct",
                        extractAudioFromVideo = preferAudio && contentType.startsWith("video/")
                    )
                )
            }

            if (contentType.contains("text/html") || contentType.contains("application/xhtml")) {
                val html = readLimitedText(conn, maxChars = 1_500_000)
                val extracted = extractDirectMediaFromHtml(html)
                if (extracted != null) {
                    val title = extractHtmlTitleOrIgAuthor(html)
                    return Result.success(
                        ResolvedMediaStream(
                            primaryUrl = extracted,
                            title = title,
                            requestHeaders = headers,
                            sourcePlatform = "Web",
                            extractAudioFromVideo = preferAudio && extracted.substringBefore('?').lowercase().endsWith(".mp4")
                        )
                    )
                }
                return Result.failure(
                    IllegalArgumentException(
                        "No playable video or audio stream was found on this webpage."
                    )
                )
            }

            val pathLower = finalUrl.substringBefore('?').lowercase()
            if (listOf(".mp4", ".m4a", ".mp3", ".webm", ".mkv", ".wav", ".aac", ".ogg", ".flac").any { pathLower.endsWith(it) }) {
                Result.success(
                    ResolvedMediaStream(
                        primaryUrl = finalUrl,
                        requestHeaders = headers,
                        sourcePlatform = "Direct",
                        extractAudioFromVideo = preferAudio && pathLower.endsWith(".mp4")
                    )
                )
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
        val igMatch = extractInstagramVideoFromText(html)
        if (igMatch != null) return igMatch

        val patterns = listOf(
            Regex("""<meta[^>]+name=["']twitter:player:stream["'][^>]+content=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<(?:video|audio|source)[^>]+src=["'](https?://[^"']+\.(?:mp4|m4a|mp3|webm|ogg)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex(""""contentUrl"\s*:\s*"(https?://[^"]+\.(?:mp4|m4a|mp3|webm)[^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""(https?://[^\s"'<>\\]+\.(?:mp4|m4a|mp3|webm)(?:\?[^\s"'<>\\]*)?)""", RegexOption.IGNORE_CASE)
        )
        for (regex in patterns) {
            val match = regex.find(html)?.groupValues?.getOrNull(1)?.let(::unescapeUrl)
            if (!match.isNullOrBlank()) return match
        }
        return null
    }

    private data class YtFormatCandidate(
        val itag: Int,
        val url: String,
        val mimeType: String,
        val height: Int,
        val bitrate: Int
    )

    private fun extractYtFormats(array: JSONArray?): List<YtFormatCandidate> {
        if (array == null) return emptyList()
        val list = ArrayList<YtFormatCandidate>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val directUrl = obj.optString("url")
            if (directUrl.isBlank() || !directUrl.startsWith("http")) continue
            list += YtFormatCandidate(
                itag = obj.optInt("itag", 0),
                url = directUrl,
                mimeType = obj.optString("mimeType", "").lowercase(),
                height = obj.optInt("height", 0),
                bitrate = obj.optInt("bitrate", obj.optInt("averageBitrate", 0))
            )
        }
        return list
    }

    private data class InnerTubeClientConfig(
        val clientName: String,
        val clientVersion: String,
        val clientIdHeader: String,
        val userAgent: String,
        val extraClientFields: Map<String, Any> = emptyMap(),
        val embedThirdPartyUrl: String? = null
    )

    private fun findBestVideoUrlInJson(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val versions = node.optJSONArray("video_versions")
                if (versions != null && versions.length() > 0) {
                    var bestUrl: String? = null
                    var bestPixels = -1
                    for (i in 0 until versions.length()) {
                        val item = versions.optJSONObject(i) ?: continue
                        val url = item.optString("url")
                        if (url.startsWith("http")) {
                            val px = item.optInt("width", 0) * item.optInt("height", 0)
                            if (px >= bestPixels) {
                                bestPixels = px
                                bestUrl = url
                            }
                        }
                    }
                    if (bestUrl != null) return bestUrl
                }
                for (key in listOf("playable_url_quality_hd", "video_url", "playable_url")) {
                    val v = node.optString(key)
                    if (v.startsWith("http")) return v
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val found = findBestVideoUrlInJson(node.opt(k))
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findBestVideoUrlInJson(node.opt(i))
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun findFirstStringByKeys(node: Any?, targetKeys: Set<String>): String? {
        when (node) {
            is JSONObject -> {
                for (key in targetKeys) {
                    val v = node.optString(key)
                    if (v.isNotBlank() && !v.equals("null", true)) return v
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findFirstStringByKeys(node.opt(keys.next()), targetKeys)
                    if (found != null) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findFirstStringByKeys(node.opt(i), targetKeys)
                    if (found != null) return found
                }
            }
        }
        return null
    }

    private fun followRedirectsOnly(urlString: String): String? {
        var conn: HttpURLConnection? = null
        return runCatching {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 7000
                readTimeout = 7000
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", BROWSER_UA)
            }
            conn.connect()
            conn.url?.toString()
        }.getOrNull().also {
            runCatching { conn?.disconnect() }
        }
    }

    private fun httpGetText(
        urlString: String,
        headers: Map<String, String>,
        timeoutMs: Int = 10000
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                readLimitedText(conn, maxChars = 1_800_000)
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText().take(8000) }.orEmpty()
            }
            code to body
        } catch (_: Throwable) {
            -1 to ""
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun httpPostJson(
        urlString: String,
        jsonBody: String,
        headers: Map<String, String>
    ): Pair<Int, String> = httpPost(urlString, jsonBody, "application/json; charset=utf-8", headers)

    private fun httpPostForm(
        urlString: String,
        formBody: String,
        headers: Map<String, String>
    ): Pair<Int, String> = httpPost(urlString, formBody, "application/x-www-form-urlencoded", headers)

    private fun httpPost(
        urlString: String,
        body: String,
        contentType: String,
        headers: Map<String, String>
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10000
                readTimeout = 12000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Content-Length", bytes.size.toString())
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            val resp = if (code in 200..299) {
                readLimitedText(conn, maxChars = 1_800_000)
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText().take(8000) }.orEmpty()
            }
            code to resp
        } catch (_: Throwable) {
            -1 to ""
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun readLimitedText(conn: HttpURLConnection, maxChars: Int): String {
        val sb = StringBuilder()
        InputStreamReader(conn.inputStream, Charsets.UTF_8).use { reader ->
            val buf = CharArray(16 * 1024)
            while (sb.length < maxChars) {
                val read = reader.read(buf)
                if (read <= 0) break
                val take = minOf(read, maxChars - sb.length)
                sb.appendRange(buf, 0, take)
            }
        }
        return sb.toString()
    }

    private fun unescapeUrl(url: String): String {
        return url
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .trim()
    }

    private fun unescapeHtmlEntities(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
