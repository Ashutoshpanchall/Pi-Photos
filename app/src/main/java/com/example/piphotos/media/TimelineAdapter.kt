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
            if (field == value) return
            field = value
            if (!value) {
                val previouslySelected = selectedIds.toSet()
                selectedIds.clear()
                onSelectionChanged(emptySet())
                for (id in previouslySelected) {
                    val pos = currentList.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == id }
                    if (pos >= 0) notifyItemChanged(pos, PAYLOAD_SELECTION)
                }
            } else {
                notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
            }
        }

    val selectedIds = mutableSetOf<Long>()
    var favoriteIds: Set<Long> = emptySet()

    fun updateFavorites(newFavIds: Set<Long>) {
        if (favoriteIds == newFavIds) return
        val changedIds = (favoriteIds - newFavIds) + (newFavIds - favoriteIds)
        favoriteIds = newFavIds
        for (id in changedIds) {
            val pos = currentList.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == id }
            if (pos >= 0) {
                notifyItemChanged(pos, PAYLOAD_FAVORITE)
            }
        }
    }

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

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) {
        if (holder is MediaViewHolder) {
            val row = getItem(position) as? TimelineRow.MediaRow ?: return
            if (payloads.contains(PAYLOAD_SELECTION)) {
                holder.updateSelection(row.item.id)
            }
            if (payloads.contains(PAYLOAD_FAVORITE)) {
                holder.updateFavorite(row.item.id)
            }
            if (!payloads.contains(PAYLOAD_SELECTION) && !payloads.contains(PAYLOAD_FAVORITE)) {
                super.onBindViewHolder(holder, position, payloads)
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
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
        val pos = currentList.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == id }
        if (pos >= 0) {
            notifyItemChanged(pos, PAYLOAD_SELECTION)
        }
        onSelectionChanged(selectedIds.toSet())
    }

    fun selectAll() {
        val allIds = currentList.mapNotNull { (it as? TimelineRow.MediaRow)?.item?.id }
        val toUpdate = (allIds.toSet() - selectedIds)
        selectedIds.clear()
        selectedIds.addAll(allIds)
        for (id in toUpdate) {
            val pos = currentList.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == id }
            if (pos >= 0) notifyItemChanged(pos, PAYLOAD_SELECTION)
        }
        onSelectionChanged(selectedIds.toSet())
    }

    fun clearSelection() {
        val previouslySelected = selectedIds.toSet()
        selectedIds.clear()
        isSelectionMode = false
        for (id in previouslySelected) {
            val pos = currentList.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == id }
            if (pos >= 0) notifyItemChanged(pos, PAYLOAD_SELECTION)
        }
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

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val row = getItem(pos) as? TimelineRow.MediaRow ?: return@setOnClickListener
                    if (isSelectionMode) {
                        toggleSelection(row.item.id)
                    } else {
                        onItemClick(row.item)
                    }
                }
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val row = getItem(pos) as? TimelineRow.MediaRow ?: return@setOnLongClickListener false
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    if (!isSelectionMode) {
                        isSelectionMode = true
                    }
                    toggleSelection(row.item.id)
                    true
                } else false
            }
        }

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
            updateSelection(item.id)
            updateFavorite(item.id)
        }

        fun updateFavorite(itemId: Long) {
            binding.ivFavoriteBadge.visibility = if (favoriteIds.contains(itemId)) View.VISIBLE else View.GONE
        }

        fun updateSelection(itemId: Long) {
            val isSelected = selectedIds.contains(itemId)
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
        }
    }

    companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_MEDIA = 1
        const val PAYLOAD_SELECTION = "PAYLOAD_SELECTION"
        const val PAYLOAD_FAVORITE = "PAYLOAD_FAVORITE"
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

