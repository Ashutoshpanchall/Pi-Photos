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
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.piphotos.databinding.ActivityPhotoViewerBinding
import com.example.piphotos.DeleteResult
import com.example.piphotos.LocalDeleteHandler
import com.example.piphotos.media.FilmstripAdapter
import com.example.piphotos.media.FilmstripCenterHelper
import com.example.piphotos.media.FrostedGlassHelper
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
    private lateinit var filmstripAdapter: FilmstripAdapter
    private var mediaItems: MutableList<LocalMediaItem> = mutableListOf()
    private var currentPosition: Int = 0
    private var areToolbarsVisible: Boolean = true
    private var sessionId: String? = null
    private var isCurrentFavorite: Boolean = false

    // Zoom state of the visible page only
    private var currentPageZoomed: Boolean = false

    private var exoPlayer: ExoPlayer? = null

    // Flags to eliminate recursive synchronization loops between ViewPager2 and Filmstrip
    private var isSyncingFromPager = false
    private var isSyncingFromFilmstrip = false

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
                    filmstripAdapter.submitList(mediaItems.toList())
                    currentPosition = currentPosition.coerceAtMost(mediaItems.size - 1)
                    binding.viewPager.setCurrentItem(currentPosition, false)
                    filmstripAdapter.setSelectedPosition(currentPosition)
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

        // Apply Apple frosted glass effect to chrome surfaces
        FrostedGlassHelper.applyGlass(binding.topBar, blurRadiusDp = 24f)
        FrostedGlassHelper.applyGlass(binding.filmstripContainer, blurRadiusDp = 20f)
        FrostedGlassHelper.applyGlass(binding.bottomBar, blurRadiusDp = 24f)

        // System Insets: Apply padding so media draws edge-to-edge behind bars
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            binding.statusBarSpacer.layoutParams.height = maxOf(statusBars, cutout)
            binding.statusBarSpacer.requestLayout()
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.systemBars()).bottom
            val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).bottom
            val effectiveBottom = maxOf(navBars, displayCutout)
            val density = v.resources.displayMetrics.density
            val extraBottom = (6 * density).toInt()
            v.setPadding(v.paddingLeft, (8 * density).toInt(), v.paddingRight, effectiveBottom + extraBottom)
            insets
        }

        loadMediaList()
        setupViewPager()
        setupFilmstrip()
        setupSwipeDismiss()
        setupTopBarActions()
        setupBottomBarActions()
        setupBackNavigation()

        lifecycleScope.launch {
            LocalMediaIndex.getInstance(applicationContext).favoritesRepo.observeIds().collect { favIds ->
                val item = mediaItems.getOrNull(currentPosition) ?: return@collect
                isCurrentFavorite = favIds.contains(item.id)
                updateFavoriteIcon(isCurrentFavorite)
            }
        }
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
            onZoomStateChanged = { position, isZoomed ->
                if (position == binding.viewPager.currentItem) {
                    currentPageZoomed = isZoomed
                }
            }
        )

        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentPosition, false)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                adapter.resetZoomExcept(position)
                currentPageZoomed = false
                attachActivePlayerToCurrentPage()
                updateHeaderAndFavoriteState()

                if (!isSyncingFromFilmstrip) {
                    isSyncingFromPager = true
                    filmstripAdapter.setSelectedPosition(position)
                    val thumbWidthPx = (58 * resources.displayMetrics.density).toInt()
                    FilmstripCenterHelper.scrollToCenter(binding.rvFilmstrip, position, thumbWidthPx)
                    isSyncingFromPager = false
                }
            }
        })

        attachActivePlayerToCurrentPage()
        updateHeaderAndFavoriteState()
    }

    private fun setupFilmstrip() {
        filmstripAdapter = FilmstripAdapter { clickedPosition ->
            if (clickedPosition != currentPosition) {
                binding.viewPager.setCurrentItem(clickedPosition, true)
            }
        }

        val layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.rvFilmstrip.layoutManager = layoutManager
        binding.rvFilmstrip.adapter = filmstripAdapter
        filmstripAdapter.submitList(mediaItems.toList())
        filmstripAdapter.setSelectedPosition(currentPosition)

        // Compute start and end padding dynamically so index 0 and index N-1 can center
        val thumbWidthPx = (58 * resources.displayMetrics.density).toInt()
        binding.rvFilmstrip.post {
            val padding = FilmstripCenterHelper.computeStartEndPadding(binding.rvFilmstrip.width, thumbWidthPx)
            binding.rvFilmstrip.setPadding(padding, 0, padding, 0)
            FilmstripCenterHelper.scrollToCenter(binding.rvFilmstrip, currentPosition, thumbWidthPx)
        }

        // Center-based filmstrip selection on scroll & fling settle
        binding.rvFilmstrip.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !isSyncingFromPager) {
                    val centerPosition = FilmstripCenterHelper.findCenterItemPosition(recyclerView)
                    if (centerPosition != RecyclerView.NO_POSITION && centerPosition != currentPosition) {
                        isSyncingFromFilmstrip = true
                        currentPosition = centerPosition
                        filmstripAdapter.setSelectedPosition(centerPosition)
                        binding.viewPager.setCurrentItem(centerPosition, false)
                        attachActivePlayerToCurrentPage()
                        updateHeaderAndFavoriteState()
                        isSyncingFromFilmstrip = false
                    }
                }
            }
        })
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
        binding.swipeDismissContainer.isZoomedSupplier = { currentPageZoomed }

        binding.swipeDismissContainer.onDismissListener = {
            closeViewerInstant()
        }

        binding.swipeDismissContainer.onDragProgressChanged = { progress ->
            val toolbarAlpha = (1.0f - progress * 2.2f).coerceIn(0f, 1f)
            binding.topBar.alpha = toolbarAlpha
            binding.bottomChromeContainer.alpha = toolbarAlpha
            val bgAlpha = ((1f - progress * 0.75f) * 255).toInt().coerceIn(0, 255)
            binding.viewerRoot.setBackgroundColor(android.graphics.Color.argb(bgAlpha, 0, 0, 0))
        }
    }

    private fun updateHeaderAndFavoriteState() {
        val item = mediaItems.getOrNull(currentPosition) ?: return

        // Actual capture timestamp from EXIF / MediaStore DATE_TAKEN
        val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateObj = Date(item.effectiveDateMs)

        binding.tvViewerDate.text = dateFormat.format(dateObj)
        binding.tvViewerTime.text = timeFormat.format(dateObj).lowercase(Locale.getDefault())

        // Update floating media counter (e.g. "3 / 24")
        binding.tvMediaCounter.text = "${currentPosition + 1} / ${mediaItems.size}"

        val favIds = LocalMediaIndex.getInstance(applicationContext).favoritesRepo.idsFlow.value
        isCurrentFavorite = favIds.contains(item.id)
        updateFavoriteIcon(isCurrentFavorite)
    }

    private fun updateFavoriteIcon(isFav: Boolean) {
        if (isFav) {
            binding.btnFavoriteStar.setImageResource(R.drawable.ic_heart)
            binding.btnFavoriteStar.setColorFilter(ContextCompat.getColor(this, R.color.status_amber))
        } else {
            binding.btnFavoriteStar.setImageResource(R.drawable.ic_heart)
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

        binding.btnCast.setOnClickListener {
            Toast.makeText(this, "Searching for cast devices on local Wi-Fi...", Toast.LENGTH_SHORT).show()
        }

        binding.btnFavoriteStar.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            lifecycleScope.launch {
                val repo = LocalMediaIndex.getInstance(applicationContext).favoritesRepo
                repo.toggle(item.id)
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
        // Real Share
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

        // Real Edit via system editor
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

        // Add to Album
        binding.btnAddToAlbum.setOnClickListener {
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            AddToAlbumBottomSheet(listOf(item.id)) { chosenAlbum ->
                Toast.makeText(this, "Added to \"$chosenAlbum\"", Toast.LENGTH_SHORT).show()
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
                                filmstripAdapter.submitList(mediaItems.toList())
                                currentPosition = currentPosition.coerceAtMost(mediaItems.size - 1)
                                binding.viewPager.setCurrentItem(currentPosition, false)
                                filmstripAdapter.setSelectedPosition(currentPosition)
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

        val density = resources.displayMetrics.density
        val slidePx = 12f * density

        val targetAlpha = if (areToolbarsVisible) 1.0f else 0.0f
        val topTargetY = if (areToolbarsVisible) 0f else -slidePx
        val bottomTargetY = if (areToolbarsVisible) 0f else slidePx

        // Spring physics for iOS-like smooth slide & fade over ~220ms
        val springTop = SpringAnimation(binding.topBar, DynamicAnimation.TRANSLATION_Y, topTargetY).apply {
            spring = SpringForce(topTargetY).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
        }
        val springBottom = SpringAnimation(binding.bottomChromeContainer, DynamicAnimation.TRANSLATION_Y, bottomTargetY).apply {
            spring = SpringForce(bottomTargetY).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
            }
        }

        binding.topBar.animate().alpha(targetAlpha).setDuration(220L).start()
        binding.bottomChromeContainer.animate().alpha(targetAlpha).setDuration(220L).start()

        springTop.start()
        springBottom.start()

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
