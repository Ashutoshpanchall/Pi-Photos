package com.example.piphotos

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.viewpager2.widget.ViewPager2
import com.example.piphotos.databinding.ActivityPhotoViewerBinding
import com.example.piphotos.DeleteResult
import com.example.piphotos.LocalDeleteHandler
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.ViewerSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding
    private lateinit var adapter: PhotoPagerAdapter
    private var mediaItems: MutableList<LocalMediaItem> = mutableListOf()
    private var currentPosition: Int = 0
    private var areToolbarsVisible: Boolean = true
    private var sessionId: String? = null
    private var isCurrentFavorite: Boolean = false

    private var exoPlayer: ExoPlayer? = null

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val removedItem = mediaItems.getOrNull(currentPosition)
            if (removedItem != null) {
                mediaItems.removeAt(currentPosition)
                if (mediaItems.isEmpty()) {
                    closeViewerInstant()
                } else {
                    adapter.notifyItemRemoved(currentPosition)
                    currentPosition = currentPosition.coerceAtMost(mediaItems.size - 1)
                    binding.viewPager.setCurrentItem(currentPosition, false)
                    attachActivePlayerToCurrentPage()
                    updateHeaderAndFavoriteState()
                }
                lifecycleScope.launch {
                    LocalMediaIndex.getInstance(applicationContext).refreshDelta()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawableResource(R.color.black)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.viewerRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.topBar,
            bottomBar = binding.bottomBar,
            extraTopDp = 0,
            extraBottomDp = 12
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemBars()).bottom
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).bottom
            val effectiveBottom = maxOf(navBars, displayCutout)
            val density = v.resources.displayMetrics.density
            val extraBottom = (14 * density).toInt()
            v.setPadding(v.paddingLeft, (10 * density).toInt(), v.paddingRight, effectiveBottom + extraBottom)
            insets
        }

        loadMediaList()
        setupViewPager()
        setupSwipeDismiss()
        setupTopBarActions()
        setupBottomBarActions()
        setupBackNavigation()
    }

    private fun loadMediaList() {
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        val sessionItems = ViewerSession.getSession(sessionId)

        if (!sessionItems.isNullOrEmpty()) {
            mediaItems = sessionItems.toMutableList()
        } else {
            // Process death recovery fallback: query LocalMediaIndex
            val index = LocalMediaIndex.getInstance(applicationContext)
            mediaItems = index.getAllItems().toMutableList()
        }

        val startingId = intent.getLongExtra(EXTRA_STARTING_ID, -1L)
        currentPosition = if (startingId != -1L) {
            ViewerSession.resolveStartingPosition(mediaItems, startingId)
        } else {
            0
        }
    }

    private fun setupViewPager() {
        adapter = PhotoPagerAdapter(
            items = mediaItems,
            onSingleTap = { toggleToolbarsVisibility() },
            onZoomStateChanged = { isZoomed ->
                binding.viewPager.isUserInputEnabled = !isZoomed
            }
        )

        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentPosition, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                attachActivePlayerToCurrentPage()
                updateHeaderAndFavoriteState()
            }
        })

        attachActivePlayerToCurrentPage()
        updateHeaderAndFavoriteState()
    }

    private fun initPlayer() {
        if (exoPlayer == null) {
            val player = ExoPlayer.Builder(this).build()
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            player.setAudioAttributes(audioAttrs, true)
            exoPlayer = player
        }
    }

    private fun attachActivePlayerToCurrentPage() {
        val item = mediaItems.getOrNull(currentPosition)
        if (item != null && item.isVideo) {
            initPlayer()
            adapter.attachPlayerToPosition(exoPlayer, currentPosition)
        } else {
            exoPlayer?.pause()
            adapter.detachPlayer()
        }
    }

    private fun setupSwipeDismiss() {
        binding.swipeDismissContainer.isZoomedSupplier = {
            adapter.isPositionZoomed(binding.viewPager.currentItem)
        }

        binding.swipeDismissContainer.onDismissListener = {
            closeViewerInstant()
        }

        binding.swipeDismissContainer.onDragProgressChanged = { progress ->
            val toolbarAlpha = (1.0f - progress * 2.2f).coerceIn(0f, 1f)
            binding.topBar.alpha = toolbarAlpha
            binding.bottomBar.alpha = toolbarAlpha
            val bgAlpha = ((1f - progress * 0.75f) * 255).toInt().coerceIn(0, 255)
            binding.viewerRoot.setBackgroundColor(android.graphics.Color.argb(bgAlpha, 0, 0, 0))
        }
    }

    private fun updateHeaderAndFavoriteState() {
        val item = mediaItems.getOrNull(currentPosition) ?: return

        val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateObj = Date(item.effectiveDateMs)

        binding.tvViewerDate.text = dateFormat.format(dateObj)
        binding.tvViewerTime.text = timeFormat.format(dateObj)

        lifecycleScope.launch {
            val repo = LocalMediaIndex.getInstance(applicationContext).favoritesRepo
            isCurrentFavorite = repo.isFavorite(item.id)
            updateFavoriteIcon(isCurrentFavorite)
        }
    }

    private fun updateFavoriteIcon(isFav: Boolean) {
        if (isFav) {
            binding.btnFavoriteStar.setImageResource(R.drawable.ic_star_filled)
            binding.btnFavoriteStar.setColorFilter(ContextCompat.getColor(this, R.color.status_amber))
        } else {
            binding.btnFavoriteStar.setImageResource(R.drawable.ic_star_outline)
            binding.btnFavoriteStar.setColorFilter(ContextCompat.getColor(this, R.color.white))
        }
    }

    private fun setupTopBarActions() {
        binding.btnViewerBack.setOnClickListener {
            binding.swipeDismissContainer.animateDismiss()
        }

        binding.layoutDateHeader.setOnClickListener {
            showPhotoInfoSheet()
        }

        binding.btnFavoriteStar.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            lifecycleScope.launch {
                val repo = LocalMediaIndex.getInstance(applicationContext).favoritesRepo
                val newState = repo.toggleFavorite(item.id)
                isCurrentFavorite = newState
                updateFavoriteIcon(newState)
            }
        }

        binding.btnMoreMenu.setOnClickListener { view ->
            showOverflowMenu(view)
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_ABOUT, 0, "Details")
        popup.menu.add(0, MENU_COPY, 1, "Copy link")
        popup.menu.add(0, MENU_DELETE, 2, "Delete")

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_ABOUT -> {
                    showPhotoInfoSheet()
                    true
                }
                MENU_COPY -> {
                    copyImageToClipboard()
                    true
                }
                MENU_DELETE -> {
                    confirmDeleteCurrentPhoto()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showPhotoInfoSheet() {
        val item = mediaItems.getOrNull(currentPosition) ?: return
        val dateFormat = SimpleDateFormat("MMMM d, yyyy · h:mm a", Locale.getDefault())
        val dateStr = dateFormat.format(Date(item.effectiveDateMs))
        val title = "${if (item.isVideo) "VID" else "IMG"}_${item.id}"
        PhotoInfoBottomSheet.newInstance(title, dateStr)
            .show(supportFragmentManager, PhotoInfoBottomSheet.TAG)
    }

    private fun copyImageToClipboard() {
        val item = mediaItems.getOrNull(currentPosition) ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Media URI", item.contentUri.toString())
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "URI copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun setupBottomBarActions() {
        // Real Share with contentUri and ClipData
        binding.btnShare.setOnClickListener {
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType
                putExtra(Intent.EXTRA_STREAM, item.contentUri)
                clipData = ClipData.newUri(contentResolver, "Media", item.contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share"))
        }

        // Real Edit via system editor Intent
        binding.btnEdit.setOnClickListener {
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            val editIntent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(item.contentUri, item.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(editIntent, "Edit photo"))
            } catch (_: Exception) {
                Toast.makeText(this, "No editor application found", Toast.LENGTH_SHORT).show()
            }
        }

        // Add to Album (hide if no-op)
        binding.btnAddToAlbum.setOnClickListener {
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            AddToAlbumBottomSheet { chosenAlbum ->
                Toast.makeText(this, "Album feature configured in G3.1d", Toast.LENGTH_SHORT).show()
            }.show(supportFragmentManager, AddToAlbumBottomSheet.TAG)
        }

        // Real Delete / Bin
        binding.btnBin.setOnClickListener {
            confirmDeleteCurrentPhoto()
        }
    }

    private fun confirmDeleteCurrentPhoto() {
        val item = mediaItems.getOrNull(currentPosition) ?: return
        val type = if (item.isVideo) "video" else "photo"
        AlertDialog.Builder(this)
            .setTitle("Move to Trash?")
            .setMessage("This $type will be moved to device Trash.")
            .setPositiveButton("Move to Trash") { _, _ ->
                lifecycleScope.launch {
                    val deleteHandler = LocalDeleteHandler(this@PhotoViewerActivity)
                    when (val result = deleteHandler.trash(listOf(item.id))) {
                        is DeleteResult.Success -> {
                            mediaItems.removeAt(currentPosition)
                            if (mediaItems.isEmpty()) {
                                closeViewerInstant()
                            } else {
                                adapter.notifyItemRemoved(currentPosition)
                                currentPosition = currentPosition.coerceAtMost(mediaItems.size - 1)
                                binding.viewPager.setCurrentItem(currentPosition, false)
                                attachActivePlayerToCurrentPage()
                                updateHeaderAndFavoriteState()
                            }
                            LocalMediaIndex.getInstance(applicationContext).refreshDelta()
                        }
                        is DeleteResult.RequiresConfirmation -> {
                            val request = IntentSenderRequest.Builder(result.intentSender).build()
                            intentSenderLauncher.launch(request)
                        }
                        is DeleteResult.Error -> {
                            Toast.makeText(this@PhotoViewerActivity, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                binding.swipeDismissContainer.animateDismiss()
            }
        })
    }

    private fun closeViewerInstant() {
        finish()
    }

    private fun toggleToolbarsVisibility() {
        areToolbarsVisible = !areToolbarsVisible
        val targetAlpha = if (areToolbarsVisible) 1.0f else 0.0f
        val duration = 200L

        binding.topBar.animate().alpha(targetAlpha).setDuration(duration).start()
        binding.bottomBar.animate().alpha(targetAlpha).setDuration(duration).start()
        adapter.setControlsVisibility(areToolbarsVisible)
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
        if (::adapter.isInitialized) {
            adapter.detachPlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
        ViewerSession.clearSession(sessionId)
    }

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_STARTING_ID = "extra_starting_id"

        private const val MENU_ABOUT = 101
        private const val MENU_COPY = 103
        private const val MENU_DELETE = 104

        fun start(
            context: Context,
            startingId: Long,
            items: List<LocalMediaItem>
        ) {
            val sessionId = ViewerSession.createSession(items)
            val intent = Intent(context, PhotoViewerActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_STARTING_ID, startingId)
            }
            context.startActivity(intent)
        }

        // Backwards compatibility overload
        fun start(
            context: Context,
            photoId: String,
            drawableRes: Int = 0,
            title: String = "Photo",
            date: String = "",
            category: String = "Recents",
            isVideo: Boolean = false,
            contentUri: String? = null
        ) {
            val id = photoId.toLongOrNull() ?: -1L
            val allItems = LocalMediaIndex.getInstance(context).getAllItems()
            start(context, id, allItems)
        }
    }
}
