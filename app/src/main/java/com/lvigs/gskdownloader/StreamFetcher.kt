package com.lvigs.gskdownloader

import android.content.Context
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.InfoItem
import java.io.IOException

class GSKDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(req: Request): Response {
        val call = client.newCall(okhttp3.Request.Builder().url(req.url()).build())
        val resp = call.execute()
        val body = resp.body?.string() ?: ""
        val headersMap: Map<String, List<String>> = resp.headers.names().associateWith { n ->
            resp.headers[n]?.split(",") ?: emptyList()
        }
        return Response(resp.code, resp.message, headersMap, body, resp.request.url.toString())
    }
}

object StreamFetcher {

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        NewPipe.init(GSKDownloader(client))
        initialized = true
    }

    fun extract(url: String): ExtractedData {
        val service = NewPipe.getService(ServiceList.YouTube.getServiceId())
        val info: StreamInfo = StreamInfo.getInfo(url)
        return ExtractedData(
            title = info.name,
            channel = info.uploaderName,
            channelUrl = info.uploaderUrl,
            avatarUrl = info.uploaderAvatars.firstOrNull()?.url,
            thumbnailUrl = info.thumbnails.firstOrNull()?.url,
            description = info.description.toString(),
            duration = info.duration,
            viewCount = info.viewCount,
            uploadDate = info.uploadDate.toString(),
            streams = buildVideoStreams(info.videoStreams),
            audioStreams = buildAudioStreams(info.audioStreams),
            related = buildRelatedItems(info.relatedItems),
            comments = emptyList()
        )
    }

    private fun buildVideoStreams(list: List<org.schabi.newpipe.extractor.stream.VideoStream>): List<StreamItem> {
        val out = mutableListOf<StreamItem>()
        for (s in list) {
            val res = s.resolution ?: ""
            val h = s.height
            val fmt = try { s.format.toString() } catch (_: Exception) { "" }
            out.add(StreamItem(s.url ?: "", res, h, fmt, s.isVideoOnly, ""))
        }
        return out.sortedByDescending { it.height }
    }

    private fun buildAudioStreams(list: List<org.schabi.newpipe.extractor.stream.AudioStream>): List<StreamItem> {
        val out = mutableListOf<StreamItem>()
        for (s in list) {
            val br = s.averageBitrate
            val fmt = try { s.format.toString() } catch (_: Exception) { "" }
            val label = if (br > 0) "Audio ${br}kbps" else "Audio"
            out.add(StreamItem(s.url ?: "", label, 0, fmt, true, ""))
        }
        return out
    }

    private fun buildRelatedItems(list: List<InfoItem>): List<RelatedItem> {
        val out = mutableListOf<RelatedItem>()
        for (r in list) {
            val ri = r as? org.schabi.newpipe.extractor.stream.StreamInfoItem ?: continue
            out.add(RelatedItem(
                url = ri.url ?: "",
                title = ri.name,
                uploader = ri.uploaderName,
                thumbnail = ri.thumbnails.firstOrNull()?.url,
                viewCount = ri.viewCount,
                duration = ri.duration
            ))
        }
        return out
    }

    data class ExtractedData(
        val title: String,
        val channel: String,
        val channelUrl: String,
        val avatarUrl: String?,
        val thumbnailUrl: String?,
        val description: String,
        val duration: Long,
        val viewCount: Long,
        val uploadDate: String,
        val streams: List<StreamItem>,
        val audioStreams: List<StreamItem>,
        val related: List<RelatedItem>,
        val comments: List<CommentItem>,
    )

    data class StreamItem(
        val url: String,
        val resolution: String,
        val height: Int,
        val mimeType: String,
        val audioOnly: Boolean,
        val audioQuality: String,
    )

    data class RelatedItem(
        val url: String,
        val title: String,
        val uploader: String,
        val thumbnail: String?,
        val viewCount: Long,
        val duration: Long,
    )

    data class CommentItem(
        val author: String,
        val text: String,
        val time: String,
        val likes: Long,
    )
}
