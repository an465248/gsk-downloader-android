package com.lvigs.gskdownloader

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class Fmt(
    val id: String,
    val ext: String,
    val type: String,
    val label: String,
    val height: Int,
    val size: Long,
    val url: String,
    val progressive: Boolean,
    val needsMerge: Boolean,
    val vcodec: String = "",
    val acodec: String = "",
)

data class AudioTrack(val url: String, val ext: String, val abr: Long)

/** Up-Next / Recent row: tap karo to bina link-copy wahi video khul jaye. */
data class UpNextItem(
    val id: String,
    val title: String,
    val url: String,
    val thumb: String,
    val durationSec: Long,
)

enum class DlState { IDLE, DOWNLOADING, PAUSED, CANCELLED, DONE, FAILED }

data class DlProgress(
    var state: DlState = DlState.IDLE,
    var frac: Float = 0f,
    var label: String = "Download",
    var speed: String = "",
)

class DownloadJob {
    @Volatile var paused = false
    @Volatile var cancelled = false
    // parallel connections (segments / video+audio) — sab ek saath cancel ho sakein
    private val calls = java.util.Collections.synchronizedSet(mutableSetOf<okhttp3.Call>())
    fun track(c: okhttp3.Call) { calls.add(c) }
    fun untrack(c: okhttp3.Call) { calls.remove(c) }
    fun cancelAll() {
        val copy: List<okhttp3.Call> = synchronized(calls) { calls.toList() }
        for (c in copy) { try { c.cancel() } catch (_: Exception) {} }
    }
}

/** Notification-bar downloads (VidMate jaisa): progress %, speed, complete/fail.
 *  Har download ka apna notification-id (formatId hash) taaki 2-3 downloads
 *  ek saath dikh sakein. Throttle: 500ms me max 1 update (system spam na ho). */
object DownloadNotifier {
    const val CHANNEL = "gsk_downloads"
    private val lastShown = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL, "Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "GSK Downloader progress" }
            )
        } catch (_: Exception) {}
    }

    fun idFor(key: String): Int = 2000 + (key.hashCode() and 0x0fffffff) % 50000

    private fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return false
        }
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun openIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val f = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(ctx, 0, i, f)
    }

    /** Saved file seedha play karo (gallery/player): notification tap = turant play. */
    private fun viewFileIntent(ctx: Context, uri: android.net.Uri, mime: String): PendingIntent {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val f = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(ctx, 1, i, f)
    }

    fun progress(ctx: Context, nid: Int, title: String, pct: Int, speed: String, paused: Boolean) {
        if (!canPost(ctx)) return
        val now = System.currentTimeMillis()
        if (now - (lastShown[nid] ?: 0L) < 600 && pct !in listOf(0, 100)) return
        lastShown[nid] = now
        try {
            val txt = when {
                paused -> "Paused — Resume ke liye tap karo"
                speed.isNotEmpty() -> "$pct% • $speed"
                else -> "$pct%"
            }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title.take(45))
                .setContentText(txt)
                .setContentIntent(openIntent(ctx))
                .setOnlyAlertOnce(true)
                .setOngoing(!paused)
                .setProgress(100, pct.coerceIn(0, 100), false)
                .build()
            NotificationManagerCompat.from(ctx).notify(nid, n)
        } catch (_: Exception) {}
    }

    fun indeterminate(ctx: Context, nid: Int, title: String, txt: String) {
        if (!canPost(ctx)) return
        try {
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title.take(45))
                .setContentText(txt)
                .setContentIntent(openIntent(ctx))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
            NotificationManagerCompat.from(ctx).notify(nid, n)
        } catch (_: Exception) {}
    }

    fun done(ctx: Context, nid: Int, title: String, savedAs: String,
             openUri: android.net.Uri? = null, mime: String = "video/mp4") {
        lastShown.remove(nid)
        if (!canPost(ctx)) return
        try {
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete ✓ — tap karke play karo ▶")
                .setContentText(savedAs.take(60))
                .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$savedAs"))
                .setContentIntent(if (openUri != null) viewFileIntent(ctx, openUri, mime) else openIntent(ctx))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(nid, n)
        } catch (_: Exception) {}
    }

    fun failed(ctx: Context, nid: Int, title: String, reason: String) {
        lastShown.remove(nid)
        if (!canPost(ctx)) return
        try {
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download fail: ${title.take(30)}")
                .setContentText(reason.take(60))
                .setContentIntent(openIntent(ctx))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(nid, n)
        } catch (_: Exception) {}
    }

    fun cancel(ctx: Context, nid: Int) {
        lastShown.remove(nid)
        try { NotificationManagerCompat.from(ctx).cancel(nid) } catch (_: Exception) {}
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var goBtn: Button
    private lateinit var statusText: TextView
    private lateinit var metaText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: FormatAdapter
    // Sidebar (drawer) v1.9
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var mainScroll: ScrollView
    private val qPills = HashMap<String, Button>()
    // preview views
    private lateinit var previewCard: LinearLayout
    private lateinit var previewTitle: TextView
    private lateinit var previewMeta: TextView
    private lateinit var thumbPreview: ImageView
    private lateinit var previewPlayerView: PlayerView
    private lateinit var previewPlayBtn: Button
    private var exo: ExoPlayer? = null
    // 10s skip + Next/Prev controls (preview me hi, bina link copy)
    private lateinit var previewControls: LinearLayout
    private lateinit var previewBackBtn: Button
    private lateinit var previewFwdBtn: Button
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    // Up Next (playlist) + Recent (bina copy dobara kholo)
    private lateinit var upNextTitle: TextView
    private lateinit var upNextRecycler: RecyclerView
    private lateinit var recentHeader: LinearLayout
    private lateinit var recentTitle: TextView
    private lateinit var recentRecycler: RecyclerView
    private lateinit var recentCloseBtn: Button
    private lateinit var recentClearBtn: Button
    // User ne Recent tab band kiya ho to true (persisted) — nayi video aane par khud khul jayega.
    private var recentHidden: Boolean = false
    private lateinit var upNextAdapter: UpNextAdapter
    private lateinit var recentAdapter: UpNextAdapter
    private lateinit var cookieStatusText: TextView
    private var upNextList: List<UpNextItem> = emptyList()
    private var upNextIndex: Int = -1
    private var playlistTitle: String = ""

    private var allFormats: List<Fmt> = emptyList()
    private var bestAudio: AudioTrack? = null
    private var bestAudioMp4: AudioTrack? = null
    private var bestAudioWebm: AudioTrack? = null
    private var videoTitle: String = "video"
    private var videoThumb: String = ""
    private var previewUrl: String = ""
    private var previewHasAudio: Boolean = false
    private var lastPageUrl: String = ""
    private var durationMs: Long = 0
    private var pyReady = false
    private var selectedQ: String = "max" // website jaisa quality filter
    // Bundled cookies.txt (Instagram/Facebook login) ka internal-storage path.
    // Khali ho to public videos bina login ke nikalti hain.
    @Volatile private var cookiePath: String = ""
    // Har Get Video par badhta session no. — 2 alag videos ke same formatId
    // (jaise dono me "134") ke jobs/notifications aapas me takrayein nahi.
    // Isi se ek-saath kayi downloads sahi chalte hain (VidMate jaisa queue).
    @Volatile private var fetchSeq = 0
    private fun dlKey(fmt: Fmt): String = "$fetchSeq::${fmt.id}"

    /** Background download zinda rahe: download chalu hote hi foreground service
     *  start (notification bar wala), sab khatm/cancel par auto-stop. */
    private fun startFg() {
        try {
            val i = Intent(this, GskDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (_: Exception) {}
    }

    private fun syncService() {
        try {
            if (jobs.isEmpty()) stopService(Intent(this, GskDownloadService::class.java))
        } catch (_: Exception) {}
    }
    // Engine ready hone se pehle aaya share-link (auto-fetch ke liye pending rakho)
    private var pendingSharedLink: String? = null
    // Watch screen se 1-tap download (fetch ke baad best file auto-start)
    private var pendingAutodl: String? = null
    private var autodlAfterFetch = false

    private val jobs = ConcurrentHashMap<String, DownloadJob>()

    // Extract ke liye EK shared single-thread executor (har Get Video par naya
    // executor banane se purani atki search ka thread leak hota tha — agli search
    // uske peeche queue ho jati thi = "bas scanning hota rehta, kuch nahi".
    // Ab nayi search purani ko cancel karke turant chalti hai (max 1 stale thread).
    // LIVE-FIX ("fetch par atka"): singleThread me purana atka extract naye ko
    // queue me rok deta tha (75s + purana = 2-3 min stuck). Cached-pool me naya
    // extract turant parallel chalta hai — purana background me khud marega.
    private val extractExec = java.util.concurrent.Executors.newCachedThreadPool()
    @Volatile private var extractFuture: java.util.concurrent.Future<String>? = null

    private val http = OkHttpClient.Builder()
        // SPEED-LOCK FIX: OkHttp default me 1 host par max 5 parallel connections
        // deta hai — segments queue me atke rehte the (10-116KB/s par atka lagta tha).
        // Ab 64 parallel khule hain — video+audio ke 16-16 segments ek saath full speed.
        .dispatcher(Dispatcher().apply { maxRequests = 128; maxRequestsPerHost = 64 })
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        // Slow-throttle link par badi file ghanton chal sakti hai — read timeout
        // lagne par beech me "fail" hota tha. 0 = no timeout (retry/pause logic khud sambhalta hai).
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    // short-timeout client sirf thumbnail ke liye
    private val httpShort = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // server-fallback client (Render/server /api/extract — 90s tak jawab de sakta hai)
    private val httpLong = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Preview dekhte time screen off na ho (1-min timeout fix, YouTube jaisa)
        try { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } catch (_: Exception) {}

        // Notification channel + Android 13+ permission (notification bar progress ke liye)
        DownloadNotifier.ensureChannel(this)
        askNotificationPermission()

        urlInput = findViewById(R.id.urlInput)
        goBtn = findViewById(R.id.goBtn)
        val pasteBtn: ImageButton = findViewById(R.id.pasteBtn)
        statusText = findViewById(R.id.statusText)
        metaText = findViewById(R.id.metaText)
        recycler = findViewById(R.id.recycler)
        // website jaise quality pills
        val pillIds = mapOf(
            "max" to R.id.qMax, "4320" to R.id.q4320, "2160" to R.id.q2160,
            "1440" to R.id.q1440, "1080" to R.id.q1080, "720" to R.id.q720,
            "480" to R.id.q480, "360" to R.id.q360, "240" to R.id.q240,
            "144" to R.id.q144, "audio" to R.id.qAudio,
        )
        for ((q, id) in pillIds) {
            val b: Button = findViewById(id)
            qPills[q] = b
            b.setOnClickListener { selectedQ = q; refreshList(); stylePills() }
        }
        previewCard = findViewById(R.id.previewCard)
        previewTitle = findViewById(R.id.previewTitle)
        previewMeta = findViewById(R.id.previewMeta)
        thumbPreview = findViewById(R.id.thumbPreview)
        previewPlayerView = findViewById(R.id.previewPlayer)
        previewPlayBtn = findViewById(R.id.previewPlayBtn)
        previewControls = findViewById(R.id.previewControls)
        previewBackBtn = findViewById(R.id.previewBack)
        previewFwdBtn = findViewById(R.id.previewFwd)
        prevBtn = findViewById(R.id.prevBtn)
        nextBtn = findViewById(R.id.nextBtn)
        upNextTitle = findViewById(R.id.upNextTitle)
        upNextRecycler = findViewById(R.id.upNextRecycler)
        recentHeader = findViewById(R.id.recentHeader)
        recentTitle = findViewById(R.id.recentTitle)
        recentRecycler = findViewById(R.id.recentRecycler)
        recentCloseBtn = findViewById(R.id.recentCloseBtn)
        recentClearBtn = findViewById(R.id.recentClearBtn)
        recentHidden = getSharedPreferences("gsk_recent", MODE_PRIVATE)
            .getBoolean("hidden", false)
        cookieStatusText = findViewById(R.id.cookieStatusText)

        upNextAdapter = UpNextAdapter(
            { item -> openUpNext(item) },
            onLongTap = { item -> showUpNextMenu(item) },
        )
        upNextRecycler.layoutManager = LinearLayoutManager(this)
        upNextRecycler.adapter = upNextAdapter
        upNextRecycler.isNestedScrollingEnabled = false
        // Recent: tap = kholo, ✕ = wahi item hatao (Up Next me ✕ nahi dikhta)
        recentAdapter = UpNextAdapter(
            { item -> openUpNext(item) },
            { item -> removeRecentItem(item) },
            showClose = true,
            onLongTap = { item -> showUpNextMenu(item) },
        )
        recentRecycler.layoutManager = LinearLayoutManager(this)
        recentRecycler.adapter = recentAdapter
        recentRecycler.isNestedScrollingEnabled = false
        // Recent header buttons: ✕ Band (hide) + 🗑 Clear (sab delete)
        recentCloseBtn.setOnClickListener { closeRecent() }
        recentClearBtn.setOnClickListener { clearRecent() }
        // ===== SIDEBAR (v1.9): hamburger + drawer menu =====
        drawerLayout = findViewById(R.id.drawerLayout)
        mainScroll = findViewById(R.id.mainScroll)
        findViewById<Button>(R.id.menuBtn).setOnClickListener {
            try { drawerLayout.openDrawer(GravityCompat.START) } catch (_: Exception) {}
        }
        findViewById<Button>(R.id.drawerDownload).setOnClickListener {
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
            try { mainScroll.post { mainScroll.smoothScrollTo(0, 0) } } catch (_: Exception) {}
        }
        findViewById<Button>(R.id.drawerPlayer).setOnClickListener {
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
            openPlayer()
        }
        findViewById<Button>(R.id.drawerRecent).setOnClickListener {
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
            try {
                if (loadRecentList().isEmpty()) {
                    toast("Recent khaali hai — pehle koi video kholo.")
                } else {
                    if (recentHidden) {
                        recentHidden = false
                        getSharedPreferences("gsk_recent", MODE_PRIVATE).edit()
                            .putBoolean("hidden", false).apply()
                        renderRecent()
                    }
                    mainScroll.post { mainScroll.smoothScrollTo(0, recentHeader.top) }
                }
            } catch (_: Exception) {}
        }
        findViewById<Button>(R.id.drawerShare).setOnClickListener {
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
            shareApp()
        }
        findViewById<Button>(R.id.drawerAbout).setOnClickListener {
            try { drawerLayout.closeDrawers() } catch (_: Exception) {}
            showAbout()
        }

        adapter = FormatAdapter(
            onDownload = { fmt, key -> onFormatClick(fmt, key) },
            onPauseToggle = { fmt, key -> togglePause(fmt, key) },
            onCancel = { fmt, key -> cancelDownload(fmt, key) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.isNestedScrollingEnabled = false

        goBtn.isEnabled = false
        status("Engine taiyaar ho raha hai... (pehli baar 10-20s)")

        // Cookies (Instagram/Facebook login-wall bypass):
        // PREFERRED: App me "IG Login / FB Login" dabao — WebView me apne account
        // se login karo, cookies auto-save (har user apne phone par ek baar).
        // FALLBACK (purana): file manager se Android/data/.../files/ me cookies.txt
        // rakho, ya APK assets se — ye mile to wahi istemal hogi.
        Thread {
            try {
                val f = File(filesDir, "cookies.txt")
                try {
                    assets.open("cookies.txt").use { inp ->
                        val bytes = inp.readBytes()
                        if (bytes.isNotEmpty()) {
                            val cur = try { f.readBytes() } catch (_: Exception) { ByteArray(0) }
                            if (!cur.contentEquals(bytes)) f.writeBytes(bytes)
                        }
                    }
                } catch (_: Exception) { /* assets me cookies nahi — public videos bina login ke */ }
                refreshCookiePath()
            } catch (_: Exception) {}
        }.start()

        // Python (yt-dlp) phone par start karo — background me
        Thread {
            try {
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                Python.getInstance().getModule("gsk")
                pyReady = true
                runOnUiThread {
                    goBtn.isEnabled = true
                    refreshCookiePath()
                    renderCookieStatus()
                    status("Link paste karo aur Get Video dabao." + cookieLine())
                    // onCreate ke time aaya share-link ab fetch karo (auto)
                    val auto0 = pendingAutodl ?: intent.getStringExtra(PlayerActivity.EXTRA_AUTODL)
                    pendingAutodl = null
                    if (!auto0.isNullOrEmpty()) {
                        urlInput.setText(auto0)
                        autodlAfterFetch = true
                        toast("1-tap download: fetch ho raha...")
                        fetchFormats()
                        return@runOnUiThread
                    }
                    val pend = pendingSharedLink ?: extractSharedLink(intent)
                    pendingSharedLink = null
                    if (!pend.isNullOrEmpty()) {
                        urlInput.setText(pend)
                        toast("Shared link mila! Fetch ho raha...")
                        fetchFormats()
                    } else handleIntent(intent)
                }
            } catch (e: Exception) {
                runOnUiThread { status("Engine error: ${e.message}") }
            }
        }.start()

        goBtn.setOnClickListener { fetchFormats() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { fetchFormats(); true } else false
        }
        pasteBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                val link = Regex("https?://[^\\s]+").find(t)?.value ?: t
                urlInput.setText(link)
                toast("Paste ho gaya! Preview load ho raha hai...")
                fetchFormats()
            } else toast("Clipboard khaali hai.")
        }
        selectedQ = "max"
        stylePills()

        previewPlayBtn.setOnClickListener { playPreview() }
        // v1.9: preview ko full Ad-Free Player me kholo (sidebar player)
        try {
            findViewById<Button>(R.id.fullPlayerBtn).setOnClickListener { openPlayer() }
        } catch (_: Exception) {}
        // 10s skip: user preview me aage-peeche kar sake
        previewBackBtn.setOnClickListener { seekPreviewBy(-10_000) }
        previewFwdBtn.setOnClickListener { seekPreviewBy(10_000) }
        prevBtn.setOnClickListener { playNeighbour(-1) }
        nextBtn.setOnClickListener { playNeighbour(1) }

        // 1-tap login: WebView me IG/FB login -> cookies auto-save
        val igBtn: Button = findViewById(R.id.igLoginBtn)
        val fbBtn: Button = findViewById(R.id.fbLoginBtn)
        val srvBtn: Button = findViewById(R.id.serverBtn)
        igBtn.setOnClickListener { openLogin("instagram") }
        fbBtn.setOnClickListener { openLogin("facebook") }
        srvBtn.setOnClickListener { showServerDialog() }
        renderRecent()

        // FAQ tap-to-open (website jaisa) + footer me version
        setupFaq(R.id.fq1, R.id.fa1)
        setupFaq(R.id.fq2, R.id.fa2)
        setupFaq(R.id.fq3, R.id.fa3)
        setupFaq(R.id.fq4, R.id.fa4)
        setupFaq(R.id.fq5, R.id.fa5)
        try {
            val ft: TextView = findViewById(R.id.footerText)
            ft.text = "© 2026 LVIGS Pvt. Ltd. • v3.1\n🇮🇳 India • English • INR"
        } catch (_: Exception) {}
    }

    private fun setupFaq(qId: Int, aId: Int) {
        try {
            val q: TextView = findViewById(qId)
            val a: TextView = findViewById(aId)
            q.setOnClickListener {
                a.visibility = if (a.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        } catch (_: Exception) {}
    }

    /** Asli login cookies hain ya sirf template/comment-lines? */
    private fun hasRealCookies(f: File): Boolean {
        return try {
            if (!f.exists() || f.length() <= 2) return false
            f.bufferedReader().useLines { seq ->
                seq.any { line ->
                    val t = line.trim()
                    t.isNotEmpty() && !t.startsWith("#") &&
                        (t.contains("\t") || t.contains("instagram.com") ||
                            t.contains("facebook.com") || t.contains("youtube.com"))
                }
            }
        } catch (_: Exception) { false }
    }

    /** Best cookies file chuno: pehle user-dropped external (bina rebuild),
     *  phir internal (assets se). cookiePath update karke batata hai ki
     *  asli login cookies mili ya nahi. */
    private fun refreshCookiePath(): Boolean {
        return try {
            val ext = File(getExternalFilesDir(null), "cookies.txt")
            val internal = File(filesDir, "cookies.txt")
            cookiePath = when {
                hasRealCookies(ext) -> ext.absolutePath
                hasRealCookies(internal) -> internal.absolutePath
                ext.exists() && ext.length() > 2 -> ext.absolutePath
                internal.exists() && internal.length() > 2 -> internal.absolutePath
                else -> ""
            }
            cookiePath.isNotEmpty() && hasRealCookies(File(cookiePath))
        } catch (_: Exception) { false }
    }

    private fun cookieLine(): String =
        if (cookiePath.isNotEmpty() && hasRealCookies(File(cookiePath)))
            " • 🔑 IG/FB login ON"
        else " • 🔑 IG/FB login OFF (IG/FB ke liye upar Login dabao)"

    /** Cookie status line + login buttons ka highlight update karo. */
    private fun renderCookieStatus() {
        try {
            val on = cookiePath.isNotEmpty() && hasRealCookies(File(cookiePath))
            val srv = getServerBase()
            cookieStatusText.text = (if (on)
                "🔑 Login ON — private videos unlock ✓"
            else
                "🔑 Login OFF (public FB/YT bina login; IG ke liye Server ya IG Login)") +
                (if (srv.isNotEmpty()) "  •  🌐 Server ON" else "  •  🌐 Server OFF (IG ke liye Settings me URL dalo)")
        } catch (_: Exception) {}
    }

    // ---------------- SERVER-FALLBACK (Instagram bina per-user login) ----------------
    // Publisher apna Render/server URL yahan hardcode kar de to har user ko
    // bina kuch kiye server-fallback milega (server ke admin cookies se IG nikalta
    // hai, download phir bhi user ke phone par hota hai). Khali ho to user
    // Settings (🌐 Server button) me khud daal sakta hai.
    // e.g. "https://gsk-downloader.onrender.com"
    private fun getServerBase(): String {
        return try {
            val s = getSharedPreferences("gsk_settings", MODE_PRIVATE)
                .getString("server_url", "")?.trim().orEmpty().trimEnd('/')
            if (s.isNotEmpty()) s else DEFAULT_SERVER_URL.trimEnd('/')
        } catch (_: Exception) { DEFAULT_SERVER_URL.trimEnd('/') }
    }

    private fun saveServerBase(v: String) {
        try {
            getSharedPreferences("gsk_settings", MODE_PRIVATE).edit()
                .putString("server_url", v.trim().trimEnd('/')).apply()
        } catch (_: Exception) {}
        renderCookieStatus()
    }

    private fun isInstagramUrl(u: String): Boolean {
        val l = u.lowercase()
        return "instagram.com" in l || "instagr.am" in l
    }

    private fun isWallError(msg: String): Boolean {
        val l = msg.lowercase()
        return "ig login" in l || "login-wall" in l || "login maang" in l || "login-wall" in l
    }

    /** Server se formats nikalo (admin cookies use hote hain). null = fail. */
    private fun fetchViaServer(pageUrl: String): JSONObject? {
        return try {
            val base = getServerBase()
            if (base.isEmpty()) return null
            val payload = JSONObject().put("url", pageUrl).toString()
            val req = Request.Builder()
                .url("$base/api/extract")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                .build()
            httpLong.newCall(req).execute().use { res ->
                val txt = res.body?.string() ?: return null
                if (txt.isEmpty()) return null
                JSONObject(txt)
            }
        } catch (_: Exception) { null }
    }

    private fun showServerDialog() {
        try {
            val et = EditText(this)
            et.hint = "https://tumhara-server.onrender.com"
            et.setText(getServerBase())
            et.isSingleLine = true
            AlertDialog.Builder(this)
                .setTitle("🌐 Server URL (Instagram bina-login ke liye)")
                .setMessage("Apna gsk-downloader server URL dalo. Instagram links server ke login se nikalenge — tumhe kuch nahi karna padega. Khali karke Save = OFF.")
                .setView(et)
                .setPositiveButton("Save") { _, _ ->
                    saveServerBase(et.text.toString())
                    toast(if (getServerBase().isNotEmpty()) "Server ON ✓ — IG bina login chalega." else "Server OFF.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) { toast("Dialog nahi khula: ${e.message}") }
    }

    /** In-app WebView login kholo (IG/FB). Wapas aane par cookies refresh. */
    private fun openLogin(site: String) {
        try {
            val i = Intent(this, LoginActivity::class.java)
            i.putExtra("site", site)
            @Suppress("DEPRECATION")
            startActivityForResult(i, if (site == "instagram") 201 else 202)
            toast(if (site == "instagram") "Instagram login khul raha..." else "Facebook login khul raha (HD unlock)...")
        } catch (e: Exception) {
            toast("Login nahi khul paya: ${e.message}")
        }
    }

    @Deprecated("use registerForActivityResult in future")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 201 || requestCode == 202) {
            refreshCookiePath()
            renderCookieStatus()
            if (resultCode == RESULT_OK) {
                status("Login save ho gaya ✓" + cookieLine() + " — ab Get Video dabao.")
                toast("Login ON ✓ — ab video fetch karo.")
            } else {
                status("Login window band." + cookieLine())
            }
        }
    }

    /** Preview me 10s aage/peeche (user chahe to skip kar sake). */
    private fun seekPreviewBy(deltaMs: Long) {
        try {
            val p = exo ?: run { toast("Pehle Preview play karo."); return }
            val dur = try { p.duration } catch (_: Exception) { -1L }
            var to = (try { p.currentPosition } catch (_: Exception) { 0L }) + deltaMs
            if (to < 0) to = 0
            if (dur > 0 && to > dur) to = dur
            p.seekTo(to)
            toast(if (deltaMs < 0) "⏪ 10s peeche" else "10s aage ⏩")
        } catch (_: Exception) { toast("Seek nahi ho paya.") }
    }

    /** Up-Next me pichla/agla video (bina link copy). */
    private fun playNeighbour(dir: Int) {
        if (upNextList.isEmpty()) { toast("Up Next khaali hai (playlist link ho to aayega)."); return }
        var idx = upNextIndex + dir
        if (idx < 0) idx = 0
        if (idx >= upNextList.size) idx = upNextList.size - 1
        openUpNext(upNextList[idx])
    }

    /** Up-Next/Recent tap: bina copy-paste wahi video fetch karo. */
    private fun openUpNext(item: UpNextItem) {
        if (item.url.isEmpty()) return
        urlInput.setText(item.url)
        toast("Next video load ho raha: ${item.title.take(40)}...")
        fetchFormats()
    }

    /** Row long-press: Play / Copy link / Download — wahi se sab (YouTube jaisa). */
    private fun showUpNextMenu(item: UpNextItem) {
        try {
            if (item.url.isEmpty()) return
            AlertDialog.Builder(this)
                .setTitle(item.title.take(60).ifEmpty { "Video" })
                .setItems(arrayOf("▶ Play", "📋 Link copy", "⬇ Download (1-tap)", "📤 Share")) { _, which ->
                    when (which) {
                        0 -> openUpNext(item)
                        1 -> {
                            try {
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("link", item.url))
                                toast("Link copy ho gaya ✓")
                            } catch (_: Exception) { toast("Copy nahi ho paya.") }
                        }
                        2 -> {
                            urlInput.setText(item.url)
                            autodlAfterFetch = true
                            toast("1-tap download: fetch ho raha...")
                            fetchFormats()
                        }
                        3 -> {
                            try {
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, item.title + "\n" + item.url)
                                }
                                startActivity(Intent.createChooser(i, "Share video link"))
                            } catch (_: Exception) {}
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: Exception) {}
    }

    /** Current video ko Recent me sabse upar rakho (max 15, duplicate hatao).
     *  Nayi video aane par user-banded Recent khud khul jata hai. */
    private fun pushRecent(item: UpNextItem) {
        try {
            val prefs = getSharedPreferences("gsk_recent", MODE_PRIVATE)
            val cur = loadRecentList().toMutableList()
            cur.removeAll { it.url == item.url }
            cur.add(0, item)
            while (cur.size > 15) cur.removeAt(cur.size - 1)
            val json = org.json.JSONArray()
            for (r in cur) {
                val o = org.json.JSONObject()
                o.put("id", r.id); o.put("title", r.title); o.put("url", r.url)
                o.put("thumb", r.thumb); o.put("dur", r.durationSec)
                json.put(o)
            }
            prefs.edit().putString("items", json.toString()).putBoolean("hidden", false).apply()
            recentHidden = false
            renderRecent()
        } catch (_: Exception) {}
    }

    /** Recent tab band karo (hide) — history delete nahi hoti, nayi video par khul jayega. */
    private fun closeRecent() {
        try {
            recentHidden = true
            getSharedPreferences("gsk_recent", MODE_PRIVATE).edit().putBoolean("hidden", true).apply()
            renderRecent()
            toast("Recent band kar diya (nayi video par khul jayega).")
        } catch (_: Exception) {}
    }

    /** Poori Recent history delete karo. */
    private fun clearRecent() {
        try {
            getSharedPreferences("gsk_recent", MODE_PRIVATE).edit()
                .putString("items", "[]").putBoolean("hidden", false).apply()
            recentHidden = false
            renderRecent()
            toast("Recent clear ho gaya.")
        } catch (_: Exception) {}
    }

    /** Ek Recent item hatao (row ka ✕). */
    private fun removeRecentItem(item: UpNextItem) {
        try {
            if (item.url.isEmpty()) return
            val prefs = getSharedPreferences("gsk_recent", MODE_PRIVATE)
            val cur = loadRecentList().toMutableList()
            val n0 = cur.size
            cur.removeAll { it.url == item.url }
            if (cur.size == n0) return
            val json = org.json.JSONArray()
            for (r in cur) {
                val o = org.json.JSONObject()
                o.put("id", r.id); o.put("title", r.title); o.put("url", r.url)
                o.put("thumb", r.thumb); o.put("dur", r.durationSec)
                json.put(o)
            }
            prefs.edit().putString("items", json.toString()).apply()
            renderRecent()
            toast("Recent se hataya.")
        } catch (_: Exception) {}
    }

    private fun loadRecentList(): List<UpNextItem> {
        return try {
            val prefs = getSharedPreferences("gsk_recent", MODE_PRIVATE)
            val s = prefs.getString("items", "[]") ?: "[]"
            val arr = org.json.JSONArray(s)
            val out = ArrayList<UpNextItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(UpNextItem(
                    o.optString("id"), o.optString("title"), o.optString("url"),
                    o.optString("thumb"), o.optLong("dur")))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun renderRecent() {
        try {
            val list = loadRecentList()
            if (list.isEmpty() || recentHidden) {
                recentHeader.visibility = View.GONE
                recentTitle.visibility = View.GONE
                recentRecycler.visibility = View.GONE
            } else {
                recentHeader.visibility = View.VISIBLE
                recentTitle.visibility = View.VISIBLE
                recentRecycler.visibility = View.VISIBLE
                recentAdapter.setItems(list)
            }
        } catch (_: Exception) {}
    }

    /** Playlist Up-Next list dikhao + Prev/Next buttons. */
    private fun renderUpNext() {
        try {
            if (upNextList.isEmpty()) {
                upNextTitle.visibility = View.GONE
                upNextRecycler.visibility = View.GONE
                prevBtn.isEnabled = false
                nextBtn.isEnabled = false
            } else {
                upNextTitle.visibility = View.VISIBLE
                upNextTitle.text = if (playlistTitle.isNotEmpty())
                    "Up Next • $playlistTitle (${upNextList.size}) — tap karo, link copy nahi"
                else
                    "Up Next (${upNextList.size}) — tap karo, link copy nahi"
                upNextRecycler.visibility = View.VISIBLE
                upNextAdapter.setItems(upNextList)
                prevBtn.isEnabled = upNextIndex > 0
                nextBtn.isEnabled = upNextIndex < upNextList.size - 1
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        // saare pending downloads + extract cancel karo
        for (j in jobs.values) { j.cancelled = true; j.cancelAll() }
        try { extractFuture?.cancel(true) } catch (_: Exception) {}
        try { extractExec.shutdownNow() } catch (_: Exception) {}
        try { stopService(Intent(this, GskDownloadService::class.java)) } catch (_: Exception) {}
        releasePreview()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Watch screen se 1-tap download aaya ho to seedha fetch+download
        val auto = intent.getStringExtra(PlayerActivity.EXTRA_AUTODL)
        if (!auto.isNullOrEmpty()) {
            if (pyReady) {
                urlInput.setText(auto)
                autodlAfterFetch = true
                toast("1-tap download: fetch ho raha...")
                fetchFormats()
            } else {
                pendingAutodl = auto
                toast("1-tap download: engine ready hote hi start hoga...")
            }
            return
        }
        // Engine abhi ready nahi to link pending rakho (auto-fetch ready par hoga)
        val link = extractSharedLink(intent)
        if (!link.isNullOrEmpty()) {
            if (pyReady) {
                urlInput.setText(link)
                toast("Shared link mila! Fetch ho raha...")
                fetchFormats()
            } else {
                pendingSharedLink = link
                toast("Shared link mila! Engine ready hote hi fetch hoga...")
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101
                )
            }
        }
    }

    /** Kisi bhi app se aaya link nikalo:
     *  YouTube/Instagram/Facebook/X/Browser Share (SEND) + link open (VIEW) +
     *  selected-text share (PROCESS_TEXT). EXTRA_TEXT ke saath EXTRA_SUBJECT
     *  bhi check karo — kai apps link wahan bhejte hain. */
    private fun extractSharedLink(intent: Intent?): String? {
        if (intent == null) return null
        val action = intent.action ?: return null
        // 1) Normal share: YouTube -> Share -> GSK (text/plain)
        if (action == Intent.ACTION_SEND) {
            val texts = listOf(
                intent.getStringExtra(Intent.EXTRA_TEXT),
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
                intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString(),
            )
            for (t in texts) {
                val link = Regex("https?://[^\\s'\"<>]+").find(t.orEmpty())?.value?.trimEnd('.', ',', ')', ']', '!')
                if (!link.isNullOrEmpty()) return link
            }
            val single = texts.firstOrNull { !it.isNullOrBlank() }?.trim()
            if (!single.isNullOrEmpty()) return single
        }
        // 2) Browser/chat me link tap -> Open with GSK (VIEW + BROWSABLE)
        if (action == Intent.ACTION_VIEW) {
            val d: Uri? = intent.data
            if (d != null && (d.scheme == "http" || d.scheme == "https")) return d.toString()
        }
        // 3) Kahi text select karke share (PROCESS_TEXT)
        if (action == Intent.ACTION_PROCESS_TEXT) {
            val t = intent.getStringExtra(Intent.EXTRA_PROCESS_TEXT)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            val link = Regex("https?://[^\\s'\"<>]+").find(t.orEmpty())?.value
            if (!link.isNullOrEmpty()) return link.trimEnd('.', ',', ')', ']', '!')
            if (!t.isNullOrBlank()) return t.trim()
        }
        return null
    }

    // Share-sheet: YouTube -> Share -> GSK Downloader (auto link fetch)
    private fun handleIntent(intent: Intent?) {
        val link = extractSharedLink(intent) ?: return
        if (link.isNotEmpty()) {
            urlInput.setText(link)
            fetchFormats()
        }
    }

    private fun stylePills() {
        // selected pill highlight (bg_btn + dark text), baki normal
        for ((q, b) in qPills) {
            if (q == selectedQ) {
                b.setBackgroundResource(R.drawable.bg_btn)
                b.setTextColor(0xFF04070F.toInt())
            } else {
                b.setBackgroundResource(R.drawable.bg_card)
                b.setTextColor(0xFFE9EEFB.toInt())
            }
        }
    }

    private fun status(s: String) {
        try {
            if (isFinishing || isDestroyed) return
            runOnUiThread { try { statusText.text = s } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun toast(s: String) {
        // BUG-FIX: dead activity par Toast/views = crash. Guard lagao.
        try {
            if (isFinishing || isDestroyed) return
            runOnUiThread {
                try { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ---------------- EXTRACT (user ke IP/network se, phone par) ----------------
    private fun fetchFormats() {
        if (!pyReady) { toast("Engine taiyaar ho raha hai, ruk jao."); return }
        refreshCookiePath() // user ne cookies.txt abhi drop ki ho to turant pakdo
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) { toast("Pehle video ka URL paste karo!"); return }
        if (!url.startsWith("http")) { toast("URL http(s):// se shuru hona chahiye."); return }
        lastPageUrl = url
        fetchSeq++ // naya session — purane chalu downloads apni key par chalte rahenge
        val mySeq = fetchSeq

        goBtn.isEnabled = false
        goBtn.text = "Scanning..."
        status("Video info + preview nikal rahe hain (aapke network se, kuch second lagega)...")
        adapter.setItems(emptyList(), mySeq)
        metaText.text = ""
        previewCard.visibility = View.GONE
        upNextList = emptyList()
        upNextIndex = -1
        playlistTitle = ""
        runOnUiThread { renderUpNext() }
        stopPreview()
        previewPlayerView.visibility = View.GONE
        thumbPreview.visibility = View.VISIBLE
        thumbPreview.setImageDrawable(null)

        Thread {
            try {
                val py = Python.getInstance()
                // Purani atki search ho to use cancel karke nayi turant chalao.
                // (Cached-pool: naya parallel chalta hai, purana background me marega.)
                try { extractFuture?.cancel(true) } catch (_: Exception) {}
                // gsk.py fast-fail + probe non-blocking: worst ~30s. 45s cap —
                // user 30-min wait nahi karega, 45s me dobara-try clear milega.
                val fut = extractExec.submit<String> {
                    py.getModule("gsk").callAttr("extract", url, cookiePath, 0).toString()
                }
                extractFuture = fut
                val json: String = try {
                    fut.get(45, java.util.concurrent.TimeUnit.SECONDS)
                } catch (te: java.util.concurrent.TimeoutException) {
                    try { fut.cancel(true) } catch (_: Exception) {}
                    status("Site ne 45s me jawab nahi diya — net check karke dobara Get Video dabao.")
                    toast("Timeout: koi response nahi. Dobara try karo.")
                    return@Thread
                } catch (ce: java.util.concurrent.CancellationException) {
                    return@Thread // purani search cancel hui — nayi chal rahi hai
                } catch (ee: java.util.concurrent.ExecutionException) {
                    status("Error: ${ee.cause?.message ?: ee.message}")
                    return@Thread
                }
                if (mySeq != fetchSeq) return@Thread // beech me nayi search — purana result chhodo
                // Instagram + server configured ho to SERVER SE PEHLE nikalo
                // (phone par login-wall pakka fail hoga; server ke admin cookies
                // se sab users BINA LOGIN download karenge — download phir bhi
                // phone par hota hai, server sirf link nikalta hai).
                val serverFirst = isInstagramUrl(url) && getServerBase().isNotEmpty()
                if (serverFirst) {
                    status("Server se link nikal rahe hain (bina login)...")
                    val so = fetchViaServer(url)
                    if (so != null && !so.has("error") && mySeq == fetchSeq) {
                        onExtractSuccess(so, url)
                        return@Thread
                    }
                    // server fail -> neeche on-device try (user-login fallback)
                    status("Server se nahi nikla — phone se try ho raha...")
                }
                val o = JSONObject(json)
                if (o.has("error") && !serverFirst) {
                    // On-device wall (khaas taur Instagram) + server set ho to
                    // server-fallback: user ko kuch nahi karna padega.
                    val msg = o.optString("error")
                    val so = if (isWallError(msg) && getServerBase().isNotEmpty()) {
                        status("Phone se nahi nikla — server se try ho raha (bina login)...")
                        fetchViaServer(url)
                    } else null
                    if (so != null && !so.has("error") && mySeq == fetchSeq) {
                        onExtractSuccess(so, url)
                    } else if (mySeq == fetchSeq) {
                        val extra = if (so != null && so.has("error"))
                            " (Server: ${so.optString("error").take(90)})" else ""
                        status("Error: $msg$extra")
                    }
                } else if (!o.has("error")) {
                    onExtractSuccess(o, url)
                } else if (mySeq == fetchSeq) {
                    status("Error: ${o.optString("error")}")
                }
            } catch (e: Exception) {
                status("Error: ${e.message}")
            } finally {
                runOnUiThread {
                    goBtn.isEnabled = true
                    goBtn.text = "Get Video"
                }
            }
        }.start()
    }

    /** Extract-success (on-device YA server-fallback) — formats/preview/UpNext/Recent.
     *  Background thread se call karo (UI kaam runOnUiThread me hai). */
    private fun onExtractSuccess(o: JSONObject, pageUrl: String) {
                    videoTitle = o.optString("title", "video")
                    videoThumb = o.optString("thumbnail", "")
                    previewUrl = o.optString("preview_url", "")
                    previewHasAudio = o.optBoolean("preview_has_audio", false)
                    durationMs = o.optLong("duration", 0) * 1000
                    val author = o.optString("author", "")
                    val source = o.optString("source", "")
                    val arr = o.getJSONArray("formats")
                    val list = ArrayList<Fmt>()
                    for (i in 0 until arr.length()) {
                        val f = arr.getJSONObject(i)
                        list.add(
                            Fmt(
                                f.optString("format_id"), f.optString("ext"),
                                f.optString("type"), f.optString("label"),
                                f.optInt("height"), f.optLong("filesize"),
                                f.optString("url"), f.optBoolean("progressive"),
                                f.optBoolean("needs_merge"),
                                f.optString("vcodec", ""), f.optString("acodec", ""),
                            )
                        )
                    }
                    allFormats = list
                    // HLS/special sites (1600+ wada): server ffmpeg se mp4 — extra row.
                    // Download phone par hi hota hai (resumable), server sirf banata hai.
                    try {
                        val hlsArr = o.optJSONArray("hls")
                        val srv = getServerBase()
                        if (hlsArr != null && hlsArr.length() > 0 && srv.isNotEmpty()) {
                            val srvUrl = "$srv/api/server-download?page=" +
                                URLEncoder.encode(pageUrl, "utf-8") + "&filename=" +
                                URLEncoder.encode("$videoTitle - Server HD.mp4", "utf-8")
                            list.add(Fmt("server-hd", "mp4", "video", "🌐 Server HD", 720, 0,
                                srvUrl, true, false, "avc1", "mp4a"))
                            allFormats = list
                        }
                    } catch (_: Exception) {}
                    fun parseAudio(key: String): AudioTrack? {
                        if (o.isNull(key)) return null
                        val b = o.getJSONObject(key)
                        val u = b.optString("url", "")
                        if (u.isEmpty()) return null
                        return AudioTrack(u, b.optString("ext", "m4a"), b.optLong("abr", 0))
                    }
                    bestAudio = parseAudio("best_audio")
                    bestAudioMp4 = parseAudio("best_audio_mp4") ?: bestAudio
                    bestAudioWebm = parseAudio("best_audio_webm") ?: bestAudio
                    // PREVIEW-FALLBACK: purana server preview_url nahi bhejta (khali) —
                    // formats me se khud sabse chhota video uthao taaki Preview kabhi
                    // "link nahi mila" na bole. Audio alag ho to player khud jod lega.
                    if (previewUrl.isEmpty()) {
                        try {
                            val vids = allFormats.filter { it.type == "video" && it.url.isNotEmpty() }
                            val pick = vids.filter { it.progressive }.minByOrNull { it.height }
                                ?: vids.minByOrNull { it.height }
                            if (pick != null) {
                                previewUrl = pick.url
                                previewHasAudio = pick.progressive
                            }
                        } catch (_: Exception) {}
                    }
                    // Up-Next playlist (preview me hi Next — bina link copy)
                    val plArr = o.optJSONArray("playlist")
                    val plTmp = ArrayList<UpNextItem>()
                    if (plArr != null) {
                        for (i in 0 until plArr.length()) {
                            try {
                                val pe = plArr.getJSONObject(i)
                                plTmp.add(UpNextItem(
                                    pe.optString("id"), pe.optString("title"),
                                    pe.optString("url"), pe.optString("thumbnail"),
                                    pe.optLong("duration")))
                            } catch (_: Exception) {}
                        }
                    }
                    val plTitle = o.optString("playlist_title", "")
                    val curUrl = o.optString("webpage_url", pageUrl)
                    runOnUiThread {
                        metaText.text = listOf(
                            videoTitle, author, source,
                            if (durationMs > 0) fmtDur(durationMs / 1000) else ""
                        ).filter { it.isNotEmpty() }.joinToString("  •  ")
                        // Facebook me HD na mile (sirf SD) to wajah batao
                        try {
                            val srcL = source.lowercase()
                            val maxH = list.filter { it.type == "video" }.maxOfOrNull { it.height } ?: 0
                            if ((srcL.contains("facebook") || srcL.contains("fb")) && maxH <= 360 && maxH > 0) {
                                status("Is Facebook video me HD nahi mila (uploader ne SD hi dala ya post private hai).")
                            }
                        } catch (_: Exception) {}
                        // HLS-only site + server set nahi → seedha rasta batao
                        try {
                            if (o.optBoolean("hls_only") && getServerBase().isEmpty()) {
                                status("Ye site HLS-only stream deti hai — 🌐 Server button me apna server URL dalo, phir 'Server HD' milega.")
                                toast("HLS site: 🌐 Server set karo.")
                            }
                        } catch (_: Exception) {}
                        refreshList()
                        upNextList = plTmp
                        playlistTitle = plTitle
                        // current video playlist me kahan hai? (Prev/Next sahi chale)
                        upNextIndex = -1
                        try {
                            for (i in plTmp.indices) {
                                if (plTmp[i].url == curUrl || (plTmp[i].id.isNotEmpty() && curUrl.contains(plTmp[i].id))) {
                                    upNextIndex = i; break
                                }
                            }
                        } catch (_: Exception) {}
                        renderUpNext()
                        showPreview()
                        // Recent me dalo (bina copy dobara khul jayega)
                        try {
                            pushRecent(UpNextItem("", videoTitle, curUrl.ifEmpty { pageUrl }, videoThumb, durationMs / 1000))
                        } catch (_: Exception) {}
                        renderCookieStatus()
                        status("${list.size} qualities mili. Preview dekho (⏪10s/10s⏩), quality chuno, download dabao.")
                        toast("Video mil gaya! Preview ready.")
                        // Watch screen se 1-tap download: best file turant start
                        if (autodlAfterFetch) {
                            autodlAfterFetch = false
                            autoBestDownload()
                        }
                    }
    }

    // ---------------- VIDEO PREVIEW ----------------
    private fun showPreview() {
        previewCard.visibility = View.VISIBLE
        previewTitle.text = videoTitle
        val meta = listOf(
            if (durationMs > 0) "⏱ ${fmtDur(durationMs / 1000)}" else "",
            "${allFormats.size} qualities",
            if (previewUrl.isNotEmpty()) "preview ready" else "preview nahi mila",
        ).filter { it.isNotEmpty() }.joinToString("  •  ")
        previewMeta.text = meta
        previewPlayerView.visibility = View.GONE
        previewPlayerView.setOnClickListener(null)
        thumbPreview.visibility = View.VISIBLE
        previewPlayBtn.visibility = View.VISIBLE
        previewPlayBtn.text = "▶ Preview"
        previewPlayBtn.setOnClickListener { playPreview() }
        // 10s skip hamesha dikhao (play se pehle bhi hint), Prev/Next playlist par
        previewControls.visibility = View.VISIBLE
        renderUpNext()
        if (videoThumb.isNotEmpty()) loadThumbnail(videoThumb)
    }

    private fun loadThumbnail(url: String) {
        Thread {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36")
                    .build()
                httpShort.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val bytes = res.body?.bytes() ?: return@use
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use
                    // RACE-FIX: beech me nayi search hui ho to purani thumb mat lagao
                    runOnUiThread { if (url == videoThumb) thumbPreview.setImageBitmap(bmp) }
                }
            } catch (_: Exception) { /* thumbnail fail = preview phir bhi chalega */ }
        }.start()
    }

    private fun playPreview() {
        if (previewUrl.isEmpty()) { toast("Preview link nahi mila."); return }
        try {
            releasePreview()
            previewPlayerView.visibility = View.VISIBLE
            thumbPreview.visibility = View.GONE
            // Play dabate hi button HIDE — video par tap karo to preview band ho jayega
            previewPlayBtn.visibility = View.GONE
            val dsFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(PREVIEW_UA)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)
            val videoSrc = ProgressiveMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(bypass(previewUrl)))
            // YouTube par video aur audio alag tracks hote hain:
            // audio track saath jod kar bajao taaki awaaz bhi aaye
            val au = bestAudioMp4 ?: bestAudio
            val source = if (previewHasAudio || au == null || au.url.isEmpty()) {
                videoSrc
            } else {
                val audioSrc = ProgressiveMediaSource.Factory(dsFactory)
                    .createMediaSource(MediaItem.fromUri(bypass(au.url)))
                MergingMediaSource(videoSrc, audioSrc)
            }
            val player = ExoPlayer.Builder(this).build()
            exo = player
            previewPlayerView.player = player
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
            toast("Preview chal raha hai (audio ke saath)...")
            // Video par tap = band karo (button hidden rehta hai jab tak preview chal raha)
            previewPlayerView.setOnClickListener { stopPreview() }
        } catch (e: Exception) {
            stopPreview()
            toast("Preview nahi chal paya: ${shortErr(e)}")
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(previewUrl))) } catch (_: Exception) {}
        }
    }

    private fun stopPreview() {
        releasePreview()
        previewPlayerView.setOnClickListener(null)
        previewPlayerView.visibility = View.GONE
        thumbPreview.visibility = View.VISIBLE
        previewPlayBtn.visibility = View.VISIBLE
        previewPlayBtn.text = "▶ Preview"
        previewPlayBtn.setOnClickListener { playPreview() }
    }

    // ---------------- AD-FREE PLAYER (v1.9, sidebar) ----------------
    /** Current video (+ Up-Next queue) Full Player me kholo.
     *  Link na ho to khaali player khulta hai (wahan link paste karke bajao). */
    private fun openPlayer() {
        try {
            val i = Intent(this, PlayerActivity::class.java)
            val arr = org.json.JSONArray()
            if (lastPageUrl.isNotEmpty()) {
                // BUG-FIX: thumb bhi bhejo — Player me kala dabba nahi, thumbnail dikhega.
                arr.put(org.json.JSONObject().put("t", videoTitle).put("u", lastPageUrl).put("h", videoThumb))
            }
            for (item in upNextList) {
                if (item.url.isNotEmpty()) {
                    arr.put(org.json.JSONObject().put("t", item.title).put("u", item.url).put("h", item.thumb))
                }
            }
            i.putExtra(PlayerActivity.EXTRA_QUEUE, arr.toString())
            i.putExtra(PlayerActivity.EXTRA_PAGE_URL, lastPageUrl)
            i.putExtra(PlayerActivity.EXTRA_TITLE, videoTitle)
            i.putExtra(PlayerActivity.EXTRA_STREAM, previewUrl)
            i.putExtra(PlayerActivity.EXTRA_AUDIO, (bestAudioMp4 ?: bestAudio)?.url.orEmpty())
            i.putExtra(PlayerActivity.EXTRA_HAS_AUDIO, previewHasAudio)
            i.putExtra(PlayerActivity.EXTRA_THUMB, videoThumb)
            startActivity(i)
        } catch (e: Exception) { toast("Player nahi khul paya: ${e.message}") }
    }

    /** App share karo (dosto ko GitHub release link bhejo). */
    private fun shareApp() {
        try {
            val txt = "GSK Downloader try karo — YouTube/IG/FB videos download + Ad-Free Player! 🚫📥\nhttps://github.com/an465248/gsk-downloader/releases"
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, txt)
            }
            startActivity(Intent.createChooser(i, "GSK App share karo"))
        } catch (e: Exception) { toast("Share nahi ho paya: ${e.message}") }
    }

    /** About dialog (sidebar). */
    private fun showAbout() {
        try {
            AlertDialog.Builder(this)
                .setTitle("ℹ GSK Downloader v3.1")
                .setMessage("YouTube, Instagram, Facebook + 1600 sites se download.\n\n★ WATCH: ☰ Sidebar me search + ad-free play + related videos + 1-tap download.\n★ Screen off par bhi audio chalta rehta hai.\n\n© 2026 LVIGS Pvt. Ltd. 🇮🇳")
                .setPositiveButton("OK", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun releasePreview() {
        try { exo?.stop() } catch (_: Exception) {}
        try { exo?.release() } catch (_: Exception) {}
        exo = null
        try { previewPlayerView.player = null } catch (_: Exception) {}
    }

    companion object {
        private const val PREVIEW_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        // Publisher ka server (Render) — yahan URL hardcode karne par har user ko
        // bina setting ke Instagram server-fallback milega. Khali = user khud dalega.
        const val DEFAULT_SERVER_URL = "https://gsk-downloader.onrender.com"
        // MAX parallel segments (cleanup loops isi se chalte hain taaki koi .part bache hi nahi)
        private const val MAX_SEG = 16
    }

    private fun refreshList() {
        // website jaisa filter: audio pill = sirf audio, max = sab video,
        // number = us height tak ke videos
        val shown = when (selectedQ) {
            "audio" -> allFormats.filter { it.type == "audio" }
            "max" -> {
                val vids = allFormats.filter { it.type == "video" }
                if (vids.isNotEmpty()) vids else allFormats
            }
            else -> {
                val t = selectedQ.toIntOrNull() ?: 0
                allFormats.filter { it.type == "video" && it.height > 0 && it.height <= t }
            }
        }
        adapter.setItems(shown, fetchSeq)
        if (shown.isEmpty() && allFormats.isNotEmpty()) {
            status("Is quality me kuch nahi mila — MAX try karo.")
            toast("Is quality me kuch nahi mila.")
        }
    }

    /** Watch screen se 1-tap download: best audio-sahit file turant start karo. */
    private fun autoBestDownload() {
        try {
            val fb = bestOneTapFallback()
                ?: allFormats.firstOrNull { it.progressive && it.type == "video" && it.url.isNotEmpty() }
                ?: allFormats.firstOrNull { it.type == "video" && it.url.isNotEmpty() }
                ?: allFormats.firstOrNull { it.url.isNotEmpty() }
            if (fb != null) {
                toast("1-tap download start: ${fb.label} (audio ke saath)...")
                status("1-tap download: ${fb.label} ...")
                onFormatClick(fb, dlKey(fb))
            } else {
                toast("Downloadable format nahi mila.")
            }
        } catch (_: Exception) {}
    }

    private fun onFormatClick(fmt: Fmt, key: String) {        if (fmt.url.isEmpty()) { toast("Is format ka link nahi mila."); return }
        val st = adapter.getState(key)
        if (st.state == DlState.DOWNLOADING || st.state == DlState.PAUSED) {
            toast("Ye download pehle se chal raha hai — Pause/Cancel use karo.")
            return
        }
        // VidMate style 1-tap: 1 button = poora kaam (download + merge single progress me).
        // Progressive (audio+video juda) ho to seedha single download, warna smart merge.
        if (fmt.needsMerge) {
            // MERGE-FAIL FIX: VP9/AV1 video phone ke muxer me aksar fail hota hai.
            // Usi height ka AVC-mp4 ho to wahi lo — same quality, pakka merge + smooth.
            val safe = mergeSafeVariant(fmt)
            val skey = if (safe.id != fmt.id) dlKey(safe) else key
            if (safe.id != fmt.id) {
                val sst = adapter.getState(skey)
                if (sst.state == DlState.DOWNLOADING || sst.state == DlState.PAUSED) {
                    toast("Ye download pehle se chal raha hai — Pause/Cancel use karo.")
                    return
                }
                toast("Smooth AVC ${safe.label} me download ho raha (same ${fmt.label} quality)...")
            }
            startMerge(safe, skey)
        } else startDirect(fmt, key)
    }

    /** Usi height ka AVC-mp4 dhoondo (merge-safe + har phone par smooth).
     *  VP9/AV1 muxer-fail ka main reason tha — ye switch use khatm karta hai.
     *  Na mile to original wapas (caller apne fallback se sambhalega). */
    private fun mergeSafeVariant(vfmt: Fmt): Fmt {
        val v = vfmt.vcodec.lowercase()
        if (!v.startsWith("vp") && !v.startsWith("av01") && v != "av1") return vfmt
        if (vfmt.height <= 0) return vfmt
        return allFormats.firstOrNull {
            it.type == "video" && it.height == vfmt.height && it.url.isNotEmpty()
                && (it.vcodec.lowercase().startsWith("avc1") || it.vcodec.lowercase().startsWith("h264"))
                && (it.ext == "mp4" || it.ext == "m4v")
        } ?: vfmt
    }

    /** VidMate jaisa 1-tap fallback: merge na ho paye to sabse best progressive
     *  (video+audio juda, single download) dhoondo taaki user ko audio wali file
     *  ek hi baar me mil jaye — fail kabhi na dikhe. */
    private fun bestOneTapFallback(preferHeight: Int = 0): Fmt? {
        val prog = allFormats.filter { it.progressive && it.type == "video" && it.url.isNotEmpty() }
        if (prog.isEmpty()) return null
        // 1) AVC/MP4 (har phone par smooth) prefer karo, 2) height high prefer
        return prog.sortedWith(
            compareByDescending<Fmt> {
                val v = it.vcodec.lowercase()
                if (v.startsWith("avc1") || v.startsWith("h264")) 2
                else if (it.ext == "mp4") 1 else 0
            }.thenByDescending {
                if (preferHeight > 0) {
                    // requested height ke sabse kareeb (neeche se) — bekar 144p na mile
                    if (it.height <= preferHeight) it.height else it.height - 10000
                } else it.height
            }.thenByDescending { it.size }
        ).firstOrNull()
    }

    private fun togglePause(fmt: Fmt, key: String) {
        val job = jobs[key] ?: return
        job.paused = !job.paused
        if (job.paused) {
            adapter.update(key, DlState.PAUSED, null, "Paused — Resume dabao", "")
            status("Paused: ${fmt.label}. Resume dabane par wahin se chalega.")
        } else {
            adapter.update(key, DlState.DOWNLOADING, null, "Resume ho raha...", null)
            status("Resume: ${fmt.label} ...")
        }
    }

    private fun cancelDownload(fmt: Fmt, key: String) {
        val job = jobs[key] ?: return
        job.cancelled = true
        job.cancelAll()
        DownloadNotifier.cancel(this, DownloadNotifier.idFor("d$key"))
        DownloadNotifier.cancel(this, DownloadNotifier.idFor("m$key"))
        status("Cancel ho raha hai: ${fmt.label} ...")
    }

    /** video ke CODEC se compatible audio chuno (container-safe merge ke liye):
     *  AVC/HEVC -> AAC(m4a), VP8/VP9 -> Opus(webm). Galat jodi = merge fail. */
    private fun compatibleAudio(vfmt: Fmt): AudioTrack? {
        val v = vfmt.vcodec.lowercase()
        return when {
            v.startsWith("vp09") || v == "vp9" || v.startsWith("vp08") || v == "vp8" ->
                bestAudioWebm ?: bestAudio
            else -> bestAudioMp4 ?: bestAudio
        }
    }

    /** 403/expire par fresh link dobara nikalo (page dobara extract karke).
     *  Returns null agar fresh link na mile. Background thread par call karo. */
    private fun freshVideoUrl(formatId: String): String? {
        return try {
            val page = lastPageUrl
            if (page.isEmpty()) return null
            val json = Python.getInstance().getModule("gsk").callAttr("extract", page, cookiePath).toString()
            val o = JSONObject(json)
            if (o.has("error")) return null
            val arr = o.getJSONArray("formats")
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (f.optString("format_id") == formatId) {
                    val u = f.optString("url", "")
                    if (u.isNotEmpty()) return u
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun freshAudioUrl(wantMp4: Boolean): AudioTrack? {
        return try {
            val page = lastPageUrl
            if (page.isEmpty()) return null
            val json = Python.getInstance().getModule("gsk").callAttr("extract", page, cookiePath).toString()
            val o = JSONObject(json)
            if (o.has("error")) return null
            val key = if (wantMp4) "best_audio_mp4" else "best_audio_webm"
            fun parse(k: String): AudioTrack? {
                if (o.isNull(k)) return null
                val b = o.getJSONObject(k)
                val u = b.optString("url", "")
                if (u.isEmpty()) return null
                return AudioTrack(u, b.optString("ext", "m4a"), b.optLong("abr", 0))
            }
            // naye result se best tracks update bhi kar do
            parse("best_audio")?.let { bestAudio = it }
            parse("best_audio_mp4")?.let { bestAudioMp4 = it }
            parse("best_audio_webm")?.let { bestAudioWebm = it }
            parse(key) ?: parse("best_audio")
        } catch (_: Exception) { null }
    }

    private fun isExpireErr(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("403") || m.contains("expire") || m.contains("410")
    }

    /** YouTube throttling bypass: googlevideo link par ratebypass=yes lagao.
     *  Iske bina speed gala ghont ke KB/s me gir jati hai (badi file atki lagti hai).
     *  yt-dlp bhi download ke time yehi lagata hai. */
    private fun bypass(url: String): String {
        if (!url.contains("googlevideo.com")) return url
        if (url.contains("ratebypass=")) return url
        return url + (if (url.contains("?")) "&" else "?") + "ratebypass=yes"
    }

    /** Storage check: itni jagah nahi to pehle hi bata do (GB file beech me fail na ho). */
    private fun storageOk(needBytes: Long): Boolean {
        return try {
            if (needBytes <= 0) return true
            cacheDir.usableSpace > needBytes
        } catch (_: Exception) { true }
    }

    // ---------------- DIRECT DOWNLOAD (1-tap single file + notification bar) ----------------
    // key = unique download key (session::formatId) — 2 videos ke same formatId takrayein nahi.
    private fun startDirect(fmt: Fmt, key: String) {
        ensureStoragePermission()
        if (fmt.size > 0 && !storageOk((fmt.size * 1.1).toLong())) {
            toast("Phone me jagah kam hai — kuch delete karke retry karo.")
            status("Storage kam: ${humanSize(cacheDir.usableSpace)} free, ~${humanSize(fmt.size)} chahiye.")
            return
        }
        // purana job ho to pehle use poori tarah roko (warna 2 threads
        // ek hi key par ladenge + finally me naya job map se ud jayega)
        jobs.remove(key)?.let { try { it.cancelled = true; it.cancelAll() } catch (_: Exception) {} }
        val job = DownloadJob()
        jobs[key] = job
        startFg()
        val nid = DownloadNotifier.idFor("d$key")
        val notifTitle = "$videoTitle • ${fmt.label}"
        DownloadNotifier.indeterminate(this, nid, notifTitle, "Download start ho raha...")
        Thread {
            var tmp: File? = null
            try {
                // Audio ko ASLI container ext me save karo (m4a/webm/opus/weba):
                // opus/webm data ko ".mp3" naam dene se player bajata nahi (rename != convert).
                // mimeFor() + MediaStore audio/video routing inhi asli ext se sahi kaam karte hain.
                val isAudioOnly = fmt.type == "audio"
                val ext = if (isAudioOnly) {
                    if (fmt.ext == "mp4") "m4a" else fmt.ext.ifEmpty { "m4a" }
                } else fmt.ext.ifEmpty { "mp4" }
                val name = "${safeName("$videoTitle - ${fmt.label}")}.$ext"
                tmp = File(cacheDir, "gsk_${System.currentTimeMillis()}.$ext")
                var dlUrl = fmt.url
                var freshTried = false
                adapter.update(key, DlState.DOWNLOADING, 0f, "0%", "Starting...")
                // retry: network blip par resume, 403/expire par FRESH link lekar retry
                var attempt = 0
                var done = false
                var lastErr: Exception? = null
                while (attempt < 3 && !done && !job.cancelled) {
                    try {
        // FAST: badi file 8 parallel connections me (YouTube har
                        // connection throttle karta hai). Chhoti file khud single
                        // connection se utarti hai — downloadFast khud faisla karta hai.
                        downloadFast(dlUrl, tmp, job, key,
                            0f, 1f,
                            { frac, label, speed ->
                                runOnUiThread {
                                    adapter.update(key, DlState.DOWNLOADING, frac, label, speed)
                                }
                                DownloadNotifier.progress(
                                    this, nid, notifTitle,
                                    (frac * 100).toInt(), speed, job.paused
                                )
                            })
                        if (tmp.length() == 0L) throw Exception("khali file mili — retry karo")
                        done = true
                    } catch (e: Exception) {
                        lastErr = e
                        if (job.cancelled) break
                        val m = (e.message ?: "").lowercase()
                        if (m.contains("cancel")) break
                        // link expire? ek baar fresh link nikalo aur scratch se retry
                        if (!freshTried && isExpireErr(e)) {
                            freshTried = true
                            runOnUiThread { adapter.update(key, DlState.DOWNLOADING, 0f, "Fresh link...", null) }
                            DownloadNotifier.indeterminate(this, nid, notifTitle, "Fresh link nikal rahe...")
                            status("Link expire tha — fresh link nikal rahe hain...")
                            val fu = freshVideoUrl(fmt.id)
                            if (fu != null && fu.isNotEmpty()) {
                                dlUrl = fu
                                try { tmp.delete() } catch (_: Exception) {}
                                // purane URL ke adhure segment parts naye link par NA chipkein
                                for (i in 0 until MAX_SEG) { try { File(tmp.absolutePath + ".part$i").delete() } catch (_: Exception) {} }
                                attempt = 0
                                continue
                            }
                        }
                        attempt++
                        if (attempt < 3 && !job.paused) {
                            runOnUiThread { adapter.update(key, DlState.DOWNLOADING, null, "Retry $attempt...", null) }
                            Thread.sleep(1500)
                        }
                    }
                }
                if (job.cancelled) {
                    tmp.delete()
                    adapter.reset(key)
                    DownloadNotifier.cancel(this, nid)
                    status("Cancelled: ${fmt.label}")
                    toast("Download cancel ho gaya.")
                    return@Thread
                }
                if (!done) throw lastErr ?: Exception("download fail")
                val mime0 = mimeFor(ext)
                val uri0 = saveToDownloads(tmp, name, mime0)
                adapter.update(key, DlState.DONE, 1f, "Done ✓", "")
                DownloadNotifier.done(this, nid, notifTitle, name, uri0, mime0)
                status("Saved: $name  (Download/gsk-downloader)")
                toast("Download complete! Notification tap karke play karo ▶")
            } catch (e: Exception) {
                val msg = if ((e.message ?: "").contains("cancel", true)) "cancelled" else shortErr(e)
                adapter.update(key, DlState.FAILED, 0f, "Download", "")
                if (msg == "cancelled") {
                    adapter.reset(key)
                    DownloadNotifier.cancel(this, nid)
                    status("Cancelled: ${fmt.label}")
                } else {
                    var hint = "Download fail: $msg"
                    if (msg.contains("403") || msg.contains("expire", true))
                        hint += " — Dobara Get Video dabao (fresh link), phir turant download karo."
                    DownloadNotifier.failed(this, nid, notifTitle, msg)
                    status(hint)
                    toast(hint)
                }
            } finally {
                try { tmp?.delete() } catch (_: Exception) {}
                // RACE-FIX: sirf APNA job hatao — purana cancelled thread naye
                // retry ka job map se uda deta tha (service jaldi stop + orphan download).
                jobs.remove(key, job)
                syncService()
            }
        }.start()
    }

    // ---------------- 1-TAP HD (VidMate style: 1 button = video+audio ek saath + notification) ----------------
    private fun startMerge(vfmt: Fmt, key: String) {
        val afmt0 = compatibleAudio(vfmt)
        if (afmt0 == null || afmt0.url.isEmpty()) {
            // Audio track hi nahi — turant 1-tap progressive try karo (fail na dikhao)
            val fb = bestOneTapFallback(vfmt.height)
            if (fb != null && fb.id != vfmt.id) {
                toast("HD audio nahi mila — 1-tap ${fb.label} download ho raha...")
                startDirect(fb, dlKey(fb))
            } else toast("Audio track nahi mila.")
            return
        }
        ensureStoragePermission()
        if (vfmt.size > 0 && !storageOk(vfmt.size * 2 + 30 * 1024 * 1024)) {
            toast("Phone me jagah kam hai (HD ko double jagah chahiye) — kuch delete karo.")
            status("Storage kam: ${humanSize(cacheDir.usableSpace)} free. HD (video+audio ek saath) ko video+audio+output teenon ke liye jagah chahiye.")
            return
        }
        jobs.remove(key)?.let { try { it.cancelled = true; it.cancelAll() } catch (_: Exception) {} }
        val job = DownloadJob()
        jobs[key] = job
        startFg()
        val nid = DownloadNotifier.idFor("m$key")
        val notifTitle = "$videoTitle • ${vfmt.label} HD"
        DownloadNotifier.indeterminate(this, nid, notifTitle, "HD 1-tap start... (video+audio ek saath)")
        Thread {
            val stamp = System.currentTimeMillis()
            val v = File(cacheDir, "gskv_$stamp.${vfmt.ext.ifEmpty { "mp4" }}")
            var aExt = afmt0.ext.ifEmpty { "m4a" }
            var a = File(cacheDir, "gska_$stamp.$aExt")
            var vUrl = vfmt.url
            var aUrl = afmt0.url
            var out: File? = null
            try {
                // ---- VIDEO + AUDIO EK SAATH (parallel, fast) — VidMate jaisa ek hi press ----
                // dono alag threads me download hote hain, progress + notification jud kar dikhta hai
                adapter.update(key, DlState.DOWNLOADING, 0f, "Starting...", "2x parallel ⚡")
                val wantMp4 = !(vfmt.vcodec.lowercase().startsWith("vp"))
                val vF = java.util.concurrent.atomic.AtomicReference(0f)
                val aF = java.util.concurrent.atomic.AtomicReference(0f)
                val vSp = java.util.concurrent.atomic.AtomicReference("")
                val aSp = java.util.concurrent.atomic.AtomicReference("")
                var lastBoth = 0L
                var lastNotif = 0L
                fun renderBoth() {
                    val now = System.currentTimeMillis()
                    if (now - lastBoth < 400) return
                    lastBoth = now
                    // Download phase 0→95% (single bar, VidMate jaisa), Saving 95→100%
                    val combined = (0.95f * (vF.get() + aF.get()) / 2f).coerceIn(0f, 0.95f)
                    val label = "V ${(vF.get() * 100).toInt()}% • A ${(aF.get() * 100).toInt()}%"
                    val sp = listOf(vSp.get(), aSp.get()).filter { it.isNotEmpty() }.joinToString(" + ")
                    runOnUiThread {
                        adapter.update(key,
                            if (job.paused) DlState.PAUSED else DlState.DOWNLOADING,
                            combined, label, sp)
                    }
                    if (now - lastNotif > 800) {
                        lastNotif = now
                        DownloadNotifier.progress(
                            this, nid, notifTitle,
                            (combined * 100).toInt(), sp, job.paused
                        )
                    }
                }
                var vErr: Exception? = null
                var aErr: Exception? = null
                val tv = Thread {
                    try {
                        vUrl = dlWithFreshLink(vUrl, v, job, key, 0f, 0.45f,
                            { freshVideoUrl(vfmt.id) },
                            { f, _, sp -> vF.set(f); vSp.set(sp); renderBoth() })
                    } catch (e: Exception) { vErr = e }
                }
                val ta = Thread {
                    try {
                        aUrl = dlWithFreshLink(aUrl, a, job, key, 0.45f, 0.17f,
                            { freshAudioUrl(wantMp4)?.url },
                            { f, _, sp -> aF.set(f); aSp.set(sp); renderBoth() })
                    } catch (e: Exception) { aErr = e }
                }
                tv.start(); ta.start()
                tv.join(); ta.join()
                if (job.cancelled) throw Exception("cancelled")
                vErr?.let { throw it }
                aErr?.let { throw it }
                if (v.length() == 0L) throw Exception("video khali mila")
                if (a.length() == 0L) throw Exception("audio khali mila")
                // VidMate-style: alag "Merging..." stage mat dikhao — same bar
                // 95% se 100% tak "Saving..." me poora karo (user ko direct jaisa lage).
                adapter.update(key, DlState.DOWNLOADING, 0.95f, "Saving...", null)
                DownloadNotifier.progress(this, nid, notifTitle, 95, "Saving...", false)
                try {
                    out = Remuxer.remuxAuto(v, a, cacheDir, stamp) { p ->
                        val pct = (95 + p * 5).toInt()
                        runOnUiThread {
                            adapter.update(key, DlState.DOWNLOADING, 0.95f + p * 0.05f, "$pct%", null)
                        }
                        DownloadNotifier.progress(
                            this, nid, notifTitle,
                            pct, "Saving...", false
                        )
                    }
                } catch (me: Exception) {
                    // VidMate style fallback: merge na ho (AV1/4K combo) to user ko FAIL mat dikhao —
                    // pehle best 1-tap progressive (audio-sahit single file) auto-download karo.
                    if (job.cancelled) throw Exception("cancelled")
                    val why = if (me is Remuxer.IncompatibleTracksException) (me.message ?: "combo support nahi")
                    else shortErr(me)
                    val oneTap = bestOneTapFallback(vfmt.height)
                    if (oneTap != null && oneTap.url.isNotEmpty() && oneTap.id != vfmt.id && !job.cancelled) {
                        runOnUiThread {
                            adapter.update(key, DlState.DOWNLOADING, 0.7f, "1-tap ${oneTap.label}...", null)
                        }
                        DownloadNotifier.indeterminate(
                            this, nid, notifTitle,
                            "HD merge possible nahi — 1-tap ${oneTap.label} de rahe..."
                        )
                        status("Ye HD combo phone par jud nahi sakta ($why). 1-tap ${oneTap.label} (audio-sahit) de rahe...")
                        try { v.delete() } catch (_: Exception) {}
                        try { a.delete() } catch (_: Exception) {}
                        try { out?.delete() } catch (_: Exception) {}
                        jobs.remove(key)
                        DownloadNotifier.cancel(this, nid)
                        runOnUiThread { adapter.reset(key) }
                        toast("HD merge possible nahi — 1-tap ${oneTap.label} download ho raha (audio ke saath)...")
                        startDirect(oneTap, dlKey(oneTap))
                        return@Thread
                    }
                    // Koi 1-tap progressive nahi (jaise FB DASH-only reel) — video-only
                    // save karne se BINA AAWAZ file milegi. Usse pehle SERVER-merge
                    // try karo (server ffmpeg VP9+AAC jaise combo jod deta hai).
                    val srvBase = getServerBase()
                    if (srvBase.isNotEmpty() && !job.cancelled) {
                        try {
                            runOnUiThread {
                                adapter.update(key, DlState.DOWNLOADING, 0.7f, "Server merge...", null)
                            }
                            DownloadNotifier.indeterminate(this, nid, notifTitle, "Server se audio jod rahe...")
                            status("Phone par merge nahi hua ($why) — server se audio-sahit la rahe...")
                            val srvUrl = "$srvBase/api/server-download?page=" +
                                URLEncoder.encode(lastPageUrl, "utf-8") +
                                "&format_id=" + URLEncoder.encode(vfmt.id, "utf-8") +
                                "&filename=" + URLEncoder.encode("$videoTitle - ${vfmt.label}.mp4", "utf-8")
                            val tmpSrv = File(cacheDir, "gsksrv_$stamp.mp4")
                            var srvOk = false
                            try {
                                downloadFast(srvUrl, tmpSrv, job, key, 0.7f, 0.25f, null)
                                if (tmpSrv.length() > 0) {
                                    val srvName = "${safeName("$videoTitle - ${vfmt.label}")}.mp4"
                                    val srvUri = saveToDownloads(tmpSrv, srvName, "video/mp4")
                                    adapter.update(key, DlState.DONE, 1f, "Done ✓", "")
                                    DownloadNotifier.done(this, nid, notifTitle, srvName, srvUri, "video/mp4")
                                    status("Saved: $srvName (server-merge, audio ke saath)")
                                    toast("HD video ready (audio ke saath)! ▶")
                                    srvOk = true
                                }
                            } finally {
                                try { tmpSrv.delete() } catch (_: Exception) {}
                            }
                            if (srvOk) {
                                try { v.delete() } catch (_: Exception) {}
                                try { a.delete() } catch (_: Exception) {}
                                try { out?.delete() } catch (_: Exception) {}
                                return@Thread
                            }
                        } catch (_: Exception) { /* neeche video-only fallback */ }
                        if (job.cancelled) throw Exception("cancelled")
                    }
                    // Aakhri option video-only save (mehnat bekar na jaye)
                    status("Audio merge possible nahi ($why). Video-only save kar rahe hain...")
                    val fbName = "${safeName("$videoTitle - ${vfmt.label} (video-only)")}.${vfmt.ext.ifEmpty { "mp4" }}"
                    val fbMime = mimeFor(vfmt.ext.ifEmpty { "mp4" })
                    val fbUri = saveToDownloads(v, fbName, fbMime)
                    adapter.update(key, DlState.DONE, 1f, "Done ✓", "")
                    DownloadNotifier.done(this, nid, notifTitle, fbName, fbUri, fbMime)
                    status("Saved (video-only, bina audio): $fbName")
                    toast("Is quality me audio jud nahi paya — 1-tap (✓ Smooth) quality chuno full audio ke liye.")
                    return@Thread
                }
                val outFile = out ?: throw Exception("merge fail")
                if (!outFile.exists() || outFile.length() == 0L) throw Exception("merge fail")
                val name = "${safeName("$videoTitle - ${vfmt.label}")}.${outFile.extension.ifEmpty { "mp4" }}"
                val outMime = mimeFor(outFile.extension.ifEmpty { "mp4" })
                val outUri = saveToDownloads(outFile, name, outMime)
                adapter.update(key, DlState.DONE, 1f, "Done ✓", "")
                DownloadNotifier.done(this, nid, notifTitle, name, outUri, outMime)
                status("Saved: $name  (Download/gsk-downloader)")
                toast("HD video ready! Notification tap karke play karo ▶")
            } catch (e: Exception) {
                val msg = shortErr(e)
                if (msg.contains("cancel", true) || job.cancelled) {
                    adapter.reset(key)
                    DownloadNotifier.cancel(this, nid)
                    status("Cancelled: ${vfmt.label}")
                    toast("Download cancel ho gaya.")
                } else {
                    adapter.update(key, DlState.FAILED, 0f, "1-Tap HD", "")
                    // Diagnosis ke liye sizes: V/A me se koi 0/KB me ho to download
                    // adhura tha (network), warna muxer stage par atka.
                    val vsz = try { v.length() } catch (_: Exception) { -1 }
                    val asz = try { a.length() } catch (_: Exception) { -1 }
                    val szTxt = "V ${if (vsz < 0) "?" else humanSize(vsz)} + A ${if (asz < 0) "?" else humanSize(asz)}"
                    var hint = "Fail: $msg ($szTxt)"
                    if (isExpireErr(e))
                        hint += " — Dobara Get Video dabao (fresh link), phir turant download karo."
                    else if (!msg.contains("video-only"))
                        hint += " — 1-tap (✓ Smooth) quality try karo, wo ek hi baar me audio-sahit milti hai."
                    DownloadNotifier.failed(this, nid, notifTitle, msg)
                    status(hint)
                    toast(hint)
                }
            } finally {
                try { v.delete() } catch (_: Exception) {}
                try { a.delete() } catch (_: Exception) {}
                try { out?.delete() } catch (_: Exception) {}
                // RACE-FIX: sirf APNA job hatao (purana thread naye retry ka job na udaye).
                jobs.remove(key, job)
                syncService()
            }
        }.start()
    }

    /** Download + auto-retry (FAST: badi file multi-connection).
     *  403/expire par ek baar FRESH link lekar scratch se retry.
     *  @return istemal hua (fresh ho sakta hai) URL. Fail/cancel par throw. */
    @Throws(Exception::class)
    private fun dlWithFreshLink(
        firstUrl: String, dest: File, job: DownloadJob, adapterId: String,
        baseFrac: Float, span: Float,
        fetchFresh: () -> String?,
        progressSink: ((Float, String, String) -> Unit)? = null,
    ): String {
        var url = firstUrl
        var freshTried = false
        var attempt = 0
        var lastErr: Exception? = null
        while (attempt < 3 && !job.cancelled) {
            try {
                downloadFast(url, dest, job, adapterId, baseFrac, span, progressSink)
                if (dest.length() == 0L) throw Exception("khali file mili")
                return url
            } catch (e: Exception) {
                lastErr = e
                if (job.cancelled) throw Exception("cancelled")
                if ((e.message ?: "").contains("cancel", true)) throw Exception("cancelled")
                if (!freshTried && isExpireErr(e)) {
                    freshTried = true
                    runOnUiThread { adapter.update(adapterId, DlState.DOWNLOADING, baseFrac, "Fresh link...", null) }
                    val fu = try { fetchFresh() } catch (_: Exception) { null }
                    if (!fu.isNullOrEmpty()) {
                        url = fu
                        try { dest.delete() } catch (_: Exception) {}
                        // stale segment parts bhi hatao
                        for (i in 0 until MAX_SEG) { try { File(dest.absolutePath + ".part$i").delete() } catch (_: Exception) {} }
                        attempt = 0
                        continue
                    }
                }
                attempt++
                if (attempt < 3 && !job.paused) Thread.sleep(1200)
            }
        }
        if (job.cancelled) throw Exception("cancelled")
        throw lastErr ?: Exception("download fail")
    }

    // ---------------- resumable network download: speed + pause + cancel ----------------
    // progressSink != null ho to row-update caller karega (parallel V+A / segments ke liye):
    // sink(rawFrac 0..1, label, speedText)
    @Throws(Exception::class)
    private fun downloadToFileResumable(
        url: String, dest: File, job: DownloadJob, adapterId: String,
        baseFrac: Float = 0f, span: Float = 1f,
        progressSink: ((Float, String, String) -> Unit)? = null,
    ) {
        var start: Long = if (dest.exists()) dest.length() else 0L
        var total: Long = -1
        var got: Long = start
        var lastUi = 0L
        var lastUiBytes: Long = got
        var emaBps = 0.0
        // STUCK-FIX counter: lagatar kitni baar resume-retry fail hua (neeche catch me).
        var resumeFails = 0

        fun ui() {
            resumeFails = 0 // data beh raha hai — fail counter reset
            val now = System.currentTimeMillis()
            if (now - lastUi < 400) return
            val dtMs = (now - lastUi).coerceAtLeast(1)
            lastUi = now
            // stable speed: is interval ka rate + EMA smoothing (fluctuation fix)
            val inst = (got - lastUiBytes) * 1000.0 / dtMs
            lastUiBytes = got
            emaBps = if (emaBps <= 0) inst else emaBps * 0.65 + inst * 0.35
            val bps = emaBps.toLong()
            val frac = if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else -1f
            val shown = baseFrac + (if (frac >= 0) frac else 0f) * span
            val speed = humanSpeed(bps)
            val pct = if (frac >= 0) "${(frac * 100).toInt()}%  •  $speed" else "$speed  •  ${humanSize(got)}"
            val eta = if (bps > 0 && total > 0 && got < total) "  •  ETA ${fmtDur((total - got) / bps)}" else ""
            if (progressSink != null) {
                progressSink(if (frac >= 0) frac else 0f, pct, speed + eta)
            } else runOnUiThread {
                val st = if (job.paused) DlState.PAUSED else DlState.DOWNLOADING
                adapter.update(adapterId, st, shown.coerceIn(0f, 1f), pct, speed + eta)
            }
        }

        while (true) {
            if (job.cancelled) throw Exception("cancelled")
            // pause: connection band karke wait karo (battery/data bachega), resume par Range se
            while (job.paused && !job.cancelled) {
                runOnUiThread { adapter.update(adapterId, DlState.PAUSED, null, "Paused", "Ruka hua — Resume dabao") }
                Thread.sleep(300)
            }
            if (job.cancelled) throw Exception("cancelled")

            val builder = Request.Builder()
                .url(bypass(url))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .header("Connection", "keep-alive")
            if (start > 0) builder.header("Range", "bytes=$start-")
            val call = http.newCall(builder.build())
            job.track(call)
            try {
                call.execute().use { res ->
                    val code = res.code
                    if (code == 403 || code == 410) throw Exception("HTTP 403 (link expire). Dobara Get Video dabao.")
                    if (code == 416) {
                        // range galat -> scratch se
                        try { dest.delete() } catch (_: Exception) {}
                        start = 0; got = 0; total = -1
                        return@use
                    }
                    if (code == 206) {
                        val cr = res.header("Content-Range") ?: ""
                        total = Regex("/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull() ?: -1
                    } else if (!res.isSuccessful) {
                        throw Exception("HTTP $code")
                    } else {
                        if (start > 0) {
                            // server ne resume ignore kiya -> scratch se
                            try { dest.delete() } catch (_: Exception) {}
                            start = 0; got = 0
                        }
                        total = res.body?.contentLength() ?: -1
                        if (total <= 0) total = -1
                    }
                    val body = res.body ?: throw Exception("empty body")
                    // append mode (resume), ya fresh
                    FileOutputStream(dest, start > 0).use { outp ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(512 * 1024)
                            while (true) {
                                if (job.cancelled) throw Exception("cancelled")
                                if (job.paused) break // outer loop resume karega
                                val n = try { inp.read(buf) } catch (ce: Exception) {
                                    if (job.cancelled) throw Exception("cancelled")
                                    throw ce
                                }
                                if (n < 0) {
                                    // file poori — size verify karo
                                    outp.flush()
                                    val finalLen = try { dest.length() } catch (_: Exception) { got }
                                    if (total > 0 && finalLen < total) {
                                        start = finalLen; got = finalLen
                                        throw Exception("adhoori file (net ruk gaya) — resume...")
                                    }
                                    runOnUiThread { adapter.update(adapterId, DlState.DOWNLOADING, baseFrac + span, "100%", null) }
                                    return
                                }
                                outp.write(buf, 0, n)
                                got += n
                                ui()
                            }
                            outp.flush()
                        }
                    }
                    start = try { dest.length() } catch (_: Exception) { got }
                    got = start
                }
            } catch (e: Exception) {
                if (job.cancelled || (e.message ?: "").contains("cancel", true)) throw Exception("cancelled")
                if (job.paused) continue // pause ke liye reconnect
                // network blip: 1.5s ruk kar resume retry (Range se)
                try { Thread.sleep(1200) } catch (_: Exception) {}
                // STUCK-FIX: pehle HAR error (403-expire/500 समेत) par resume-loop
                // INFINITE tha — download X% par atka rehta, fail bhi nahi hota tha
                // aur fresh-link logic tak baat pahunchti hi nahi thi.
                // Ab 10 lagatar fail ke baad error upar phenko (caller fresh-link
                // nikalega ya fail-status dikhayega).
                if (dest.exists() && dest.length() > 0 && resumeFails < 10) {
                    resumeFails++
                    start = dest.length(); got = start
                    continue
                }
                throw e
            } finally {
                job.untrack(call)
            }
        }
    }

    // ---------------- FAST download: badi file N parallel connections me ----------------
    // YouTube 1 connection ko ~100KB/s par lock karta hai — lock connections se
    // divide hota hai (user ka poora bandwidth use ho, VidMate jaisa fast).
    // File jitni badi, utne zyada segments: 2-8MB=4, 8-32MB=8, 32MB+=16.
    // 2MB se chhoti file hi single connection se (usme overhead bekar hai).
    @Throws(Exception::class)
    private fun downloadFast(
        url: String, dest: File, job: DownloadJob, adapterId: String,
        baseFrac: Float = 0f, span: Float = 1f,
        progressSink: ((Float, String, String) -> Unit)? = null,
    ) {
        val total = probeTotal(bypass(url), job)
        if (total < 2 * 1024 * 1024) {
            downloadToFileResumable(url, dest, job, adapterId, baseFrac, span, progressSink)
            return
        }
        val nSeg = when {
            total < 8 * 1024 * 1024 -> 4
            total < 32 * 1024 * 1024 -> 8
            else -> MAX_SEG
        }
        val parts = (0 until nSeg).map { i -> File(dest.absolutePath + ".part$i") }
        // segment boundaries
        val bounds = (0 until nSeg).map { i ->
            val s = total * i / nSeg
            val e = if (i == nSeg - 1) total - 1 else total * (i + 1) / nSeg - 1
            Pair(s, e)
        }
        // purane adhure parts validate karo (galat size = delete)
        for (i in 0 until nSeg) {
            val (s, e) = bounds[i]
            val p = parts[i]
            if (p.exists() && p.length() > (e - s + 1)) { try { p.delete() } catch (_: Exception) {} }
        }
        val segGot = LongArray(nSeg) { i -> if (parts[i].exists()) parts[i].length() else 0L }
        val segErr: Array<Exception?> = arrayOfNulls(nSeg)
        var lastUi = 0L
        var lastUiBytes = 0L
        var emaBps = 0.0
        fun report() {
            val now = System.currentTimeMillis()
            if (now - lastUi < 400) return
            val dtMs = (now - lastUi).coerceAtLeast(1)
            lastUi = now
            var got = 0L
            for (i in 0 until nSeg) got += segGot[i]
            val inst = (got - lastUiBytes) * 1000.0 / dtMs
            lastUiBytes = got
            emaBps = if (emaBps <= 0) inst else emaBps * 0.65 + inst * 0.35
            val bps = emaBps.toLong()
            val frac = (got.toFloat() / total).coerceIn(0f, 1f)
            val shown = baseFrac + frac * span
            val speed = humanSpeed(bps) + " ⚡x$nSeg"
            val pct = "${(frac * 100).toInt()}%  •  $speed"
            val eta = if (bps > 0 && got < total) "  •  ETA ${fmtDur((total - got) / bps)}" else ""
            if (progressSink != null) progressSink(frac, pct, speed + eta)
            else runOnUiThread {
                val st = if (job.paused) DlState.PAUSED else DlState.DOWNLOADING
                adapter.update(adapterId, st, shown.coerceIn(0f, 1f), pct, speed + eta)
            }
        }
        val threads = (0 until nSeg).map { i ->
            Thread {
                try {
                    downloadRangePart(bypass(url), parts[i], bounds[i].first, bounds[i].second, job) { delta ->
                        segGot[i] += delta
                        report()
                    }
                } catch (e: Exception) {
                    segErr[i] = e
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        if (job.cancelled) throw Exception("cancelled")
        for (i in 0 until nSeg) {
            val e = segErr[i]
            if (e != null) {
                if ((e.message ?: "").contains("cancel", true)) throw Exception("cancelled")
                throw e
            }
        }
        // verify + jodo
        for (i in 0 until nSeg) {
            val (s, e) = bounds[i]
            if (!parts[i].exists() || parts[i].length() != (e - s + 1)) {
                // adhura part: single-connection se poora karo. dest me pichhle
                // attempt ka adhura join ho sakta hai — append+CORRUPT se bachne
                // ke liye pehle scratch karo (resume waise bhi Range se hoga).
                try { dest.delete() } catch (_: Exception) {}
                downloadToFileResumable(url, dest, job, adapterId, baseFrac, span, progressSink)
                for (p in parts) { try { p.delete() } catch (_: Exception) {} }
                return
            }
        }
        try {
            FileOutputStream(dest, false).use { outp ->
                val buf = ByteArray(1024 * 1024)
                for (p in parts) {
                    p.inputStream().use { inp ->
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            outp.write(buf, 0, n)
                        }
                    }
                }
                outp.flush()
            }
        } finally {
            for (p in parts) { try { p.delete() } catch (_: Exception) {} }
        }
        if (dest.length() != total) throw Exception("adhoori file — retry karo")
        if (progressSink != null) progressSink(1f, "100%", "")
        else runOnUiThread { adapter.update(adapterId, DlState.DOWNLOADING, baseFrac + span, "100%", null) }
    }

    /** Total size pata karo (1-byte Range probe). -1 = pata nahi chala. */
    @Throws(Exception::class)
    private fun probeTotal(burl: String, job: DownloadJob): Long {
        val req = Request.Builder()
            .url(burl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-0")
            .build()
        val call = http.newCall(req)
        job.track(call)
        try {
            call.execute().use { res ->
                if (job.cancelled) throw Exception("cancelled")
                if (res.code == 403 || res.code == 410) throw Exception("HTTP 403 (link expire). Dobara Get Video dabao.")
                if (res.code == 206) {
                    val cr = res.header("Content-Range") ?: return -1
                    // drain 1 byte
                    try { res.body?.byteStream()?.read() } catch (_: Exception) {}
                    return Regex("/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull() ?: -1
                }
                return -1 // Range support nahi -> single download
            }
        } finally {
            job.untrack(call)
        }
    }

    /** Ek segment [segStart, segEnd] download karo (resume + pause + retry ke saath). */
    @Throws(Exception::class)
    private fun downloadRangePart(
        burl: String, part: File, segStart: Long, segEnd: Long,
        job: DownloadJob, onDelta: (Long) -> Unit,
    ) {
        var cur = segStart + (if (part.exists()) part.length() else 0L)
        var fails = 0
        while (cur <= segEnd) {
            if (job.cancelled) throw Exception("cancelled")
            while (job.paused && !job.cancelled) Thread.sleep(300)
            if (job.cancelled) throw Exception("cancelled")
            val req = Request.Builder()
                .url(burl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$cur-$segEnd")
                .build()
            val call = http.newCall(req)
            job.track(call)
            try {
                call.execute().use { res ->
                    if (job.cancelled) throw Exception("cancelled")
                    if (res.code == 403 || res.code == 410) throw Exception("HTTP 403 (link expire). Dobara Get Video dabao.")
                    if (res.code == 416) {
                        // part already poora (doosre attempt me) — verify bahar hoga
                        return
                    }
                    if (res.code != 206) throw Exception("HTTP ${res.code}")
                    val body = res.body ?: throw Exception("empty body")
                    FileOutputStream(part, true).use { outp ->
                        body.byteStream().use { inp ->
                            val buf = ByteArray(1024 * 1024)
                            while (true) {
                                if (job.cancelled) throw Exception("cancelled")
                                if (job.paused) break
                                val n = try { inp.read(buf) } catch (ce: Exception) {
                                    if (job.cancelled) throw Exception("cancelled")
                                    throw ce
                                }
                                if (n < 0) return // segment poora
                                outp.write(buf, 0, n)
                                cur += n
                                onDelta(n.toLong())
                                if (cur > segEnd + 1) throw Exception("segment overflow")
                            }
                            outp.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                if (job.cancelled || (e.message ?: "").contains("cancel", true)) throw Exception("cancelled")
                if (job.paused) continue
                val m = (e.message ?: "")
                if (m.contains("403") || m.contains("expire")) throw e // fresh-link caller karega
                fails++
                if (fails > 8) throw e
                try { Thread.sleep(1000) } catch (_: Exception) {}
                cur = segStart + (if (part.exists()) part.length() else 0L)
            } finally {
                job.untrack(call)
            }
        }
    }

    private fun saveToDownloads(src: File, name: String, mime: String): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.SIZE, src.length())
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/gsk-downloader")
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val appDir = File(dir, "gsk-downloader")
                if (!appDir.exists()) appDir.mkdirs()
                put(MediaStore.MediaColumns.DATA, File(appDir, name).absolutePath)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            // Android 10+: Download/ folder me save ke liye MediaStore.Downloads use karo.
            // Video/Audio collection me "Download/..." RELATIVE_PATH mana hai —
            // wahi "Primary directory Download not allowed" crash deta tha (merge fail).
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else if (mime.startsWith("audio")) {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val uri: Uri = contentResolver.insert(collection, values)
            ?: throw Exception("gallery save fail")
        contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(src).use { inp -> inp.copyTo(out) }
        } ?: throw Exception("write fail")
        return uri
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT < 29) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 100
                )
            }
        }
    }

    private fun safeName(s: String): String {
        return s.replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(100).ifEmpty { "video" }
    }

    private fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/opus"
        "weba" -> "audio/webm"
        "wav" -> "audio/wav"
        else -> "application/octet-stream"
    }

    private fun fmtDur(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return (if (h > 0) "$h:" else "") + "%02d:%02d".format(m, sec)
    }

    private fun humanSize(b: Long): String = when {
        b >= 1_073_741_824 -> "%.2f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024 -> "%d KB".format(b / 1024)
        else -> "$b B"
    }

    private fun humanSpeed(bps: Long): String = when {
        bps >= 1_048_576 -> "%.1f MB/s".format(bps / 1_048_576.0)
        bps >= 1024 -> "%d KB/s".format(bps / 1024)
        bps > 0 -> "$bps B/s"
        else -> "…"
    }

    /** Codec ka chhota naam: AVC / VP9 / AV1 / AAC / OPUS */
    private fun shortCodec(c: String): String {
        val v = c.lowercase()
        return when {
            v.startsWith("avc1") || v.startsWith("h264") -> "AVC"
            v.startsWith("vp09") || v == "vp9" -> "VP9"
            v.startsWith("av01") || v == "av1" -> "AV1"
            v.startsWith("mp4a") -> "AAC"
            v == "opus" -> "OPUS"
            v == "vorbis" -> "VORBIS"
            v.isEmpty() || v == "none" -> ""
            else -> c.substringBefore(".").uppercase()
        }
    }

    /** Smooth-playing hint: AVC/progressive = har phone par smooth;
     *  AV1 ya VP9-4K kamzor phone par atak sakta hai. */
    private fun smoothHint(f: Fmt): String {
        val v = f.vcodec.lowercase()
        if (f.progressive) return "✓ Smooth"
        if (v.startsWith("avc1") || v.startsWith("h264")) return "✓ Smooth"
        if (v.startsWith("av01") || v == "av1") return "⚠ bhaari (AV1)"
        if ((v.startsWith("vp09") || v == "vp9") && f.height > 1080) return "⚠ bhaari (4K)"
        return "HD"
    }

    private fun shortErr(e: Exception): String {
        val m = e.message.orEmpty()
        return if (m.length > 110) m.take(110) + "..." else m.ifEmpty { "unknown" }
    }

    // ---------------- Up-Next / Recent adapter (tap = bina copy fetch) ----------------
    inner class UpNextAdapter(
        private val onTap: (UpNextItem) -> Unit,
        private val onClose: ((UpNextItem) -> Unit)? = null,
        private val showClose: Boolean = false,
        private val onLongTap: ((UpNextItem) -> Unit)? = null,
    ) : RecyclerView.Adapter<UpNextAdapter.VH>() {
        private var items: List<UpNextItem> = emptyList()
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.uThumb)
            val title: TextView = v.findViewById(R.id.uTitle)
            val meta: TextView = v.findViewById(R.id.uMeta)
            val close: ImageButton = v.findViewById(R.id.uClose)
        }
        fun setItems(list: List<UpNextItem>) {
            items = list
            runOnUiThread { notifyDataSetChanged() }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_upnext, parent, false)
            return VH(v)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.title.text = item.title.ifEmpty { "Video" }
            h.meta.text = if (item.durationSec > 0) fmtDur(item.durationSec) else item.url.take(50)
            // RECYCLE-FIX: holder reuse hone par purani thumb-thread galat row me
            // bitmap laga deti thi — tag se verify karo, warna galat thumbnails.
            h.thumb.tag = item.thumb
            h.thumb.setImageDrawable(null)
            if (item.thumb.isNotEmpty()) {
                Thread {
                    try {
                        val req = Request.Builder().url(item.thumb)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36")
                            .build()
                        httpShort.newCall(req).execute().use { res ->
                            if (!res.isSuccessful) return@use
                            val bytes = res.body?.bytes() ?: return@use
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@use
                            runOnUiThread {
                                try {
                                    if (h.thumb.tag == item.thumb) h.thumb.setImageBitmap(bmp)
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }.start()
            }
            h.itemView.setOnClickListener { onTap(item) }
            h.itemView.setOnLongClickListener {
                try { onLongTap?.invoke(item) } catch (_: Exception) {}
                true
            }
            // Recent row ka ✕ (Up Next me hidden): wahi item history se hatao
            if (showClose && onClose != null) {
                h.close.visibility = View.VISIBLE
                h.close.setOnClickListener { onClose.invoke(item) }
            } else {
                h.close.visibility = View.GONE
                h.close.setOnClickListener(null)
            }
        }
    }

    // ---------------- list adapter (speed + pause/cancel) ----------------
    inner class FormatAdapter(
        private val onDownload: (Fmt, String) -> Unit,
        private val onPauseToggle: (Fmt, String) -> Unit,
        private val onCancel: (Fmt, String) -> Unit,
    ) : RecyclerView.Adapter<FormatAdapter.VH>() {

        private var items: List<Fmt> = emptyList()
        private var keys: List<String> = emptyList()
        private val prog = HashMap<String, DlProgress>()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val badge: TextView = v.findViewById(R.id.badge)
            val title: TextView = v.findViewById(R.id.fTitle)
            val sub: TextView = v.findViewById(R.id.fSub)
            val speed: TextView = v.findViewById(R.id.fSpeed)
            val bar: ProgressBar = v.findViewById(R.id.fBar)
            val btn: Button = v.findViewById(R.id.fBtn)
            val controls: LinearLayout = v.findViewById(R.id.fControls)
            val pauseBtn: Button = v.findViewById(R.id.fPauseBtn)
            val cancelBtn: Button = v.findViewById(R.id.fCancelBtn)
        }

        fun setItems(list: List<Fmt>, seq: Int) {
            items = list
            keys = list.map { "$seq::${it.id}" }
            // DUP-FIX: pehle prog.clear() tha — quality pill dabate hi chalti
            // download ka UI progress ud jata, aur Download dobara dabane par
            // DUPLICATE download shuru ho jata (purana thread bhi chalta rehta).
            // Ab sirf gayab keys hatao: pill-change (same seq) me progress bachta hai,
            // nayi search (naya seq) me purane apne aap saaf hote hain.
            prog.keys.retainAll(keys.toSet())
            runOnUiThread { notifyDataSetChanged() }
        }

        fun keyAt(pos: Int): String = keys.getOrNull(pos) ?: items.getOrNull(pos)?.id.orEmpty()

        fun getState(key: String): DlProgress = prog[key] ?: DlProgress()

        fun reset(key: String) {
            prog.remove(key)
            runOnUiThread {
                val idx = keys.indexOf(key)
                if (idx >= 0) notifyItemChanged(idx)
            }
        }

        fun update(key: String, state: DlState, frac: Float?, label: String?, speed: String?) {
            val p = prog.getOrPut(key) { DlProgress() }
            p.state = state
            if (frac != null) p.frac = frac.coerceIn(0f, 1f)
            if (label != null) p.label = label
            if (speed != null) p.speed = speed
            runOnUiThread {
                val idx = keys.indexOf(key)
                if (idx >= 0) notifyItemChanged(idx)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_format, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val f = items[pos]
            val key = keyAt(pos)
            val isAud = f.type == "audio"
            h.badge.text = if (isAud) f.ext.uppercase().ifEmpty { "AUD" } else when {
                f.height >= 1080 -> "FHD"
                f.height >= 720 -> "HD"
                else -> "SD"
            }
            h.title.text = f.label + when {
                f.progressive -> "  ★1-Tap"
                f.needsMerge -> "  (1-Tap HD)"
                else -> ""
            }
            h.sub.text = buildString {
                append(f.ext.uppercase().ifEmpty { if (isAud) "AUDIO" else "VIDEO" })
                if (f.size > 0) append("  •  " + humanSize(f.size))
                val vc = shortCodec(f.vcodec)
                val ac = shortCodec(f.acodec)
                if (vc.isNotEmpty()) append("  •  " + vc)
                else if (ac.isNotEmpty()) append("  •  " + ac)
                // Smooth-play hint: AVC har phone par makhan chalega,
                // AV1/VP9 4K purane phone par atak sakta hai
                if (!isAud) append("  •  " + smoothHint(f))
            }
            val pr = prog[key]
            val active = pr != null && (pr.state == DlState.DOWNLOADING || pr.state == DlState.PAUSED)
            if (active && pr != null) {
                h.bar.visibility = View.VISIBLE
                h.bar.progress = (pr.frac * 100).toInt()
                h.btn.text = pr.label
                h.btn.isEnabled = false
                h.controls.visibility = View.VISIBLE
                h.pauseBtn.text = if (pr.state == DlState.PAUSED) "Resume" else "Pause"
                if (pr.speed.isNotEmpty()) {
                    h.speed.visibility = View.VISIBLE
                    h.speed.text = "⚡ " + pr.speed
                } else {
                    h.speed.visibility = View.GONE
                }
                h.pauseBtn.setOnClickListener { onPauseToggle(f, key) }
                h.cancelBtn.setOnClickListener { onCancel(f, key) }
            } else {
                h.bar.visibility = View.GONE
                h.speed.visibility = View.GONE
                h.controls.visibility = View.GONE
                h.btn.isEnabled = true
                h.btn.text = pr?.label ?: when {
                    f.progressive -> "Download ★1-Tap"
                    f.needsMerge -> "1-Tap HD"
                    else -> "Download"
                }
                if (pr?.state == DlState.DONE) {
                    h.btn.text = "Done ✓"
                    h.btn.isEnabled = false
                }
            }
            h.btn.setOnClickListener { onDownload(f, key) }
        }
    }
}

/** Lossless remux (no re-encode): video track + audio track ko ek file me jodta hai.
 *  Sab kuch phone par hota hai — user ka CPU/RAM, koi server nahi.
 *
 *  IMPORTANT: container CODEC dekh kar chuna jata hai (extension dekh kar nahi) —
 *  VP9/AV1 video ko MP4 me dalne par MediaMuxer throw karta tha = "merge failed".
 *  - AVC/HEVC + AAC/MP3 -> .mp4 (har phone par chalta hai)
 *  - VP8/VP9 + Opus/Vorbis -> .webm
 *  - AV1 (av01) -> MediaMuxer support nahi karta = IncompatibleTracksException,
 *    caller video-only save karega + sahi quality suggest karega. */
object Remuxer {
    class IncompatibleTracksException(msg: String) : Exception(msg)

    @Throws(Exception::class)
    fun remuxAuto(video: File, audio: File, dir: File, stamp: Long, onP: (Float) -> Unit): File {
        // pehle actual MIME nikalo (extension par bharosa nahi)
        val vMime: String
        val aMime: String
        val vEx0 = MediaExtractor()
        val aEx0 = MediaExtractor()
        try {
            vEx0.setDataSource(video.absolutePath)
            aEx0.setDataSource(audio.absolutePath)
            vMime = mimeOf(vEx0, "video/")
            aMime = mimeOf(aEx0, "audio/")
        } finally {
            try { vEx0.release() } catch (_: Exception) {}
            try { aEx0.release() } catch (_: Exception) {}
        }
        val v = vMime.lowercase()
        val a = aMime.lowercase()
        val mp4VideoOk = v == "video/avc" || v == "video/hevc" || v == "video/mp4v-es"
        val mp4AudioOk = a == "audio/mp4a-latm" || a == "audio/mpeg"
        val webmVideoOk = v == "video/x-vnd.on2.vp8" || v == "video/x-vnd.on2.vp9"
        val webmAudioOk = a == "audio/opus" || a == "audio/vorbis"
        val (outFormat, outExt) = when {
            mp4VideoOk && mp4AudioOk ->
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "mp4"
            webmVideoOk && webmAudioOk ->
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM to "webm"
            else -> throw IncompatibleTracksException(
                "${shortMime(vMime)}+${shortMime(aMime)} phone par jud nahi sakta")
        }
        val out = File(dir, "gsko_$stamp.$outExt")
        remux(video, audio, out, outFormat, onP)
        return out
    }

    private fun mimeOf(ex: MediaExtractor, prefix: String): String {
        for (i in 0 until ex.trackCount) {
            val m = try { ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty() }
            catch (_: Exception) { "" }
            if (m.startsWith(prefix)) return m
        }
        throw Exception("track nahi mila ($prefix)")
    }

    /** Sample buffer track ke hisab se: MAX_INPUT_SIZE lo, kam se kam `min`.
     *  Chhota buffer = bada key-frame drop = merge fail (khaas taur 1080p/4K me). */
    private fun maxInputSize(fmt: MediaFormat, min: Int): Int {
        return try {
            val k = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            k.coerceIn(min, 32 * 1024 * 1024)
        } catch (_: Exception) { min }
    }

    private fun shortMime(m: String): String {
        val v = m.lowercase()
        return when {
            v == "video/avc" -> "AVC"
            v == "video/hevc" -> "HEVC"
            v.contains("vp9") -> "VP9"
            v.contains("vp8") -> "VP8"
            v == "video/av01" -> "AV1"
            v == "audio/mp4a-latm" -> "AAC"
            v == "audio/opus" -> "OPUS"
            v == "audio/vorbis" -> "VORBIS"
            v == "audio/mpeg" -> "MP3"
            else -> m.substringAfter("/")
        }
    }

    @Throws(Exception::class)
    fun remux(video: File, audio: File, out: File, outFormat: Int, onP: (Float) -> Unit) {
        val vEx = MediaExtractor()
        val aEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            vEx.setDataSource(video.absolutePath)
            aEx.setDataSource(audio.absolutePath)
            val vTrack = selectTrack(vEx, "video/")
            val aTrack = selectTrack(aEx, "audio/")
            val vFormat = vEx.getTrackFormat(vTrack)
            val aFormat = aEx.getTrackFormat(aTrack)
            muxer = MediaMuxer(out.absolutePath, outFormat)
            val vIdx = muxer.addTrack(vFormat)
            val aIdx = muxer.addTrack(aFormat)
            muxer.start()
            vEx.selectTrack(vTrack)
            aEx.selectTrack(aTrack)

            val totalUs = try {
                vFormat.getLong(MediaFormat.KEY_DURATION)
            } catch (e: Exception) {
                0L
            }
            // HD/4K key-frame 2MB se bada ho jata hai — chhota buffer hone par
            // readSampleData sample drop kar deta hai = adhuri/tooti file ya merge fail.
            // Isliye track ke MAX_INPUT_SIZE se buffer banao (yahi asli merge-fail fix hai).
            val vBuf = java.nio.ByteBuffer.allocate(maxInputSize(vFormat, 8 * 1024 * 1024))
            val aBuf = java.nio.ByteBuffer.allocate(maxInputSize(aFormat, 1 * 1024 * 1024))
            val vInfo = MediaCodec.BufferInfo()
            val aInfo = MediaCodec.BufferInfo()
            var vHas = fillSample(vEx, vBuf, vInfo)
            var aHas = fillSample(aEx, aBuf, aInfo)
            // SYNC FIX: dono track ka pehla timestamp alag hota hai (audio aage/peeche).
            // Base offset ghata kar dono ko 0 se start karo, warna video atak-atak chalega.
            val vBase = if (vHas) vInfo.presentationTimeUs else 0L
            val aBase = if (aHas) aInfo.presentationTimeUs else 0L
            if (vHas) vInfo.presentationTimeUs -= vBase
            if (aHas) aInfo.presentationTimeUs -= aBase
            var lastV = -1L
            var lastA = -1L
            var drops = 0
            // dono me se jo sample pehle aaye use likho (interleave).
            // Har track me time kabhi peeche na jaye (monotonic) — player stutter fix.
            // Ek-adh kharab sample aaye to use chhodo aur aage badho (poora merge
            // fail mat karo); lagatar 100+ drops = track hi kharab, tabhi abort.
            while (vHas || aHas) {
                if (!aHas || (vHas && vInfo.presentationTimeUs <= aInfo.presentationTimeUs)) {
                    if (vInfo.presentationTimeUs < 0) vInfo.presentationTimeUs = 0
                    if (vInfo.presentationTimeUs <= lastV) vInfo.presentationTimeUs = lastV + 1
                    lastV = vInfo.presentationTimeUs
                    try {
                        muxer.writeSampleData(vIdx, vBuf, vInfo)
                        drops = 0
                    } catch (we: Exception) {
                        drops++
                        if (drops > 100) throw Exception("merge fail (video track kharab, ${drops} drop)")
                    }
                    if (totalUs > 0) {
                        onP((vInfo.presentationTimeUs.toFloat() / totalUs).coerceIn(0f, 1f))
                    }
                    vHas = fillSample(vEx, vBuf, vInfo)
                    if (vHas) {
                        vInfo.presentationTimeUs -= vBase
                        if (vInfo.presentationTimeUs < 0) vInfo.presentationTimeUs = 0
                    }
                } else {
                    if (aInfo.presentationTimeUs < 0) aInfo.presentationTimeUs = 0
                    if (aInfo.presentationTimeUs <= lastA) aInfo.presentationTimeUs = lastA + 1
                    lastA = aInfo.presentationTimeUs
                    try {
                        muxer.writeSampleData(aIdx, aBuf, aInfo)
                        drops = 0
                    } catch (we: Exception) {
                        drops++
                        if (drops > 100) throw Exception("merge fail (audio track kharab, ${drops} drop)")
                    }
                    aHas = fillSample(aEx, aBuf, aInfo)
                    if (aHas) {
                        aInfo.presentationTimeUs -= aBase
                        if (aInfo.presentationTimeUs < 0) aInfo.presentationTimeUs = 0
                    }
                }
            }
            onP(1f)
        } finally {
            try { muxer?.stop() } catch (e: Exception) { /* ignore */ }
            try { muxer?.release() } catch (e: Exception) { /* ignore */ }
            vEx.release()
            aEx.release()
        }
    }

    private fun selectTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = try {
                ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            } catch (e: Exception) {
                ""
            }
            if (mime.startsWith(prefix)) return i
        }
        throw Exception("track nahi mila ($prefix)")
    }

    /** Agla sample buffer me padhta hai. Return: true = sample mila, false = track khatm. */
    private fun fillSample(
        ex: MediaExtractor,
        buf: java.nio.ByteBuffer,
        info: MediaCodec.BufferInfo
    ): Boolean {
        buf.clear()
        val n = ex.readSampleData(buf, 0)
        if (n < 0) return false
        info.set(0, n, ex.sampleTime, ex.sampleFlags)
        ex.advance()
        return true
    }
}
