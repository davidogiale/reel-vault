package com.reelvault.app

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.reelvault.app.databinding.ItemGridBinding

class GridAdapter(
    private val onOpen: (MediaEntry) -> Unit,
    private val onMenu: (MediaEntry) -> Unit,
) : ListAdapter<MediaEntry, GridAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val b = ItemGridBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val cell = parent.resources.displayMetrics.widthPixels / 3
        b.root.layoutParams = b.root.layoutParams.apply { height = cell * 16 / 9 }
        return Holder(b)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val e = getItem(position)
        val b = holder.b
        Glide.with(b.thumb).load(e.uri).centerCrop().into(b.thumb)
        val isVideo = e.kind == Kind.VIDEO
        b.duration.isVisible = isVideo
        b.duration.text = if (isVideo) fmtDuration(e.durationMs) else ""
        b.photoIcon.isVisible = !isVideo
        b.root.contentDescription = "${e.name}. Hold for options."
        b.root.setOnClickListener { onOpen(e) }
        b.root.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onMenu(e)
            true
        }
    }

    class Holder(val b: ItemGridBinding) : RecyclerView.ViewHolder(b.root)

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MediaEntry>() {
            override fun areItemsTheSame(a: MediaEntry, b: MediaEntry) = a.id == b.id
            override fun areContentsTheSame(a: MediaEntry, b: MediaEntry) = a == b
        }
    }
}
