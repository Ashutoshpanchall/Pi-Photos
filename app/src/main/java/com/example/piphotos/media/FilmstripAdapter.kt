package com.example.piphotos.media

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.piphotos.R
import java.util.Locale

class FilmstripAdapter(
    private val onItemClicked: (Int) -> Unit
) : ListAdapter<LocalMediaItem, FilmstripAdapter.ViewHolder>(DiffCallback) {

    private var selectedPosition: Int = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id
    }

    /**
     * Updates selection. When [recyclerView] is provided, visible ViewHolders are updated directly
     * without calling [notifyItemChanged], completely eliminating layout re-computation and
     * preventing `IllegalStateException: Cannot call this method while RecyclerView is computing a layout or scrolling`.
     */
    fun setSelectedPosition(newPosition: Int, recyclerView: RecyclerView? = null) {
        if (selectedPosition == newPosition) return
        val oldPos = selectedPosition
        selectedPosition = newPosition

        if (recyclerView != null) {
            // Direct ViewHolder state update: zero notifyItemChanged, 60fps smooth & crash-proof
            (recyclerView.findViewHolderForAdapterPosition(oldPos) as? ViewHolder)?.updateSelection(isSelected = false, animate = true)
            (recyclerView.findViewHolderForAdapterPosition(newPosition) as? ViewHolder)?.updateSelection(isSelected = true, animate = true)
        } else {
            if (oldPos in 0 until itemCount) {
                notifyItemChanged(oldPos, PAYLOAD_SELECTION)
            }
            if (newPosition in 0 until itemCount) {
                notifyItemChanged(newPosition, PAYLOAD_SELECTION)
            }
        }
    }

    fun getSelectedPosition(): Int = selectedPosition

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_filmstrip_thumbnail, parent, false)
        return ViewHolder(view, onItemClicked)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val isSelected = position == selectedPosition
        holder.bind(item, isSelected)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            val isSelected = position == selectedPosition
            holder.updateSelection(isSelected, animate = true)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    class ViewHolder(
        itemView: View,
        private val onItemClicked: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val layoutVideoBadge: View = itemView.findViewById(R.id.layoutVideoBadge)
        private val tvVideoDuration: TextView = itemView.findViewById(R.id.tvVideoDuration)
        private val viewActiveBorder: View = itemView.findViewById(R.id.viewActiveBorder)

        private var thumbnailSignal: CancellationSignal? = null
        private var scaleXSpring: SpringAnimation? = null
        private var scaleYSpring: SpringAnimation? = null

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onItemClicked(pos)
                }
            }
        }

        fun bind(item: LocalMediaItem, isSelected: Boolean) {
            thumbnailSignal?.cancel()
            thumbnailSignal = null

            // Instant 60fps cache check or bounded native MediaStore thumbnail decode
            val cached = ThumbnailLoader.getFromCache(item.id)
            if (cached != null && !cached.isRecycled) {
                ivThumbnail.setImageBitmap(cached)
            } else {
                ivThumbnail.setImageResource(R.color.surface_card)
                thumbnailSignal = ThumbnailLoader.loadThumbnail(
                    context = itemView.context,
                    item = item,
                    targetSizePx = 120
                ) { bitmap ->
                    ivThumbnail.setImageBitmap(bitmap)
                }
            }

            if (item.isVideo) {
                layoutVideoBadge.visibility = View.VISIBLE
                tvVideoDuration.text = formatDuration(item.durationMs)
            } else {
                layoutVideoBadge.visibility = View.GONE
            }

            updateSelection(isSelected, animate = false)
        }

        fun updateSelection(isSelected: Boolean, animate: Boolean) {
            val targetScale = if (isSelected) 1.08f else 1.0f

            // All cards stay at full original brightness (1.0f alpha, no dim scrim)
            itemView.alpha = 1.0f
            viewActiveBorder.visibility = if (isSelected) View.VISIBLE else View.GONE

            if (animate) {
                getOrCreateSpringX().animateToFinalPosition(targetScale)
                getOrCreateSpringY().animateToFinalPosition(targetScale)
            } else {
                itemView.scaleX = targetScale
                itemView.scaleY = targetScale
            }
        }

        fun recycle() {
            thumbnailSignal?.cancel()
            thumbnailSignal = null
            scaleXSpring?.cancel()
            scaleYSpring?.cancel()
            ivThumbnail.setImageDrawable(null)
        }

        private fun getOrCreateSpringX(): SpringAnimation {
            return scaleXSpring ?: SpringAnimation(itemView, DynamicAnimation.SCALE_X).apply {
                spring = SpringForce().apply {
                    stiffness = SpringForce.STIFFNESS_LOW
                    dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                }
                scaleXSpring = this
            }
        }

        private fun getOrCreateSpringY(): SpringAnimation {
            return scaleYSpring ?: SpringAnimation(itemView, DynamicAnimation.SCALE_Y).apply {
                spring = SpringForce().apply {
                    stiffness = SpringForce.STIFFNESS_LOW
                    dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
                }
                scaleYSpring = this
            }
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format(Locale.US, "%d:%02d", min, sec)
        }
    }

    companion object {
        private const val PAYLOAD_SELECTION = "payload_selection"

        private val DiffCallback = object : DiffUtil.ItemCallback<LocalMediaItem>() {
            override fun areItemsTheSame(oldItem: LocalMediaItem, newItem: LocalMediaItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: LocalMediaItem, newItem: LocalMediaItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
