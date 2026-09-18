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
        Thread {
            try {
                val o = JSONObject(
                    Python.getInstance().getModule("gsk")
                        .callAttr("search", query, limit).toString()
                )
                if (o.has("error")) {
                    cb(Result.failure(Exception(o.optString("error"))))
                    return@Thread
                }
                cb(Result.success(parseItems(o.optJSONArray("results"))))
            } catch (e: Exception) {
                cb(Result.failure(e))
            }
        }.start()
    }

    fun extract(pageUrl: String, cookiePath: String, cb: (Result<WatchData>) -> Unit) {
        Thread {
            try {
                val o = JSONObject(
                    Python.getInstance().getModule("gsk")
                        .callAttr("extract", pageUrl, cookiePath).toString()
                )
                if (o.has("error")) {
                    cb(Result.failure(Exception(o.optString("error"))))
                    return@Thread
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
                    cb(Result.failure(Exception("Is link ka playable stream nahi mila.")))
                    return@Thread
                }
                cb(Result.success(WatchData(
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
                cb(Result.failure(e))
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
