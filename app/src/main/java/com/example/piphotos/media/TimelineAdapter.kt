package com.example.piphotos.media

import android.os.CancellationSignal
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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

    private val selectionState = SelectionState()

    var isSelectionMode: Boolean
        get() = selectionState.isSelectionMode
        set(value) {
            if (selectionState.isSelectionMode == value) return
            selectionState.setSelectionMode(value)
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
            onSelectionChanged(selectionState.selectedIds)
        }

    val selectedIds: Set<Long> get() = selectionState.selectedIds
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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is MediaViewHolder) {
            holder.recycle()
        }
    }

    fun toggleSelection(id: Long) {
        when (val result = selectionState.toggle(id)) {
            is SelectionState.Result.SingleItemToggled -> {
                val pos = currentList.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == id }
                if (pos >= 0) notifyItemChanged(pos, PAYLOAD_SELECTION)
                onSelectionChanged(selectionState.selectedIds)
            }
            is SelectionState.Result.SelectionExited -> {
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
                onSelectionChanged(emptySet())
            }
            is SelectionState.Result.AllSelected -> {
                if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
                onSelectionChanged(selectionState.selectedIds)
            }
        }
    }

    fun selectAll() {
        val allIds = currentList.mapNotNull { (it as? TimelineRow.MediaRow)?.item?.id }
        if (allIds.isEmpty()) return
        selectionState.selectAll(allIds)
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        onSelectionChanged(selectionState.selectedIds)
    }

    fun clearSelection() {
        if (isSelectionMode) {
            isSelectionMode = false
        } else {
            selectionState.clear()
            onSelectionChanged(emptySet())
        }
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

        private var thumbnailSignal: CancellationSignal? = null

        fun bind(item: LocalMediaItem) {
            thumbnailSignal?.cancel()
            thumbnailSignal = null

            val cached = ThumbnailLoader.getFromCache(item.id)
            if (cached != null && !cached.isRecycled) {
                binding.ivMediaPhoto.setImageBitmap(cached)
            } else {
                binding.ivMediaPhoto.setImageResource(R.color.surface_card)
                thumbnailSignal = ThumbnailLoader.loadThumbnail(
                    context = binding.root.context,
                    item = item,
                    targetSizePx = 280
                ) { bitmap ->
                    binding.ivMediaPhoto.setImageBitmap(bitmap)
                }
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

        fun recycle() {
            thumbnailSignal?.cancel()
            thumbnailSignal = null
            binding.ivMediaPhoto.setImageDrawable(null)
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

