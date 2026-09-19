package com.lvigs.gskdownloader

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Background download zinda rakhe: app minimize/home karne par bhi Android
 *  process ko jaldi na maare. Notification bar me ek "Downloading..."
 *  notification dikhta hai jab tak koi download chal raha ho.
 *  MainActivity.startFg() se start, syncService() se auto-stop. */
class GskDownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        DownloadNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = NotificationCompat.Builder(this, DownloadNotifier.CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("GSK Downloader")
                .setContentText("Download chal raha hai... (background me bhi)")
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 29)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, FG_ID, n, type)
        } catch (_: Exception) {}
        // NOT_STICKY: process kill hua to download threads bhi mar chuke hain —
        // dobara khali "Downloading..." notification lekar zinda mat ho.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val FG_ID = 1001
    }
}
