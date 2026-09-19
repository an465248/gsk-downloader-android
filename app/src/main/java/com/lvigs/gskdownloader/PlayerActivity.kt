package com.lvigs.gskdownloader

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

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
        const val DEFAULT_SERVER_URL = "https://gsk-downloader.onrender.com"
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
    private lateinit var zoomBtn: Button
    // Master-prompt rows (10s/Prev/Next/Replay + Volume/Retry/Settings)
    private lateinit var seekFlash: TextView
    private lateinit var prevBtn: Button
    private lateinit var back10Btn: Button
    private lateinit var replayBtn: Button
    private lateinit var fwd10Btn: Button
    private lateinit var nextBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var volumeBar: android.widget.SeekBar
    private lateinit var retryBtn: Button
    private lateinit var settingsBtn: Button
    private var audioMgr: android.media.AudioManager? = null
    private var lastVol: Int = -1
    private var flashHide: Runnable? = null

    // SINGLE-PLAYER ARCHITECTURE: poori app me playback ka ek hi engine hai —
    // PlayerService ke andar wala ExoPlayer (merge factory + wake-lock + audio
    // attrs sahit). Yahan koi doosra ExoPlayer kabhi nahi banega.
    // Activity (same process) ISI engine ko directly drive karti hai:
    // PLAY/PAUSE/SEEK/±10s/PREV/NEXT/SPEED/QUALITY sab isi player par jate hain.
    // MediaSession/lock-screen/notification sirf BRIDGE hain — ye ISI player ko
    // observe karte hain, khud kuch nahi bajate. Bridge ka fail/timeout/artwork
    // /permission playback KABHI nahi rokega (koi MediaController-bind dependency
    // nahi hai — foreground playback seedha engine se chalta hai).
    // Service engine ka OWNER hai: activity use karti hai, release KABHI nahi karti.
    private var engine: ExoPlayer? = null
    private var engineAttached: Boolean = false
    private var engineWaits: Int = 0
    // Engine abhi taiyaar na ho to aakhri play-request yahan rehti hai —
    // taiyaar hote hi ISI player par fire hogi (doosra player nahi banega).
    private data class PendingPlay(
        val title: String,
        val streamUrl: String,
        val audioUrl: String,
        val hasAudio: Boolean,
        val startAtMs: Long,
        val localUri: android.net.Uri? = null,
    )
    private var pendingPlay: PendingPlay? = null
    private var cookiePath: String = ""
    private var pyReady = false

    private var queue: List<VideoItem> = emptyList()
    private var qIndex: Int = -1
    private var loadingBusy = false
    // WATCHDOG: callback kho jaye to bhi UI 35s me free pakka (30s repo + 5s).
    @Volatile private var loadSeq = 0

    private data class QOpt(val label: String, val url: String, val audioUrl: String, val hasAudio: Boolean)
    private var qOpts: List<QOpt> = emptyList()
    private var qSel: Int = -1
    private var curData: WatchData? = null
    private var curPageUrl: String = ""
    private var curTitle: String = "Video"
    // Aakhri play-request (bind-retry/watchdog isi ko fire karega).
    // Ye sab user-device par bajta hai (phone CPU/RAM/network) — server sirf
    // link nikalta hai, stream phone se seedha googlevideo se aata hai.

    // search state
    private var lastQuery: String = ""
    private var searchLimit: Int = 12
    private var searchResults: List<VideoItem> = emptyList()

    // download UI state
    private var vPct: Int = 0
    private var aPct: Int = 0

    // player options (master defaults: Auto ON, Resume ON, 1.0x, Keep ON, BG ON)
    private val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private var speedIdx: Int = 3
    private var isFullscreen: Boolean = false
    private var isInPip: Boolean = false
    private var seekDurMs: Long = 10_000L
    private var doubleTapOn: Boolean = true
    private var autoNextOn: Boolean = true
    private var resumeOn: Boolean = true
    private var keepScreenOn: Boolean = true
    private var bgPlayOn: Boolean = true
    private var pipOn: Boolean = true
    private var defaultQuality: String = "Auto"
    private var longPressSpeed: Boolean = false
    private var savedSpeedIdx: Int = 3

    // YouTube-style zoom: Fit -> Fill -> Crop(Zoom) -> Original (no distortion)
    private val zoomModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
    )
    private val zoomLabels = arrayOf("🔍 Fit", "🔍 Fill", "🔍 Crop", "🔍 Orig")
    private var zoomIdx: Int = 0
    private var pinchScale: Float = 1f
    private var scaleDetector: ScaleGestureDetector? = null

    // search/related thumbnails: chhota HTTP client + memory cache
    private val thumbHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    private val thumbCache = object : LinkedHashMap<String, android.graphics.Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?
        ): Boolean = size > 60
    }

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
        zoomBtn = findViewById(R.id.playerZoomBtn)
        seekFlash = findViewById(R.id.playerSeekFlash)
        prevBtn = findViewById(R.id.playerPrevBtn)
        back10Btn = findViewById(R.id.playerBack10Btn)
        replayBtn = findViewById(R.id.playerReplayBtn)
        fwd10Btn = findViewById(R.id.playerFwd10Btn)
        nextBtn = findViewById(R.id.playerNextBtn)
        muteBtn = findViewById(R.id.playerMuteBtn)
        volumeBar = findViewById(R.id.playerVolumeBar)
        retryBtn = findViewById(R.id.playerRetryBtn)
        settingsBtn = findViewById(R.id.playerSettingsBtn)

        // Fullscreen me bhi ye controls dikhengi (nahi to Exit milta hi nahi):
        // video + seek/volume rows + speed/fullscreen/zoom + quality pills + download.
        fsKeep = setOf(
            R.id.playerCtrlRow, R.id.playerQPillsScroll, R.id.playerDlRow,
            R.id.playerSeekRow, R.id.playerSysRow,
        )

        // Back dabane par fullscreen se pehle normal screen par aao (app band nahi).
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isFullscreen) {
                        toggleFullscreen()
                        return
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            },
        )

        // FIX: 1 min baad screen off — video dekhte time screen ON rakho
        // (YouTube jaisa). Setting se OFF ho sakta hai. Pause par flag hattega.
        loadPlayerSettings()
        applyKeepScreenOn()
        updateSpeedLabel()

        // YouTube jaisa zoom: button se Fit/Fill/Crop/Orig + 2-ungli pinch zoom
        try {
            playerView.resizeMode = zoomModes[zoomIdx]
            zoomBtn.text = zoomLabels[zoomIdx]
        } catch (_: Exception) {}
        zoomBtn.setOnClickListener { cycleZoom() }
        setupGestures()
        setupVolumeRow()
        // Android 13+: media notification ke liye permission (MainActivity me
        // bhi hai; Player direct khule to yahan se mango — system dialog only).
        askNotificationPermission()
        ensurePlayerService()

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
        prevBtn.setOnClickListener { stepQueue(-1) }
        nextBtn.setOnClickListener { stepQueue(1) }
        back10Btn.setOnClickListener { seekBy(-seekDurMs) }
        fwd10Btn.setOnClickListener { seekBy(seekDurMs) }
        replayBtn.setOnClickListener { replayCurrent() }
        retryBtn.setOnClickListener { retryCurrent() }
        settingsBtn.setOnClickListener { showSettingsDialog() }
        muteBtn.setOnClickListener { toggleMute() }
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
                // Downloaded/local file (ACTION_VIEW video/* ya file Uri) — wahi player me chalao.
                val viewData = try {
                    if (intent?.action == Intent.ACTION_VIEW) intent.data else null
                } catch (_: Exception) { null }
                if (viewData != null) {
                    curTitle = try {
                        viewData.lastPathSegment?.substringAfterLast('/')?.ifEmpty { "Video" } ?: "Video"
                    } catch (_: Exception) { "Video" }
                    curPageUrl = viewData.toString()
                    playLocalUri(curTitle, viewData)
                    status("📁 Downloaded file chal rahi — ad-free player me.")
                    return@runOnUiThread
                }
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
                                    if (isFinishing || isDestroyed) return@runOnUiThread
                                    curData = data
                                    // BUG-FIX: intent queue me thumbs nahi hote
                                    // (kala dabba dikhta tha) — related me thumbs
                                    // hon to wahi dikhao (current sabse upar).
                                    if (data.related.isNotEmpty() &&
                                        (queue.isEmpty() || queue.all { it.thumb.isEmpty() })
                                    ) {
                                        queue = listOf(VideoItem("", data.title, data.pageUrl, data.thumb, 0)) + data.related
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

    /** SINGLE engine taiyaar karo + ISI se jodo (direct, same-process).
     *  Koi MediaController-bind dependency NAHI — foreground playback seedha
     *  engine se chalta hai. Bridge (MediaSession/notification/lock-screen)
     *  service me ISI engine ko observe karta hai, best-effort. */
    private fun ensurePlayerService() {
        try {
            startEngineService()
        } catch (_: Exception) {}
        // Notification ke Next/Prev = queue ka agla/pichla (extract karke).
        try {
            PlayerService.externalNext = { runOnUiThread { stepQueue(1) } }
            PlayerService.externalPrev = { runOnUiThread { stepQueue(-1) } }
        } catch (_: Exception) {}
        // Engine taiyaar hote hi attach (service onCreate se callback aayega).
        try {
            PlayerService.onPlayerReady = { runOnUiThread { attachEngine() } }
        } catch (_: Exception) {}
        attachEngine()
        // Watchdog: engine abhi taiyaar na ho to service dobara jagao.
        // Pending play-request surakshit hai — taiyaar hote hi fire hogi.
        // Stuck status kabhi nahi: har haal me status aage badhta hai.
        try {
            playerRoot.postDelayed({
                try {
                    if (!engineAttached && !isFinishing && !isDestroyed) {
                        if (pendingPlay != null) {
                            status("Player taiyaar ho raha... thoda ruko ya ↻ Retry dabao.")
                        }
                        try { startEngineService() } catch (_: Exception) {}
                        attachEngine()
                    }
                } catch (_: Exception) {}
            }, 8000)
        } catch (_: Exception) {}
    }

    /** Engine service start karo — FGS se, fail ho to plain start fallback. */
    private fun startEngineService() {
        try {
            val i = Intent(this, PlayerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (_: Exception) {
                // FGS-start mana ho (rare) to plain start — engine phir bhi banega.
                try { startService(Intent(this, PlayerService::class.java)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** ISI (single) engine se jodo: PlayerView + listener + speed + pending play.
     *  Engine mar chuka ho (null) to service dobara jagao + thode gap me retry
     *  (max ~5) — uske baad bhi na bane to status me Retry (kala screen nahi). */
    private fun attachEngine() {
        try {
            if (isFinishing || isDestroyed) return
            if (engineAttached && engine != null) return
            val p = try {
                if (PlayerService.playerReady) PlayerService.playerRef else null
            } catch (_: Exception) { null }
            if (p == null) {
                if (engineWaits < 5) {
                    engineWaits++
                    try {
                        playerRoot.postDelayed({
                            try {
                                if (!engineAttached && !isFinishing && !isDestroyed) {
                                    try { startEngineService() } catch (_: Exception) {}
                                    attachEngine()
                                }
                            } catch (_: Exception) {}
                        }, 2000)
                    } catch (_: Exception) {}
                } else if (pendingPlay != null) {
                    status("Player taiyaar nahi hua — ↻ Retry dabao.")
                }
                return
            }
            engineWaits = 0
            engine = p
            engineAttached = true
            try { playerView.player = p } catch (_: Exception) {}
            try { p.addListener(engineListener) } catch (_: Exception) {}
            try { applySpeed() } catch (_: Exception) {}
            // Atki play-request ISI engine par fire karo.
            try {
                pendingPlay?.let { pp ->
                    pendingPlay = null
                    firePlayOnEngine(pp)
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    /** Engine op (speed/seek type): engine ho to abhi, warna jagao + toast.
     *  Play-requests isme kabhi nahi aati (pendingPlay alag). */
    private fun withEngine(fn: (ExoPlayer) -> Unit) {
        try {
            val e = engine
            if (e != null && engineAttached) {
                try { fn(e) } catch (_: Exception) {}
            } else {
                try { startEngineService() } catch (_: Exception) {}
                try { attachEngine() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun applySpeed() {
        try { engine?.setPlaybackSpeed(speeds[speedIdx]) } catch (_: Exception) {}
    }

    /** ISI single engine ka state listener: playing/paused/buffering/position/
     *  duration/completed/title sab yahin se UI + MediaSession me jata hai. */
    private val engineListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                // Auto Next ON: agla apne aap; OFF: stop + suggestion.
                if (!autoNextOn) {
                    status("Khatm! (Auto Next OFF — Next dabao ya Related se chuno.)")
                    return
                }
                val next = qIndex + 1
                if (next < queue.size) {
                    try { toast("Agla: ${queue[next].title.take(30)}...") } catch (_: Exception) {}
                    playQueueItem(next)
                } else {
                    status("Khatm! 🚫 Poora video zero ads ke saath.")
                }
            }
            // Keep-screen: pause par normal timeout allow, play par ON.
            try {
                val playing = try { engine?.isPlaying ?: false } catch (_: Exception) { false }
                if (playing && keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else if (!playing) {
                    // pause par position save (resume ke liye)
                    try {
                        if (curPageUrl.isNotEmpty()) {
                            val p = curPos()
                            val d = try { engine?.duration ?: 0L } catch (_: Exception) { 0L }
                            saveResume(curPageUrl, p, d)
                            curData?.let { saveLastPlayed(it, p) }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        override fun onPlayerError(error: PlaybackException) {
            status("Video couldn't be loaded — ↻ Retry dabao. (${error.message?.take(80)})")
        }
    }

    /** Queue me aage/peeche (notification Next/Prev + buttons sab yahi). */
    private fun stepQueue(dir: Int) {
        try {
            if (queue.isEmpty()) { toast("Next video unavailable."); return }
            var idx = qIndex + dir
            if (idx < 0) idx = 0
            if (idx >= queue.size) idx = queue.size - 1
            if (idx == qIndex && dir > 0) { toast("Next video unavailable."); return }
            if (idx == qIndex && dir < 0) {
                // shuruat me ho to previous, warna beginning rewind (predictable)
                try {
                    if (curPos() > 10_000L) { seekBy(-curPos()); return }
                } catch (_: Exception) {}
            }
            playQueueItem(idx)
        } catch (_: Exception) {}
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
        // BUG-FIX: extract chalte time search dabane par chup-chaap kuch
        // nahi hota tha — wajah batao.
        if (loadingBusy) { toast("Ruko — video load ho raha hai..."); return }
        loadingBusy = true
        goBtn.isEnabled = false
        lastQuery = query
        status("Search ho raha: $query ...")
        WatchRepository.search(query, searchLimit) { res ->
            runOnUiThread {
                // BUG-FIX: beech me Back dabane par dead-views ko chhuna = crash.
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadingBusy = false
                try { goBtn.isEnabled = true } catch (_: Exception) {}
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

    /** YouTube-jaisi row: thumbnail + title + channel • views • duration.
     *  Tap = turant ad-free play, long-press = Copy link / Download / Share. */
    private fun addVideoRow(
        parent: LinearLayout, item: VideoItem, marker: String,
        highlight: Boolean, onTap: () -> Unit,
    ) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = android.view.Gravity.CENTER_VERTICAL
        box.setPadding(8, 10, 8, 10)
        // thumbnail (112x63, duration badge neeche)
        val thumbWrap = FrameLayout(this)
        val tw = (112 * resources.displayMetrics.density).toInt()
        val th = (63 * resources.displayMetrics.density).toInt()
        thumbWrap.layoutParams = LinearLayout.LayoutParams(tw, th)
        val iv = ImageView(this)
        iv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.setBackgroundColor(0xFF000000.toInt())
        thumbWrap.addView(iv)
        val dur = fmtDur(item.durationSec)
        if (dur.isNotEmpty()) {
            val badge = TextView(this)
            badge.text = dur
            badge.setTextColor(0xFFFFFFFF.toInt())
            badge.textSize = 10f
            badge.setBackgroundColor(0xCC000000.toInt())
            badge.setPadding(6, 2, 6, 2)
            val blp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            blp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            blp.setMargins(0, 0, 4, 4)
            badge.layoutParams = blp
            thumbWrap.addView(badge)
        }
        box.addView(thumbWrap)
        loadThumbInto(item.thumb, iv)
        // text side
        val txtBox = LinearLayout(this)
        txtBox.orientation = LinearLayout.VERTICAL
        val tlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        tlp.setMargins(10, 0, 0, 0)
        txtBox.layoutParams = tlp
        val t = TextView(this)
        t.text = marker + item.title.take(65)
        t.setTextColor(if (highlight) 0xFF22D3EE.toInt() else 0xFFE9EEFB.toInt())
        t.textSize = 13f
        t.maxLines = 2
        t.ellipsize = android.text.TextUtils.TruncateAt.END
        val m = TextView(this)
        val meta = listOf(
            item.channel.take(30),
            fmtViews(item.views),
        ).filter { it.isNotEmpty() }.joinToString(" • ")
        m.text = meta.ifEmpty { item.url.take(40) }
        m.setTextColor(0xFF93A0C4.toInt())
        m.textSize = 11f
        txtBox.addView(t)
        if (m.text.isNotEmpty()) txtBox.addView(m)
        box.addView(txtBox)
        box.setOnClickListener { onTap() }
        // long-press: link copy / download / share — wahi se sab
        box.setOnLongClickListener { showRowMenu(item); true }
        parent.addView(box)
    }

    private fun loadThumbInto(url: String, iv: ImageView) {
        try {
            if (url.isEmpty()) return
            synchronized(thumbCache) { thumbCache[url] }?.let { iv.setImageBitmap(it); return }
            Thread {
                try {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", UA).build()
                    thumbHttp.newCall(req).execute().use { res ->
                        if (!res.isSuccessful) return@use
                        val bytes = res.body?.bytes() ?: return@use
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use
                        try {
                            synchronized(thumbCache) { thumbCache[url] = bmp }
                        } catch (_: Exception) {}
                        runOnUiThread { try { iv.setImageBitmap(bmp) } catch (_: Exception) {} }
                    }
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }

    /** Row long-press menu: tap-play ke alawa copy/download/share wahi se. */
    private fun showRowMenu(item: VideoItem) {
        try {
            val opts = arrayOf("▶ Play", "📋 Link copy", "⬇ Download (1-tap)", "📤 Share")
            AlertDialog.Builder(this)
                .setTitle(item.title.take(60))
                .setItems(opts) { _, which ->
                    when (which) {
                        0 -> {
                            clearSearch()
                            queue = emptyList()
                            qIndex = -1
                            renderQueue()
                            urlInput.setText(item.url)
                            extractAndPlay(item.url, true)
                        }
                        1 -> copyText(item.url)
                        2 -> downloadViaMain(item)
                        3 -> shareText(item.title + "\n" + item.url)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun copyText(s: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("link", s))
            toast("Link copy ho gaya ✓")
        } catch (_: Exception) { toast("Copy nahi ho paya.") }
    }

    private fun shareText(s: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s)
            }
            startActivity(Intent.createChooser(i, "Share video link"))
        } catch (_: Exception) {}
    }

    /** Is row ka video MainActivity me 1-tap download karo (fetch+save auto). */
    private fun downloadViaMain(item: VideoItem) {
        try {
            val i = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_AUTODL, item.url)
            }
            startActivity(i)
            toast("Download tab me 1-tap start ho raha...")
        } catch (e: Exception) { toast("Download nahi khul paya: ${e.message}") }
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
                if (u.isNotEmpty()) out.add(VideoItem(
                    o.optString("id"), o.optString("t", "Video"), u,
                    o.optString("h"), 0))
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
        if (loadingBusy) { toast("Ruko — pichla load ho raha hai..."); return }
        loadingBusy = true
        loadSeq++
        val myLoad = loadSeq
        goBtn.isEnabled = false
        status("Ad-free link nikal rahe hain... (10-15s)")
        // WATCHDOG: 35s me callback na aaye to khud free — "fetch par atka" band.
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (loadingBusy && myLoad == loadSeq && !isFinishing && !isDestroyed) {
                        loadingBusy = false
                        try { goBtn.isEnabled = true } catch (_: Exception) {}
                        status("Time-out: link nahi nikla — net check karke dobara Play dabao.")
                    }
                } catch (_: Exception) {}
            }, 35000)
        } catch (_: Exception) {}
        WatchRepository.extract(pageUrl, cookiePath) { res ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Purana stale callback naye load ko free na kare.
                if (myLoad != loadSeq) return@runOnUiThread
                res.onSuccess { data -> 
                    loadingBusy = false
                    try { goBtn.isEnabled = true } catch (_: Exception) {}
                    onWatchData(data, freshQueue) 
                }
                .onFailure { e ->
                    val msg = (e.message ?: "").take(200)
                    // Error category parse karo
                    val category = when {
                        msg.startsWith("NETWORK_ERROR:") || msg.startsWith("TEMP_API_ERROR:") -> "RETRY"
                        msg.startsWith("UNAVAILABLE:") -> "UNAVAILABLE"
                        msg.startsWith("AUTH_REQUIRED:") -> "AUTH"
                        msg.startsWith("EXPIRED:") -> "EXPIRED"
                        else -> "ERROR"
                    }
                    showErrorState(msg, category, pageUrl)
                }
            }
        }
    }

    private fun getServerBase(): String {
        return try {
            val s = getSharedPreferences("gsk_settings", MODE_PRIVATE)
                .getString("server_url", "")?.trim().orEmpty().trimEnd('/')
            if (s.isNotEmpty()) s else DEFAULT_SERVER_URL.trimEnd('/')
        } catch (_: Exception) { DEFAULT_SERVER_URL.trimEnd('/') }
    }

    /** Server /api/extract -> WatchData (phone slow ho to fallback). null = fail. */
    private fun fetchWatchViaServer(pageUrl: String): WatchData? {
        return try {
            val base = getServerBase()
            if (base.isEmpty()) return null
            val payload = JSONObject().put("url", pageUrl).toString()
            val req = Request.Builder()
                .url("$base/api/extract")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", UA)
                .build()
            // short-timeout client: server 25s me na de to phone-error dikhao.
            val cli = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
            cli.newCall(req).execute().use { res ->
                val txt = res.body?.string() ?: return null
                if (txt.isEmpty()) return null
                val o = JSONObject(txt)
                if (o.has("error")) return null
                var stream = o.optString("preview_url", "")
                var hasAudio = o.optBoolean("preview_has_audio", false)
                var audioUrl = ""
                try {
                    audioUrl = o.optJSONObject("best_audio_mp4")?.optString("url", "").orEmpty()
                    if (audioUrl.isEmpty()) audioUrl = o.optJSONObject("best_audio")?.optString("url", "").orEmpty()
                    if (stream.isEmpty()) {
                        val arr = o.optJSONArray("formats") ?: return null
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
                if (stream.isEmpty()) return null
                WatchData(
                    title = o.optString("title", "Video").ifEmpty { "Video" },
                    pageUrl = o.optString("webpage_url", pageUrl).ifEmpty { pageUrl },
                    thumb = o.optString("thumbnail", ""),
                    streamUrl = stream,
                    audioUrl = audioUrl,
                    hasAudio = hasAudio,
                    extractJson = o,
                    related = WatchRepository.parseItems(o.optJSONArray("playlist")),
                )
            }
        } catch (_: Exception) { null }
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
            // BUG-FIX: current ko sabse upar rakho (pehle qIndex=0
            // related ke pehle item par tha, current gayab tha).
            queue = listOf(VideoItem("", data.title, data.pageUrl, data.thumb, 0)) + data.related
            qIndex = 0
        }
        val opts = buildQOpts(data.extractJson)
        // Default quality (Auto/BEST/specific) — settings se.
        var sel = 0
        try {
            if (defaultQuality.equals("Auto", true)) {
                sel = opts.indexOfFirst { it.label.startsWith("Auto") }.takeIf { it >= 0 } ?: 0
            } else {
                val wanted = defaultQuality.filter { it.isDigit() }.toIntOrNull() ?: 0
                if (wanted > 0) {
                    var best = -1
                    for (i in opts.indices) {
                        val h = opts[i].label.filter { it.isDigit() }.toIntOrNull() ?: 0
                        if (h in 1..wanted && (best < 0 || h > (opts[best].label.filter { it.isDigit() }.toIntOrNull() ?: 0))) best = i
                    }
                    if (best >= 0) sel = best
                }
            }
        } catch (_: Exception) {}
        setQOpts(opts, sel)
        // Resume: बीच में छोड़ा था तो पूछो — "Resume from 12:35?"
        val resumeAt = if (resumeOn) loadResume(curPageUrl) else 0L
        if (sel < opts.size) {
            val q = opts[sel]
            if (resumeAt > 10_000L) {
                askResume(resumeAt) { at ->
                    playStream(data.title, q.url, q.audioUrl, q.hasAudio, at)
                    saveLastPlayed(data, at)
                }
            } else {
                playStream(data.title, data.streamUrl, data.audioUrl, data.hasAudio)
            }
        } else {
            playStream(data.title, data.streamUrl, data.audioUrl, data.hasAudio)
        }
        renderQueue()
    }

    // ---------------- QUALITY PILLS (Auto + specific, source ke hisab se) ----------------
    // YouTube jaisa: Auto sabse pehle, phir available heights. Jo source me nahi
    // hai wo dikhta hi nahi. Current ✓ se indicate. Seamless: position preserve.
    private fun buildQOpts(o: JSONObject): List<QOpt> {
        val out = ArrayList<QOpt>()
        try {
            val arr = o.optJSONArray("formats") ?: return out
            var audioUrl = ""
            try {
                audioUrl = o.optJSONObject("best_audio_mp4")?.optString("url", "").orEmpty()
                if (audioUrl.isEmpty()) audioUrl = o.optJSONObject("best_audio")?.optString("url", "").orEmpty()
            } catch (_: Exception) {}
            if (audioUrl.isEmpty()) {
                for (i in 0 until arr.length()) {
                    try {
                        val f = arr.getJSONObject(i)
                        if (f.optString("type") == "audio") {
                            val u = f.optString("url", "")
                            if (u.isNotEmpty()) { audioUrl = u; break }
                        }
                    } catch (_: Exception) {}
                }
            }
            // height -> best progressive + best video-only
            val progByH = HashMap<Int, String>()
            val dashByH = HashMap<Int, String>()
            var audioOnly = ""
            for (i in 0 until arr.length()) {
                try {
                    val f = arr.getJSONObject(i)
                    val u = f.optString("url", "")
                    if (u.isEmpty()) continue
                    if (f.optString("type") == "audio") {
                        if (audioOnly.isEmpty()) audioOnly = u
                        continue
                    }
                    if (f.optString("type") != "video") continue
                    val h = f.optInt("height")
                    if (h <= 0) continue
                    if (f.optBoolean("progressive")) {
                        if (!progByH.containsKey(h)) progByH[h] = u
                    } else {
                        if (!dashByH.containsKey(h)) dashByH[h] = u
                    }
                } catch (_: Exception) {}
            }
            val heights = (progByH.keys + dashByH.keys).distinct().sortedDescending().take(8)
            val dashNoAudio = ArrayList<Pair<Int, String>>()
            // Auto = best available (network-friendly: best progressive, else top)
            try {
                val autoUrl = progByH[heights.firstOrNull { progByH.containsKey(it) } ?: -1]
                    ?: dashByH[heights.firstOrNull() ?: -1].orEmpty()
                val autoAudio = if (progByH.containsKey(heights.firstOrNull { progByH.containsKey(it) } ?: -1)) "" else audioUrl
                val autoHas = progByH.containsKey(heights.firstOrNull { progByH.containsKey(it) } ?: -1)
                if (autoUrl.isNotEmpty()) out.add(QOpt("Auto ✓", autoUrl, autoAudio, autoHas))
            } catch (_: Exception) {}
            for ((idx, h) in heights.withIndex()) {
                val pu = progByH[h]
                if (!pu.isNullOrEmpty()) {
                    out.add(QOpt(if (idx == 0) "BEST ${h}p" else "${h}p", pu, "", true))
                } else {
                    val du = dashByH[h].orEmpty()
                    if (du.isNotEmpty()) {
                        if (audioUrl.isNotEmpty()) {
                            // video-only + audio merge = awaaz-sahit play
                            out.add(QOpt(
                                if (idx == 0) "BEST ${h}p" else "${h}p",
                                du, audioUrl, false,
                            ))
                        } else {
                            // BUG-FIX: bina-audio dash pill dabane par silent
                            // play hota tha — ise aakhri option rakho.
                            dashNoAudio.add(h to du)
                        }
                    }
                }
            }
            if (out.size <= 1) {
                for ((h, du) in dashNoAudio) out.add(QOpt("${h}p 🔇", du, "", false))
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
                // Current ✓ clearly indicate (Auto pehle se ✓ rakhta hai)
                var label = opts[i].label
                if (i == sel && !label.contains("✓")) label = "$label ✓"
                b.text = label
                b.textSize = 11f
                b.minWidth = 0
                b.minimumWidth = 0
                b.contentDescription = "Quality ${opts[i].label}"
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
        // BUG-FIX: quality badalne par video shuru se nahi — wahi position se
        // (YouTube jaisa). Seek prepare ke baad lagta hai.
        val pos = try {
            engine?.currentPosition ?: 0L
        } catch (_: Exception) { 0L }
        playStream(curTitle, q.url, q.audioUrl, q.hasAudio, pos)
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
    private fun playStream(title: String, streamUrl: String, audioUrl: String, hasAudio: Boolean, startAtMs: Long = 0) {
        try {
            // naya video = zoom reset (pinch 1x + chosen mode rakho, Original me scale 1x)
            try {
                pinchScale = 1f
                playerView.scaleX = 1f
                playerView.scaleY = 1f
                playerView.resizeMode = zoomModes[zoomIdx]
            } catch (_: Exception) {}
            titleText.text = title
            // SINGLE-PLAYER order: fetch -> ISI engine par item set -> READY ->
            // play. MediaSession/notification BRIDGE khud sync hota hai
            // (best-effort) — uska fail/timeout/artwork/permission playback
            // NAHI rokta (koi bind-dependency nahi).
            // Stream khali ho to kala screen nahi — turant error dikhao.
            if (streamUrl.isEmpty()) {
                loadingBusy = false
                try { goBtn.isEnabled = true } catch (_: Exception) {}
                status("Video couldn't be loaded — ↻ Retry dabao. (empty stream)")
                return
            }
            // Har play-request yaad rakho (engine-wait isi ko fire karega).
            try {
                curTitle = title
                pendingPlay = PendingPlay(title, streamUrl, audioUrl, hasAudio, startAtMs)
            } catch (_: Exception) {}
            val eng = try {
                if (engineAttached) engine else null
            } catch (_: Exception) { null }
            if (eng == null) {
                // Engine taiyaar ho raha hai — request pending hai, taiyaar hote
                // hi ISI player par bajegi. Stuck status nahi: wait-retry chalu hai.
                status("🚫 Ad-Free load ho raha... (player taiyaar ho raha hai)")
                try { attachEngine() } catch (_: Exception) {}
                return
            }
            try {
                pendingPlay = null
                firePlayOnEngine(eng, title, streamUrl, audioUrl, hasAudio, startAtMs)
            } catch (e: Exception) {
                android.util.Log.e("GSK-Player", "playStream fire failed", e)
                status("Video couldn't be loaded — ↻ Retry dabao. (${e.message?.take(80)})")
            }
        } catch (e: Exception) {
            android.util.Log.e("GSK-Player", "playStream failed", e)
            status("Video couldn't be loaded — ↻ Retry dabao. (${e.message?.take(80)})")
        }
    }

    /** ISI (single) engine par media item lagao + bajao.
     *  Artwork/thumb fail ho to bhi playback START hogi (thumb optional hai).
     *  Engine mar chuka ho to request pending rakho + dobara jagao. */
    private fun firePlayOnEngine(
        eng: ExoPlayer, title: String, streamUrl: String,
        audioUrl: String, hasAudio: Boolean, startAtMs: Long,
    ) {
        try {
            val thumb = try { curData?.thumb.orEmpty() } catch (_: Exception) { "" }
            val item = try {
                PlayerService.itemFor(title, bypass(streamUrl), bypass(audioUrl), hasAudio, thumb)
            } catch (_: Exception) {
                // Artwork/Uri build fail -> thumb ke bina item banao, ruko mat.
                try {
                    PlayerService.itemFor(title, bypass(streamUrl), bypass(audioUrl), hasAudio, "")
                } catch (e2: Exception) {
                    status("Video couldn't be loaded — ↻ Retry dabao. (${e2.message?.take(80)})")
                    return
                }
            }
            try {
                eng.setMediaItem(item, if (startAtMs > 1000) startAtMs else 0)
                eng.prepare()
                try { eng.setPlaybackSpeed(speeds[speedIdx]) } catch (_: Exception) {}
                eng.play()
                try { applyKeepScreenOn() } catch (_: Exception) {}
                status("🚫 Ad-Free chal raha hai... (Back = background play, notification se ⏮ ⏪ ⏯ ⏩ ⏭)")
            } catch (e: Exception) {
                android.util.Log.e("GSK-Player", "engine fire failed", e)
                // Engine dead -> dobara jagao, request pending rakho.
                try {
                    pendingPlay = PendingPlay(title, streamUrl, audioUrl, hasAudio, startAtMs)
                } catch (_: Exception) {}
                try {
                    engineAttached = false
                    engine = null
                    startEngineService()
                    attachEngine()
                } catch (_: Exception) {}
                status("🚫 Ad-Free load ho raha... (player taiyaar ho raha hai)")
            }
        } catch (e: Exception) {
            status("Video couldn't be loaded — ↻ Retry dabao. (${e.message?.take(80)})")
        }
    }

    /** PendingPlay holder se fire karo (engine-ready/watchdog ke liye). */
    private fun firePlayOnEngine(pp: PendingPlay) {
        val e = try {
            if (engineAttached) engine else null
        } catch (_: Exception) { null } ?: return
        try {
            if (pp.localUri != null) {
                val item = try {
                    PlayerService.itemForLocal(pp.title, pp.localUri)
                } catch (_: Exception) {
                    status("Video couldn't be loaded — ↻ Retry dabao.")
                    return
                }
                try {
                    e.setMediaItem(item)
                    e.prepare()
                    e.play()
                    status("📁 Local file chal rahi — ad-free.")
                } catch (ex: Exception) {
                    android.util.Log.e("GSK-Player", "engine local fire failed", ex)
                    try { pendingPlay = pp } catch (_: Exception) {}
                    try {
                        engineAttached = false
                        engine = null
                        startEngineService()
                        attachEngine()
                    } catch (_: Exception) {}
                }
                return
            }
            firePlayOnEngine(e, pp.title, pp.streamUrl, pp.audioUrl, pp.hasAudio, pp.startAtMs)
        } catch (_: Exception) {}
    }

    /** Downloaded local file (file/content Uri) — ISI single engine me.
     *  Engine taiyaar na ho to request pending rahegi, hote hi bajegi. */
    private fun playLocalUri(title: String, uri: android.net.Uri) {
        try {
            titleText.text = title
            try {
                curTitle = title
                pendingPlay = PendingPlay(title, "", "", true, 0L, localUri = uri)
            } catch (_: Exception) {}
            val e = try {
                if (engineAttached) engine else null
            } catch (_: Exception) { null }
            if (e == null) {
                status("📁 Local file taiyaar... (player taiyaar ho raha hai)")
                try { attachEngine() } catch (_: Exception) {}
                return
            }
            try {
                pendingPlay = null
                firePlayOnEngine(PendingPlay(title, "", "", true, 0L, localUri = uri))
            } catch (_: Exception) {
                status("Video couldn't be loaded — ↻ Retry dabao.")
            }
        } catch (_: Exception) {
            status("Video couldn't be loaded — ↻ Retry dabao.")
        }
    }

    private fun showSpeedDialog() {
        try {
            val labels = speeds.map {
                when (it) {
                    1f -> "1.0x (Normal)"
                    else -> "${it}x"
                }
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Playback speed (default ${speeds[speedIdx]}x)")
                .setSingleChoiceItems(labels, speedIdx) { d, which ->
                    speedIdx = which
                    savedSpeedIdx = which
                    savePlayerSettings()
                    withEngine { try { it.setPlaybackSpeed(speeds[which]) } catch (_: Exception) {} }
                    updateSpeedLabel()
                    toast("Speed: ${labels[which]}")
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun updateSpeedLabel() {
        try {
            val s = speeds.getOrNull(speedIdx) ?: 1f
            speedBtn.text = "${if (s == 1f) "1" else s}x ⚙"
        } catch (_: Exception) {}
    }

    // ---------------- 10s / Replay / Retry / Volume / Settings ----------------
    private fun curPos(): Long {
        return try {
            engine?.currentPosition ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun seekBy(deltaMs: Long) {
        try {
            val e = engine
            if (e != null && engineAttached) {
                val dur = try { e.duration } catch (_: Exception) { androidx.media3.common.C.TIME_UNSET }
                var np = e.currentPosition + deltaMs
                if (np < 0) np = 0
                if (dur != androidx.media3.common.C.TIME_UNSET && dur > 0 && np > dur) np = dur
                e.seekTo(np)
                flashSeek(if (deltaMs < 0) "↶ ${kotlin.math.abs(deltaMs / 1000)} seconds" else "↷ ${deltaMs / 1000} seconds")
                return
            }
            toast("Player taiyaar ho raha hai, ruko...")
            try { attachEngine() } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun replayCurrent() {
        try {
            withEngine { it.seekTo(0); it.play() }
            flashSeek("↺ Replay")
            toast("Replay — shuru se.")
        } catch (_: Exception) {}
    }

    private fun retryCurrent() {
        try {
            val url = curPageUrl
            if (url.isEmpty()) { toast("Pehle link paste karo."); return }
            toast("Retry ho raha...")
            extractAndPlay(url, false)
        } catch (_: Exception) {}
    }

    private fun flashSeek(text: String) {
        try {
            seekFlash.text = text
            seekFlash.visibility = View.VISIBLE
            seekFlash.removeCallbacks(flashHide)
            val r = Runnable { try { seekFlash.visibility = View.GONE } catch (_: Exception) {} }
            flashHide = r
            seekFlash.postDelayed(r, 900)
        } catch (_: Exception) {}
    }

    private fun setupVolumeRow() {
        try {
            audioMgr = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val am = audioMgr ?: return
            val max = try { am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) } catch (_: Exception) { 15 }
            val cur = try { am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) } catch (_: Exception) { max / 2 }
            volumeBar.max = max
            volumeBar.progress = cur
            updateMuteIcon(cur == 0)
            volumeBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    try {
                        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, p, 0)
                        updateMuteIcon(p == 0)
                    } catch (_: Exception) {}
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        } catch (_: Exception) {}
    }

    private fun updateMuteIcon(muted: Boolean) {
        try { muteBtn.text = if (muted) "🔇" else "🔊" } catch (_: Exception) {}
    }

    private fun toggleMute() {
        try {
            val am = audioMgr ?: return
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            if (cur == 0) {
                val back = if (lastVol > 0) lastVol else am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) / 2
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, back, 0)
                volumeBar.progress = back
                updateMuteIcon(false)
            } else {
                lastVol = cur
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
                volumeBar.progress = 0
                updateMuteIcon(true)
            }
        } catch (_: Exception) {}
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        applyFullscreen()
    }

    /** YouTube jaisa full/half: portrait = half-screen (video upar + list neeche),
     *  landscape-fullscreen = sirf video (immersive, status/nav hidden).
     *  Button se toggle + phone ghumane par auto-adjust (onConfigurationChanged). */
    private fun applyFullscreen() {
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
            // immersive: fullscreen me system bars hatao, half me wapas lao
            if (isFullscreen) hideSystemBars() else showSystemBars()
        } catch (_: Exception) {}
    }

    private fun hideSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } catch (_: Exception) {}
    }

    private fun showSystemBars() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.show(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        } catch (_: Exception) {}
    }

    /** Phone ghumaya to YouTube jaisa auto full/half (recreate nahi hota). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try {
            val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            if (land != isFullscreen) {
                isFullscreen = land
                applyChromeVisibility()
                val lp = playerFrame.layoutParams
                lp.height = if (isFullscreen) ViewGroup.LayoutParams.MATCH_PARENT
                else (230 * resources.displayMetrics.density).toInt()
                playerFrame.layoutParams = lp
                fsBtn.text = if (isFullscreen) "⛶ Exit" else "⛶ Fullscreen"
                if (isFullscreen) hideSystemBars() else showSystemBars()
            }
        } catch (_: Exception) {}
    }

    // ---------------- ZOOM (Fit/Fill/Crop/Orig, pinch, double-tap) ----------------
    private fun cycleZoom() {
        try {
            zoomIdx = (zoomIdx + 1) % zoomModes.size
            playerView.resizeMode = zoomModes[zoomIdx]
            // Original = bilkul 1.0x, bina stretch (aspect preserve, no distortion)
            pinchScale = 1f
            playerView.scaleX = 1f
            playerView.scaleY = 1f
            zoomBtn.text = zoomLabels[zoomIdx]
            toast(when (zoomIdx) {
                0 -> "Fit — poora video dikhega"
                1 -> "Fill — screen bhar ke"
                2 -> "Crop — zoom-in crop"
                else -> "Original — asli ratio"
            })
        } catch (_: Exception) {}
    }

    /** Gestures: pinch zoom + double-tap L/R ±10s + center double-tap zoom + long-press 2x.
     *  Single tap consume nahi — PlayerView controller show/hide chalta rahe. */
    private fun setupGestures() {
        try {
            scaleDetector = ScaleGestureDetector(this,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(det: ScaleGestureDetector): Boolean {
                        try {
                            pinchScale = (pinchScale * det.scaleFactor).coerceIn(1f, 3f)
                            playerView.scaleX = pinchScale
                            playerView.scaleY = pinchScale
                        } catch (_: Exception) {}
                        return true
                    }
                })
            val gd = android.view.GestureDetector(this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (!doubleTapOn) return false
                        try {
                            val w = playerView.width.takeIf { it > 0 } ?: return false
                            val x = e.x
                            when {
                                x < w * 0.4f -> { seekBy(-seekDurMs); return true }
                                x > w * 0.6f -> { seekBy(seekDurMs); return true }
                                else -> { cycleZoom(); return true }
                            }
                        } catch (_: Exception) {}
                        return false
                    }
                    override fun onLongPress(e: MotionEvent) {
                        // Long-press = 2x jab tak ungli rahe (optional speed)
                        try {
                            if (!longPressSpeed) {
                                longPressSpeed = true
                                savedSpeedIdx = speedIdx
                                withEngine { try { it.setPlaybackSpeed(2f) } catch (_: Exception) {} }
                                flashSeek("2x ▶")
                            }
                        } catch (_: Exception) {}
                    }
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false
                })
            playerView.setOnTouchListener { _, ev ->
                try { scaleDetector?.onTouchEvent(ev) } catch (_: Exception) {}
                try { gd.onTouchEvent(ev) } catch (_: Exception) {}
                try {
                    if (ev.action == MotionEvent.ACTION_UP && longPressSpeed) {
                        longPressSpeed = false
                        val s = speeds.getOrNull(savedSpeedIdx) ?: 1f
                        withEngine { try { it.setPlaybackSpeed(s) } catch (_: Exception) {} }
                    }
                } catch (_: Exception) {}
                false // consume mat karo — play/pause/controller chalta rahe
            }
        } catch (_: Exception) {}
    }

    // ---------------- SETTINGS (Playback/Player/Background) ----------------
    private fun playerPrefs() = getSharedPreferences("gsk_player_settings", MODE_PRIVATE)

    private fun loadPlayerSettings() {
        try {
            val p = playerPrefs()
            autoNextOn = p.getBoolean("auto_next", true)
            resumeOn = p.getBoolean("resume", true)
            defaultQuality = p.getString("def_quality", "Auto") ?: "Auto"
            val ds = p.getFloat("def_speed", 1f)
            speedIdx = speeds.indexOfFirst { it == ds }.takeIf { it >= 0 } ?: 3
            savedSpeedIdx = speedIdx
            keepScreenOn = p.getBoolean("keep_screen", true)
            bgPlayOn = p.getBoolean("bg_play", true)
            pipOn = p.getBoolean("pip", true)
            doubleTapOn = p.getBoolean("double_tap", true)
            seekDurMs = p.getLong("seek_ms", 10_000L)
        } catch (_: Exception) {}
    }

    private fun savePlayerSettings() {
        try {
            playerPrefs().edit()
                .putBoolean("auto_next", autoNextOn)
                .putBoolean("resume", resumeOn)
                .putString("def_quality", defaultQuality)
                .putFloat("def_speed", speeds.getOrNull(speedIdx) ?: 1f)
                .putBoolean("keep_screen", keepScreenOn)
                .putBoolean("bg_play", bgPlayOn)
                .putBoolean("pip", pipOn)
                .putBoolean("double_tap", doubleTapOn)
                .putLong("seek_ms", seekDurMs)
                .apply()
        } catch (_: Exception) {}
    }

    private fun applyKeepScreenOn() {
        try {
            if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }

    private fun showSettingsDialog() {
        try {
            val names = arrayOf("Auto Play Next", "Resume Playback", "Keep Screen ON", "Background Play", "Picture-in-Picture", "Double-Tap Seek")
            val vals = booleanArrayOf(autoNextOn, resumeOn, keepScreenOn, bgPlayOn, pipOn, doubleTapOn)
            AlertDialog.Builder(this)
                .setTitle("Player Settings (ad-free)")
                .setMultiChoiceItems(names, vals) { _, which, checked ->
                    when (which) {
                        0 -> autoNextOn = checked
                        1 -> resumeOn = checked
                        2 -> { keepScreenOn = checked; applyKeepScreenOn() }
                        3 -> bgPlayOn = checked
                        4 -> pipOn = checked
                        5 -> doubleTapOn = checked
                    }
                }
                .setSingleChoiceItems(
                    arrayOf("Default Quality: Auto", "Default Quality: 480p", "Default Quality: 720p", "Default Quality: 1080p"),
                    when (defaultQuality) { "480p" -> 1; "720p" -> 2; "1080p" -> 3; else -> 0 },
                ) { _, which ->
                    defaultQuality = when (which) { 1 -> "480p"; 2 -> "720p"; 3 -> "1080p"; else -> "Auto" }
                }
                .setPositiveButton("Save") { d, _ -> savePlayerSettings(); toast("Settings save ✓"); d.dismiss() }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Exception) {}
    }

    // ---------------- RESUME + LAST PLAYED ----------------
    private fun resumeKey(url: String): String {
        return try { "pos_" + url.hashCode().toString() } catch (_: Exception) { "pos_x" }
    }

    private fun loadResume(url: String): Long {
        return try { prefs().getLong(resumeKey(url), 0L) } catch (_: Exception) { 0L }
    }

    private fun saveResume(url: String, pos: Long, dur: Long) {
        try {
            // shuru/ant ke 10s chhodo — beech me chhoda tabhi resume pucho
            if (pos > 10_000L && (dur <= 0 || pos < dur - 10_000L)) {
                prefs().edit().putLong(resumeKey(url), pos).apply()
            } else {
                prefs().edit().remove(resumeKey(url)).apply()
            }
        } catch (_: Exception) {}
    }

    private fun saveLastPlayed(data: WatchData, pos: Long) {
        try {
            val arr = try { JSONArray(prefs().getString("last_played", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
            val obj = JSONObject()
                .put("t", data.title.take(80))
                .put("u", data.pageUrl)
                .put("h", data.thumb)
                .put("p", pos)
            // same url upar lao (max 10)
            val out = JSONArray()
            out.put(obj)
            for (i in 0 until arr.length()) {
                try {
                    val o = arr.getJSONObject(i)
                    if (o.optString("u") != data.pageUrl) out.put(o)
                    if (out.length() >= 10) break
                } catch (_: Exception) {}
            }
            prefs().edit().putString("last_played", out.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun askResume(at: Long, go: (Long) -> Unit) {
        try {
            val label = fmtDur(at)
            AlertDialog.Builder(this)
                .setTitle("Resume from $label?")
                .setMessage("Pichli baar yahin chhoda tha.")
                .setPositiveButton("▶ Resume") { d, _ -> go(at); d.dismiss() }
                .setNegativeButton("↺ Start") { d, _ -> go(0L); d.dismiss() }
                .show()
        } catch (_: Exception) { try { go(0L) } catch (_: Exception) {} }
    }

    /** Fullscreen/PiP me extra UI chhupao, wapas par dikhao.
     *  BUG-FIX: pehle control rows (Fullscreen/quality buttons) bhi chhup
     *  jati thin — wapas aane ka button hi nahi milta tha. Ab video +
     *  controls + quality pills fullscreen me bhi dikhengi (YouTube jaisa),
     *  taaki play ke dauran quality badal sako aur Exit dabakar wapas aao. */
    private var fsKeep: Set<Int> = emptySet()
    private fun applyChromeVisibility() {
        try {
            val hide = isFullscreen || isInPip
            for (i in 0 until playerRoot.childCount) {
                val v = playerRoot.getChildAt(i)
                if (v.id == R.id.playerFrame || fsKeep.contains(v.id)) {
                    v.visibility = View.VISIBLE
                    continue
                }
                v.visibility = if (hide) View.GONE else View.VISIBLE
            }
        } catch (_: Exception) {}
    }

    /** Home dabate hi chhota PiP window (setting ON ho to, video chalta rehta hai). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        try {
            if (!pipOn) return
            val e = engine
            val st = e?.playbackState ?: Player.STATE_IDLE
            val ready = e?.playWhenReady ?: false
            if (Build.VERSION.SDK_INT >= 26 && st != Player.STATE_IDLE && ready) {
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

    // WakeLock ab service me hai (ExoPlayer WAKE_MODE_LOCAL) — yahan window
    // flag (screen ON) hi kaafi hai.

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

    /** Error category ke hisaab se UI state dikhao — NO BLACK SCREEN. */
    private fun showErrorState(msg: String, category: String, pageUrl: String) {
        loadingBusy = false
        try { goBtn.isEnabled = true } catch (_: Exception) {}
        runOnUiThread {
            try {
                val cleanMsg = if (msg.contains(":")) msg.substringAfter(":").trim() else msg
                when (category) {
                    "RETRY" -> {
                        status("🔄 $cleanMsg — Retry dabao")
                        showErrorOverlay("🔄 Network/API error", cleanMsg, true) { extractAndPlay(pageUrl, false) }
                    }
                    "UNAVAILABLE" -> {
                        status("⛔ $cleanMsg")
                        showErrorOverlay("⛔ Video unavailable", cleanMsg, false) {}
                    }
                    "AUTH" -> {
                        status("🔐 $cleanMsg")
                        showErrorOverlay("🔐 Login/Auth required", cleanMsg, false) {}
                    }
                    "EXPIRED" -> {
                        status("⏳ $cleanMsg")
                        showErrorOverlay("⏳ Link expired", cleanMsg, true) { extractAndPlay(pageUrl, false) }
                    }
                    else -> {
                        status("❌ $cleanMsg")
                        showErrorOverlay("❌ Error", cleanMsg, true) { extractAndPlay(pageUrl, false) }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /** Player area me error overlay dikhao (black screen ke bajay). */
    private fun showErrorOverlay(title: String, message: String, showRetry: Boolean, onRetry: () -> Unit) {
        try {
            // PlayerView ko hide karo, error message dikhao
            playerView.visibility = View.GONE
            // playerFrame ke upar TextView overlay banao
            val overlay = TextView(this).apply {
                text = "$title\n$message"
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC000000.toInt())
                setPadding(24, 24, 24, 24)
                id = View.generateViewId()
                tag = "error_overlay"
            }
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            playerFrame.addView(overlay, params)

            if (showRetry) {
                val retryBtn = Button(this).apply {
                    text = "🔄 Retry"
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(0xFF22D3EE.toInt())
                    id = View.generateViewId()
                    tag = "error_overlay"
                    setOnClickListener {
                        playerFrame.removeView(overlay)
                        playerFrame.removeView(this)
                        playerView.visibility = View.VISIBLE
                        onRetry()
                    }
                }
                val btnParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { gravity = android.view.Gravity.CENTER }
                btnParams.setMargins(0, 120, 0, 0)
                playerFrame.addView(retryBtn, btnParams)
            }
        } catch (_: Exception) {
            // Fallback: status text me dikhao
            status("$title: $message")
        }
    }

    /** Android 13+ media-notification permission (system dialog only,
     *  GSK UI me koi change nahi). Bina iske lock-screen/shade card nahi dikhta. */
    private fun askNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102
                    )
                }
            }
        } catch (_: Exception) {}
    }

    private fun status(s: String) {
        try {
            if (isFinishing || isDestroyed) return
            runOnUiThread { try { statusText.text = s } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun toast(s: String) {
        // BUG-FIX: dead activity par Toast = BadToken crash. Guard lagao.
        try {
            if (isFinishing || isDestroyed) return
            runOnUiThread {
                try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Activity band ho rahi hai — engine se detach karo (view + listener),
     *  lekin SINGLE engine service me chalta rahe (background + notification).
     *  Baj NA raha ho to service bhi band karo (zombie service nahi).
     *  Engine ko release KABHI mat karo — OWNER service hai. */
    private fun releaseEngine() {
        try { engine?.removeListener(engineListener) } catch (_: Exception) {}
        try { playerView.player = null } catch (_: Exception) {}
        engine = null
        engineAttached = false
        try { pendingPlay = null } catch (_: Exception) {}
        engineWaits = 0
        try { PlayerService.onPlayerReady = null } catch (_: Exception) {}
    }

    // NOTE: Background ON ho to onPause me player rokna NAHI — screen off /
    // Home (PiP) / Back par background audio service me chalta rahe.
    override fun onPause() {
        super.onPause()
        try {
            // position hamesha save (resume + last-played ke liye)
            if (curPageUrl.isNotEmpty()) {
                val p = curPos()
                val d = try { engine?.duration ?: 0L } catch (_: Exception) { 0L }
                saveResume(curPageUrl, p, d)
                curData?.let { saveLastPlayed(it, p) }
            }
            if (!bgPlayOn) {
                try { engine?.pause() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            if (curPageUrl.isNotEmpty()) {
                val p = curPos()
                val d = try { engine?.duration ?: 0L } catch (_: Exception) { 0L }
                saveResume(curPageUrl, p, d)
            }
        } catch (_: Exception) {}
        try { WatchDownloader.cancel("wv") } catch (_: Exception) {}
        try { WatchDownloader.cancel("wa") } catch (_: Exception) {}
        try { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}
        try { showSystemBars() } catch (_: Exception) {}
        try {
            val e = engine
            val playing = try {
                e != null && e.playWhenReady &&
                    (e.playbackState == Player.STATE_BUFFERING || e.playbackState == Player.STATE_READY)
            } catch (_: Exception) { false }
            if (!playing) {
                // Kuch baj nahi raha — idle service ko band karo.
                try {
                    startService(Intent(this, PlayerService::class.java).setAction(PlayerService.ACTION_CLOSE))
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            PlayerService.externalNext = null
            PlayerService.externalPrev = null
        } catch (_: Exception) {}
        releaseEngine()
        super.onDestroy()
    }
}
