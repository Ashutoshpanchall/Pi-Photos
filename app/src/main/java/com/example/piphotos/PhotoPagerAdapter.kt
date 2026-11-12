package com.example.piphotos

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.Disposable
import coil.size.Scale
import com.example.piphotos.media.LocalMediaItem
import java.util.Locale

class PhotoPagerAdapter(
    private var items: List<LocalMediaItem>,
    private val onSingleTap: () -> Unit,
    private val onZoomStateChanged: (position: Int, isZoomed: Boolean) -> Unit,
    private val onPlaybackProgress: ((currentMs: Long, totalMs: Long, isPlaying: Boolean) -> Unit)? = null
) : RecyclerView.Adapter<PhotoPagerAdapter.PhotoViewHolder>() {

    private val attachedHolders = mutableMapOf<Int, PhotoViewHolder>()
    private var activePlayer: ExoPlayer? = null
    private var activePlayerPosition: Int = -1
    private var areControlsVisible: Boolean = true

    fun getItem(position: Int): LocalMediaItem? {
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

    fun attachPlayerToPosition(player: ExoPlayer?, position: Int) {
        activePlayer = player
        activePlayerPosition = position
        attachedHolders.forEach { (pos, holder) ->
            if (pos == position && player != null) {
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

    fun setControlsVisibility(visible: Boolean) {
        areControlsVisible = visible
        attachedHolders.values.forEach { it.setOverlayControlsVisible(visible) }
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

    override fun onViewRecycled(holder: PhotoViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    class PhotoViewHolder(
        itemView: View,
        private val onSingleTap: () -> Unit,
        private val onZoomStateChanged: (position: Int, isZoomed: Boolean) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val zoomImageView: ZoomableImageView = itemView.findViewById(R.id.zoomImageView)
        private val ivVideoPoster: ImageView = itemView.findViewById(R.id.ivVideoPoster)
        private val playerView: PlayerView = itemView.findViewById(R.id.playerView)
        private val videoOverlayContainer: View = itemView.findViewById(R.id.videoOverlayContainer)
        private val videoLoadingSpinner: ProgressBar = itemView.findViewById(R.id.videoLoadingSpinner)
        private val btnCenterPlayPause: FrameLayout = itemView.findViewById(R.id.btnCenterPlayPause)
        private val ivCenterPlayPause: ImageView = itemView.findViewById(R.id.ivCenterPlayPause)
        private val btnRewind10: FrameLayout = itemView.findViewById(R.id.btnRewind10)
        private val btnForward10: FrameLayout = itemView.findViewById(R.id.btnForward10)

        private val handler = Handler(Looper.getMainLooper())
        private var boundPlayer: ExoPlayer? = null
        private var currentItem: LocalMediaItem? = null
        private var progressCallback: ((Long, Long, Boolean) -> Unit)? = null
        private var imageDisposable: Disposable? = null
        private var posterDisposable: Disposable? = null

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
                    ivVideoPoster.visibility = View.GONE
                    videoLoadingSpinner.visibility = View.GONE
                    handler.post(progressUpdateRunnable)
                } else {
                    handler.removeCallbacks(progressUpdateRunnable)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val player = boundPlayer ?: return
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        videoLoadingSpinner.visibility = View.VISIBLE
                    }
                    Player.STATE_READY -> {
                        videoLoadingSpinner.visibility = View.GONE
                        val durationMs = player.duration
                        if (durationMs > 0) {
                            progressCallback?.invoke(player.currentPosition, durationMs, player.isPlaying)
                        }
                    }
                    Player.STATE_ENDED -> {
                        videoLoadingSpinner.visibility = View.GONE
                        updatePlayPauseIcon(false)
                    }
                    else -> {
                        videoLoadingSpinner.visibility = View.GONE
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

            // Cancel any pending decode requests on rebind
            imageDisposable?.dispose()
            posterDisposable?.dispose()

            if (item.isVideo) {
                zoomImageView.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                ivVideoPoster.visibility = View.VISIBLE
                videoOverlayContainer.visibility = if (areControlsVisible) View.VISIBLE else View.GONE
                videoLoadingSpinner.visibility = View.GONE

                // Instant first-frame poster thumbnail so user never sees a black box
                posterDisposable = ivVideoPoster.load(item.contentUri) {
                    size(1024, 1024)
                    scale(Scale.FIT)
                    crossfade(true)
                }
            } else {
                unbindPlayer()
                playerView.visibility = View.GONE
                ivVideoPoster.visibility = View.GONE
                videoOverlayContainer.visibility = View.GONE
                videoLoadingSpinner.visibility = View.GONE
                zoomImageView.visibility = View.VISIBLE

                // Drop previous photo first to prevent incorrect sizing
                zoomImageView.setImageDrawable(null)

                // Smooth native photo paging: full 1.0x scale and 1.0 alpha
                zoomImageView.scaleX = 1.0f
                zoomImageView.scaleY = 1.0f
                zoomImageView.alpha = 1.0f

                imageDisposable = zoomImageView.load(item.contentUri) {
                    size(2048, 2048)
                    scale(Scale.FIT)
                    crossfade(180)
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
                ivVideoPoster.visibility = View.GONE
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
            videoLoadingSpinner.visibility = View.GONE
        }

        fun recycle() {
            imageDisposable?.dispose()
            imageDisposable = null
            posterDisposable?.dispose()
            posterDisposable = null
            zoomImageView.setImageDrawable(null)
            ivVideoPoster.setImageDrawable(null)
            unbindPlayer()
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
    }
}
