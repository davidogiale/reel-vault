package com.reelvault.app

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.reelvault.app.databinding.ItemFeedPageBinding

class FeedAdapter(private val cb: Callbacks) : ListAdapter<Clip, FeedAdapter.Holder>(DIFF) {

    interface Callbacks {
        fun onTap(clip: Clip)
        fun onBoost(on: Boolean)
        fun onMenu(clip: Clip)
        fun onSave(clip: Clip)
        fun onToggleSound()
        fun onCycleSpeed()
        fun onSeek(fraction: Float)
        fun onOpenOriginal(clip: Clip)
        fun speedLabel(): String
        fun isMuted(): Boolean
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemFeedPageBinding.inflate(LayoutInflater.from(parent.context), parent, false), cb)

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) holder.update(getItem(position)) else holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: Holder) {
        holder.b.playerView.player = null
        holder.b.webHolder.removeAllViews()
    }

    @SuppressLint("ClickableViewAccessibility")
    class Holder(val b: ItemFeedPageBinding, private val cb: Callbacks) : RecyclerView.ViewHolder(b.root) {
        var clip: Clip? = null
            private set
        var scrubbing = false
            private set
        private var boosting = false
        private var lastDuration = 0L

        private val detector = GestureDetector(b.root.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                clip?.let(cb::onTap)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val c = clip ?: return
                val w = b.touchLayer.width
                val rel = if (w > 0) e.x / w else 0.5f
                b.touchLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                // Hold the left or right side for 2x. Hold the middle for options.
                if (c.kind == Kind.VIDEO && (rel < 0.3f || rel > 0.7f)) {
                    boosting = true
                    b.touchLayer.parent?.requestDisallowInterceptTouchEvent(true)
                    cb.onBoost(true)
                } else {
                    cb.onMenu(c)
                }
            }
        })

        init {
            b.touchLayer.setOnTouchListener { v, ev ->
                detector.onTouchEvent(ev)
                val end = ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
                if (end && boosting) {
                    boosting = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    cb.onBoost(false)
                }
                true
            }
            b.seek.max = 1000
            b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && lastDuration > 0) {
                        b.timecode.text = "${fmtDuration(lastDuration * progress / 1000)} / ${fmtDuration(lastDuration)}"
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    scrubbing = true
                    s.parent?.requestDisallowInterceptTouchEvent(true)
                    b.timecode.isVisible = lastDuration > 0
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    scrubbing = false
                    s.parent?.requestDisallowInterceptTouchEvent(false)
                    b.timecode.isVisible = false
                    cb.onSeek(s.progress / 1000f)
                }
            })
            b.btnSave.setOnClickListener { clip?.let(cb::onSave) }
            b.btnSound.setOnClickListener { cb.onToggleSound() }
            b.btnSpeed.setOnClickListener { cb.onCycleSpeed() }
            b.btnOpen.setOnClickListener { clip?.let(cb::onOpenOriginal) }
            b.btnMore.setOnClickListener { clip?.let(cb::onMenu) }
        }

        fun bind(c: Clip) {
            clip = c
            lastDuration = 0
            b.playerView.player = null
            b.webHolder.removeAllViews()
            val isVideo = c.kind == Kind.VIDEO
            b.playerView.isVisible = isVideo
            b.image.isVisible = c.kind == Kind.IMAGE
            if (c.kind == Kind.IMAGE) Glide.with(b.image).load(c.uri).fitCenter().into(b.image)
            else Glide.with(b.image).clear(b.image)

            val coverSource: Any? = c.cover ?: if (isVideo && c.entry != null) c.uri else null
            b.cover.isVisible = coverSource != null
            if (coverSource != null) Glide.with(b.cover).load(coverSource).fitCenter().into(b.cover)
            else Glide.with(b.cover).clear(b.cover)

            b.touchLayer.isVisible = c.kind != Kind.EMBED
            b.seek.isVisible = isVideo
            b.seek.progress = 0
            b.playIcon.isVisible = false
            b.spinner.isVisible = false
            b.error.isVisible = false
            b.timecode.isVisible = false
            update(c)
        }

        /** Refreshes text and the right-hand buttons without touching playback. */
        fun update(c: Clip) {
            clip = c
            b.handle.text = c.handle
            b.caption.text = c.caption
            b.caption.isVisible = c.caption.isNotBlank()
            b.tag.text = c.tag
            renderRail()
        }

        fun renderRail() {
            val c = clip ?: return
            val ctx = b.root.context
            b.btnSave.isVisible = c.savable
            b.btnSave.alpha = if (c.saving) 0.6f else 1f
            b.saveLabel.text = if (c.saving) "Saving…" else "Save"
            b.saveIcon.setImageResource(if (c.saving) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            b.btnSound.isVisible = c.kind == Kind.VIDEO
            b.soundIcon.setImageResource(if (cb.isMuted()) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            b.btnSpeed.isVisible = c.kind == Kind.VIDEO
            val label = cb.speedLabel()
            val fast = label != "1×"
            b.speedText.text = label
            b.speedText.setBackgroundResource(if (fast) R.drawable.bg_circle_green else R.drawable.bg_circle_glass)
            b.speedText.setTextColor(ContextCompat.getColor(ctx, if (fast) R.color.green_ink else R.color.fg))
            b.btnOpen.isVisible = c.link != null && c.entry == null
            b.btnMore.isVisible = c.entry != null
        }

        fun setPaused(paused: Boolean) {
            b.playIcon.isVisible = paused
        }

        fun setBuffering(on: Boolean) {
            b.spinner.isVisible = on
        }

        fun showError(on: Boolean) {
            b.error.isVisible = on
            if (on) b.spinner.isVisible = false
        }

        fun updateProgress(position: Long, duration: Long) {
            if (scrubbing || duration <= 0) return
            lastDuration = duration
            b.seek.progress = (position * 1000 / duration).toInt().coerceIn(0, 1000)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Clip>() {
            override fun areItemsTheSame(a: Clip, b: Clip) = a.key == b.key
            override fun areContentsTheSame(a: Clip, b: Clip) = a == b
            override fun getChangePayload(a: Clip, b: Clip): Any? =
                if (a.uri == b.uri && a.kind == b.kind && a.cover == b.cover && a.embedUrl == b.embedUrl) "text" else null
        }
    }
}
