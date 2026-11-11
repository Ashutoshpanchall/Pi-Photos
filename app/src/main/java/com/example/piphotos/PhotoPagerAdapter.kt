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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.example.piphotos.media.LocalMediaItem
import java.util.Locale

class PhotoPagerAdapter(
    private var items: List<LocalMediaItem>,
    private val onSingleTap: () -> Unit,
    private val onZoomStateChanged: (position: Int, isZoomed: Boolean) -> Unit
) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

    private val attachedHolders = mutableMapOf<Int, PhotoViewHolder>()
    private var areControlsVisible: Boolean = true
    private var activePlayer: ExoPlayer? = null
    private var activePlayerPosition: Int = -1

    fun updateItems(newItems: List<LocalMediaItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun getItemAt(position: Int): LocalMediaItem? {
        return items.getOrNull(position)
    }

    /** Zoom every page except [position] back to fit-center (called when the pager settles). */
    fun resetZoomExcept(position: Int) {
        attachedHolders.values.forEach { holder ->
            if (holder.bindingAdapterPosition != position) holder.resetZoom()
        }
    }

    fun isPositionZoomed(position: Int): Boolean {
        return attachedHolders[position]?.isZoomed() == true
    }

    fun setControlsVisibility(visible: Boolean) {
        areControlsVisible = visible
        attachedHolders.values.forEach { it.setOverlayControlsVisible(visible) }
    }

    fun attachPlayerToPosition(player: ExoPlayer?, position: Int) {
        activePlayer = player
        activePlayerPosition = position
        attachedHolders.forEach { (pos, holder) ->
            if (pos == position) {
                holder.bindPlayer(player)
            } else {
                holder.unbindPlayer()
            }
        }
    }

    fun detachPlayer() {
        activePlayer = null
        activePlayerPosition = -1
        attachedHolders.values.forEach { it.unbindPlayer() }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_page, parent, false)
        return PhotoViewHolder(view, onSingleTap, onZoomStateChanged)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, areControlsVisible)
        if (position == activePlayerPosition && activePlayer != null) {
            holder.bindPlayer(activePlayer)
        } else {
            holder.unbindPlayer()
        }
    }

    override fun onViewAttachedToWindow(holder: PhotoViewHolder) {
        super.onViewAttachedToWindow(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            attachedHolders[pos] = holder
            if (pos == activePlayerPosition && activePlayer != null) {
                holder.bindPlayer(activePlayer)
            }
        }
    }

    override fun onViewDetachedFromWindow(holder: PhotoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val pos = holder.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            attachedHolders.remove(pos)
        }
        holder.resetZoom()
        holder.unbindPlayer()
    }

    class PhotoViewHolder(
        itemView: View,
        private val onSingleTap: () -> Unit,
        private val onZoomStateChanged: (position: Int, isZoomed: Boolean) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val zoomImageView: ZoomableImageView = itemView.findViewById(R.id.zoomImageView)
        private val playerView: PlayerView = itemView.findViewById(R.id.playerView)
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
        private var boundPlayer: ExoPlayer? = null
        private var currentItem: LocalMediaItem? = null
        private var isUserTrackingTouch = false

        private val progressUpdateRunnable = object : Runnable {
            override fun run() {
                val player = boundPlayer
                if (player != null && player.isPlaying) {
                    if (!isUserTrackingTouch) {
                        val currentMs = player.currentPosition
                        val totalMs = player.duration.coerceAtLeast(1L)
                        videoSeekBar.max = totalMs.toInt()
                        videoSeekBar.progress = currentMs.toInt()
                        tvCurrentTime.text = formatMs(currentMs)
                        tvTotalDuration.text = formatMs(totalMs)
                    }
                    handler.postDelayed(this, 250)
                }
            }
        }

        private val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                if (isPlaying) {
                    handler.post(progressUpdateRunnable)
                } else {
                    handler.removeCallbacks(progressUpdateRunnable)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val player = boundPlayer ?: return
                if (playbackState == Player.STATE_READY) {
                    val durationMs = player.duration
                    if (durationMs > 0) {
                        videoSeekBar.max = durationMs.toInt()
                        tvTotalDuration.text = formatMs(durationMs)
                        tvDurationBadge.text = formatMs(durationMs)
                    }
                }
            }
        }

        init {
            zoomImageView.onSingleTapListener = { onSingleTap() }
            // Report WHICH page changed so the activity can ignore off-screen pages that are only
            // being bound / recycled (they must not unlock or lock the visible page).
            zoomImageView.onZoomStateChanged = { zoomed ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onZoomStateChanged(pos, zoomed)
            }

            btnCenterPlayPause.setOnClickListener {
                val player = boundPlayer ?: return@setOnClickListener
                if (player.isPlaying) {
                    player.pause()
                } else {
                    if (player.playbackState == Player.STATE_ENDED) {
                        player.seekTo(0)
                    }
                    player.play()
                }
            }

            videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        tvCurrentTime.text = formatMs(progress.toLong())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserTrackingTouch = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserTrackingTouch = false
                    val player = boundPlayer ?: return
                    val targetMs = seekBar?.progress?.toLong() ?: 0L
                    player.seekTo(targetMs)
                }
            })
        }

        fun isZoomed(): Boolean = zoomImageView.isZoomed

        fun resetZoom() {
            zoomImageView.resetZoom()
        }

        fun bind(item: LocalMediaItem, areControlsVisible: Boolean) {
            currentItem = item
            resetZoom()

            if (item.isVideo) {
                zoomImageView.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                videoOverlayContainer.visibility = if (areControlsVisible) View.VISIBLE else View.GONE
                tvStreamBadge.text = item.mimeType.substringAfter("/")
                val initialDuration = item.durationMs
                tvTotalDuration.text = formatMs(initialDuration)
                tvDurationBadge.text = formatMs(initialDuration)
                tvCurrentTime.text = "0:00"
                videoSeekBar.progress = 0
            } else {
                unbindPlayer()
                playerView.visibility = View.GONE
                videoOverlayContainer.visibility = View.GONE
                zoomImageView.visibility = View.VISIBLE

                // Drop the previous photo first: a recycled holder still holds the last image, and
                // its size must never be used to place the new one.
                zoomImageView.setImageDrawable(null)

                // Decode at most 2048px on the long side (never the raw 48MP bitmap). No crossfade
                // and no placeholder: the zoom view recomputes the fit-center matrix every time the
                // drawable changes, so the whole image is visible as soon as it arrives.
                zoomImageView.load(item.contentUri) {
                    size(2048, 2048)
                    scale(Scale.FIT)
                    error(R.color.surface_card)
                }
            }
        }

        fun bindPlayer(player: ExoPlayer?) {
            if (boundPlayer == player && playerView.player == player) return
            unbindPlayer()

            val item = currentItem ?: return
            if (!item.isVideo || player == null) return

            boundPlayer = player
            playerView.player = player
            player.addListener(playerListener)

            val mediaItem = androidx.media3.common.MediaItem.fromUri(item.contentUri)
            player.setMediaItem(mediaItem)
            player.prepare()
            updatePlayPauseIcon(player.isPlaying)
            if (player.isPlaying) {
                handler.post(progressUpdateRunnable)
            }
        }

        fun unbindPlayer() {
            handler.removeCallbacks(progressUpdateRunnable)
            boundPlayer?.removeListener(playerListener)
            if (playerView.player != null) {
                playerView.player = null
            }
            boundPlayer = null
            updatePlayPauseIcon(false)
        }

        private fun updatePlayPauseIcon(isPlaying: Boolean) {
            ivCenterPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
        }

        fun setOverlayControlsVisible(visible: Boolean) {
            val item = currentItem ?: return
            if (item.isVideo) {
                videoOverlayContainer.visibility = if (visible) View.VISIBLE else View.GONE
            }
        }

        private fun formatMs(ms: Long): String {
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format(Locale.US, "%d:%02d", min, sec)
        }
    }
}
