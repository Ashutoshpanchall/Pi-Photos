package com.example.piphotos.media

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.piphotos.R
import com.example.piphotos.databinding.ItemMediaTileBinding
import com.example.piphotos.databinding.ItemTimelineHeaderBinding
import java.util.Locale

class TimelineAdapter(
    private val onItemClick: (LocalMediaItem) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit
) : ListAdapter<TimelineRow, RecyclerView.ViewHolder>(TimelineDiffCallback()) {

    init {
        setHasStableIds(true)
    }

    var isSelectionMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                selectedIds.clear()
                onSelectionChanged(emptySet())
            }
            notifyDataSetChanged()
        }

    val selectedIds = mutableSetOf<Long>()

    override fun getItemId(position: Int): Long {
        return getItem(position).stableId
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is TimelineRow.HeaderRow -> VIEW_TYPE_HEADER
            is TimelineRow.MediaRow -> VIEW_TYPE_MEDIA
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val binding = ItemTimelineHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemMediaTileBinding.inflate(inflater, parent, false)
                MediaViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is TimelineRow.HeaderRow -> (holder as HeaderViewHolder).bind(row)
            is TimelineRow.MediaRow -> (holder as MediaViewHolder).bind(row.item)
        }
    }

    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id)
            if (selectedIds.isEmpty()) {
                isSelectionMode = false
                return
            }
        } else {
            selectedIds.add(id)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.toSet())
    }

    fun selectAll() {
        val allIds = currentList.mapNotNull { (it as? TimelineRow.MediaRow)?.item?.id }
        selectedIds.clear()
        selectedIds.addAll(allIds)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.toSet())
    }

    fun clearSelection() {
        selectedIds.clear()
        isSelectionMode = false
        notifyDataSetChanged()
        onSelectionChanged(emptySet())
    }

    inner class HeaderViewHolder(private val binding: ItemTimelineHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: TimelineRow.HeaderRow) {
            binding.tvHeaderTitle.text = header.title
            if (!header.subtitle.isNullOrEmpty()) {
                binding.tvHeaderSubtitle.text = header.subtitle
                binding.tvHeaderSubtitle.visibility = View.VISIBLE
            } else {
                binding.tvHeaderSubtitle.visibility = View.GONE
            }
        }
    }

    inner class MediaViewHolder(private val binding: ItemMediaTileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LocalMediaItem) {
            // Sized thumbnail with Coil ~320px (never load full res)
            binding.ivMediaPhoto.load(item.contentUri) {
                crossfade(true)
                size(320)
                placeholder(R.color.surface_card)
                error(R.color.surface_card)
            }

            // Video badge
            if (item.isVideo) {
                binding.layoutVideoBadge.visibility = View.VISIBLE
                val totalSec = item.durationMs / 1000
                val min = totalSec / 60
                val sec = totalSec % 60
                binding.tvVideoDuration.text = String.format(Locale.US, "%d:%02d", min, sec)
            } else {
                binding.layoutVideoBadge.visibility = View.GONE
            }

            // Selection state
            val isSelected = selectedIds.contains(item.id)
            if (isSelectionMode) {
                binding.layoutCheckOverlay.visibility = View.VISIBLE
                if (isSelected) {
                    binding.layoutCheckOverlay.setBackgroundResource(R.drawable.bg_selection_circle_selected)
                } else {
                    binding.layoutCheckOverlay.setBackgroundResource(R.drawable.bg_selection_circle_unselected)
                }
            } else {
                binding.layoutCheckOverlay.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    toggleSelection(item.id)
                } else {
                    onItemClick(item)
                }
            }

            binding.root.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                if (!isSelectionMode) {
                    isSelectionMode = true
                }
                toggleSelection(item.id)
                true
            }
        }
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_MEDIA = 1
    }
}

class TimelineDiffCallback : DiffUtil.ItemCallback<TimelineRow>() {
    override fun areItemsTheSame(oldItem: TimelineRow, newItem: TimelineRow): Boolean {
        return oldItem.stableId == newItem.stableId
    }

    override fun areContentsTheSame(oldItem: TimelineRow, newItem: TimelineRow): Boolean {
        return oldItem == newItem
    }
}

