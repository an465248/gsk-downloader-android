package com.lvigs.gskdownloader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
 * Watch screen (v2.0, YouTube jaisa): search + ad-free play +
 * neeche related suggestions + quality pills + 1-tap download.
 * Direct stream bajta hai — ads aate hi nahi. Screen off par bhi
 * audio chalta rehta hai (background play).
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
        const val EXTRA_AUTODL = "autodl_url" // MainActivity ke liye (wahan handle hota hai)
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
    private lateinit var searchTitle: TextView
    private lateinit var searchList: LinearLayout
    private lateinit var qPillsRow: LinearLayout
    private lateinit var dlBtn: Button

    private var exo: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cookiePath: String = ""
    private var pyReady = false

    private data class QItem(val title: String, val pageUrl: String)
    private var queue: List<QItem> = emptyList()
    private var qIndex: Int = -1
    private var loadingBusy = false

    // quality pills: progressive (video+audio juda) streams — jo chal raha wahi save hoga
    private data class QOpt(val label: String, val url: String, val audioUrl: String, val hasAudio: Boolean)
    private var qOpts: List<QOpt> = emptyList()
    private var qSel: Int = -1
    private var curPageUrl: String = ""
    private var curTitle: String = "Video"

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
        searchTitle = findViewById(R.id.playerSearchTitle)
        searchList = findViewById(R.id.playerSearchList)
        qPillsRow = findViewById(R.id.playerQPills)
        dlBtn = findViewById(R.id.playerDlBtn)

        findViewById<Button>(R.id.playerBackBtn).setOnClickListener { finish() }
        goBtn.setOnClickListener { playFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { playFromInput(); true } else false
        }
        dlBtn.setOnClickListener { downloadCurrent() }

        cookiePath = findCookies()

        // Engine background me ready karo
        goBtn.isEnabled = false
        dlBtn.isEnabled = false
        status("Engine taiyaar ho raha hai...")
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                Python.getInstance().getModule("gsk")
                pyReady = true
                runOnUiThread {
                    goBtn.isEnabled = true
                    dlBtn.isEnabled = true
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
                        curPageUrl = page
                        curTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "Video" }
                        if (qIndex < 0 && queue.isNotEmpty()) qIndex = 0
                        // formats nahi aaye (MainActivity se) — pills ke liye page se nikalo
                        if (page.isNotEmpty()) extractFormatsOnly(page)
                        renderQueue()
                    } else if (page.isNotEmpty()) {
                        urlInput.setText(page)
                        extractAndPlay(page, true)
                    } else {
                        status("Search karo ya link paste karo ▶ — ads nahi aayenge.")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status("Engine error: ${e.message}") }
            }
        }.start()
    }

    /** Input link ho to play, warna YouTube search. */
    private fun playFromInput() {
        val txt = urlInput.text.toString().trim()
        if (txt.isEmpty()) { toast("Search text ya link likho!"); return }
        if (txt.startsWith("http")) {
            clearSearch()
            queue = emptyList()
            qIndex = -1
            renderQueue()
            extractAndPlay(txt, true)
        } else {
            doSearch(txt)
        }
    }

    // ---------------- SEARCH (YouTube jaisa) ----------------
    private fun doSearch(query: String) {
        if (!pyReady) { toast("Engine taiyaar ho raha hai, ruk jao."); return }
        if (loadingBusy) return
        loadingBusy = true
        goBtn.isEnabled = false
        status("Search ho raha: $query ...")
        Thread {
            try {
                val json = Python.getInstance().getModule("gsk")
                    .callAttr("search", query, 12).toString()
                val o = JSONObject(json)
                if (o.has("error")) {
                    status("Search error: ${o.optString("error").take(120)}")
                    return@Thread
                }
                val arr = o.optJSONArray("results")
                val list = ArrayList<QItem>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val r = arr.getJSONObject(i)
                        val u = r.optString("url")
                        if (u.isNotEmpty()) list.add(QItem(r.optString("title", "Video"), u))
                    }
                }
                runOnUiThread { renderSearch(list) }
                status(if (list.isEmpty()) "Kuch nahi mila — aur likh ke try karo."
                    else "${list.size} results — tap karo, ad-free chalega.")
            } catch (e: Exception) {
                status("Search error: ${e.message?.take(100)}")
            } finally {
                loadingBusy = false
                runOnUiThread { goBtn.isEnabled = true }
            }
        }.start()
    }

    private fun renderSearch(list: List<QItem>) {
        try {
            searchList.removeAllViews()
            if (list.isEmpty()) {
                searchTitle.visibility = View.GONE
                return
            }
            searchTitle.visibility = View.VISIBLE
            searchTitle.text = "Search results (${list.size}) — tap karo ▶"
            for ((i, item) in list.withIndex()) {
                val tv = TextView(this)
                tv.text = "${i + 1}. ${item.title.take(65)}"
                tv.setTextColor(0xFFE9EEFB.toInt())
                tv.textSize = 13f
                tv.setPadding(8, 10, 8, 10)
                tv.setOnClickListener {
                    clearSearch()
                    queue = emptyList()
                    qIndex = -1
                    renderQueue()
                    urlInput.setText(item.pageUrl)
                    extractAndPlay(item.pageUrl, true)
                }
                searchList.addView(tv)
            }
        } catch (_: Exception) {}
    }

    private fun clearSearch() {
        try {
            searchList.removeAllViews()
            searchTitle.visibility = View.GONE
        } catch (_: Exception) {}
    }

    // ---------------- QUEUE (related suggestions neeche) ----------------
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
            queueTitle.text = "Related videos (${queue.size}) — tap karo ▶"
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

    // ---------------- EXTRACT + PLAY ----------------
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
                onExtractData(o, pageUrl, freshQueue)
            } catch (e: Exception) {
                status("Error: ${e.message?.take(120)}")
            } finally {
                loadingBusy = false
                runOnUiThread { goBtn.isEnabled = true }
            }
        }.start()
    }

    /** Pills ke liye formats dobara nikalo (playback stream wahi rehta hai). */
    private fun extractFormatsOnly(pageUrl: String) {
        Thread {
            try {
                val json = Python.getInstance().getModule("gsk")
                    .callAttr("extract", pageUrl, cookiePath).toString()
                val o = JSONObject(json)
                if (!o.has("error")) {
                    val opts = buildQOpts(o)
                    runOnUiThread { setQOpts(opts, -1) }
                }
            } catch (_: Exception) {}
        }.start()
    }

    /** Extract JSON se playback state set karo (UI thread par call karo). */
    private fun onExtractData(o: JSONObject, pageUrl: String, freshQueue: Boolean) {
        var stream = o.optString("preview_url", "")
        var hasAudio = o.optBoolean("preview_has_audio", false)
        val title = o.optString("title", "Video").ifEmpty { "Video" }
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
            return
        }
        // Related suggestions: queue khaali ho to playlist (related) se bharo
        var newQueue: List<QItem>? = null
        var newIdx = qIndex
        try {
            if (queue.isEmpty()) {
                val plArr = o.optJSONArray("playlist")
                if (plArr != null && plArr.length() > 0) {
                    val out = ArrayList<QItem>()
                    val cur = o.optString("webpage_url", pageUrl).ifEmpty { pageUrl }
                    out.add(QItem(title, cur))
                    for (i in 0 until plArr.length()) {
                        val pe = plArr.getJSONObject(i)
                        val u = pe.optString("url")
                        if (u.isNotEmpty() && out.none { it.pageUrl == u }) {
                            out.add(QItem(pe.optString("title", "Video"), u))
                        }
                    }
                    newQueue = out
                    newIdx = 0
                }
            } else if (freshQueue) {
                // playlist link ho to queue refresh karo
                val plArr = o.optJSONArray("playlist")
                if (plArr != null && plArr.length() > 1) {
                    val out = ArrayList<QItem>()
                    for (i in 0 until plArr.length()) {
                        val pe = plArr.getJSONObject(i)
                        val u = pe.optString("url")
                        if (u.isNotEmpty()) out.add(QItem(pe.optString("title", "Video"), u))
                    }
                    if (out.size > 1) {
                        newQueue = out
                        newIdx = 0
                    }
                }
            }
        } catch (_: Exception) {}
        val opts = buildQOpts(o)
        val s = stream
        val a = audioUrl
        val ha = hasAudio
        val t = title
        runOnUiThread {
            curPageUrl = pageUrl
            curTitle = t
            if (newQueue != null) {
                queue = newQueue
                qIndex = newIdx
            }
            setQOpts(opts, 0)
            playStream(t, s, a, ha)
            renderQueue()
        }
    }

    // ---------------- QUALITY PILLS (jo chal raha wahi save hoga) ----------------
    /** Progressive (video+audio juda) streams me se pills banao. */
    private fun buildQOpts(o: JSONObject): List<QOpt> {
        val out = ArrayList<QOpt>()
        try {
            val arr = o.optJSONArray("formats") ?: return out
            data class P(val h: Int, val url: String)
            val prog = ArrayList<P>()
            var audioOnly = ""
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val u = f.optString("url", "")
                if (u.isEmpty()) continue
                if (f.optString("type") == "audio" && audioOnly.isEmpty()) {
                    audioOnly = u
                }
                if (f.optString("type") == "video" && f.optBoolean("progressive")) {
                    prog.add(P(f.optInt("height"), u))
                }
            }
            val heights = prog.map { it.h }.filter { it > 0 }.distinct().sortedDescending()
            if (heights.isNotEmpty()) {
                val best = prog.filter { it.h == heights[0] }.maxByOrNull { it.url.length }!!
                out.add(QOpt("BEST ${heights[0]}p", best.url, "", true))
                for (h in heights.drop(1).take(4)) {
                    val p = prog.filter { it.h == h }.maxByOrNull { it.url.length }!!
                    out.add(QOpt("${h}p", p.url, "", true))
                }
            }
            if (audioOnly.isNotEmpty()) out.add(QOpt("🎵 Audio", audioOnly, "", true))
        } catch (_: Exception) {}
        return out
    }

    private fun setQOpts(opts: List<QOpt>, sel: Int) {
        try {
            qOpts = opts
            qSel = sel
            qPillsRow.removeAllViews()
            if (opts.isEmpty()) return
            for (i in opts.indices) {
                val b = Button(this)
                b.text = opts[i].label
                b.textSize = 11f
                b.minWidth = 0
                b.minimumWidth = 0
                if (i == sel) {
                    b.setBackgroundResource(R.drawable.bg_btn)
                    b.setTextColor(0xFF04070F.toInt())
                } else {
                    b.setBackgroundResource(R.drawable.bg_card)
                    b.setTextColor(0xFFE9EEFB.toInt())
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 12, 0)
                b.layoutParams = lp
                b.setOnClickListener { switchQuality(i) }
                qPillsRow.addView(b)
            }
        } catch (_: Exception) {}
    }

    private fun switchQuality(i: Int) {
        if (i < 0 || i >= qOpts.size) return
        qSel = i
        setQOpts(qOpts, i)
        val q = qOpts[i]
        playStream(curTitle, q.url, q.audioUrl, q.hasAudio)
        toast("Quality: ${q.label} (download bhi yahi hoga)")
    }

    // ---------------- 1-TAP DOWNLOAD (jo chal raha hai) ----------------
    /** Jo stream chal raha hai use MainActivity se 1-tap download karwao. */
    private fun downloadCurrent() {
        val page = curPageUrl.ifEmpty { urlInput.text.toString().trim() }
        if (page.isEmpty() || !page.startsWith("http")) {
            toast("Pehle koi video chalao, phir Download dabao.")
            return
        }
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_AUTODL, page)
            }
            toast("Download start ho raha (best quality, audio ke saath)...")
            startActivity(i)
        } catch (e: Exception) { toast("Download nahi ho paya: ${e.message}") }
    }

    // ---------------- PLAYER (ad-free by design + background play) ----------------
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
                            toast("Agla: ${queue[next].title.take(30)}...")
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
            acquireWake()
            status("🚫 Ad-Free chal raha hai... (screen off par bhi bajega)")
        } catch (e: Exception) {
            status("Play nahi ho paya: ${e.message?.take(100)}")
        }
    }

    private fun bypass(url: String): String {
        if (!url.contains("googlevideo.com")) return url
        if (url.contains("ratebypass=")) return url
        return url + (if (url.contains("?")) "&" else "?") + "ratebypass=yes"
    }

    /** Screen-off background play ke liye CPU awake rakho. */
    private fun acquireWake() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GSK:PlayerWake")
                wakeLock?.setReferenceCounted(false)
            }
            if (wakeLock?.isHeld != true) wakeLock?.acquire(4 * 60 * 60 * 1000L)
        } catch (_: Exception) {}
    }

    private fun releaseWake() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
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
        releaseWake()
    }

    // NOTE: onPause me player rokna NAHI — screen off par background audio
    // chalta rahe (user wapas aaye to wahin se). Back dabane par onDestroy me band.

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}
