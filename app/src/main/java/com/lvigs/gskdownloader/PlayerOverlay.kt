package com.lvigs.gskdownloader

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * YouTube-style in-video controls overlay (NEW, additive).
 * Purane UI/controls/gestures ko nahi badalta — playerFrame ke upar nayi layer hai:
 * - Tap = show/hide, 3s me auto-hide.
 * - Center: Play/Pause + 10s Rewind + 10s Forward.
 * - Bottom: sleek seekbar + current/total time + fullscreen button.
 * - Top-right: Settings gear (quality bottom sheet).
 * - Left/Right double-tap = -/+10s (seekStepMs ke hisab se).
 *
 * Sab actions caller ke lambdas se SAME single engine par jate hain.
 */
class PlayerOverlay(
    private val host: android.app.Activity,
    private val parent: FrameLayout,
    private val engineOf: () -> ExoPlayer?,
    private val controls: Controls,
) {
    data class Controls(
        val onTogglePlay: () -> Unit,
        val onSeekBy: (Long) -> Unit,
        val onSeekTo: (Long) -> Unit,
        val onFullscreen: () -> Unit,
        val onQuality: () -> Unit,
        val seekStepMs: () -> Long,
    )

    private val root: View
    private val btnPlay: TextView
    private val btnRew: TextView
    private val btnFwd: TextView
    private val btnGear: TextView
    private val btnFs: TextView
    private val flash: TextView
    private val tvCur: TextView
    private val tvDur: TextView
    private val seek: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var dragging = false
    private var hideRun: Runnable? = null
    private val tick = object : Runnable {
        override fun run() {
            try { refresh() } catch (_: Exception) {}
            try { handler.postDelayed(this, 500) } catch (_: Exception) {}
        }
    }

    init {
        val v = LayoutInflater.from(host).inflate(R.layout.player_overlay_controls, parent, false)
        root = v
        btnPlay = v.findViewById(R.id.ovPlay)
        btnRew = v.findViewById(R.id.ovRew)
        btnFwd = v.findViewById(R.id.ovFwd)
        btnGear = v.findViewById(R.id.ovGear)
        btnFs = v.findViewById(R.id.ovFs)
        flash = v.findViewById(R.id.ovFlash)
        tvCur = v.findViewById(R.id.ovCur)
        tvDur = v.findViewById(R.id.ovDur)
        seek = v.findViewById(R.id.ovSeek)

        btnPlay.setOnClickListener { try { controls.onTogglePlay() } catch (_: Exception) {}; poke() }
        btnRew.setOnClickListener { doSeekBy(-controls.seekStepMs()); poke() }
        btnFwd.setOnClickListener { doSeekBy(controls.seekStepMs()); poke() }
        btnGear.setOnClickListener { try { controls.onQuality() } catch (_: Exception) {} }
        btnFs.setOnClickListener { try { controls.onFullscreen() } catch (_: Exception) {} }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                try { tvCur.text = fmt(p.toLong()) } catch (_: Exception) {}
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                dragging = true
                cancelHide()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                dragging = false
                try {
                    val e = engineOf() ?: return
                    val d = try { e.duration } catch (_: Exception) { C.TIME_UNSET }
                    if (d != C.TIME_UNSET && d > 0) {
                        val pos = (sb?.progress?.toLong() ?: 0L).coerceIn(0L, d)
                        try { controls.onSeekTo(pos) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                poke()
            }
        })

        val gd = GestureDetector(host, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggle()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                try {
                    val w = root.width.takeIf { it > 0 } ?: return true
                    val step = controls.seekStepMs()
                    if (e.x < w * 0.4f) doSeekBy(-step) else if (e.x > w * 0.6f) doSeekBy(step)
                    poke()
                } catch (_: Exception) {}
                return true
            }
        })
        root.setOnTouchListener { _, ev ->
            try { gd.onTouchEvent(ev) } catch (_: Exception) {}
            true
        }

        try { parent.addView(root) } catch (_: Exception) {}
        try { handler.post(tick) } catch (_: Exception) {}
    }

    private fun doSeekBy(delta: Long) {
        try { controls.onSeekBy(delta) } catch (_: Exception) {}
        try {
            val s = kotlin.math.abs(delta / 1000)
            flash.text = if (delta < 0) "↶ $s seconds" else "↷ $s seconds"
            flash.visibility = View.VISIBLE
            flash.removeCallbacks(flashHide)
            flash.postDelayed(flashHide, 900)
        } catch (_: Exception) {}
    }

    private val flashHide = Runnable { try { flash.visibility = View.GONE } catch (_: Exception) {} }

    fun isShowing(): Boolean = try {
        root.visibility == View.VISIBLE
    } catch (_: Exception) { false }

    fun show() {
        try {
            root.visibility = View.VISIBLE
            refresh()
            poke()
        } catch (_: Exception) {}
    }

    fun hide() {
        try {
            root.visibility = View.GONE
            cancelHide()
        } catch (_: Exception) {}
    }

    fun toggle() {
        try {
            if (isShowing()) hide() else show()
        } catch (_: Exception) {}
    }

    private fun poke() {
        try {
            cancelHide()
            val r = Runnable { try { hide() } catch (_: Exception) {} }
            hideRun = r
            handler.postDelayed(r, 3000)
        } catch (_: Exception) {}
    }

    private fun cancelHide() {
        try { hideRun?.let { handler.removeCallbacks(it) } } catch (_: Exception) {}
        hideRun = null
    }

    /** Engine state se overlay refresh (icon + time + seekbar). */
    fun refresh() {
        try {
            if (!isShowing()) return
            val e = engineOf()
            val playing = try { e?.isPlaying == true } catch (_: Exception) { false }
            try { btnPlay.text = if (playing) "⏸" else "▶" } catch (_: Exception) {}
            val d = try { e?.duration ?: C.TIME_UNSET } catch (_: Exception) { C.TIME_UNSET }
            val p = try { e?.currentPosition ?: 0L } catch (_: Exception) { 0L }
            try { tvDur.text = if (d != C.TIME_UNSET && d > 0) fmt(d) else "0:00" } catch (_: Exception) {}
            try { if (!dragging) tvCur.text = fmt(p) } catch (_: Exception) {}
            try {
                if (!dragging) {
                    if (d != C.TIME_UNSET && d > 0) {
                        val maxI = d.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        if (seek.max != maxI) seek.max = maxI
                        seek.progress = p.coerceIn(0L, d).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    } else {
                        seek.max = 100
                        seek.progress = 0
                    }
                }
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    fun release() {
        try { handler.removeCallbacks(tick) } catch (_: Exception) {}
        try { cancelHide() } catch (_: Exception) {}
        try { parent.removeView(root) } catch (_: Exception) {}
    }

    companion object {
        fun fmt(ms: Long): String {
            return try {
                if (ms < 0) return "0:00"
                val s = ms / 1000
                val h = s / 3600
                val m = (s % 3600) / 60
                val r = s % 60
                if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
            } catch (_: Exception) { "0:00" }
        }
    }
}
