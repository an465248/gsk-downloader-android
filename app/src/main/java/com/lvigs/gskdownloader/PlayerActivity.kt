package com.lvigs.gskdownloader

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Watch screen (v2.1, YouTube jaisa): search + history + ad-free play +
 * related suggestions + quality pills + 1-tap Video/Audio download +
 * speed + fullscreen + PiP + background play. Strictly no ads.
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
        const val EXTRA_AUTODL = "autodl_url" // MainActivity ke liye
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }

    private lateinit var playerView: PlayerView
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerRoot: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var urlInput: EditText
    private lateinit var goBtn: Button
    private lateinit var queueTitle: TextView
    private lateinit var queueList: LinearLayout
    private lateinit var searchTitle: TextView
    private lateinit var searchList: LinearLayout
    private lateinit var moreBtn: Button
    private lateinit var histRow: LinearLayout
    private lateinit var qPillsRow: LinearLayout
    private lateinit var dlVideoBtn: Button
    private lateinit var dlAudioBtn: Button
    private lateinit var cancelDlBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var fsBtn: Button

    private var exo: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var cookiePath: String = ""
    private var pyReady = false

    private var queue: List<VideoItem> = emptyList()
    private var qIndex: Int = -1
    private var loadingBusy = false

    private data class QOpt(val label: String, val url: String, val audioUrl: String, val hasAudio: Boolean)
    private var qOpts: List<QOpt> = emptyList()
    private var qSel: Int = -1
    private var curData: WatchData? = null
    private var curPageUrl: String = ""
    private var curTitle: String = "Video"

    // search state
    private var lastQuery: String = ""
    private var searchLimit: Int = 12
    private var searchResults: List<VideoItem> = emptyList()

    // download UI state
    private var vPct: Int = 0
    private var aPct: Int = 0

    // player options
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private var speedIdx: Int = 2
    private var isFullscreen: Boolean = false
    private var isInPip: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        playerFrame = findViewById(R.id.playerFrame)
        playerRoot = findViewById(R.id.playerRoot)
        titleText = findViewById(R.id.playerTitle)
        statusText = findViewById(R.id.playerStatus)
        urlInput = findViewById(R.id.playerUrlInput)
        goBtn = findViewById(R.id.playerGoBtn)
        queueTitle = findViewById(R.id.playerQueueTitle)
        queueList = findViewById(R.id.playerQueueList)
        searchTitle = findViewById(R.id.playerSearchTitle)
        searchList = findViewById(R.id.playerSearchList)
        moreBtn = findViewById(R.id.playerMoreBtn)
        histRow = findViewById(R.id.playerHistRow)
        qPillsRow = findViewById(R.id.playerQPills)
        dlVideoBtn = findViewById(R.id.playerDlVideoBtn)
        dlAudioBtn = findViewById(R.id.playerDlAudioBtn)
        cancelDlBtn = findViewById(R.id.playerCancelDl)
        speedBtn = findViewById(R.id.playerSpeedBtn)
        fsBtn = findViewById(R.id.playerFsBtn)

        findViewById<Button>(R.id.playerBackBtn).setOnClickListener { finish() }
        goBtn.setOnClickListener { playFromInput() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { playFromInput(); true } else false
        }
        moreBtn.setOnClickListener {
            if (lastQuery.isNotEmpty() && searchLimit < 25) {
                searchLimit = 25
                doSearch(lastQuery)
            }
        }
        findViewById<Button>(R.id.playerHistClear).setOnClickListener {
            saveHistList(mutableListOf())
            renderHist()
            toast("Search history clear.")
        }
        speedBtn.setOnClickListener { showSpeedDialog() }
        fsBtn.setOnClickListener { toggleFullscreen() }
        dlVideoBtn.setOnClickListener { onDlVideo() }
        dlAudioBtn.setOnClickListener { onDlAudio() }
        cancelDlBtn.setOnClickListener {
            WatchDownloader.cancel("wv")
            WatchDownloader.cancel("wa")
            toast("Download cancel ho raha...")
        }

        cookiePath = findCookies()
        renderHist()

        // Engine background me ready karo (Repository layer)
        goBtn.isEnabled = false
        status("Engine taiyaar ho raha hai...")
        Thread {
            val ok = WatchRepository.ensureEngine(this)
            pyReady = ok
            runOnUiThread {
                if (!ok) {
                    status("Engine error — app restart karo.")
                    return@runOnUiThread
                }
                goBtn.isEnabled = true
                val stream = intent.getStringExtra(EXTRA_STREAM).orEmpty()
                val page = intent.getStringExtra(EXTRA_PAGE_URL).orEmpty()
                parseQueue(intent.getStringExtra(EXTRA_QUEUE).orEmpty())
                if (stream.isNotEmpty()) {
                    curTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifEmpty { "Video" }
                    curPageUrl = page
                    playStream(
                        curTitle, stream,
                        intent.getStringExtra(EXTRA_AUDIO).orEmpty(),
                        intent.getBooleanExtra(EXTRA_HAS_AUDIO, false),
                    )
                    if (qIndex < 0 && queue.isNotEmpty()) qIndex = 0
                    renderQueue()
                    // related + pills ke liye background extract
                    if (page.isNotEmpty()) {
                        WatchRepository.extract(page, cookiePath) { res ->
                            res.onSuccess { data ->
                                runOnUiThread {
                                    curData = data
                                    if (queue.isEmpty() && data.related.isNotEmpty()) {
                                        queue = listOf(VideoItem("", data.title, data.pageUrl, "", 0)) + data.related
                                        qIndex = 0
                                        renderQueue()
                                    }
                                    setQOpts(buildQOpts(data.extractJson), -1)
                                }
                            }
                        }
                    }
                } else if (page.isNotEmpty()) {
                    urlInput.setText(page)
                    extractAndPlay(page, true)
                } else {
                    status("Search karo ya link paste karo ▶ — ads nahi aayenge.")
                }
            }
        }.start()
    }

    /** Input link ho to play, warna YouTube search (+ history save). */
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
            searchLimit = 12
            saveHistory(txt)
            doSearch(txt)
        }
    }

    // ---------------- SEARCH HISTORY ----------------
    private fun prefs() = getSharedPreferences("gsk_watch", MODE_PRIVATE)

    private fun loadHist(): MutableList<String> {
        return try {
            val arr = JSONArray(prefs().getString("hist", "[]") ?: "[]")
            MutableList(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    private fun saveHistList(list: List<String>) {
        try {
            val arr = JSONArray()
            for (s in list.take(8)) arr.put(s)
            prefs().edit().putString("hist", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun saveHistory(q: String) {
        try {
            val cur = loadHist()
            cur.removeAll { it.equals(q, ignoreCase = true) }
            cur.add(0, q)
            saveHistList(cur)
            renderHist()
        } catch (_: Exception) {}
    }

    private fun renderHist() {
        try {
            histRow.removeAllViews()
            val list = loadHist()
            if (list.isEmpty()) {
                val tv = TextView(this)
                tv.text = "koi search nahi"
                tv.setTextColor(0xFF5B6A94.toInt())
                tv.textSize = 11f
                histRow.addView(tv)
                return
            }
            for (q in list) {
                val b = Button(this)
                b.text = q.take(16)
                b.textSize = 11f
                b.minWidth = 0
                b.minimumWidth = 0
                b.setBackgroundResource(R.drawable.bg_card)
                b.setTextColor(0xFF22D3EE.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 8, 0)
                b.layoutParams = lp
                b.setOnClickListener {
                    urlInput.setText(q)
                    searchLimit = 12
                    doSearch(q)
                }
                histRow.addView(b)
            }
        } catch (_: Exception) {}
    }

    // ---------------- SEARCH (results + pagination) ----------------
    private fun doSearch(query: String) {
        if (!pyReady) { toast("Engine taiyaar ho raha hai, ruk jao."); return }
        if (loadingBusy) return
        loadingBusy = true
        goBtn.isEnabled = false
        lastQuery = query
        status("Search ho raha: $query ...")
        WatchRepository.search(query, searchLimit) { res ->
            runOnUiThread {
                loadingBusy = false
                goBtn.isEnabled = true
                res.onSuccess { list ->
                    searchResults = list
                    renderSearch(list)
                    status(if (list.isEmpty()) "Kuch nahi mila — aur likh ke try karo."
                        else "${list.size} results — tap karo, ad-free chalega.")
                }.onFailure { e ->
                    status("Search error: ${(e.message ?: "").take(120)}")
                }
            }
        }
    }

    private fun renderSearch(list: List<VideoItem>) {
        try {
            searchList.removeAllViews()
            if (list.isEmpty()) {
                searchTitle.visibility = View.GONE
                moreBtn.visibility = View.GONE
                return
            }
            searchTitle.visibility = View.VISIBLE
            searchTitle.text = "Search results (${list.size}) — tap karo ▶"
            for ((i, item) in list.withIndex()) {
                addVideoRow(searchList, item, "${i + 1}. ", false) {
                    clearSearch()
                    queue = emptyList()
                    qIndex = -1
                    renderQueue()
                    urlInput.setText(item.url)
                    extractAndPlay(item.url, true)
                }
            }
            // pagination: 12 mile aur limit badh sakti ho to "aur dikhao"
            moreBtn.visibility =
                if (list.size >= searchLimit && searchLimit < 25) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    private fun clearSearch() {
        try {
            searchList.removeAllViews()
            searchTitle.visibility = View.GONE
            moreBtn.visibility = View.GONE
        } catch (_: Exception) {}
    }

    /** Thumbnail-less rich row: title + channel • views • duration. */
    private fun addVideoRow(
        parent: LinearLayout, item: VideoItem, marker: String,
        highlight: Boolean, onTap: () -> Unit,
    ) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(8, 10, 8, 10)
        val t = TextView(this)
        t.text = marker + item.title.take(65)
        t.setTextColor(if (highlight) 0xFF22D3EE.toInt() else 0xFFE9EEFB.toInt())
        t.textSize = 13f
        val m = TextView(this)
        val meta = listOf(
            item.channel.take(30),
            fmtViews(item.views),
            fmtDur(item.durationSec),
        ).filter { it.isNotEmpty() }.joinToString(" • ")
        m.text = meta.ifEmpty { item.url.take(40) }
        m.setTextColor(0xFF93A0C4.toInt())
        m.textSize = 11f
        box.addView(t)
        if (m.text.isNotEmpty()) box.addView(m)
        box.setOnClickListener { onTap() }
        parent.addView(box)
    }

    private fun fmtViews(v: Long): String = when {
        v >= 10000000 -> "%.1fCr views".format(v / 10000000.0)
        v >= 100000 -> "%.1fL views".format(v / 100000.0)
        v >= 1000 -> "%.1fK views".format(v / 1000.0)
        v > 0 -> "$v views"
        else -> ""
    }

    private fun fmtDur(s: Long): String {
        if (s <= 0) return ""
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
    }

    // ---------------- QUEUE (related, screen reload nahi) ----------------
    private fun parseQueue(json: String) {
        try {
            if (json.isEmpty()) return
            val arr = JSONArray(json)
            val out = ArrayList<VideoItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.optString("u")
                if (u.isNotEmpty()) out.add(VideoItem("", o.optString("t", "Video"), u, "", 0))
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
                addVideoRow(queueList, queue[i],
                    if (i == qIndex) "▶ " else "${i + 1}. ", i == qIndex) {
                    playQueueItem(i)
                }
            }
        } catch (_: Exception) {}
    }

    private fun playQueueItem(i: Int) {
        if (i < 0 || i >= queue.size || loadingBusy) return
        qIndex = i
        renderQueue()
        urlInput.setText(queue[i].url)
        // player reload nahi — wahi screen par naya stream lagao
        extractAndPlay(queue[i].url, false)
    }

    // ---------------- EXTRACT + PLAY ----------------
    private fun extractAndPlay(pageUrl: String, freshQueue: Boolean) {
        if (!pyReady) { toast("Engine taiyaar ho raha hai, ruk jao."); return }
        if (loadingBusy) return
        loadingBusy = true
        goBtn.isEnabled = false
        status("Ad-free link nikal rahe hain...")
        WatchRepository.extract(pageUrl, cookiePath) { res ->
            runOnUiThread {
                loadingBusy = false
                goBtn.isEnabled = true
                res.onSuccess { data -> onWatchData(data, freshQueue) }
                    .onFailure { e -> status("Error: ${(e.message ?: "").take(120)}") }
            }
        }
    }

    private fun onWatchData(data: WatchData, freshQueue: Boolean) {
        curData = data
        curPageUrl = data.pageUrl
        curTitle = data.title
        if (queue.isEmpty() && data.related.isNotEmpty()) {
            // related suggestions: current sabse upar
            queue = listOf(VideoItem("", data.title, data.pageUrl, data.thumb, 0)) + data.related
            qIndex = 0
        } else if (freshQueue && data.related.size > 1) {
            queue = data.related
            qIndex = 0
        }
        setQOpts(buildQOpts(data.extractJson), 0)
        playStream(data.title, data.streamUrl, data.audioUrl, data.hasAudio)
        renderQueue()
    }

    // ---------------- QUALITY PILLS ----------------
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
                if (f.optString("type") == "audio" && audioOnly.isEmpty()) audioOnly = u
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
        toast("Quality: ${q.label}")
    }

    // ---------------- 1-TAP DOWNLOADS (Video + Audio) ----------------
    private fun bestProgressive(): JSONObject? {
        val o = curData?.extractJson ?: return null
        return try {
            val arr = o.optJSONArray("formats") ?: return null
            var best: JSONObject? = null
            var bestScore = -1
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (f.optString("type") != "video" || !f.optBoolean("progressive")) continue
                if (f.optString("url", "").isEmpty()) continue
                val v = f.optString("vcodec", "").lowercase()
                val score = (if (v.startsWith("avc1") || v.startsWith("h264")) 100000 else 0) +
                    f.optInt("height")
                if (score > bestScore) {
                    bestScore = score
                    best = f
                }
            }
            best
        } catch (_: Exception) { null }
    }

    private fun bestAudio(): Triple<String, String, String>? {
        val o = curData?.extractJson ?: return null
        return try {
            for (k in listOf("best_audio_mp4", "best_audio")) {
                val b = o.optJSONObject(k) ?: continue
                val u = b.optString("url", "")
                if (u.isEmpty()) continue
                var ext = b.optString("ext", "m4a").ifEmpty { "m4a" }
                if (ext == "mp4") ext = "m4a"
                val mime = if (ext == "m4a") "audio/mp4" else "audio/$ext"
                return Triple(u, ext, mime)
            }
            null
        } catch (_: Exception) { null }
    }

    private fun onDlVideo() {
        val key = "wv"
        if (WatchDownloader.isActive(key)) {
            val p = WatchDownloader.togglePause(key)
            toast(if (p) "Paused — dobara dabao resume" else "Resume ho raha...")
            updateDlUi()
            return
        }
        val f = bestProgressive()
        if (f == null) {
            toast("Pehle koi video chalao, phir Download dabao.")
            return
        }
        val ext = f.optString("ext", "mp4").ifEmpty { "mp4" }
        val label = f.optString("label", "").ifEmpty { "${f.optInt("height")}p" }
        val name = WatchDownloader.safeName("$curTitle - $label") + ".$ext"
        vPct = 0
        WatchDownloader.download(this, f.optString("url"), name, "video/$ext",
            audio = false, key = key,
            onProgress = { pct, _ -> vPct = pct; runOnUiThread { updateDlUi() } },
            onDone = { ok ->
                runOnUiThread {
                    updateDlUi()
                    toast(if (ok) "Video saved ✓ (Download/gsk-downloader)" else "Ruka/fail — notification dekho")
                }
            })
        toast("Video download start...")
        updateDlUi()
    }

    private fun onDlAudio() {
        val key = "wa"
        if (WatchDownloader.isActive(key)) {
            val p = WatchDownloader.togglePause(key)
            toast(if (p) "Paused — dobara dabao resume" else "Resume ho raha...")
            updateDlUi()
            return
        }
        val a = bestAudio()
        if (a == null) {
            toast("Pehle koi video chalao, phir Audio dabao.")
            return
        }
        val name = WatchDownloader.safeName("$curTitle - audio") + ".${a.second}"
        aPct = 0
        WatchDownloader.download(this, a.first, name, a.third,
            audio = true, key = key,
            onProgress = { pct, _ -> aPct = pct; runOnUiThread { updateDlUi() } },
            onDone = { ok ->
                runOnUiThread {
                    updateDlUi()
                    toast(if (ok) "Audio saved ✓ (Music/gsk-downloader)" else "Ruka/fail — notification dekho")
                }
            })
        toast("Audio download start...")
        updateDlUi()
    }

    private fun updateDlUi() {
        try {
            val vOn = WatchDownloader.isActive("wv")
            val aOn = WatchDownloader.isActive("wa")
            dlVideoBtn.text = if (vOn) "⏸ Video $vPct%" else "⬇ Video"
            dlAudioBtn.text = if (aOn) "⏸ Audio $aPct%" else "🎵 Audio"
            cancelDlBtn.visibility = if (vOn || aOn) View.VISIBLE else View.GONE
        } catch (_: Exception) {}
    }

    // ---------------- PLAYER (ad-free + PiP + background) ----------------
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
            player.setPlaybackSpeed(speeds[speedIdx])
            acquireWake()
            status("🚫 Ad-Free chal raha hai... (Home dabao PiP, screen off par audio)")
        } catch (e: Exception) {
            status("Play nahi ho paya: ${e.message?.take(100)}")
        }
    }

    private fun showSpeedDialog() {
        try {
            val labels = speeds.map { if (it == 1f) "Normal" else "${it}x" }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Playback speed")
                .setSingleChoiceItems(labels, speedIdx) { d, which ->
                    speedIdx = which
                    try { exo?.setPlaybackSpeed(speeds[which]) } catch (_: Exception) {}
                    speedBtn.text = "${labels[which]} ⚙"
                    toast("Speed: ${labels[which]}")
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        try {
            requestedOrientation = if (isFullscreen)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            applyChromeVisibility()
            val lp = playerFrame.layoutParams
            lp.height = if (isFullscreen) ViewGroup.LayoutParams.MATCH_PARENT
            else (230 * resources.displayMetrics.density).toInt()
            playerFrame.layoutParams = lp
            fsBtn.text = if (isFullscreen) "⛶ Exit" else "⛶ Fullscreen"
        } catch (_: Exception) {}
    }

    /** Fullscreen/PiP me extra UI chhupao, wapas par dikhao. */
    private fun applyChromeVisibility() {
        try {
            val hide = isFullscreen || isInPip
            for (i in 0 until playerRoot.childCount) {
                val v = playerRoot.getChildAt(i)
                if (v.id == R.id.playerFrame) {
                    v.visibility = View.VISIBLE
                    continue
                }
                v.visibility = if (hide) View.GONE else View.VISIBLE
            }
        } catch (_: Exception) {}
    }

    /** Home dabate hi chhota PiP window (video chalta rehta hai). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        try {
            if (Build.VERSION.SDK_INT >= 26 && exo != null &&
                exo?.playbackState != Player.STATE_IDLE &&
                exo?.playWhenReady == true
            ) {
                val p = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(p)
            }
        } catch (_: Exception) {}
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
        applyChromeVisibility()
    }

    private fun bypass(url: String): String {
        if (!url.contains("googlevideo.com")) return url
        if (url.contains("ratebypass=")) return url
        return url + (if (url.contains("?")) "&" else "?") + "ratebypass=yes"
    }

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

    // NOTE: onPause me player rokna NAHI — screen off / Home (PiP) par
    // background audio chalta rahe. Back dabane par onDestroy me band.

    override fun onDestroy() {
        try { WatchDownloader.cancel("wv") } catch (_: Exception) {}
        try { WatchDownloader.cancel("wa") } catch (_: Exception) {}
        releasePlayer()
        super.onDestroy()
    }
}
