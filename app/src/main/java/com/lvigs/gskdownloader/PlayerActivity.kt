package com.lvigs.gskdownloader

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ad-Free Player (v1.9): sidebar se khulta full player.
 * yt-dlp ke DIRECT stream URL bajte hain — YouTube ads player me aate hain,
 * direct stream me nahi. Isliye playback 100% ad-free hai.
 * Up-Next queue auto-play hoti hai (ek khatm -> agla).
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE_URL = "page_url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_STREAM = "stream_url"
        const val EXTRA_AUDIO = "audio_url"
        const val EXTRA_HAS_AUDIO = "has_audio"
        const val EXTRA_THUMB = "thumb"
        const val EXTRA_QUEUE = "queue_json" // [{"t":title,"u":pageUrl}]
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var playerView: PlayerView
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var urlInput: EditText
    private lateinit var goBtn: Button
    private lateinit var queueTitle: TextView
    private lateinit var queueList: LinearLayout

    private var exo: ExoPlayer? = null
    private var cookiePath: String = ""
    private var pyReady = false

    private data class QItem(val title: String, val pageUrl: String)
    private var queue: List<QItem> = emptyList()
    private var qIndex: Int = -1
    private var loadingBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        titleText = findViewById(R.id.playerTitle)
        statusText = findViewById(R.id.playerStatus)
        urlInput = findViewById(R.id.playerUrlInput)
        goBtn = findViewById(R.id.playerGoBtn)
        queueTitle = findViewById(R.id.playerQueueTitle)
        queueList = findViewById(R.id.playerQueueList)

        findViewById<Button>(R.id.playerBackBtn).setOnClickListener { finish() }
        goBtn.setOnClickListener { playFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { playFromInput(); true } else false
        }

        cookiePath = findCookies()

        // Engine background me ready karo
        goBtn.isEnabled = false
        status("Engine taiyaar ho raha hai...")
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                Python.getInstance().getModule("gsk")
                pyReady = true
                runOnUiThread {
                    goBtn.isEnabled = true
                    // MainActivity se aaya data ho to seedha bajao
                    val stream = intent.getStringExtra(EXTRA_STREAM).orEmpty()
                    val page = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
                    parseQueue(intent.getStringExtra(EXTRA_QUEUE).orEmpty())
                    if (stream.isNotEmpty()) {
                        playStream(
                            intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "Video" },
                            stream,
                            intent.getStringExtra(EXTRA_AUDIO).orEmpty(),
                            intent.getBooleanExtra(EXTRA_HAS_AUDIO, false),
                        )
                        // current video queue me sabse upar ho to index set karo
                        if (qIndex < 0 && queue.isNotEmpty()) qIndex = 0
                        renderQueue()
                    } else if (page.isNotEmpty()) {
                        urlInput.setText(page)
                        extractAndPlay(page, true)
                    } else {
                        status("Link paste karo aur ▶ Play dabao — ads nahi aayenge.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status("Engine error: ${e.message}") }
            }
        }.start()
    }

    private fun playFromInput() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) { toast("Pehle YouTube link paste karo!"); return }
        if (!url.startsWith("http")) { toast("URL http(s):// se shuru hona chahiye."); return }
        queue = emptyList()
        qIndex = -1
        renderQueue()
        extractAndPlay(url, true)
    }

    /** Queue JSON parse karo: [{"t":title,"u":pageUrl}] */
    private fun parseQueue(json: String) {
        try {
            if (json.isEmpty()) return
            val arr = JSONArray(json)
            val out = ArrayList<QItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u")
                if (u.isNotEmpty()) out.add(QItem(o.optString("t", "Video"), u))
            }
            queue = out
        } catch (_: Exception) { queue = emptyList() }
    }

    private fun renderQueue() {
        try {
            queueList.removeAllViews()
            if (queue.isEmpty()) {
                queueTitle.visibility = View.GONE
                return
            }
            queueTitle.visibility = View.VISIBLE
            queueTitle.text = "Up Next (auto-play) • ${queue.size}"
            for (i in queue.indices) {
                val item = queue[i]
                val tv = TextView(this)
                val marker = if (i == qIndex) "▶ " else "${i + 1}. "
                tv.text = marker + item.title.take(60)
                tv.setTextColor(if (i == qIndex) 0xFF22D3EE.toInt() else 0xFFE9EEFB.toInt())
                tv.textSize = 13f
                tv.setPadding(8, 10, 8, 10)
                tv.setOnClickListener { playQueueItem(i) }
                queueList.addView(tv)
            }
        } catch (_: Exception) {}
    }

    private fun playQueueItem(i: Int) {
        if (i < 0 || i >= queue.size || loadingBusy) return
        qIndex = i
        renderQueue()
        urlInput.setText(queue[i].pageUrl)
        extractAndPlay(queue[i].pageUrl, false)
    }

    /** Page URL se direct stream nikalo (phone par yt-dlp), phir bajao. */
    private fun extractAndPlay(pageUrl: String, freshQueue: Boolean) {
        if (!pyReady) { toast("Engine taiyaar ho raha hai, ruk jao."); return }
        if (loadingBusy) return
        loadingBusy = true
        goBtn.isEnabled = false
        status("Ad-free link nikal rahe hain...")
        Thread {
            try {
                val json = Python.getInstance().getModule("gsk")
                    .callAttr("extract", pageUrl, cookiePath).toString()
                val o = JSONObject(json)
                if (o.has("error")) {
                    status("Error: ${o.optString("error").take(120)}")
                    return@Thread
                }
                var stream = o.optString("preview_url", "")
                var hasAudio = o.optBoolean("preview_has_audio", false)
                val title = o.optString("title", "Video").ifEmpty { "Video" }
                // preview_url khali ho to sabse chhota progressive stream uthao
                var audioUrl = ""
                try {
                    audioUrl = o.optJSONObject("best_audio_mp4")?.optString("url", "").orEmpty()
                    if (audioUrl.isEmpty()) audioUrl = o.optJSONObject("best_audio")?.optString("url", "").orEmpty()
                    if (stream.isEmpty()) {
                        val arr = o.getJSONArray("formats")
                        var best: JSONObject? = null
                        for (i in 0 until arr.length()) {
                            val f = arr.getJSONObject(i)
                            if (f.optString("type") != "video" || f.optString("url", "").isEmpty()) continue
                            if (f.optBoolean("progressive")) { best = f; break }
                            if (best == null) best = f
                        }
                        if (best != null) {
                            stream = best.optString("url", "")
                            hasAudio = best.optBoolean("progressive")
                        }
                    }
                } catch (_: Exception) {}
                if (stream.isEmpty()) {
                    status("Is link ka playable stream nahi mila.")
                    return@Thread
                }
                // Playlist mili ho aur queue nayi ho to queue banao (auto-play ke liye)
                if (freshQueue) {
                    try {
                        val plArr = o.optJSONArray("playlist")
                        if (plArr != null && plArr.length() > 0) {
                            val out = ArrayList<QItem>()
                            out.add(QItem(title, o.optString("webpage_url", pageUrl).ifEmpty { pageUrl }))
                            for (i in 0 until plArr.length()) {
                                val pe = plArr.getJSONObject(i)
                                val u = pe.optString("url")
                                if (u.isNotEmpty() && out.none { it.pageUrl == u }) {
                                    out.add(QItem(pe.optString("title", "Video"), u))
                                }
                            }
                            queue = out
                            qIndex = 0
                        }
                    } catch (_: Exception) {}
                }
                val s = stream
                val a = audioUrl
                val ha = hasAudio
                val t = title
                runOnUiThread {
                    playStream(t, s, a, ha)
                    renderQueue()
                }
            } catch (e: Exception) {
                status("Error: ${e.message?.take(120)}")
            } finally {
                loadingBusy = false
                runOnUiThread { goBtn.isEnabled = true }
            }
        }.start()
    }

    /** Direct stream bajao — isme ads hote hi nahi (ad-free by design). */
    private fun playStream(title: String, streamUrl: String, audioUrl: String, hasAudio: Boolean) {
        try {
            releasePlayer()
            titleText.text = title
            val dsFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)
            val videoSrc = ProgressiveMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(bypass(streamUrl)))
            val source = if (hasAudio || audioUrl.isEmpty()) {
                videoSrc
            } else {
                val audioSrc = ProgressiveMediaSource.Factory(dsFactory)
                    .createMediaSource(MediaItem.fromUri(bypass(audioUrl)))
                MergingMediaSource(videoSrc, audioSrc)
            }
            val player = ExoPlayer.Builder(this).build()
            exo = player
            playerView.player = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        // khatm -> agla auto-play (ad-free marathon)
                        val next = qIndex + 1
                        if (next < queue.size) {
                            toast("Agla video: ${queue[next].title.take(30)}...")
                            playQueueItem(next)
                        } else {
                            status("Khatm! 🚫 Poora video zero ads ke saath.")
                        }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    status("Play error: ${error.message?.take(100)} — dobara Play dabao.")
                }
            })
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
            status("🚫 Ad-Free chal raha hai...")
        } catch (e: Exception) {
            status("Play nahi ho paya: ${e.message?.take(100)}")
        }
    }

    private fun bypass(url: String): String {
        if (!url.contains("googlevideo.com")) return url
        if (url.contains("ratebypass=")) return url
        return url + (if (url.contains("?")) "&" else "?") + "ratebypass=yes"
    }

    private fun findCookies(): String {
        return try {
            val ext = File(getExternalFilesDir(null), "cookies.txt")
            val internal = File(filesDir, "cookies.txt")
            when {
                ext.exists() && ext.length() > 2 -> ext.absolutePath
                internal.exists() && internal.length() > 2 -> internal.absolutePath
                else -> ""
            }
        } catch (_: Exception) { "" }
    }

    private fun status(s: String) {
        runOnUiThread { statusText.text = s }
    }

    private fun toast(s: String) {
        runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }
    }

    private fun releasePlayer() {
        try { exo?.stop() } catch (_: Exception) {}
        try { exo?.release() } catch (_: Exception) {}
        exo = null
        try { playerView.player = null } catch (_: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { exo?.playWhenReady = false } catch (_: Exception) {}
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
