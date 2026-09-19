package com.lvigs.gskdownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.WebView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * ADDITIVE standalone helper — existing PlayerActivity/Layouts untouched.
 *
 * Seedha phone ke network (user ka original IP — Jio/Airtel/Wi-Fi) se YouTube
 * watch-page HTML uthakar `ytInitialPlayerResponse` parse karta hai aur DIRECT
 * stream URLs (pre-signed, signature-decrypt ki zaroorat nahi) nikalta hai.
 * Koi backend/VPS/cookies nahi.
 *
 * Flow: [extract] (Dispatchers.IO) -> PlayerData -> [playOn] (ExoPlayer) ya
 * [startDownload] (DownloadManager). Bot-block mile to [BotBlockedException]
 * aata hai — tab [extractViaWebView] fallback use karo.
 */
object DirectPlayerExtractor {

    /** Bot-detection spoof ke liye high-end device User-Agents (har request par rotate). */
    private val AGENTS = arrayOf(
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 12; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 13; ONEPLUS A10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
    )

    fun randomAgent(): String = AGENTS[Random.nextInt(AGENTS.size)]

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    class BotBlockedException(msg: String) : Exception(msg)
    class NoStreamException(msg: String) : Exception(msg)

    data class StreamOption(
        val url: String,
        val quality: String,
        val mimeType: String,
        val itag: Int,
        val height: Int,
        val isAudio: Boolean,
    )

    data class PlayerData(
        val title: String,
        val videoStreams: List<StreamOption>,
        val audioStreams: List<StreamOption>,
    ) {
        /** Best audio: itag 140 (m4a) > 251 (webm) > highest bitrate. */
        fun bestAudio(): StreamOption? {
            val byItag = audioStreams.sortedBy {
                when (it.itag) { 140 -> 0; 251 -> 1; else -> 2 }
            }
            return byItag.firstOrNull()
        }

        /** Best progressive (video+audio single file) ya highest video. */
        fun bestVideo(): StreamOption? =
            videoStreams.filter { !it.isAudio }.maxByOrNull { it.height }
    }

    /** 1) Watch-page HTML fetch (background thread, rotating UA). */
    suspend fun fetchPlayerHtml(videoId: String): String = withContext(Dispatchers.IO) {
        val id = videoId.trim().substringAfterLast("v=").substringBefore("&")
            .substringAfterLast("/").substringBefore("?")
        val req = Request.Builder()
            .url("https://www.youtube.com/watch?v=$id")
            .header("User-Agent", randomAgent())
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { resp ->
            val html = resp.body?.string().orEmpty()
            if (html.contains("Sign in to confirm", ignoreCase = true)) {
                throw BotBlockedException(
                    "YouTube ne is IP par bot-check lagaya hai. WebView fallback use karo.")
            }
            if (html.isEmpty()) throw NoStreamException("Khali response aaya.")
            return@withContext html
        }
    }

    /** 2) HTML se ytInitialPlayerResponse JSON nikalo (brace-balanced, split()-fragile nahi).
     *  NOTE: page me pehle `= null;` stub bhi hota hai — sare occurrences try karo,
     *  pehla VALID (playabilityStatus wala) JSON lo. */
    fun extractPlayerJson(html: String): String {
        for (marker in arrayOf("ytInitialPlayerResponse = ", "var ytInitialPlayerResponse = ")) {
            var from = 0
            while (true) {
                val mi = html.indexOf(marker, from)
                if (mi < 0) break
                from = mi + marker.length
                // 'null'/';' stub skip — asli data '{' se shuru hota hai.
                var p = from
                while (p < html.length && html[p].isWhitespace()) p++
                if (p >= html.length || html[p] != '{') continue
                val start = p
                var depth = 0
                var inStr = false
                var esc = false
                var end = -1
                for (i in start until html.length) {
                    val c = html[i]
                    if (inStr) {
                        if (esc) esc = false
                        else if (c == '\\') esc = true
                        else if (c == '"') inStr = false
                    } else {
                        when (c) {
                            '"' -> inStr = true
                            '{' -> depth++
                            '}' -> {
                                depth--
                                if (depth == 0) { end = i + 1; break }
                            }
                        }
                    }
                }
                if (end > start) {
                    val cand = html.substring(start, end)
                    // Valid playerResponse me playabilityStatus hota hai.
                    if (cand.contains("\"playabilityStatus\"")) return cand
                }
                if (end <= start) break
            }
        }
        throw NoStreamException("playerResponse HTML me nahi mila.")
    }

    /** 3) streamingData -> direct URLs (formats + adaptiveFormats, signatureCipher skip). */
    fun parse(jsonStr: String): PlayerData {
        val root = JSONObject(jsonStr)
        val sd = root.optJSONObject("streamingData")
            ?: throw NoStreamException("streamingData missing (bot-block ho sakta hai).")
        val videos = mutableListOf<StreamOption>()
        val audios = mutableListOf<StreamOption>()
        // Progressive formats (360p/720p/1080p single-file, pre-signed URL).
        collectFormats(sd.optJSONArray("formats"), videos, audios)
        // DASH adaptive (high quality video + itag 140/251 audio).
        collectFormats(sd.optJSONArray("adaptiveFormats"), videos, audios)
        if (videos.isEmpty() && audios.isEmpty()) {
            val status = root.optJSONObject("playabilityStatus")
            val reason = status?.optString("reason").orEmpty()
            throw NoStreamException(
                "Koi playable URL nahi. ${if (reason.isNotEmpty()) "Reason: $reason" else ""}")
        }
        val title = root.optJSONObject("videoDetails")?.optString("title").orEmpty()
        return PlayerData(
            title = title,
            videoStreams = videos.sortedByDescending { it.height },
            audioStreams = audios.sortedByDescending { it.itag == 140 },
        )
    }

    private fun collectFormats(
        arr: JSONArray?, videos: MutableList<StreamOption>, audios: MutableList<StreamOption>,
    ) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            // signatureCipher wale (decrypt-mangta) skip — sirf pre-signed direct URL.
            val url = f.optString("url").trim()
            if (url.isEmpty()) continue
            val mime = f.optString("mimeType")
            val isAudio = mime.contains("audio", ignoreCase = true)
            val opt = StreamOption(
                url = url,
                quality = f.optString("qualityLabel").ifEmpty {
                    if (isAudio) "audio" else "${f.optInt("height")}p"
                },
                mimeType = mime.substringBefore(";"),
                itag = f.optInt("itag"),
                height = f.optInt("height"),
                isAudio = isAudio,
            )
            if (isAudio) audios.add(opt) else videos.add(opt)
        }
    }

    /** Full pipeline: fetch -> extract -> parse (background). */
    suspend fun extract(videoIdOrUrl: String): PlayerData {
        val html = fetchPlayerHtml(videoIdOrUrl)
        return parse(extractPlayerJson(html))
    }

    /** 4) WebView fallback: page ek baar kholo, JS se playerResponse uthao. */
    fun extractViaWebView(
        webView: WebView, videoIdOrUrl: String, cb: (PlayerData?) -> Unit,
    ) {
        try {
            val id = videoIdOrUrl.trim().substringAfterLast("v=").substringBefore("&")
                .substringAfterLast("/").substringBefore("?")
            webView.settings.javaScriptEnabled = true
            webView.settings.userAgentString = randomAgent()
            webView.evaluateJavascript("JSON.stringify(window.ytInitialPlayerResponse)") { value ->
                try {
                    // evaluateJavascript quoted-JSON-string deta hai — JSONTokener unquote karta hai.
                    val json = org.json.JSONTokener(value ?: "").nextValue().toString()
                    cb(parse(json))
                } catch (_: Exception) {
                    try { cb(null) } catch (_: Exception) {}
                }
            }
            webView.loadUrl("https://www.youtube.com/watch?v=$id")
        } catch (_: Exception) {
            try { cb(null) } catch (_: Exception) {}
        }
    }

    /** 5) Direct DownloadManager download (notification + background). */
    fun startDownload(context: Context, directUrl: String, fileName: String): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(directUrl))
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        try { req.allowScanningByMediaScanner() } catch (_: Exception) {}
        req.addRequestHeader("User-Agent", randomAgent())
        req.addRequestHeader("Referer", "https://www.youtube.com/")
        return dm.enqueue(req)
    }

    /** 6) ExoPlayer (Media3) me direct play. */
    fun playOn(player: ExoPlayer, directUrl: String, playWhenReady: Boolean = true) {
        player.setMediaItem(MediaItem.fromUri(directUrl))
        player.prepare()
        player.playWhenReady = playWhenReady
    }
}
