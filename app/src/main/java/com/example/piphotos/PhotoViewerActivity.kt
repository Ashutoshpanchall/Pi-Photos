package com.example.piphotos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.piphotos.databinding.ActivityPhotoViewerBinding

class PhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoViewerBinding
    private lateinit var adapter: PhotoPagerAdapter
    private var mediaItems: MutableList<MediaItem> = mutableListOf()
    private var currentPosition: Int = 0
    private var areToolbarsVisible: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setBackgroundDrawableResource(R.color.black)
        binding = ActivityPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Robust edge-to-edge insets on top bar and bottom bar
        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.viewerRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.topBar,
            bottomBar = binding.bottomBar,
            extraTopDp = 0,
            extraBottomDp = 12
        )

        // Direct insets listener on bottomBar to guarantee navigation bar clearance
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
        val initialId = intent.getStringExtra(EXTRA_PHOTO_ID)
        val category = intent.getStringExtra(EXTRA_CATEGORY) ?: "Recents"

        // Fetch collection from MediaRepository (excluding Trash and Encrypted Vault unless opened from Vault)
        val rawItems = if (category.equals("Vault", ignoreCase = true)) {
            MediaRepository.getMediaForCategory("vault")
        } else {
            MediaRepository.getMediaForCategory(category).ifEmpty {
                MediaRepository.getMediaForCategory("recents")
            }
        }
        mediaItems = rawItems.toMutableList()

        // Fallback for single photo launches
        if (mediaItems.isEmpty()) {
            val fallbackRes = intent.getIntExtra(EXTRA_PHOTO_RES, 0)
            val fallbackTitle = intent.getStringExtra(EXTRA_PHOTO_TITLE) ?: "Media"
            val fallbackDate = intent.getStringExtra(EXTRA_PHOTO_DATE) ?: "1 Nov · 10:20 am"
            val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
            val contentUri = intent.getStringExtra(EXTRA_CONTENT_URI)
            mediaItems.add(
                MediaItem(
                    id = initialId ?: "temp_1",
                    title = fallbackTitle,
                    date = fallbackDate,
                    category = category,
                    drawableRes = fallbackRes,
                    contentUri = contentUri,
                    isVideo = isVideo
                )
            )
        }

        // Determine starting position
        currentPosition = if (!initialId.isNullOrEmpty()) {
            mediaItems.indexOfFirst { it.id == initialId }.coerceAtLeast(0)
        } else {
            0
        }
    }

    private fun setupViewPager() {
        adapter = PhotoPagerAdapter(mediaItems) {
            toggleToolbarsVisibility()
        }

        binding.viewPager.adapter = adapter
        binding.viewPager.setCurrentItem(currentPosition, false)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                adapter.pauseAllVideos()
                updateHeaderAndFavoriteState()
            }
        })

        updateHeaderAndFavoriteState()
    }

    private fun setupSwipeDismiss() {
        // Supply zoom state so dragging inside zoomed photos doesn't trigger dismiss
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

        // Format Date and Time
        val parts = item.date.split("·").map { it.trim() }
        val dateText = if (parts.isNotEmpty()) parts[0] else item.date
        val timeText = if (parts.size > 1) parts[1] else "10:20 am"

        binding.tvViewerDate.text = dateText
        binding.tvViewerTime.text = timeText

        // Update star favorite icon
        if (item.isFavorite) {
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

        // Tapping Date/Time header opens EXIF details bottom sheet
        binding.layoutDateHeader.setOnClickListener {
            showPhotoInfoSheet()
        }

        // Favorites Star Toggle
        binding.btnFavoriteStar.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            val isFav = MediaRepository.toggleFavorite(item.id)
            item.isFavorite = isFav
            updateHeaderAndFavoriteState()
            val msg = if (isFav) "Added to Favorites" else "Removed from Favorites"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 3-Dots Overflow Menu: About, Google Lens, Copy, Delete
        binding.btnMoreMenu.setOnClickListener { view ->
            showOverflowMenu(view)
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_ABOUT, 0, "About")
        popup.menu.add(0, MENU_COPY, 1, "Copy")
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
        PhotoInfoBottomSheet.newInstance(item.title, item.date)
            .show(supportFragmentManager, PhotoInfoBottomSheet.TAG)
    }

    private fun launchGoogleLens() {
        val item = mediaItems.getOrNull(currentPosition) ?: return
        try {
            // Attempt Google Lens package intent
            val lensIntent = Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.google.android.apps.lens")
            }
            startActivity(lensIntent)
        } catch (e: Exception) {
            // Fallback: visual search web intent
            val fallbackIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://lens.google.com/upload")
            )
            startActivity(fallbackIntent)
        }
    }

    private fun copyImageToClipboard() {
        val item = mediaItems.getOrNull(currentPosition) ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Photo Title", "${item.title} (${item.date}) · Pi Photos")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Image details copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun setupBottomBarActions() {
        // Share
        binding.btnShare.setOnClickListener {
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            val mediaType = if (item.isVideo) "video" else "photo"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Sharing $mediaType '${item.title}'"
                )
            }
            startActivity(Intent.createChooser(shareIntent, "Share $mediaType"))
        }

        // Edit (Phone-only, no server-side transcoding)
        binding.btnEdit.setOnClickListener {
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            Toast.makeText(this, "Opening device photo editor for '${item.title}' (phone-only)", Toast.LENGTH_SHORT).show()
        }

        // Add to Album
        binding.btnAddToAlbum.setOnClickListener {
            val item = mediaItems.getOrNull(currentPosition) ?: return@setOnClickListener
            AddToAlbumBottomSheet { chosenAlbum ->
                item.category = chosenAlbum
                Toast.makeText(this, "Added to album: $chosenAlbum", Toast.LENGTH_SHORT).show()
            }.show(supportFragmentManager, AddToAlbumBottomSheet.TAG)
        }

        // Bin / Delete
        binding.btnBin.setOnClickListener {
            confirmDeleteCurrentPhoto()
        }
    }

    private fun confirmDeleteCurrentPhoto() {
        val item = mediaItems.getOrNull(currentPosition) ?: return
        val type = if (item.isVideo) "video" else "item"
        AlertDialog.Builder(this)
            .setTitle("Move to Trash?")
            .setMessage("This $type will be moved to Trash and permanently deleted after 30 days.")
            .setPositiveButton("Move to Trash") { _, _ ->
                MediaRepository.moveToTrash(item.id)
                Toast.makeText(this, "Moved to Trash", Toast.LENGTH_SHORT).show()

                mediaItems.removeAt(currentPosition)
                if (mediaItems.isEmpty()) {
                    closeViewerInstant()
                } else {
                    adapter.notifyItemRemoved(currentPosition)
                    currentPosition = currentPosition.coerceAtMost(mediaItems.size - 1)
                    binding.viewPager.setCurrentItem(currentPosition, false)
                    updateHeaderAndFavoriteState()
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
        overridePendingTransition(R.anim.viewer_enter_fade, R.anim.viewer_exit_fade)
    }

    private fun toggleToolbarsVisibility() {
        areToolbarsVisible = !areToolbarsVisible
        val targetAlpha = if (areToolbarsVisible) 1.0f else 0.0f
        val duration = 200L

        binding.topBar.animate().alpha(targetAlpha).setDuration(duration).start()
        binding.bottomBar.animate().alpha(targetAlpha).setDuration(duration).start()
        adapter.setControlsVisibility(areToolbarsVisible)
    }

    override fun onPause() {
        super.onPause()
        if (::adapter.isInitialized) {
            adapter.pauseAllVideos()
        }
    }

    companion object {
        const val EXTRA_PHOTO_ID = "extra_photo_id"
        const val EXTRA_PHOTO_RES = "extra_photo_res"
        const val EXTRA_PHOTO_TITLE = "extra_photo_title"
        const val EXTRA_PHOTO_DATE = "extra_photo_date"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_IS_VIDEO = "extra_is_video"
        const val EXTRA_CONTENT_URI = "extra_content_uri"

        private const val MENU_ABOUT = 101
        private const val MENU_LENS = 102
        private const val MENU_COPY = 103
        private const val MENU_DELETE = 104

        fun start(
            context: Context,
            photoId: String,
            drawableRes: Int = 0,
            title: String = "Photo",
            date: String = "1 Nov · 10:20 am",
            category: String = "Recents",
            isVideo: Boolean = false,
            contentUri: String? = null
        ) {
            val intent = Intent(context, PhotoViewerActivity::class.java).apply {
                putExtra(EXTRA_PHOTO_ID, photoId)
                putExtra(EXTRA_PHOTO_RES, drawableRes)
                putExtra(EXTRA_PHOTO_TITLE, title)
                putExtra(EXTRA_PHOTO_DATE, date)
                putExtra(EXTRA_CATEGORY, category)
                putExtra(EXTRA_IS_VIDEO, isVideo)
                putExtra(EXTRA_CONTENT_URI, contentUri)
            }
            context.startActivity(intent)
        }
    }
}
