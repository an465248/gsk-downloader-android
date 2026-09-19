package com.lvigs.gskdownloader

import android.media.MediaCodecList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * YouTube-style Quality bottom sheet (NEW, additive).
 * Purane quality pills / switch logic ko nahi badalta — ye sirf naya UI hai
 * jo wahi existing switchQuality(index) ko call karta hai (bina reload ke
 * position-preserve ke saath, single engine par).
 *
 * NOTE (honest): backend alag-alag progressive URLs deta hai (adaptive
 * DASH/HLS manifest nahi), isliye ExoPlayer ko ek time par 1 track dikhta hai.
 * DefaultTrackSelector-seamless-switch manifest-streams par hota hai; yahan
 * same-engine par position-preserve switch hota hai (video reload nahi lagta).
 * DeviceCaps filter karta hai taaki unsupported height par MediaCodec error na aaye.
 */
class QualitySheet : BottomSheetDialogFragment() {

    data class Item(val index: Int, val label: String)

    private var items: List<Item> = emptyList()
    private var selectedIndex: Int = -1
    private var onPick: ((Int) -> Unit)? = null

    // AppCompat theme me BottomSheet crash na ho — Material overlay use karo.
    override fun getTheme(): Int =
        com.google.android.material.R.style.ThemeOverlay_MaterialComponents_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val v = inflater.inflate(R.layout.quality_sheet, container, false)
        try {
            val list = v.findViewById<LinearLayout>(R.id.sheetList)
            val sub = v.findViewById<TextView>(R.id.sheetSub)
            try {
                sub.text = "Device max: ${DeviceCaps.maxVideoHeight()}p • tick = current"
            } catch (_: Exception) {}
            list.removeAllViews()
            val ctx = requireContext()
            for (it in items) {
                val itemIdx = it.index
                val itemLabel = it.label
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.setPadding(8, 22, 8, 22)
                row.isClickable = true
                row.isFocusable = true
                // Selected row highlight (checkmark + accent), YouTube jaisa.
                val sel = itemIdx == selectedIndex
                val label = TextView(ctx)
                label.text = itemLabel
                label.textSize = 15f
                label.setTextColor(if (sel) 0xFF22D3EE.toInt() else 0xFFE9EEFB.toInt())
                label.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                val tick = TextView(ctx)
                tick.text = if (sel) "✓" else ""
                tick.textSize = 17f
                tick.setTextColor(0xFF22D3EE.toInt())
                tick.setPadding(16, 0, 8, 0)
                row.addView(label)
                row.addView(tick)
                row.setOnClickListener {
                    try { onPick?.invoke(itemIdx) } catch (_: Exception) {}
                    try { dismiss() } catch (_: Exception) {}
                }
                list.addView(row)
            }
        } catch (_: Exception) {}
        return v
    }

    companion object {
        fun new(items: List<Item>, selectedIndex: Int, onPick: (Int) -> Unit): QualitySheet {
            return QualitySheet().apply {
                this.items = items
                this.selectedIndex = selectedIndex
                this.onPick = onPick
            }
        }
    }
}

/**
 * Device video-decode capability (NEW, additive).
 * AVC (H.264) decoder ki max supported height nikalta hai taaki usse upar ke
 * options list me na aayein = 'MediaCodecVideoRenderer error' se bachao.
 */
object DeviceCaps {
    @Volatile private var cached: Int = -1

    fun maxVideoHeight(): Int {
        try {
            if (cached > 0) return cached
            var best = 0
            try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in list.codecInfos) {
                    try {
                        if (info.isEncoder) continue
                        var avc = false
                        try {
                            for (t in info.supportedTypes) {
                                if (t.equals("video/avc", true)) { avc = true; break }
                            }
                        } catch (_: Exception) {}
                        if (!avc) continue
                        val caps = try {
                            info.getCapabilitiesForType("video/avc")
                        } catch (_: Exception) { continue }
                        val vr = try { caps.videoCapabilities } catch (_: Exception) { null }
                            ?: continue
                        val up = try { vr.supportedHeights.upper } catch (_: Exception) { 0 }
                        if (up > best) best = up
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            // Snap: 144/240/360/480/720/1080/1440/2160 me se nearest-neeche.
            val steps = intArrayOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
            var snap = 1080
            try {
                snap = if (best <= 0) 1080
                else steps.lastOrNull { it <= best } ?: 144
            } catch (_: Exception) {}
            cached = snap
            return snap
        } catch (_: Exception) {
            return 1080
        }
    }
}
