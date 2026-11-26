package com.alexvid.player.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.alexvid.player.R
import com.alexvid.player.databinding.ItemHistoryBinding
import com.alexvid.player.model.FormatUtils
import com.alexvid.player.model.HistoryItem

class HistoryAdapter(
    private val onClick: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<HistoryItem>()

    fun submitList(list: List<HistoryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: HistoryItem) {
            binding.ivHistoryType.setImageResource(
                if (item.isOnline) R.drawable.ic_stream else R.drawable.ic_video_library
            )
            binding.tvHistoryTitle.text = item.title
            val typeStr = if (item.isOnline) "Онлайн" else "Локальное"
            val timeStr = if (item.durationMs > 0L) {
                " • ${FormatUtils.formatDuration(item.positionMs)} / ${FormatUtils.formatDuration(item.durationMs)}"
            } else if (item.positionMs > 0L) {
                " • ${FormatUtils.formatDuration(item.positionMs)}"
            } else {
                ""
            }
            binding.tvHistorySub.text = "$typeStr$timeStr"
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
