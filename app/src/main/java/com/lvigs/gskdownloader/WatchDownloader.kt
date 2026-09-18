package com.lvigs.gskdownloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 1-tap downloader (Watch screen): resumable single-stream download +
 * foreground notification (progress %, speed, pause/resume/cancel).
 * HD video+audio merge MainActivity ke engine me hai (Download tab).
 */
object WatchDownloader {

    class Job {
        @Volatile var paused = false
        @Volatile var cancelled = false
        @Volatile var call: okhttp3.Call? = null
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    fun isActive(key: String): Boolean = jobs.containsKey(key)

    /** Pause <-> Resume toggle. Returns naya paused state (false = job nahi hai). */
    fun togglePause(key: String): Boolean {
        val j = jobs[key] ?: return false
        j.paused = !j.paused
        return j.paused
    }

    fun cancel(key: String) {
        jobs[key]?.cancelled = true
        try { jobs[key]?.call?.cancel() } catch (_: Exception) {}
    }

    /**
     * @param audio true = Music/gsk-downloader me, false = Download/gsk-downloader me
     * @param onProgress background thread par aata hai (pct 0-100, speed text)
     */
    fun download(
        ctx0: Context,
        url: String,
        fileName: String,
        mime: String,
        audio: Boolean,
        key: String,
        onProgress: ((pct: Int, speed: String) -> Unit)? = null,
        onDone: ((ok: Boolean) -> Unit)? = null,
    ) {
        val ctx = ctx0.applicationContext
        jobs.remove(key)?.let {
            it.cancelled = true
            try { it.call?.cancel() } catch (_: Exception) {}
        }
        val job = Job()
        jobs[key] = job
        startFg(ctx)
        val nid = DownloadNotifier.idFor("w$key")
        DownloadNotifier.indeterminate(ctx, nid, fileName, "Download start ho raha...")
        Thread {
            var tmp: File? = null
            var ok = false
            try {
                tmp = File(ctx.cacheDir, "gskw_${System.currentTimeMillis()}")
                var start = if (tmp.exists()) tmp.length() else 0L
                var got = start
                var total = -1L
                var lastUi = 0L
                var lastBytes = got
                var ema = 0.0
                var fails = 0
                fun report(force: Boolean = false) {
                    val now = System.currentTimeMillis()
                    if (!force && now - lastUi < 500) return
                    lastUi = now
                    val dt = (now - lastUi).coerceAtLeast(1)
                    val inst = (got - lastBytes) * 1000.0 / dt
                    lastBytes = got
                    ema = if (ema <= 0) inst else ema * 0.65 + inst * 0.35
                    val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 100) else 0
                    val sp = humanSpeed(ema.toLong())
                    DownloadNotifier.progress(ctx, nid, fileName, pct, sp, job.paused)
                    try { onProgress?.invoke(pct, sp) } catch (_: Exception) {}
                }
                while (!job.cancelled) {
                    while (job.paused && !job.cancelled) {
                        report(force = true)
                        Thread.sleep(300)
                    }
                    if (job.cancelled) break
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", UA)
                        .header("Accept", "*/*")
                        .header("Accept-Encoding", "identity")
                        .header("Connection", "keep-alive")
                        .apply { if (start > 0) header("Range", "bytes=$start-") }
                        .build()
                    val call = http.newCall(req)
                    job.call = call
                    try {
                        call.execute().use { res ->
                            if (res.code == 403 || res.code == 410) {
                                throw Exception("HTTP ${res.code} (link expire — dobara Play dabao)")
                            }
                            if (!res.isSuccessful && res.code != 206) {
                                throw Exception("HTTP ${res.code}")
                            }
                            if (res.code == 206) {
                                val cr = res.header("Content-Range") ?: ""
                                total = Regex("/(\\d+)").find(cr)?.groupValues?.get(1)?.toLongOrNull() ?: -1
                            } else {
                                if (start > 0) {
                                    try { tmp.delete() } catch (_: Exception) {}
                                    start = 0
                                    got = 0
                                }
                                total = res.body?.contentLength() ?: -1
                            }
                            val body = res.body ?: throw Exception("empty body")
                            FileOutputStream(tmp, start > 0).use { out ->
                                body.byteStream().use { inp ->
                                    val buf = ByteArray(256 * 1024)
                                    while (true) {
                                        if (job.cancelled) throw Exception("cancelled")
                                        if (job.paused) break
                                        val n = inp.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        got += n
                                        report()
                                    }
                                    out.flush()
                                }
                            }
                        }
                        fails = 0 // data beha — counter reset
                        if (total > 0 && tmp.length() < total) {
                            start = tmp.length()
                            got = start
                            continue // adhuri — resume
                        }
                        break // poori
                    } catch (e: Exception) {
                        if (job.cancelled || (e.message ?: "").contains("cancel", true)) {
                            throw Exception("cancelled")
                        }
                        if (job.paused) continue
                        fails++
                        if (fails > 6) throw e
                        start = if (tmp.exists()) tmp.length() else 0
                        got = start
                        Thread.sleep(1500)
                    } finally {
                        job.call = null
                    }
                }
                if (job.cancelled) throw Exception("cancelled")
                if (tmp.length() == 0L) throw Exception("khali file mili")
                val uri = saveMedia(ctx, tmp, fileName, mime, audio)
                report(force = true)
                DownloadNotifier.done(ctx, nid, fileName, fileName, uri, mime)
                ok = true
            } catch (e: Exception) {
                if ((e.message ?: "") == "cancelled") {
                    DownloadNotifier.cancel(ctx, nid)
                } else {
                    DownloadNotifier.failed(ctx, nid, fileName, (e.message ?: "fail").take(60))
                }
            } finally {
                try { tmp?.delete() } catch (_: Exception) {}
                jobs.remove(key)
                stopFgIfIdle(ctx)
                try { onDone?.invoke(ok) } catch (_: Exception) {}
            }
        }.start()
    }

    private fun startFg(ctx: Context) {
        try {
            val i = Intent(ctx, GskDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i)
            else ctx.startService(i)
        } catch (_: Exception) {}
    }

    private fun stopFgIfIdle(ctx: Context) {
        try {
            if (jobs.isEmpty()) ctx.stopService(Intent(ctx, GskDownloadService::class.java))
        } catch (_: Exception) {}
    }

    /** Video -> Download/gsk-downloader, Audio -> Music/gsk-downloader. */
    private fun saveMedia(ctx: Context, tmp: File, name: String, mime: String, audio: Boolean): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        if (audio) "Music/gsk-downloader" else "Download/gsk-downloader")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val coll = if (audio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val uri = ctx.contentResolver.insert(coll, values) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    tmp.inputStream().use { inp -> inp.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val base = if (audio) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(base, "gsk-downloader")
                dir.mkdirs()
                val dest = File(dir, name)
                tmp.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        } catch (_: Exception) { null }
    }

    private fun humanSpeed(bps: Long): String = when {
        bps <= 0 -> ""
        bps >= 1048576 -> "%.1f MB/s".format(bps / 1048576.0)
        bps >= 1024 -> "%d KB/s".format(bps / 1024)
        else -> "%d B/s".format(bps)
    }

    fun safeName(s: String): String {
        var r = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (r.length > 90) r = r.substring(0, 90)
        return r.ifEmpty { "video" }
    }
}
