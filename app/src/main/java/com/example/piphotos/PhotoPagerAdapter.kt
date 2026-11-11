package com.example.piphotos

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.size.Scale
import com.example.piphotos.media.FrostedGlassHelper
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
        private val btnRewind10: FrameLayout = itemView.findViewById(R.id.btnRewind10)
        private val btnForward10: FrameLayout = itemView.findViewById(R.id.btnForward10)
        private val videoBottomControls: View = itemView.findViewById(R.id.videoBottomControls)
        private val videoSeekBar: SeekBar = itemView.findViewById(R.id.videoSeekBar)
        private val tvCurrentTime: TextView = itemView.findViewById(R.id.tvCurrentTime)
        private val tvTotalDuration: TextView = itemView.findViewById(R.id.tvTotalDuration)
        private val btnPlaybackSpeed: TextView = itemView.findViewById(R.id.btnPlaybackSpeed)
        private val btnMuteToggle: ImageView = itemView.findViewById(R.id.btnMuteToggle)

        private val handler = Handler(Looper.getMainLooper())
        private var boundPlayer: ExoPlayer? = null
        private var currentItem: LocalMediaItem? = null
        private var isUserTrackingTouch = false
        private var isMuted = false
        private var currentSpeedIndex = 1 // 1.0x default
        private val speedOptions = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f)

        private val autoHideRunnable = Runnable {
            if (boundPlayer?.isPlaying == true) {
                videoOverlayContainer.animate()
                    .alpha(0f)
                    .setDuration(220L)
                    .withEndAction { videoOverlayContainer.visibility = View.GONE }
                    .start()
            }
        }

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
                    scheduleAutoHide()
                } else {
                    handler.removeCallbacks(progressUpdateRunnable)
                    cancelAutoHide()
                    setOverlayControlsVisible(true)
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
            zoomImageView.onZoomStateChanged = { zoomed ->
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onZoomStateChanged(pos, zoomed)
            }

            // Apply Apple glass effect to video bottom scrubber bar
            FrostedGlassHelper.applyGlass(videoBottomControls, blurRadiusDp = 20f)

            btnCenterPlayPause.setOnClickListener {
                resetAutoHide()
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
                resetAutoHide()
                val player = boundPlayer ?: return@setOnClickListener
                val target = (player.currentPosition - 10000L).coerceAtLeast(0L)
                player.seekTo(target)
            }

            btnForward10.setOnClickListener {
                resetAutoHide()
                val player = boundPlayer ?: return@setOnClickListener
                val target = (player.currentPosition + 10000L).coerceAtMost(player.duration)
                player.seekTo(target)
            }

            btnMuteToggle.setOnClickListener {
                resetAutoHide()
                val player = boundPlayer ?: return@setOnClickListener
                isMuted = !isMuted
                player.volume = if (isMuted) 0f else 1f
                btnMuteToggle.setImageResource(if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            }

            btnPlaybackSpeed.setOnClickListener {
                resetAutoHide()
                val player = boundPlayer ?: return@setOnClickListener
                currentSpeedIndex = (currentSpeedIndex + 1) % speedOptions.size
                val newSpeed = speedOptions[currentSpeedIndex]
                player.playbackParameters = PlaybackParameters(newSpeed)
                btnPlaybackSpeed.text = String.format(Locale.US, "%.1fx", newSpeed)
            }

            videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        tvCurrentTime.text = formatMs(progress.toLong())
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserTrackingTouch = true
                    cancelAutoHide()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserTrackingTouch = false
                    val player = boundPlayer ?: return
                    val targetMs = seekBar?.progress?.toLong() ?: 0L
                    player.seekTo(targetMs)
                    scheduleAutoHide()
                }
            })

            itemView.setOnClickListener {
                onSingleTap()
            }
        }

        private fun scheduleAutoHide() {
            handler.removeCallbacks(autoHideRunnable)
            handler.postDelayed(autoHideRunnable, 2500L)
        }

        private fun cancelAutoHide() {
            handler.removeCallbacks(autoHideRunnable)
        }

        private fun resetAutoHide() {
            if (boundPlayer?.isPlaying == true) {
                scheduleAutoHide()
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
                tvTotalDuration.text = formatMs(initialDuration)
                tvDurationBadge.text = formatMs(initialDuration)
                tvCurrentTime.text = "0:00"
                videoSeekBar.progress = 0
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
                scheduleAutoHide()
            }
        }

        fun unbindPlayer() {
            cancelAutoHide()
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
                if (visible) {
                    cancelAutoHide()
                    videoOverlayContainer.visibility = View.VISIBLE
                    videoOverlayContainer.animate().alpha(1f).setDuration(200L).start()
                    if (boundPlayer?.isPlaying == true) {
                        scheduleAutoHide()
                    }
                } else {
                    cancelAutoHide()
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
