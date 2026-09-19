package com.lvigs.gskdownloader

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Watch-screen item: search result / related video. */
data class VideoItem(
    val id: String,
    val title: String,
    val url: String,
    val thumb: String,
    val durationSec: Long,
    val channel: String = "",
    val views: Long = 0,
)

/** Direct extraction result (phone-side, no server/Python). */
data class DirectWatchData(
    val title: String,
    val pageUrl: String,
    val thumb: String,
    val streamUrl: String,
    val audioUrl: String,
    val hasAudio: Boolean,
    val extractJson: JSONObject,
    val related: List<VideoItem> = emptyList(),
)

/** Extract ka parsed result: player + suggestions + pills ke liye. */
data class WatchData(
    val title: String,
    val pageUrl: String,
    val thumb: String,
    val streamUrl: String,
    val audioUrl: String,
    val hasAudio: Boolean,
    val extractJson: JSONObject,
    val related: List<VideoItem>,
)

/**
 * Data layer (Repository): saare yt-dlp calls ek jagah, UI se alag.
 * Background thread par chalta hai — callback me UI thread par aao.
 */
object WatchRepository {

    fun ensureEngine(ctx: Context): Boolean {
        return try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
            Python.getInstance().getModule("gsk")
            true
        } catch (_: Exception) { false }
    }

    fun search(query: String, limit: Int, cb: (Result<List<VideoItem>>) -> Unit) {
        // LIVE-FIX: search me bhi timeout — warna loadingBusy true atka rehta
        // aur uske baad Ad-free fetch "Ruko — video load ho raha" par atak jata.
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun once(r: Result<List<VideoItem>>) {
            if (done.compareAndSet(false, true)) {
                try { cb(r) } catch (_: Exception) {}
            }
        }
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val fut: java.util.concurrent.Future<*> = exec.submit {
            try {
                val o = JSONObject(
                    Python.getInstance().getModule("gsk")
                        .callAttr("search", query, limit).toString()
                )
                if (o.has("error")) {
                    once(Result.failure(Exception(o.optString("error"))))
                    return@submit
                }
                once(Result.success(parseItems(o.optJSONArray("results"))))
            } catch (e: Exception) {
                once(Result.failure(e))
            } finally {
                try { exec.shutdown() } catch (_: Exception) {}
            }
        }
        Thread {
            try {
                fut.get(45, java.util.concurrent.TimeUnit.SECONDS)
            } catch (te: java.util.concurrent.TimeoutException) {
                try { fut.cancel(true) } catch (_: Exception) {}
                try { exec.shutdownNow() } catch (_: Exception) {}
                once(Result.failure(Exception("Search me 45s lag gaya — net check karke dobara try karo.")))
            } catch (_: Exception) {}
        }.start()
    }

    /** Related/Up-Next (video chalne KE BAAD background fill ke liye).
     *  Fast-play path related skip karta hai — ye halka fetch list bharta hai.
     *  Playback/loadingBusy ko nahi chhoota; 25s timeout; fail-soft. */
    fun related(pageUrl: String, cookiePath: String, cb: (Result<List<VideoItem>>) -> Unit) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun once(r: Result<List<VideoItem>>) {
            if (done.compareAndSet(false, true)) {
                try { cb(r) } catch (_: Exception) {}
            }
        }
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val fut: java.util.concurrent.Future<*> = exec.submit {
            try {
                val o = JSONObject(
                    Python.getInstance().getModule("gsk")
                        .callAttr("related", pageUrl, cookiePath).toString()
                )
                if (o.has("error")) {
                    once(Result.failure(Exception(o.optString("error"))))
                    return@submit
                }
                once(Result.success(parseItems(o.optJSONArray("results"))))
            } catch (e: Exception) {
                once(Result.failure(e))
            } finally {
                try { exec.shutdown() } catch (_: Exception) {}
            }
        }
        Thread {
            try {
                fut.get(25, java.util.concurrent.TimeUnit.SECONDS)
            } catch (te: java.util.concurrent.TimeoutException) {
                try { fut.cancel(true) } catch (_: Exception) {}
                try { exec.shutdownNow() } catch (_: Exception) {}
                once(Result.failure(Exception("Related list time-out.")))
            } catch (_: Exception) {}
        }.start()
    }

    /** Comments preview (video chalne KE BAAD background fill, best-effort).
     *  20s timeout; fail-soft. Playback ko nahi chhoota. */
    data class Comment(val author: String, val text: String, val likes: Long)

    fun comments(pageUrl: String, cb: (Result<List<Comment>>) -> Unit) {
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun once(r: Result<List<Comment>>) {
            if (done.compareAndSet(false, true)) {
                try { cb(r) } catch (_: Exception) {}
            }
        }
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val fut: java.util.concurrent.Future<*> = exec.submit {
            try {
                val o = JSONObject(
                    Python.getInstance().getModule("gsk")
                        .callAttr("comments", pageUrl, 5).toString()
                )
                val out = ArrayList<Comment>()
                try {
                    val arr = o.optJSONArray("results")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            val t = c.optString("text")
                            if (t.isNotEmpty()) out.add(Comment(
                                c.optString("author", "User").ifEmpty { "User" },
                                t, c.optLong("likes"),
                            ))
                        }
                    }
                } catch (_: Exception) {}
                once(Result.success(out))
            } catch (e: Exception) {
                once(Result.failure(e))
            } finally {
                try { exec.shutdown() } catch (_: Exception) {}
            }
        }
        Thread {
            try {
                fut.get(20, java.util.concurrent.TimeUnit.SECONDS)
            } catch (te: java.util.concurrent.TimeoutException) {
                try { fut.cancel(true) } catch (_: Exception) {}
                try { exec.shutdownNow() } catch (_: Exception) {}
                once(Result.failure(Exception("Comments time-out.")))
            } catch (_: Exception) {}
        }.start()
    }

    fun extract(pageUrl: String, cookiePath: String, cb: (Result<WatchData>) -> Unit) {
        extractInternal(pageUrl, cookiePath, fast = 1, cb = cb)
    }

    /** fast=1: Watch (ad-free play) — 6-10s target, no related/probe.
     *  fast=0: poora (related + probe) — Download tab ke liye. */
    fun extractInternal(pageUrl: String, cookiePath: String, fast: Int,
                        cb: (Result<WatchData>) -> Unit) {
        // 30-MIN HANG FIX: pehle timeout nahi tha + probe-executor wait=True me
        // atka rehta tha. Ab fast-mode (related/probe skip) + 30s hard-timeout:
        // user ko 30s me jawab pakka — success ya dobara-dabao error.
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun once(r: Result<WatchData>) {
            if (done.compareAndSet(false, true)) {
                try { cb(r) } catch (_: Exception) {}
            }
        }
        val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
        val fut: java.util.concurrent.Future<*> = exec.submit {
            try {
                val o = JSONObject(
                    Python.getInstance().getModule("gsk")
                        .callAttr("extract", pageUrl, cookiePath, fast).toString()
                )
                if (o.has("error")) {
                    once(Result.failure(Exception(o.optString("error"))))
                    return@submit
                }
                var stream = o.optString("preview_url", "")
                var hasAudio = o.optBoolean("preview_has_audio", false)
                val title = o.optString("title", "Video").ifEmpty { "Video" }
                var audioUrl = ""
                try {
                    audioUrl = o.optJSONObject("best_audio_mp4")?.optString("url", "").orEmpty()
                    if (audioUrl.isEmpty()) {
                        audioUrl = o.optJSONObject("best_audio")?.optString("url", "").orEmpty()
                    }
                    if (stream.isEmpty()) {
                        val arr = o.getJSONArray("formats")
                        var best: JSONObject? = null
                        for (i in 0 until arr.length()) {
                            val f = arr.getJSONObject(i)
                            if (f.optString("type") != "video" || f.optString("url", "").isEmpty()) continue
                            if (f.optBoolean("progressive")) {
                                best = f
                                break
                            }
                            if (best == null) best = f
                        }
                        if (best != null) {
                            stream = best.optString("url", "")
                            hasAudio = best.optBoolean("progressive")
                        }
                    }
                } catch (_: Exception) {}
                if (stream.isEmpty()) {
                    once(Result.failure(Exception("Is link ka playable stream nahi mila.")))
                    return@submit
                }
                once(Result.success(WatchData(
                    title = title,
                    pageUrl = o.optString("webpage_url", pageUrl).ifEmpty { pageUrl },
                    thumb = o.optString("thumbnail", ""),
                    streamUrl = stream,
                    audioUrl = audioUrl,
                    hasAudio = hasAudio,
                    extractJson = o,
                    related = parseItems(o.optJSONArray("playlist")),
                )))
            } catch (e: Exception) {
                once(Result.failure(e))
            } finally {
                try { exec.shutdown() } catch (_: Exception) {}
            }
        }
        // Watchdog: 30s me Python jawab na de to timeout-error pakka.
        // (60s bahut lamba — user 30s me dobara try kare, wahi pasand karega.)
        Thread {
            try {
                fut.get(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (te: java.util.concurrent.TimeoutException) {
                try { fut.cancel(true) } catch (_: Exception) {}
                try { exec.shutdownNow() } catch (_: Exception) {}
                once(Result.failure(Exception("Site ne 30s me jawab nahi diya — net check karke dobara Play dabao.")))
            } catch (_: Exception) {
                // success / failure already `once()` se bhej di — kuch mat karo.
            }
        }.start()
    }

    /** playlist/related/search JSONArray -> VideoItem list. */
    fun parseItems(arr: org.json.JSONArray?): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        try {
            if (arr == null) return out
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val u = r.optString("url")
                if (u.isNotEmpty()) {
                    out.add(VideoItem(
                        r.optString("id"), r.optString("title", "Video"),
                        u, r.optString("thumbnail"), r.optLong("duration"),
                        r.optString("channel"), r.optLong("views"),
                    ))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** DIRECT phone-side extraction (no server, no Python, no cookies).
     *  Fetches YouTube watch page with spoofed Android Chrome UA, parses
     *  ytInitialPlayerResponse, returns direct pre-signed URLs.
     *  Returns Result.failure(BotBlockedException) if YouTube shows bot-check. */
    suspend fun extractDirect(pageUrl: String): Result<DirectWatchData> = withContext(Dispatchers.IO) {
        try {
            // Extract video ID
            val id = pageUrl.trim().substringAfterLast("v=").substringBefore("&")
                .substringAfterLast("/").substringBefore("?")
            // Direct extraction via DirectPlayerExtractor
            val playerData = DirectPlayerExtractor.extract(id)
            // Build extractJson compatible with existing format
            val json = buildExtractJson(playerData)
            return@withContext Result.success(DirectWatchData(
                title = playerData.title.ifEmpty { "Video" },
                pageUrl = pageUrl,
                thumb = "", // thumbnail not directly available from playerResponse
                streamUrl = playerData.bestVideo()?.url ?: "",
                audioUrl = playerData.bestAudio()?.url ?: "",
                hasAudio = playerData.bestVideo() != null, // progressive = has audio
                extractJson = json,
            ))
        } catch (e: DirectPlayerExtractor.BotBlockedException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Build extractJson from PlayerData (compatible with existing format). */
    private fun buildExtractJson(data: DirectPlayerExtractor.PlayerData): JSONObject {
        val o = JSONObject()
        try {
            o.put("title", data.title)
            o.put("webpage_url", "")
            o.put("thumbnail", "")
            val formats = JSONArray()
            // Progressive formats (video+audio)
            for (s in data.videoStreams) {
                val f = JSONObject()
                f.put("url", s.url)
                f.put("type", "video")
                f.put("height", s.height)
                f.put("qualityLabel", s.quality)
                f.put("mimeType", s.mimeType)
                f.put("itag", s.itag)
                f.put("progressive", !s.isAudio)
                f.put("ext", if (s.mimeType.contains("mp4")) "mp4" else "webm")
                f.put("vcodec", if (s.mimeType.contains("avc")) "avc1" else "vp9")
                formats.put(f)
            }
            // Audio-only
            for (s in data.audioStreams) {
                val f = JSONObject()
                f.put("url", s.url)
                f.put("type", "audio")
                f.put("qualityLabel", s.quality)
                f.put("mimeType", s.mimeType)
                f.put("itag", s.itag)
                f.put("ext", if (s.mimeType.contains("mp4")) "m4a" else "webm")
                formats.put(f)
            }
            o.put("formats", formats)
            // best_audio
            data.bestAudio()?.let { a ->
                val ba = JSONObject()
                ba.put("url", a.url)
                ba.put("ext", if (a.mimeType.contains("mp4")) "m4a" else "webm")
                o.put("best_audio", ba)
                if (a.mimeType.contains("mp4")) o.put("best_audio_mp4", ba)
            }
            // preview_url (first progressive or best video)
            val preview = data.videoStreams.firstOrNull { !it.isAudio }?.url
                ?: data.audioStreams.firstOrNull()?.url ?: ""
            o.put("preview_url", preview)
            o.put("preview_has_audio", data.videoStreams.firstOrNull { !it.isAudio } != null)
        } catch (_: Exception) {}
        return o
    }
}
