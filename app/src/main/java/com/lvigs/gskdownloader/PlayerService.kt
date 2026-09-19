package com.lvigs.gskdownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import androidx.media3.session.MediaController

/**
 * Background player (Media3 MediaSessionService):
 * - Back/Home dabane par bhi bajta rahe (activity marne par bhi zinda).
 * - Notification bar me Pause/Play + Prev + Next + Stop (user ka control).
 * - YouTube ke alag video/audio tracks yahin merge hote hain (factory).
 * - Screen-off par audio chalta rahe (wake-mode local).
 *
 * Activity (PlayerActivity) ISI player ko same-process me directly drive karti
 *  hai — play/pause/seek/speed/quality sab isi par, player ek hi rehta hai.
 *  MediaSession/lock-screen/notification sirf BRIDGE hain (observe + system
 *  commands), khud kuch nahi bajate.
 */
class PlayerService : MediaSessionService() {

    companion object {
        const val CHANNEL = "gsk_player"
        const val NOTIF_ID = 3001
        const val ACTION_CLOSE = "gsk.action.CLOSE"
        const val ACTION_TOGGLE = "gsk.action.TOGGLE"
        const val ACTION_NEXT = "gsk.action.NEXT"
        const val ACTION_PREV = "gsk.action.PREV"
        const val ACTION_REWIND = "gsk.action.REWIND"
        const val ACTION_FF = "gsk.action.FF"
        const val EXTRA_AUDIO_URL = "gsk.audio_url"
        const val EXTRA_THUMB_URL = "gsk.thumb_url"
        const val SEEK_MS = 10_000L

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** Notification ke Next/Prev dabane par activity ka queue badle.
         *  Activity zinda ho to set karti hai, marne par null (tab no-op). */
        @Volatile var externalNext: (() -> Unit)? = null
        @Volatile var externalPrev: (() -> Unit)? = null

        /** SINGLE engine ka same-process access: PlayerActivity ISI player ko
         *  directly drive karti hai (play/pause/seek/quality sab yahi).
         *  Koi doosra player kahin nahi banta. Service engine ka OWNER hai —
         *  activity sirf use karti hai, release KABHI nahi karti. */
        @Volatile var playerRef: ExoPlayer? = null
        @Volatile var playerReady: Boolean = false
        /** Engine taiyaar hote hi (main thread par) bulaya jata hai. */
        @Volatile var onPlayerReady: (() -> Unit)? = null
        /** MediaSession bridge taiyaar? (false = sirf foreground, card retry me). */
        @Volatile var sessionReady: Boolean = false

        fun itemFor(
            title: String, videoUrl: String, audioUrl: String, hasAudio: Boolean,
            thumbUrl: String = "",
        ): MediaItem {
            val extras = Bundle()
            if (!hasAudio && audioUrl.isNotEmpty()) extras.putString(EXTRA_AUDIO_URL, audioUrl)
            return MediaItem.Builder()
                .setUri(videoUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title.ifEmpty { "Video" })
                        .setArtist("GSK • Ad-Free 🚫")
                        .setArtworkUri(
                            try {
                                if (thumbUrl.isNotEmpty()) android.net.Uri.parse(thumbUrl) else null
                            } catch (_: Exception) { null }
                        )
                        .build()
                )
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder().setExtras(extras).build()
                )
                .build()
        }

        /** Local downloaded file bhi isi player me chalao (file/content Uri). */
        fun itemForLocal(title: String, uri: android.net.Uri): MediaItem {
            return MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title.ifEmpty { "Video" })
                        .setArtist("GSK • Ad-Free 🚫")
                        .build()
                )
                .build()
        }
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        try { ensureChannel() } catch (_: Exception) {}
        // FGS-timeout killer: turant placeholder notification (extract me
        // seconds lagte hain — tab tak process zinda rahe).
        try { startForegroundNow() } catch (_: Exception) {}
        try {
            val dsFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)
            val progFactory = ProgressiveMediaSource.Factory(dsFactory)
            // Har item ke liye: video + (alag audio ho to) merge — activity ko
            // kuch nahi karna, bas MediaItem bhejna hai.
            val mergingFactory = object : MediaSource.Factory {
                override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                    try {
                        val uri = mediaItem.localConfiguration?.uri
                            ?: throw IllegalArgumentException("media uri missing")
                        val video = progFactory.createMediaSource(MediaItem.fromUri(uri))
                        val au = try {
                            mediaItem.requestMetadata.extras?.getString(EXTRA_AUDIO_URL).orEmpty()
                        } catch (_: Exception) { "" }
                        return if (au.isEmpty()) video
                        else MergingMediaSource(
                            video,
                            progFactory.createMediaSource(MediaItem.fromUri(au)),
                        )
                    } catch (e: Exception) {
                        // Merge fail -> caller ko error milega, service ZINDA rahega.
                        throw e
                    }
                }

                override fun getSupportedTypes(): IntArray =
                    intArrayOf(C.CONTENT_TYPE_OTHER)

                override fun setDrmSessionManagerProvider(
                    drmSessionManagerProvider: DrmSessionManagerProvider
                ): MediaSource.Factory = this

                override fun setLoadErrorHandlingPolicy(
                    loadErrorHandlingPolicy: LoadErrorHandlingPolicy
                ): MediaSource.Factory = this
            }
            val p = ExoPlayer.Builder(this)
                .setMediaSourceFactory(mergingFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
            player = p
            // Engine publish karo — activity ISI instance ko drive karegi.
            // (onCreate main thread par chalta hai, isliye direct assign safe.)
            try {
                playerRef = p
                playerReady = true
                try { onPlayerReady?.invoke() } catch (_: Exception) {}
            } catch (_: Exception) {}
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    // Kuch bajne layak nahi bacha (Stop dab gaya) = service band.
                    if (state == Player.STATE_IDLE) {
                        try { stopSelf() } catch (_: Exception) {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // SINGLE player ka error: session/notification zinda rahe,
                    // activity apna error overlay dikhayegi (Retry sahit).
                    try { p.pause() } catch (_: Exception) {}
                }
            })
            try {
                session = MediaSession.Builder(this, p).build()
                try {
                    sessionReady = session != null
                    android.util.Log.i("GSK-Media", "session built: $sessionReady")
                } catch (_: Exception) {}
            } catch (e: Exception) {
                // Session (bridge) fail -> player phir bhi ZINDA (foreground
                // playback direct-engine se chalega). Bridge ko periodic retry
                // karo — playback kabhi nahi rukegi.
                try { android.util.Log.e("GSK-Media", "session build failed", e) } catch (_: Exception) {}
                session = null
                try { sessionReady = false } catch (_: Exception) {}
                try { scheduleSessionRetry() } catch (_: Exception) {}
            }
            try { setMediaNotificationProvider(Provider()) } catch (_: Exception) {}
        } catch (_: Exception) {
            // Player build hi fail -> service bekar hai, band karo (activity
            // engine-wait me Retry dikhayegi, kala screen nahi).
            try { stopSelf() } catch (_: Exception) {}
        }
    }

    /** Bridge (MediaSession) dobara banao — sirf tab jab player ZINDA ho.
     *  Main thread par chalao (MediaSession ko Looper chahiye). */
    private fun ensureSession() {
        try {
            if (session != null) {
                try { sessionReady = true } catch (_: Exception) {}
                return
            }
            val p = player ?: return
            session = MediaSession.Builder(this, p).build()
            try {
                sessionReady = session != null
                android.util.Log.i("GSK-Media", "session rebuilt: $sessionReady")
            } catch (_: Exception) {}
            // Session der se bana ho to notification-manager ko dobara jodo
            // taaki media card + lock-screen publish ho.
            try {
                if (session != null) setMediaNotificationProvider(Provider())
            } catch (_: Exception) {}
        } catch (e: Exception) {
            try { android.util.Log.e("GSK-Media", "session rebuild failed", e) } catch (_: Exception) {}
        }
    }

    /** Session null rahe to periodic retry (5s/15s/30s/60s...) jab tak player
     *  ZINDA hai — max ~12 baar (~3 min). Playback par zero asar. */
    @Volatile private var sessionRetries: Int = 0

    private fun scheduleSessionRetry() {
        try {
            if (session != null || player == null) return
            if (sessionRetries >= 12) {
                try { android.util.Log.e("GSK-Media", "session retries exhausted") } catch (_: Exception) {}
                return
            }
            sessionRetries++
            val delay = when {
                sessionRetries <= 1 -> 5000L
                sessionRetries <= 3 -> 15000L
                else -> 30000L
            }
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        if (session == null && player != null) {
                            ensureSession()
                            scheduleSessionRetry()
                        }
                    } catch (_: Exception) {}
                }, delay)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Bridge manga gaya par bana nahi hai -> banane ki koshish karo.
        // (Binder thread par build nahi kar sakte, isliye main par post.)
        try {
            if (session == null && player != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try { ensureSession() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return session
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLOSE -> {
                try { player?.stop() } catch (_: Exception) {}
                try { player?.clearMediaItems() } catch (_: Exception) {}
                try { stopSelf() } catch (_: Exception) {}
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                try {
                    val p = player
                    if (p != null) p.playWhenReady = !p.playWhenReady
                } catch (_: Exception) {}
                return START_STICKY
            }
            ACTION_NEXT -> {
                try { externalNext?.invoke() } catch (_: Exception) {}
                return START_STICKY
            }
            ACTION_PREV -> {
                try { externalPrev?.invoke() } catch (_: Exception) {}
                return START_STICKY
            }
            ACTION_REWIND -> {
                // Notification ⏪ 10s — player ke andar wale control jaisa, bina restart.
                try {
                    val p = player
                    if (p != null) {
                        val np = (p.currentPosition - SEEK_MS).coerceAtLeast(0L)
                        p.seekTo(np)
                    }
                } catch (_: Exception) {}
                return START_STICKY
            }
            ACTION_FF -> {
                // Notification ⏩ 10s — foreground open kiye bina seek.
                try {
                    val p = player
                    if (p != null) {
                        val dur = try { p.duration } catch (_: Exception) { C.TIME_UNSET }
                        var np = p.currentPosition + SEEK_MS
                        if (dur != C.TIME_UNSET && dur > 0) np = np.coerceAtMost(dur)
                        p.seekTo(np)
                    }
                } catch (_: Exception) {}
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swipe-away: baj raha ho to background me chalne do (user wahi
        // chahta hai), ruka ho to service band kar do.
        try {
            val p = player
            if (p == null || (!p.playWhenReady && p.playbackState != Player.STATE_BUFFERING)) {
                try { p?.stop() } catch (_: Exception) {}
                stopSelf()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { externalNext = null } catch (_: Exception) {}
        try { externalPrev = null } catch (_: Exception) {}
        try { onPlayerReady = null } catch (_: Exception) {}
        try { playerReady = false } catch (_: Exception) {}
        try { playerRef = null } catch (_: Exception) {}
        try { sessionReady = false } catch (_: Exception) {}
        try { sessionRetries = 0 } catch (_: Exception) {}
        try { session?.release() } catch (_: Exception) {}
        session = null
        try { player?.release() } catch (_: Exception) {}
        player = null
        super.onDestroy()
    }

    // ---------------- notification (Prev / -10s / Play / +10s / Next) ----------------
    // REQUIRED order: ⏮ Prev, ⏪ 10s, ▶/⏸ Play, ⏩ 10s, ⏭ Next.
    // Stop swipe-away (deleteIntent) se milta rahega — extra action se chhoti
    // screen par bheed nahi badhate. Lock-screen par artworkUri se thumb.
    private inner class Provider : MediaNotification.Provider {
        override fun createNotification(
            session: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            callback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            // Notification BRIDGE hai — iska koi bhi fail playback NAHI rokega.
            // Artwork/permission/metadata me se kuch bhi fail ho to minimal
            // card dikhao (kali screen / crash kabhi nahi).
            try {
                val p = session.player
                val playing = try { p.isPlaying } catch (_: Exception) { false }
                val md = try { p.currentMediaItem?.mediaMetadata } catch (_: Exception) { null }
                val title = try {
                    md?.title?.toString()?.ifEmpty { "GSK Player" } ?: "GSK Player"
                } catch (_: Exception) { "GSK Player" }
                val nb = NotificationCompat.Builder(this@PlayerService, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(title)
                    .setContentText("🚫 Ad-Free • baj raha hai (background me bhi)")
                    .setContentIntent(contentIntent())
                    .setDeleteIntent(actionIntent(ACTION_CLOSE))
                    .setOngoing(playing)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    
                    .addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREV))
                    .addAction(android.R.drawable.ic_media_rew, "Back 10s", actionIntent(ACTION_REWIND))
                    .addAction(
                        if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        if (playing) "Pause" else "Play",
                        actionIntent(ACTION_TOGGLE),
                    )
                    .addAction(android.R.drawable.ic_media_ff, "Forward 10s", actionIntent(ACTION_FF))
                    .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT))
                return MediaNotification(NOTIF_ID, nb.build())
            } catch (e: Exception) {
                // Fallback me bhi controls rakho (Prev/Play/Next) — card bina
                // controls ke kabhi na dikhe. Har action alag try me.
                try { android.util.Log.e("GSK-Media", "notification build failed", e) } catch (_: Exception) {}
                val nb = NotificationCompat.Builder(this@PlayerService, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("GSK Player")
                    .setContentText("🚫 Ad-Free • baj raha hai")
                    .setContentIntent(contentIntent())
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    
                try {
                    nb.addAction(android.R.drawable.ic_media_previous, "Previous", actionIntent(ACTION_PREV))
                } catch (_: Exception) {}
                try {
                    nb.addAction(android.R.drawable.ic_media_play, "Play", actionIntent(ACTION_TOGGLE))
                } catch (_: Exception) {}
                try {
                    nb.addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT))
                } catch (_: Exception) {}
                return MediaNotification(NOTIF_ID, nb.build())
            }
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = false
    }

    /** Service start hote hi foreground pakdo (timeout-killer). */
    private fun startForegroundNow() {
        try {
            val nb = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("GSK Player")
                .setContentText("Taiyaar ho raha hai... (link fetch ho raha)")
                .setContentIntent(contentIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, nb.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                ServiceCompat.startForeground(this, NOTIF_ID, nb.build(), 0)
            }
        } catch (_: Exception) {}
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Background Play", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "GSK player controls (pause/next/stop)" }
            )
        } catch (_: Exception) {}
    }

    private fun contentIntent(): PendingIntent {
        val i = Intent(this, PlayerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val f = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 10, i, f)
    }

    private fun actionIntent(action: String): PendingIntent {
        val i = Intent(this, PlayerService::class.java).apply { this.action = action }
        val f = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val rc = when (action) {
            ACTION_TOGGLE -> 11
            ACTION_NEXT -> 12
            ACTION_PREV -> 13
            ACTION_REWIND -> 15
            ACTION_FF -> 16
            else -> 14
        }
        return PendingIntent.getService(this, rc, i, f)
    }
}
