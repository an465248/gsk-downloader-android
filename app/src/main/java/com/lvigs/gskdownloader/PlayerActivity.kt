package com.lvigs.gskdownloader

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import androidx.fragment.app.FragmentActivity

class PlayerActivity : FragmentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var overlayHost: FrameLayout
    private lateinit var urlInput: EditText
    private lateinit var playerTitle: TextView
    private lateinit var goBtn: Button
    private lateinit var queueList: RecyclerView
    private lateinit var queueTitle: TextView
    private lateinit var searchList: LinearLayout
    private lateinit var dlVideoBtn: Button
    private lateinit var dlAudioBtn: Button
    private lateinit var cancelDl: Button
    private lateinit var likeBtn: Button
    private lateinit var dislikeBtn: Button
    private lateinit var subscribeBtn: Button
    private lateinit var copyLinkBtn: Button
    private lateinit var commentsCard: LinearLayout
    private lateinit var commentsList: LinearLayout
    private lateinit var descText: TextView
    private lateinit var seeMore: TextView
    private lateinit var channelName: TextView
    private lateinit var channelSubs: TextView
    private lateinit var channelAvatar: ImageView
    private lateinit var btnComment: Button
    private lateinit var shareBtn: Button
    private lateinit var fsBtn: Button
    private lateinit var settingsBtn: Button
    private lateinit var qpills: LinearLayout
    private lateinit var qpillsScroll: HorizontalScrollView
    private lateinit var commentsTitle: TextView

    private var player: ExoPlayer? = null
    private var overlay: PlayerOverlay? = null
    private var currentUrl: String = ""
    private var currentTitle: String = ""
    private var currentStreams: List<StreamFetcher.StreamItem> = emptyList()
    private var currentAudioStreams: List<StreamFetcher.StreamItem> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val permsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        setContentView(R.layout.activity_player)
        bindViews()
        StreamFetcher.init(this)
        ensurePlayer()
        setupOverlay()
        setupPlay()
        handleIntent(intent)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.playerView)
        overlayHost = findViewById(R.id.overlayHost)
        urlInput = findViewById(R.id.playerUrlInput)
        playerTitle = findViewById(R.id.playerTitle)
        goBtn = findViewById(R.id.playerGoBtn)
        queueList = findViewById(R.id.playerQueueList)
        queueTitle = findViewById(R.id.playerQueueTitle)
        searchList = findViewById(R.id.playerSearchList)
        dlVideoBtn = findViewById(R.id.playerDlVideoBtn)
        dlAudioBtn = findViewById(R.id.playerDlAudioBtn)
        cancelDl = findViewById(R.id.playerCancelDl)
        fsBtn = findViewById(R.id.playerFsBtn)
        settingsBtn = findViewById(R.id.playerSettingsBtn)
        qpills = findViewById(R.id.playerQPills)
        qpillsScroll = findViewById(R.id.playerQPillsScroll)
        likeBtn = findViewById(R.id.btnLike)
        dislikeBtn = findViewById(R.id.btnDislike)
        subscribeBtn = findViewById(R.id.btnSubscribe)
        copyLinkBtn = findViewById(R.id.btnCopyLink)
        commentsCard = findViewById(R.id.commentsCard)
        commentsList = findViewById(R.id.commentsList)
        descText = findViewById(R.id.descText)
        seeMore = findViewById(R.id.seeMore)
        channelName = findViewById(R.id.channelName)
        channelSubs = findViewById(R.id.channelSubs)
        channelAvatar = findViewById(R.id.channelAvatar)
        btnComment = findViewById(R.id.btnComment)
        shareBtn = findViewById(R.id.btnShare)
        commentsTitle = findViewById(R.id.commentsTitle)
    }

    private fun setupOverlay() {
        overlay = PlayerOverlay(this, overlayHost, { player }, PlayerOverlay.Controls(
            onTogglePlay = { togglePlay() },
            onSeekBy = { delta -> player?.seekTo((player?.currentPosition ?: 0) + delta) },
            onSeekTo = { pos -> player?.seekTo(pos) },
            onFullscreen = { toggleFullscreen() },
            onQuality = { showQualitySheet() },
            seekStepMs = { 10_000L },
            onMinimize = { minimizePlayer() },
        ))
    }

    private fun setupPlay() {
        goBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) loadUrl(url)
        }
        urlInput.setOnEditorActionListener { _, _, _ ->
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) loadUrl(url)
            true
        }
        dlVideoBtn.setOnClickListener { downloadVideo() }
        dlAudioBtn.setOnClickListener { downloadAudio() }
        cancelDl.setOnClickListener { DownloadManagerHelper.cancelDownload(this) }
        likeBtn.setOnClickListener { toggleLike() }
        dislikeBtn.setOnClickListener { toggleDislike() }
        subscribeBtn.setOnClickListener { toggleSubscribe() }
        copyLinkBtn.setOnClickListener { copyLink() }
        shareBtn.setOnClickListener { share() }
        btnComment.setOnClickListener { toggleComments() }
        fsBtn.setOnClickListener { toggleFullscreen() }
        settingsBtn.setOnClickListener { showQualitySheet() }
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString
        if (data != null && (data.contains("youtube.com") || data.contains("youtu.be") ||
                data.contains("youtube.shorts"))) {
            urlInput.setText(data)
            loadUrl(data)
        }
    }

    private fun loadUrl(url: String) {
        currentUrl = url
        Thread {
            try {
                val data = StreamFetcher.extract(url)
                runOnUiThread {
                    playerTitle.text = data.title
                    currentTitle = data.title
                    currentStreams = data.streams
                    currentAudioStreams = data.audioStreams
                    fillMeta(data)
                    fillQualityPills(data.streams)
                    fillRelated(data.related)
                }
                val firstUrl = data.streams.firstOrNull()?.url ?: return@Thread
                runOnUiThread { startPlayback(firstUrl) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Error: ${e.message?.take(80)}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun ensurePlayer() {
        if (PlayerService.engine == null) {
            PlayerService.engine = ExoPlayer.Builder(this).build()
        }
        player = PlayerService.engine as? ExoPlayer
        playerView.player = player
    }

    private fun startPlayback(url: String) {
        try {
            ensurePlayer()
            val item = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build())
                .build()
            player?.setMediaItem(item)
            player?.prepare()
            player?.playWhenReady = true
            overlay?.show()
            overlay?.refresh()
            handler.post(tick)
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message?.take(80)}", Toast.LENGTH_SHORT).show()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            try { overlay?.refresh() } catch (_: Exception) {}
            handler.postDelayed(this, 500)
        }
    }

    private fun togglePlay() {
        if (player == null) return
        if (player?.isPlaying == true) { player?.pause() }
        else { player?.play() }
    }

    private fun toggleFullscreen() {
        val fullscreen = (resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
        val flags = if (!fullscreen) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun minimizePlayer() {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    private fun showQualitySheet() {
        val all = currentStreams + currentAudioStreams
        if (all.isEmpty()) return
        val items = all.mapIndexed { i, s ->
            val label = if (s.audioOnly) "${s.resolution} (Audio)" else s.resolution
            QualitySheet.Item(index = i, label = label)
        }
        val sheet = QualitySheet.new(items, -1) { idx ->
            val s = all[idx]
            if (s.url.isNotEmpty()) {
                val item = MediaItem.Builder()
                    .setUri(s.url)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build())
                    .build()
                player?.clearMediaItems()
                player?.setMediaItem(item)
                player?.prepare()
                player?.playWhenReady = true
            }
        }
        sheet.show(supportFragmentManager, "quality")
    }

    private fun downloadVideo() {
        if (currentStreams.isEmpty()) return
        val best = currentStreams.filter { !it.audioOnly }.maxByOrNull { it.height } ?: return
        val id = DownloadManagerHelper.startDownload(this, best.url, currentTitle, best.resolution)
        DownloadManagerHelper.setDownloadId(id)
        cancelDl.visibility = View.VISIBLE
        Toast.makeText(this, "⬇ ${best.resolution} download started", Toast.LENGTH_SHORT).show()
    }

    private fun downloadAudio() {
        if (currentAudioStreams.isEmpty()) return
        val best = currentAudioStreams.maxByOrNull { it.height } ?: return
        val id = DownloadManagerHelper.startDownload(this, best.url, currentTitle, "${best.resolution} Audio")
        DownloadManagerHelper.setDownloadId(id)
        cancelDl.visibility = View.VISIBLE
        Toast.makeText(this, "⬇ Audio download started", Toast.LENGTH_SHORT).show()
    }

    private fun copyLink() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("video_url", currentUrl))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, currentUrl)
        }
        startActivity(Intent.createChooser(share, "Share via"))
    }

    private fun toggleLike() {
        val prefs = getSharedPreferences("gsk_likes", MODE_PRIVATE)
        val cur = prefs.getBoolean("liked", false)
        prefs.edit().putBoolean("liked", !cur).apply()
        likeBtn.text = if (!cur) "👍 Liked" else "👍 Like"
        Toast.makeText(this, if (!cur) "Liked!" else "Unliked", Toast.LENGTH_SHORT).show()
    }

    private fun toggleDislike() {
        val prefs = getSharedPreferences("gsk_likes", MODE_PRIVATE)
        val cur = prefs.getBoolean("disliked", false)
        prefs.edit().putBoolean("disliked", !cur).apply()
        dislikeBtn.text = if (!cur) "👎 Disliked" else "👎 Dislike"
    }

    private fun toggleSubscribe() {
        val prefs = getSharedPreferences("gsk_likes", MODE_PRIVATE)
        val cur = prefs.getBoolean("subscribed", false)
        prefs.edit().putBoolean("subscribed", !cur).apply()
        subscribeBtn.text = if (!cur) "Subscribed" else "Subscribe"
        Toast.makeText(this, if (!cur) "Subscribed!" else "Unsubscribed", Toast.LENGTH_SHORT).show()
    }

    private fun toggleComments() {
        commentsCard.visibility = if (commentsCard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun fillMeta(data: StreamFetcher.ExtractedData) {
        descText.text = data.description.ifEmpty { "No description available." }
        descText.maxLines = 3
        seeMore.visibility = if (data.description.length > 150) View.VISIBLE else View.GONE
        seeMore.setOnClickListener {
            descText.maxLines = if (descText.maxLines == 3) Int.MAX_VALUE else 3
        }
        channelName.text = data.channel
        channelSubs.text = "${data.viewCount} views"
        Glide.with(this).load(data.avatarUrl).circleCrop().into(channelAvatar)
        val prefs = getSharedPreferences("gsk_likes", MODE_PRIVATE)
        likeBtn.text = if (prefs.getBoolean("liked", false)) "👍 Liked" else "👍 Like"
        dislikeBtn.text = if (prefs.getBoolean("disliked", false)) "👎 Disliked" else "👎 Dislike"
        subscribeBtn.text = if (prefs.getBoolean("subscribed", false)) "Subscribed" else "Subscribe"
    }

    private fun fillQualityPills(streams: List<StreamFetcher.StreamItem>) {
        qpills.removeAllViews()
        val maxH = QualityHelper.maxVideoHeight()
        val filtered = streams.filter { !it.audioOnly && it.height <= maxH }
            .sortedByDescending { it.height }
        for (s in filtered) {
            val btn = MaterialButton(this)
            btn.text = s.resolution
            btn.setTextColor(0xFFFFFFFF.toInt())
            btn.textSize = 11f
            btn.setBackgroundColor(0xFF22D3EE.toInt())
            btn.setPadding(12, 6, 12, 6)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.marginEnd = 6
            btn.layoutParams = lp
            btn.setOnClickListener {
                if (s.url.isNotEmpty()) {
                    val item = MediaItem.Builder()
                        .setUri(s.url)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build())
                        .build()
                    player?.clearMediaItems()
                    player?.setMediaItem(item)
                    player?.prepare()
                    player?.playWhenReady = true
                }
            }
            qpills.addView(btn)
        }
        qpillsScroll.visibility = if (filtered.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun fillRelated(related: List<StreamFetcher.RelatedItem>) {
        if (related.isEmpty()) { queueTitle.visibility = View.GONE; return }
        queueTitle.visibility = View.VISIBLE
        queueList.visibility = View.VISIBLE
        queueList.adapter = RelatedAdapter(related)
        queueList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this,
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { if (it.contains("youtube")) { urlInput.setText(it); loadUrl(it) } }
    }

    override fun onBackPressed() {
        if (overlay?.isShowing() == true) { overlay?.hide() }
        else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        overlay?.release()
        super.onDestroy()
    }

    inner class RelatedAdapter(private val items: List<StreamFetcher.RelatedItem>) :
        androidx.recyclerview.widget.RecyclerView.Adapter<RelatedAdapter.VH>() {
        inner class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.uThumb)
            val title: TextView = v.findViewById(R.id.uTitle)
            val meta: TextView = v.findViewById(R.id.uMeta)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_upnext, p, false)
            return VH(v)
        }
        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.title.text = item.title
            h.meta.text = "${item.uploader} • ${item.viewCount} views"
            Glide.with(h.itemView.context).load(item.thumbnail).centerCrop().into(h.thumb)
            h.itemView.setOnClickListener {
                if (item.url.isNotEmpty()) loadUrl(item.url)
            }
        }
        override fun getItemCount() = items.size
    }

    companion object {
        const val TAG = "PlayerActivity"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }
}
