package com.lvigs.gskdownloader

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
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
}
