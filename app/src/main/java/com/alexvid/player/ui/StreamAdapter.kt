package com.alexvid.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.alexvid.player.databinding.ItemStreamBinding
import com.alexvid.player.model.FormatUtils
import com.alexvid.player.model.StreamItem

class StreamAdapter(
    private val onPlay: (StreamItem) -> Unit,
    private val onDelete: (StreamItem) -> Unit
) : RecyclerView.Adapter<StreamAdapter.StreamViewHolder>() {

    private val items = mutableListOf<StreamItem>()

    fun submitList(list: List<StreamItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StreamViewHolder {
        val binding = ItemStreamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StreamViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StreamViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class StreamViewHolder(
        private val binding: ItemStreamBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: StreamItem) {
            binding.tvProtocol.text = FormatUtils.detectProtocolBadge(item.url)
            binding.tvStreamTitle.text = item.title
            binding.tvStreamUrl.text = item.url
            binding.btnDeleteStream.visibility = if (item.isBuiltIn) View.GONE else View.VISIBLE

            binding.root.setOnClickListener { onPlay(item) }
            binding.btnDeleteStream.setOnClickListener { onDelete(item) }
        }
    }
}
