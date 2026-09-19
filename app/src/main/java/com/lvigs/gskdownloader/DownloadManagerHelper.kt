package com.lvigs.gskdownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object DownloadManagerHelper {

    private var downloadId: Long = -1

    fun getDownloadId(): Long = downloadId

    fun setDownloadId(id: Long) { downloadId = id }

    fun startDownload(context: Context, streamUrl: String, title: String, quality: String): Long {
        val req = DownloadManager.Request(Uri.parse(streamUrl))
            .setTitle(title)
            .setDescription("GSK • $quality")
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_MOVIES,
                "${title}_${quality}.mp4"
            )
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadId = dm.enqueue(req)
        return downloadId
    }

    fun cancelDownload(context: Context) {
        if (downloadId > 0) {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(downloadId)
            downloadId = -1
        }
    }
}
