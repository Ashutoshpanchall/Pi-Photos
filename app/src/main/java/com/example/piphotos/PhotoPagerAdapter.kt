package com.example.piphotos

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
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
    private val onZoomStateChanged: (position: Int, isZoomed: Boolean) -> Unit,
    var onPlaybackProgress: ((currentMs: Long, totalMs: Long, isPlaying: Boolean) -> Unit)? = null
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
                holder.bindPlayer(player, onPlaybackProgress)
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
            holder.bindPlayer(activePlayer, onPlaybackProgress)
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
                holder.bindPlayer(activePlayer, onPlaybackProgress)
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
        private val btnRewind10: FrameLayout = itemView.findViewById(R.id.btnRewind10)
        private val btnForward10: FrameLayout = itemView.findViewById(R.id.btnForward10)

        private val handler = Handler(Looper.getMainLooper())
        private var boundPlayer: ExoPlayer? = null
        private var currentItem: LocalMediaItem? = null
        private var progressCallback: ((Long, Long, Boolean) -> Unit)? = null

        private val progressUpdateRunnable = object : Runnable {
            override fun run() {
                val player = boundPlayer
                if (player != null && player.isPlaying) {
                    val currentMs = player.currentPosition
                    val totalMs = player.duration.coerceAtLeast(1L)
                    progressCallback?.invoke(currentMs, totalMs, true)
                    handler.postDelayed(this, 250)
                }
            }
        }

        private val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayPauseIcon(isPlaying)
                val player = boundPlayer
                if (player != null) {
                    val currentMs = player.currentPosition
                    val totalMs = player.duration.coerceAtLeast(1L)
                    progressCallback?.invoke(currentMs, totalMs, isPlaying)
                }
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
                        tvDurationBadge.text = formatMs(durationMs)
                        progressCallback?.invoke(player.currentPosition, durationMs, player.isPlaying)
                    }
                }
            }
        }

        init {
            zoomImageView.onSingleTapListener = { onSingleTap() }
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

            btnRewind10.setOnClickListener {
                val player = boundPlayer ?: return@setOnClickListener
                val target = (player.currentPosition - 10000L).coerceAtLeast(0L)
                player.seekTo(target)
            }

            btnForward10.setOnClickListener {
                val player = boundPlayer ?: return@setOnClickListener
                val target = (player.currentPosition + 10000L).coerceAtMost(player.duration)
                player.seekTo(target)
            }

            itemView.setOnClickListener {
                onSingleTap()
            }
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
                val initialDuration = item.durationMs
                tvDurationBadge.text = formatMs(initialDuration)
            } else {
                unbindPlayer()
                playerView.visibility = View.GONE
                videoOverlayContainer.visibility = View.GONE
                zoomImageView.visibility = View.VISIBLE

                // Drop previous photo first to prevent incorrect sizing
                zoomImageView.setImageDrawable(null)

                // Decelerate scale (0.985 -> 1.0) and crossfade transition without black frame
                zoomImageView.scaleX = 0.985f
                zoomImageView.scaleY = 0.985f
                zoomImageView.alpha = 0.7f

                zoomImageView.load(item.contentUri) {
                    size(2048, 2048)
                    scale(Scale.FIT)
                    crossfade(280)
                    listener(
                        onSuccess = { _, _ ->
                            zoomImageView.animate()
                                .scaleX(1.0f)
                                .scaleY(1.0f)
                                .alpha(1.0f)
                                .setDuration(280L)
                                .setInterpolator(DecelerateInterpolator())
                                .start()
                        }
                    )
                    error(R.color.surface_card)
                }
            }
        }

        fun bindPlayer(player: ExoPlayer?, callback: ((Long, Long, Boolean) -> Unit)?) {
            if (boundPlayer == player && playerView.player == player) return
            unbindPlayer()

            val item = currentItem ?: return
            if (!item.isVideo || player == null) return

            boundPlayer = player
            progressCallback = callback
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
            progressCallback = null
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
                if (visible) {
                    videoOverlayContainer.visibility = View.VISIBLE
                    videoOverlayContainer.animate().alpha(1f).setDuration(200L).start()
                } else {
                    videoOverlayContainer.animate().alpha(0f).setDuration(200L).withEndAction {
                        videoOverlayContainer.visibility = View.GONE
                    }.start()
                }
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
