package com.example.piphotos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.example.piphotos.databinding.ActivityAlbumDetailBinding
import com.example.piphotos.media.LocalMediaIndex
import kotlinx.coroutines.launch

class AlbumDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumDetailBinding
    private lateinit var adapter: MediaGridAdapter
    private var currentCategory: String = "Recents"
    private var mediaItems: MutableList<MediaItem> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAlbumDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Robust edge-to-edge insets applied on root view with synchronous status bar fallback
        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.albumDetailRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.albumTopBar,
            bottomBar = binding.layoutSelectionBar,
            scrollableView = binding.rvMediaGrid,
            extraTopDp = 0,
            extraBottomDp = 12
        )

        currentCategory = intent.getStringExtra(EXTRA_ALBUM_TITLE) ?: "Recents"
        val subtitle = intent.getStringExtra(EXTRA_ALBUM_COUNT) ?: "Media Vault"

        binding.tvAlbumDetailTitle.text = currentCategory
        binding.tvAlbumDetailCount.text = subtitle

        binding.btnAlbumBack.setOnClickListener {
            if (adapter.isSelectionMode) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        setupRecyclerView()
        loadData()
        setupActions()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun setupRecyclerView() {
        adapter = MediaGridAdapter(
            items = mediaItems,
            onPhotoClick = { item ->
                PhotoViewerActivity.start(
                    context = this,
                    photoId = item.id,
                    drawableRes = item.drawableRes,
                    title = item.title,
                    date = item.date,
                    category = currentCategory,
                    contentUri = item.contentUri
                )
            },
            onVideoClick = { item ->
                PhotoViewerActivity.start(
                    context = this,
                    photoId = item.id,
                    drawableRes = item.drawableRes,
                    title = item.title,
                    date = item.date,
                    category = currentCategory,
                    isVideo = true,
                    contentUri = item.contentUri
                )
            },
            onSelectionChanged = { count ->
                if (count > 0) {
                    binding.layoutSelectionBar.visibility = View.VISIBLE
                    binding.tvSelectedCount.text = "$count selected"

                    val isTrash = currentCategory.equals("Trash", ignoreCase = true)
                    binding.btnSelectionRestore.visibility = if (isTrash) View.VISIBLE else View.GONE
                    binding.btnSelectionShare.visibility = if (isTrash) View.GONE else View.VISIBLE
                } else {
                    binding.layoutSelectionBar.visibility = View.GONE
                }
            }
        )

        binding.rvMediaGrid.layoutManager = GridLayoutManager(this, 3)
        binding.rvMediaGrid.adapter = adapter
    }

    private fun loadData() {
        val index = LocalMediaIndex.getInstance(this)
        val bucketId = intent.getLongExtra(EXTRA_BUCKET_ID, -1L)

        lifecycleScope.launch {
            val localItems = when {
                bucketId != -1L -> index.getItemsForFolder(bucketId)
                currentCategory.equals("Favorites", ignoreCase = true) -> index.getFavoriteItems()
                currentCategory.equals("Videos", ignoreCase = true) -> index.getVideoItems()
                currentCategory.equals("Recents", ignoreCase = true) -> index.getAllItems()
                currentCategory.equals("Trash", ignoreCase = true) -> emptyList()
                else -> {
                    val folderMatches = index.getItemsForFolderName(currentCategory)
                    if (folderMatches.isNotEmpty()) folderMatches else emptyList()
                }
            }

            val favIds = index.favoritesRepo.getAllFavoriteIds()
            mediaItems = if (localItems.isNotEmpty()) {
                localItems.map { index.toUiMediaItem(it, isFav = favIds.contains(it.id)) }.toMutableList()
            } else {
                MediaRepository.getMediaForCategory(currentCategory).toMutableList()
            }

            adapter.updateData(mediaItems)

            val unit = if (currentCategory.equals("Videos", ignoreCase = true)) "videos" else "items"
            binding.tvAlbumDetailCount.text = "${mediaItems.size} $unit"

            val isTrash = currentCategory.equals("Trash", ignoreCase = true)
            binding.layoutTrashBanner.visibility = if (isTrash && mediaItems.isNotEmpty()) View.VISIBLE else View.GONE
            binding.btnEmptyTrash.visibility = if (isTrash && mediaItems.isNotEmpty()) View.VISIBLE else View.GONE
            binding.btnSelect.visibility = if (isTrash && mediaItems.isEmpty()) View.GONE else View.VISIBLE

            if (mediaItems.isEmpty()) {
                showEmptyState()
            } else {
                binding.layoutEmptyState.visibility = View.GONE
                binding.rvMediaGrid.visibility = View.VISIBLE
            }
        }
    }

    private fun showEmptyState() {
        binding.rvMediaGrid.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.layoutSelectionBar.visibility = View.GONE

        when {
            currentCategory.contains("Vault", ignoreCase = true) -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_lock)
                binding.tvEmptyTitle.text = "Encrypted Vault is Empty"
                binding.tvEmptySubtitle.text = "Items added to Encrypted Vault are encrypted with XChaCha20-Poly1305 and isolated from your gallery."
                binding.btnEmptyAction.text = "Return to Albums"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
            currentCategory.equals("favorites", ignoreCase = true) -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_heart)
                binding.tvEmptyTitle.text = "No Favorites Yet"
                binding.tvEmptySubtitle.text = "Tap ♡ on any photo in your timeline to add it to your favorites."
                binding.btnEmptyAction.text = "Explore Photos"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
            currentCategory.equals("trash", ignoreCase = true) -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_trash)
                binding.tvEmptyTitle.text = "Trash is Empty"
                binding.tvEmptySubtitle.text = "Photos and videos moved to trash will appear here for 30 days before permanent deletion."
                binding.btnEmptyAction.text = "Return to Albums"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
            currentCategory.equals("videos", ignoreCase = true) -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_video_cam)
                binding.tvEmptyTitle.text = "No Videos Found"
                binding.tvEmptySubtitle.text = "Videos on your device will appear here."
                binding.btnEmptyAction.text = "Return to Albums"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
            else -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_nav_albums)
                binding.tvEmptyTitle.text = "This Album is Empty"
                binding.tvEmptySubtitle.text = "Add photos to this album using the Select button from any photo collection."
                binding.btnEmptyAction.text = "Add Photos"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
        }
    }

    private fun setupActions() {
        binding.btnSelect.setOnClickListener {
            if (adapter.isSelectionMode) {
                exitSelectionMode()
            } else {
                adapter.isSelectionMode = true
                binding.btnSelect.text = "Cancel"
            }
        }

        binding.btnEmptyTrash.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Empty Trash?")
                .setMessage("All items in Trash will be permanently deleted. This action cannot be undone.")
                .setPositiveButton("Empty Trash") { _, _ ->
                    val purged = MediaRepository.emptyTrash()
                    Toast.makeText(this, "Purged $purged items permanently", Toast.LENGTH_SHORT).show()
                    loadData()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnSelectionRestore.setOnClickListener {
            val count = adapter.selectedIds.size
            adapter.selectedIds.forEach { id ->
                MediaRepository.restoreFromTrash(id)
            }
            Toast.makeText(this, "Restored $count items to gallery", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
            loadData()
        }

        binding.btnSelectionShare.setOnClickListener {
            val count = adapter.selectedIds.size
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Sharing $count items from Pi Photos")
            }
            startActivity(Intent.createChooser(shareIntent, "Share"))
            exitSelectionMode()
        }

        binding.btnSelectionDownload.setOnClickListener {
            val count = adapter.selectedIds.size
            Toast.makeText(this, "Saved $count photos to device gallery", Toast.LENGTH_SHORT).show()
            exitSelectionMode()
        }

        binding.btnSelectionDelete.setOnClickListener {
            val count = adapter.selectedIds.size
            val isTrash = currentCategory.equals("Trash", ignoreCase = true)
            val title = if (isTrash) "Permanently Delete $count items?" else "Move $count items to Trash?"
            val msg = if (isTrash) {
                "These items will be permanently erased. This cannot be undone."
            } else {
                "Selected items will be moved to Trash."
            }
            val confirmBtn = if (isTrash) "Delete Permanently" else "Move to Trash"

            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(confirmBtn) { _, _ ->
                    adapter.selectedIds.forEach { id ->
                        if (isTrash) {
                            MediaRepository.deletePermanently(id)
                        } else {
                            MediaRepository.moveToTrash(id)
                        }
                    }
                    val feedback = if (isTrash) "Permanently deleted $count items" else "Moved $count items to Trash"
                    Toast.makeText(this, feedback, Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    loadData()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun exitSelectionMode() {
        adapter.isSelectionMode = false
        binding.btnSelect.text = "Select"
        binding.layoutSelectionBar.visibility = View.GONE
    }

    override fun onBackPressed() {
        if (adapter.isSelectionMode) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        const val EXTRA_ALBUM_TITLE = "extra_album_title"
        const val EXTRA_ALBUM_COUNT = "extra_album_count"
        const val EXTRA_BUCKET_ID = "extra_bucket_id"

        fun start(context: Context, title: String, count: String, bucketId: Long = -1L) {
            val intent = Intent(context, AlbumDetailActivity::class.java).apply {
                putExtra(EXTRA_ALBUM_TITLE, title)
                putExtra(EXTRA_ALBUM_COUNT, count)
                if (bucketId != -1L) {
                    putExtra(EXTRA_BUCKET_ID, bucketId)
                }
            }
            context.startActivity(intent)
        }
    }
}
