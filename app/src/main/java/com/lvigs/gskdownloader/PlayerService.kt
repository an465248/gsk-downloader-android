package com.lvigs.gskdownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

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
        const val SEEK_MS = 10_000L
        var engine: Player? = null
            set
        var playerReady: Boolean = false
        private var sessionReady: Boolean = false
        private var session: MediaSession? = null
        private var sessionWaiters = mutableListOf<(Boolean) -> Unit>()

        fun observeReady(cb: (Boolean) -> Unit) {
            sessionWaiters += cb
            try { cb(sessionReady) } catch (_: Exception) {}
        }

        private fun onSessionReady(ready: Boolean) {
            sessionReady = ready
            sessionWaiters.forEach { try { it(ready) } catch (_: Exception) {} }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = androidx.media3.exoplayer.ExoPlayer.Builder(this).build()
        createChannel()
        session = MediaSession.Builder(this, engine!!).build()
        onSessionReady(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CLOSE) { stopSelf(); return START_NOT_STICKY }
        if (action == ACTION_TOGGLE) { engine?.let { if (it.isPlaying) it.pause() else it.play() } }
        else if (action == ACTION_NEXT) { engine?.seekTo((engine?.duration ?: 0).coerceAtLeast(0)) }
        else if (action == ACTION_REWIND) { engine?.seekTo((engine?.currentPosition ?: 0) - SEEK_MS) }
        else if (action == ACTION_FF) { engine?.seekTo((engine?.currentPosition ?: 0) + SEEK_MS) }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        engine?.release()
        engine = null
        session?.release()
        session = null
        stopForeground(true)
        stopSelf()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "GSK Playback", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}
