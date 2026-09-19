package com.lvigs.gskdownloader

import android.content.Context
import android.media.MediaCodecList

object QualityHelper {

    private var cachedMaxHeight: Int = -1

    fun maxVideoHeight(): Int {
        if (cachedMaxHeight > 0) return cachedMaxHeight
        var best = 0
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                try {
                    if (info.isEncoder) continue
                    var avc = false
                    for (t in info.supportedTypes) {
                        if (t.equals("video/avc", true)) { avc = true; break }
                    }
                    if (!avc) continue
                    val caps = info.getCapabilitiesForType("video/avc")
                    val vr = caps?.videoCapabilities ?: continue
                    val up = vr.supportedHeights.upper
                    if (up > best) best = up
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val steps = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
        cachedMaxHeight = if (best <= 0) 1080 else steps.lastOrNull { it <= best } ?: 144
        return cachedMaxHeight
    }

    fun qualityItems(available: List<StreamFetcher.StreamItem>, current: Int): List<QualityItem> {
        val maxH = maxVideoHeight()
        val items = mutableListOf<QualityItem>()
        val sorted = available.sortedByDescending { it.height }
        for ((idx, s) in sorted.withIndex()) {
            if (s.height > maxH) continue
            val label = if (s.audioOnly) "${s.resolution} (Audio)" else s.resolution
            items.add(QualityItem(index = idx, label = label, height = s.height))
        }
        return items
    }

    data class QualityItem(val index: Int, val label: String, val height: Int)
}
