package com.lvigs.gskdownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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

/**
 * Background player (Media3 MediaSessionService):
 * - Back/Home dabane par bhi bajta rahe (activity marne par bhi zinda).
 * - Notification bar me Pause/Play + Prev + Next + Stop (user ka control).
 * - YouTube ke alag video/audio tracks yahin merge hote hain (factory).
 * - Screen-off par audio chalta rahe (wake-mode local).
 *
 * Activity (PlayerActivity) MediaController se judti hai — play/pause/seek/
 * speed/quality sab controller se, player ek hi (service me) rehta hai.
 */
class PlayerService : MediaSessionService() {

    companion object {
        const val CHANNEL = "gsk_player"
        const val NOTIF_ID = 3001
        const val ACTION_CLOSE = "gsk.action.CLOSE"
        const val ACTION_TOGGLE = "gsk.action.TOGGLE"
        const val ACTION_NEXT = "gsk.action.NEXT"
        const val ACTION_PREV = "gsk.action.PREV"
        const val EXTRA_AUDIO_URL = "gsk.audio_url"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /** Notification ke Next/Prev dabane par activity ka queue badle.
         *  Activity zinda ho to set karti hai, marne par null (tab no-op). */
        @Volatile var externalNext: (() -> Unit)? = null
        @Volatile var externalPrev: (() -> Unit)? = null

        fun itemFor(title: String, videoUrl: String, audioUrl: String, hasAudio: Boolean): MediaItem {
            val extras = Bundle()
            if (!hasAudio && audioUrl.isNotEmpty()) extras.putString(EXTRA_AUDIO_URL, audioUrl)
            return MediaItem.Builder()
                .setUri(videoUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title.ifEmpty { "Video" })
                        .setArtist("GSK • Ad-Free 🚫")
                        .build()
                )
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder().setExtras(extras).build()
                )
                .build()
        }
    }

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
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
                val uri = mediaItem.localConfiguration?.uri
                    ?: throw IllegalArgumentException("media uri missing")
                val video = progFactory.createMediaSource(MediaItem.fromUri(uri))
                val au = try {
                    mediaItem.requestMetadata.extras?.getString(EXTRA_AUDIO_URL).orEmpty()
                } catch (_: Exception) { "" }
                return if (au.isEmpty()) video
                else MergingMediaSource(video, progFactory.createMediaSource(MediaItem.fromUri(au)))
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
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Kuch bajne layak nahi bacha (Stop dab gaya) = service band.
                if (state == Player.STATE_IDLE) {
                    try { stopSelf() } catch (_: Exception) {}
                }
            }
        })
        session = MediaSession.Builder(this, p).build()
        setMediaNotificationProvider(Provider())
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        }
        return super.onStartCommand(intent, flags, startId)
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
        try { session?.release() } catch (_: Exception) {}
        session = null
        try { player?.release() } catch (_: Exception) {}
        player = null
        super.onDestroy()
    }

    // ---------------- notification (Pause/Next/Prev/Stop) ----------------
    private inner class Provider : MediaNotification.Provider {
        override fun createNotification(
            session: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            callback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            val p = session.player
            val playing = try { p.isPlaying } catch (_: Exception) { false }
            val md = try { p.currentMediaItem?.mediaMetadata } catch (_: Exception) { null }
            val nb = NotificationCompat.Builder(this@PlayerService, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(md?.title?.toString()?.ifEmpty { "GSK Player" } ?: "GSK Player")
                .setContentText("🚫 Ad-Free • baj raha hai (background me bhi)")
                .setContentIntent(contentIntent())
                .setDeleteIntent(actionIntent(ACTION_CLOSE))
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Prev", actionIntent(ACTION_PREV))
                .addAction(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) "Pause" else "Play",
                    actionIntent(ACTION_TOGGLE),
                )
                .addAction(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent(ACTION_CLOSE))
            return MediaNotification(NOTIF_ID, nb.build())
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = false
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
            else -> 14
        }
        return PendingIntent.getService(this, rc, i, f)
    }
}
