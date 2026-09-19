package com.lvigs.gskdownloader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * Standalone client-side YouTube stream extractor (ADDITIVE — existing code untouched).
 *
 * - Device ke native network (mobile data/Wi-Fi, user ka original IP) se DIRECT
 *   YouTube endpoints hit karta hai. Koi backend/VPS/cookies nahi.
 * - Background me chalao: [extract] khud Dispatchers.IO par execute hota hai.
 * - Use: PlayerActivity se `ClientStreamExtractor.extract(url)` call karo aur
 *   mile hue direct URL ko PlayerView/ExoPlayer ya DownloadManager me pass karo.
 *
 * Pehle ek baar [init] call karna zaroori hai (Application ya Activity onCreate se).
 */
object ClientStreamExtractor {

    data class VideoStream(
        val url: String,
        val label: String,
        val height: Int,
        val width: Int,
        val mimeType: String,
        val videoOnly: Boolean,
    )

    data class AudioStream(
        val url: String,
        val label: String,
        val bitrateKbps: Int,
        val mimeType: String,
    )

    data class Result(
        val title: String,
        val author: String,
        val durationSec: Long,
        val thumbnailUrl: String?,
        val videoStreams: List<VideoStream>,
        val audioStreams: List<AudioStream>,
    )

    @Volatile private var ready = false

    /**
     * Idempotent init — dobara call safe hai. Ek baar NewPipe ko OkHttp-based
     * downloader de deta hai jo phone ke network se direct request karta hai.
     */
    @Synchronized
    fun init(appContext: Context) {
        if (ready) return
        appContext.applicationContext // hold app context, leak-safe (no field kept)
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        NewPipe.init(NpOkHttpDownloader(client))
        ready = true
    }

    /**
     * YouTube video URL ya video-ID se direct stream URLs nikalo.
     * Hamesha background thread (Dispatchers.IO) par chalta hai.
     *
     * @throws Exception network/extract fail par (caller try/catch kare).
     */
    suspend fun extract(urlOrId: String): Result = withContext(Dispatchers.IO) {
        check(ready) { "ClientStreamExtractor.init(context) pehle call karo." }
        val pageUrl = toPageUrl(urlOrId)
        val info: StreamInfo = StreamInfo.getInfo(pageUrl)

        val videos = info.videoStreams.mapNotNull { s ->
            val u = s.url ?: return@mapNotNull null
            VideoStream(
                url = u,
                label = s.resolution ?: "${s.height}p",
                height = s.height,
                width = s.width,
                mimeType = runCatching { s.format.toString() }.getOrDefault(""),
                videoOnly = s.isVideoOnly,
            )
        }.sortedByDescending { it.height }

        val audios = info.audioStreams.mapNotNull { s ->
            val u = s.url ?: return@mapNotNull null
            val br = s.averageBitrate
            AudioStream(
                url = u,
                label = if (br > 0) "Audio ${br}kbps" else "Audio",
                bitrateKbps = br,
                mimeType = runCatching { s.format.toString() }.getOrDefault(""),
            )
        }.sortedByDescending { it.bitrateKbps }

        Result(
            title = info.name,
            author = info.uploaderName,
            durationSec = info.duration,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            videoStreams = videos,
            audioStreams = audios,
        )
    }

    /** Video-ID mile to watch-URL banao, warna input ko trim karke wapas do. */
    fun toPageUrl(urlOrId: String): String {
        val t = urlOrId.trim()
        return if (t.startsWith("http", ignoreCase = true)) t
        else "https://www.youtube.com/watch?v=$t"
    }

    /**
     * NewPipeExtractor ka Downloader: pure OkHttp par, device network se direct.
     * GET/POST/HEAD + headers + body sab forward hote hain (YouTube player API
     * POST maangta hai — sirf-GET wrapper yahin fail hota).
     */
    private class NpOkHttpDownloader(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: Request): Response {
            val builder = okhttp3.Request.Builder().url(request.url())
            try {
                for ((k, vs) in request.headers()) {
                    for (v in vs) builder.addHeader(k, v)
                }
            } catch (_: Exception) {}
            val body = request.dataToSend()
            when (request.httpMethod()) {
                "POST" -> builder.post(
                    body?.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                        ?: ByteArray(0).toRequestBody(null)
                )
                "HEAD" -> builder.head()
                else -> builder.get()
            }
            client.newCall(builder.build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                val headersMap: Map<String, List<String>> =
                    resp.headers.names().associateWith { n -> resp.headers.values(n) }
                return Response(
                    resp.code, resp.message, headersMap, respBody,
                    resp.request.url.toString()
                )
            }
        }
    }
}
