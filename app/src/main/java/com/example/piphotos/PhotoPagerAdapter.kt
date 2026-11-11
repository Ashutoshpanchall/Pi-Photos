package com.example.piphotos

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class PhotoPagerAdapter(
    private var items: List<MediaItem>,
    private val onSingleTap: () -> Unit
) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

    private val attachedHolders = mutableMapOf<Int, PhotoViewHolder>()
    private var areControlsVisible: Boolean = true

    fun updateItems(newItems: List<MediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): MediaItem? {
        return items.getOrNull(position)
    }

    fun isPositionZoomed(position: Int): Boolean {
        return attachedHolders[position]?.isZoomed() == true
    }

    fun pauseAllVideos() {
        attachedHolders.values.forEach { it.pauseVideo() }
    }

    fun setControlsVisibility(visible: Boolean) {
        areControlsVisible = visible
        attachedHolders.values.forEach { it.setOverlayControlsVisible(visible) }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_page, parent, false)
        return PhotoViewHolder(view, onSingleTap)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(items[position], areControlsVisible)
    }

    override fun onViewAttachedToWindow(holder: PhotoViewHolder) {
        super.onViewAttachedToWindow(holder)
        attachedHolders[holder.bindingAdapterPosition] = holder
    }

    override fun onViewDetachedFromWindow(holder: PhotoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        attachedHolders.remove(holder.bindingAdapterPosition)
        holder.resetZoom()
        holder.pauseVideo()
    }

    class PhotoViewHolder(
        itemView: View,
        private val onSingleTap: () -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val zoomImageView: ZoomableImageView = itemView.findViewById(R.id.zoomImageView)
        private val videoOverlayContainer: View = itemView.findViewById(R.id.videoOverlayContainer)
        private val layoutDurationBadge: View = itemView.findViewById(R.id.layoutDurationBadge)
        private val tvDurationBadge: TextView = itemView.findViewById(R.id.tvDurationBadge)
        private val btnCenterPlayPause: FrameLayout = itemView.findViewById(R.id.btnCenterPlayPause)
        private val ivCenterPlayPause: ImageView = itemView.findViewById(R.id.ivCenterPlayPause)
        private val videoBottomControls: View = itemView.findViewById(R.id.videoBottomControls)
        private val videoSeekBar: SeekBar = itemView.findViewById(R.id.videoSeekBar)
        private val tvCurrentTime: TextView = itemView.findViewById(R.id.tvCurrentTime)
        private val tvStreamBadge: TextView = itemView.findViewById(R.id.tvStreamBadge)
        private val tvTotalDuration: TextView = itemView.findViewById(R.id.tvTotalDuration)

        private val handler = Handler(Looper.getMainLooper())
        private var isPlaying = false
        private var currentSec = 0
        private var totalSec = 42

        private val playbackRunnable = object : Runnable {
            override fun run() {
                if (isPlaying) {
                    currentSec++
                    if (currentSec >= totalSec) {
                        currentSec = 0
                        pauseVideo()
                    } else {
                        handler.postDelayed(this, 1000)
                    }
                    videoSeekBar.progress = currentSec
                    tvCurrentTime.text = formatSeconds(currentSec)
                }
            }
        }

        fun bind(item: MediaItem, controlsVisible: Boolean) {
            if (!item.contentUri.isNullOrEmpty()) {
                zoomImageView.load(item.contentUri) {
                    crossfade(true)
                }
            } else if (item.drawableRes != 0) {
                zoomImageView.setImageResource(item.drawableRes)
            } else {
                zoomImageView.setImageResource(R.color.photo_tile_1)
            }
            zoomImageView.resetZoom()
            zoomImageView.onSingleTapListener = onSingleTap

            if (item.isVideo) {
                videoOverlayContainer.visibility = View.VISIBLE
                val durStr = item.duration ?: "0:42"
                tvDurationBadge.text = durStr
                tvTotalDuration.text = durStr
                totalSec = parseDurationSeconds(durStr)
                videoSeekBar.max = totalSec
                currentSec = 0
                videoSeekBar.progress = 0
                tvCurrentTime.text = "0:00"
                pauseVideo()

                setOverlayControlsVisible(controlsVisible)

                btnCenterPlayPause.setOnClickListener {
                    togglePlayPause()
                }

                videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            currentSec = progress
                            tvCurrentTime.text = formatSeconds(currentSec)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            } else {
                pauseVideo()
                videoOverlayContainer.visibility = View.GONE
            }
        }

        fun setOverlayControlsVisible(visible: Boolean) {
            val alpha = if (visible) 1f else 0f
            layoutDurationBadge.animate().alpha(alpha).setDuration(200).start()
            videoBottomControls.animate().alpha(alpha).setDuration(200).start()
            btnCenterPlayPause.animate().alpha(if (visible || !isPlaying) 1f else 0f).setDuration(200).start()
        }

        private fun togglePlayPause() {
            if (isPlaying) {
                pauseVideo()
            } else {
                startVideo()
            }
        }

        private fun startVideo() {
            isPlaying = true
            ivCenterPlayPause.setImageResource(R.drawable.ic_pause)
            handler.removeCallbacks(playbackRunnable)
            handler.postDelayed(playbackRunnable, 1000)
        }

        fun pauseVideo() {
            isPlaying = false
            ivCenterPlayPause.setImageResource(R.drawable.ic_play_arrow)
            handler.removeCallbacks(playbackRunnable)
        }

        fun isZoomed(): Boolean = zoomImageView.isZoomed

        fun resetZoom() {
            zoomImageView.resetZoom()
        }

        private fun parseDurationSeconds(duration: String): Int {
            val parts = duration.split(":")
            return if (parts.size == 2) {
                val m = parts[0].toIntOrNull() ?: 0
                val s = parts[1].toIntOrNull() ?: 0
                m * 60 + s
            } else {
                42
            }
        }

        private fun formatSeconds(sec: Int): String {
            val m = sec / 60
            val s = sec % 60
            return String.format("%d:%02d", m, s)
        }
    }
}
