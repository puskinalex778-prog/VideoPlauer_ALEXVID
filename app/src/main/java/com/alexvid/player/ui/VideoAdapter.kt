package com.alexvid.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.alexvid.player.R
import com.alexvid.player.data.PlayerPreferences
import com.alexvid.player.databinding.ItemVideoBinding
import com.alexvid.player.model.FormatUtils
import com.alexvid.player.model.LocalVideo
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions

class VideoAdapter(
    private val prefs: PlayerPreferences,
    private val onClick: (LocalVideo, Int) -> Unit,
    private val onLongClick: ((LocalVideo) -> Unit)? = null
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private val items = mutableListOf<LocalVideo>()

    fun submitList(list: List<LocalVideo>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun currentItems(): List<LocalVideo> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(video: LocalVideo, position: Int) {
            binding.tvTitle.text = video.title
            binding.tvFormatBadge.text = video.extension
            binding.tvDuration.text = FormatUtils.formatDuration(video.durationMs)

            val resPart = if (video.width > 0 && video.height > 0) " • ${video.width}×${video.height}" else ""
            val sizePart = FormatUtils.formatSize(video.sizeBytes)
            binding.tvMeta.text = "${video.folderName} • $sizePart$resPart"

            val savedPos = prefs.getPosition(video.uri.toString())
            if (savedPos > 3000L && video.durationMs > 0L) {
                val pct = ((savedPos.toDouble() / video.durationMs.toDouble()) * 100.0).toInt().coerceIn(1, 100)
                binding.pbResume.progress = pct
                binding.pbResume.visibility = View.VISIBLE
            } else {
                binding.pbResume.visibility = View.GONE
            }

            Glide.with(binding.ivThumbnail)
                .load(video.uri)
                .centerCrop()
                .placeholder(R.color.bg_surface)
                .error(R.color.bg_surface)
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .into(binding.ivThumbnail)

            binding.root.setOnClickListener {
                onClick(video, position)
            }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(video)
                onLongClick != null
            }
        }
    }
}
