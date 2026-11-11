package com.example.piphotos

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.piphotos.databinding.ItemMediaTileBinding

class MediaGridAdapter(
    private var items: List<MediaItem>,
    private val onPhotoClick: (MediaItem) -> Unit,
    private val onVideoClick: (MediaItem) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<MediaGridAdapter.MediaViewHolder>() {

    var isSelectionMode = false
        set(value) {
            field = value
            if (!value) selectedIds.clear()
            notifyDataSetChanged()
        }

    val selectedIds = mutableSetOf<String>()

    fun updateData(newItems: List<MediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(items.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val binding = ItemMediaTileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MediaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class MediaViewHolder(private val binding: ItemMediaTileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            binding.ivMediaPhoto.setImageResource(item.drawableRes)

            if (item.isVideo && !item.duration.isNullOrEmpty()) {
                binding.layoutVideoBadge.visibility = View.VISIBLE
                binding.tvVideoDuration.text = item.duration
            } else {
                binding.layoutVideoBadge.visibility = View.GONE
            }

            if (isSelectionMode) {
                binding.layoutCheckOverlay.visibility = View.VISIBLE
                val isSelected = selectedIds.contains(item.id)
                if (isSelected) {
                    binding.layoutCheckOverlay.setBackgroundResource(R.drawable.bg_selection_circle_selected)
                    binding.ivCheckIcon.visibility = View.VISIBLE
                } else {
                    binding.layoutCheckOverlay.setBackgroundResource(R.drawable.bg_selection_circle_unselected)
                    binding.ivCheckIcon.visibility = View.GONE
                }
            } else {
                binding.layoutCheckOverlay.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        if (selectedIds.contains(item.id)) {
                            selectedIds.remove(item.id)
                        } else {
                            selectedIds.add(item.id)
                        }
                        notifyItemChanged(pos)
                        onSelectionChanged(selectedIds.size)
                    }
                } else {
                    if (item.isVideo) {
                        onVideoClick(item)
                    } else {
                        onPhotoClick(item)
                    }
                }
            }

            binding.root.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!isSelectionMode) {
                    isSelectionMode = true
                    selectedIds.add(item.id)
                    notifyDataSetChanged()
                    onSelectionChanged(selectedIds.size)
                }
                true
            }
        }
    }
}
