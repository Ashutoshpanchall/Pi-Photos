package com.example.piphotos.media

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
import coil.load
import coil.size.Scale
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

    fun setSelectedPosition(newPosition: Int) {
        if (selectedPosition == newPosition) return
        val oldPos = selectedPosition
        selectedPosition = newPosition
        if (oldPos in 0 until itemCount) {
            notifyItemChanged(oldPos, PAYLOAD_SELECTION)
        }
        if (newPosition in 0 until itemCount) {
            notifyItemChanged(newPosition, PAYLOAD_SELECTION)
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

    class ViewHolder(
        itemView: View,
        private val onItemClicked: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        private val layoutVideoBadge: View = itemView.findViewById(R.id.layoutVideoBadge)
        private val tvVideoDuration: TextView = itemView.findViewById(R.id.tvVideoDuration)
        private val viewUnselectedScrim: View = itemView.findViewById(R.id.viewUnselectedScrim)
        private val viewActiveBorder: View = itemView.findViewById(R.id.viewActiveBorder)

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
            // Load appropriately downsampled thumbnail for the filmstrip (~128px)
            ivThumbnail.load(item.contentUri) {
                size(128, 128)
                scale(Scale.FILL)
                crossfade(true)
                error(R.color.surface_card)
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
            val targetAlpha = if (isSelected) 1.0f else 0.55f

            viewActiveBorder.visibility = if (isSelected) View.VISIBLE else View.GONE
            viewUnselectedScrim.visibility = if (isSelected) View.GONE else View.VISIBLE

            if (animate) {
                itemView.animate().alpha(targetAlpha).setDuration(160L).start()
                getOrCreateSpringX().animateToFinalPosition(targetScale)
                getOrCreateSpringY().animateToFinalPosition(targetScale)
            } else {
                itemView.alpha = targetAlpha
                itemView.scaleX = targetScale
                itemView.scaleY = targetScale
            }
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
