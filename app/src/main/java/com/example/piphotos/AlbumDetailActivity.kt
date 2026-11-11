package com.example.piphotos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.ActivityAlbumDetailBinding
import com.example.piphotos.db.AlbumsRepository
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AlbumDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumDetailBinding
    private var currentCategory: String = "Recents"
    private var albumId: Long = -1L
    private var bucketId: Long = -1L
    private var currentItems: List<LocalMediaItem> = emptyList()

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            binding.timelineView.clearSelection()
            lifecycleScope.launch {
                LocalMediaIndex.getInstance(applicationContext).refreshDelta()
                loadData()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAlbumDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.albumDetailRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.albumTopBar,
            bottomBar = binding.layoutSelectionBar,
            scrollableView = null,
            extraTopDp = 0,
            extraBottomDp = 12
        )

        currentCategory = intent.getStringExtra(EXTRA_ALBUM_TITLE) ?: "Recents"
        albumId = intent.getLongExtra(EXTRA_ALBUM_ID, -1L)
        bucketId = intent.getLongExtra(EXTRA_BUCKET_ID, -1L)
        val subtitle = intent.getStringExtra(EXTRA_ALBUM_COUNT) ?: ""

        binding.tvAlbumDetailTitle.text = currentCategory
        binding.tvAlbumDetailCount.text = subtitle

        if (albumId != -1L) {
            binding.btnAlbumMenu.visibility = View.VISIBLE
            binding.btnAlbumMenu.setOnClickListener {
                showUserAlbumMenu()
            }
            binding.tvAlbumDetailTitle.setOnClickListener {
                showRenameAlbumDialog()
            }
        } else {
            binding.btnAlbumMenu.visibility = View.GONE
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.timelineView.isSelectionMode) {
                    exitSelectionMode()
                } else {
                    finish()
                }
            }
        })

        binding.btnAlbumBack.setOnClickListener {
            if (binding.timelineView.isSelectionMode) {
                exitSelectionMode()
            } else {
                finish()
            }
        }

        setupTimelineView()
        setupActions()
        setupLiveObservers()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun setupTimelineView() {
        binding.timelineView.onItemClick = { item ->
            PhotoViewerActivity.start(
                context = this,
                startingId = item.id,
                items = currentItems
            )
        }

        binding.timelineView.onSelectionChanged = { selectedIds ->
            val count = selectedIds.size
            if (count > 0) {
                binding.layoutSelectionBar.visibility = View.VISIBLE
                binding.tvSelectedCount.text = "$count selected"
                binding.btnSelect.text = "Cancel"

                val isTrash = currentCategory.equals("Trash", ignoreCase = true)
                binding.btnSelectionRestore.visibility = if (isTrash) View.VISIBLE else View.GONE
                binding.btnSelectionShare.visibility = if (isTrash) View.GONE else View.VISIBLE
            } else {
                binding.layoutSelectionBar.visibility = View.GONE
                binding.btnSelect.text = "Select"
            }
        }
    }

    private fun setupLiveObservers() {
        val index = LocalMediaIndex.getInstance(this)

        // Live favorite overlay updates
        lifecycleScope.launch {
            index.favoritesRepo.observeIds().collectLatest { favIds ->
                binding.timelineView.updateFavorites(favIds)
                if (currentCategory.equals("Favorites", ignoreCase = true)) {
                    val all = index.getAllItems()
                    val favItems = all.filter { favIds.contains(it.id) }
                    displayItems(favItems)
                }
            }
        }

        // Live updates for custom user album
        if (albumId != -1L) {
            val albumsRepo = AlbumsRepository(this)
            lifecycleScope.launch {
                albumsRepo.getItemsForAlbum(albumId).collectLatest { items ->
                    displayItems(items)
                }
            }
        }
    }

    private fun loadData() {
        val index = LocalMediaIndex.getInstance(this)

        lifecycleScope.launch {
            val isTrash = currentCategory.equals("Trash", ignoreCase = true)
            val localItems = when {
                albumId != -1L -> AlbumsRepository(this@AlbumDetailActivity).getItemsForAlbumList(albumId)
                isTrash -> {
                    val binItems = LocalDeleteHandler(this@AlbumDetailActivity).listBin()
                    binItems.map { b ->
                        LocalMediaItem(
                            id = b.id,
                            isVideo = b.isVideo,
                            mimeType = b.mimeType,
                            dateTakenMs = b.dateModifiedSec * 1000L,
                            dateAddedSec = b.dateModifiedSec,
                            dateModifiedSec = b.dateModifiedSec,
                            bucketId = -100L,
                            bucketName = "${b.daysRemaining} days left",
                            sizeBytes = b.sizeBytes
                        )
                    }
                }
                bucketId != -1L -> index.getItemsForFolder(bucketId)
                currentCategory.equals("Favorites", ignoreCase = true) -> index.getFavoriteItems()
                currentCategory.equals("Videos", ignoreCase = true) -> index.getVideoItems()
                currentCategory.equals("Recents", ignoreCase = true) -> index.getAllItems()
                else -> {
                    val folderMatches = index.getItemsForFolderName(currentCategory)
                    if (folderMatches.isNotEmpty()) folderMatches else emptyList()
                }
            }

            displayItems(localItems)
        }
    }

    private fun displayItems(items: List<LocalMediaItem>) {
        currentItems = items
        binding.timelineView.submitItems(items)

        val unit = if (currentCategory.equals("Videos", ignoreCase = true)) "videos" else "items"
        binding.tvAlbumDetailCount.text = "${items.size} $unit"

        val isTrash = currentCategory.equals("Trash", ignoreCase = true)
        binding.layoutTrashBanner.visibility = if (isTrash && items.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnEmptyTrash.visibility = if (isTrash && items.isNotEmpty()) View.VISIBLE else View.GONE
        binding.btnSelect.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        if (items.isEmpty()) {
            showEmptyState()
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.timelineView.visibility = View.VISIBLE
        }
    }

    private fun showEmptyState() {
        binding.timelineView.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.layoutSelectionBar.visibility = View.GONE

        when {
            albumId != -1L -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_nav_albums)
                binding.tvEmptyTitle.text = "This Album is Empty"
                binding.tvEmptySubtitle.text = "Add photos to this album from your photo collection."
                binding.btnEmptyAction.text = "Return to Albums"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
            currentCategory.equals("favorites", ignoreCase = true) -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_heart)
                binding.tvEmptyTitle.text = "No Favourites Yet"
                binding.tvEmptySubtitle.text = "Tap the star icon on any photo in your timeline to add it to your favourites."
                binding.btnEmptyAction.text = "Explore Photos"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
            currentCategory.equals("trash", ignoreCase = true) -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_trash)
                binding.tvEmptyTitle.text = "Trash is Empty"
                binding.tvEmptySubtitle.text = "Photos and videos moved to trash will appear here."
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
                binding.tvEmptyTitle.text = "This Folder is Empty"
                binding.tvEmptySubtitle.text = "No media found in this folder."
                binding.btnEmptyAction.text = "Return to Albums"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
        }
    }

    private fun showUserAlbumMenu() {
        val options = arrayOf("Rename Album", "Delete Album")
        AlertDialog.Builder(this)
            .setTitle(currentCategory)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameAlbumDialog()
                    1 -> showDeleteAlbumDialog()
                }
            }
            .show()
    }

    private fun showRenameAlbumDialog() {
        val input = EditText(this).apply {
            setText(currentCategory)
            setSelection(currentCategory.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Album")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentCategory) {
                    lifecycleScope.launch {
                        AlbumsRepository(this@AlbumDetailActivity).renameAlbum(albumId, newName)
                        currentCategory = newName
                        binding.tvAlbumDetailTitle.text = newName
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAlbumDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete Album \"$currentCategory\"?")
            .setMessage("Original photos will remain safe in your library. Only this album will be deleted.")
            .setPositiveButton("Delete Album") { _, _ ->
                lifecycleScope.launch {
                    AlbumsRepository(this@AlbumDetailActivity).deleteAlbum(albumId)
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupActions() {
        binding.btnSelect.setOnClickListener {
            if (binding.timelineView.isSelectionMode) {
                exitSelectionMode()
            } else {
                binding.timelineView.isSelectionMode = true
                binding.btnSelect.text = "Cancel"
            }
        }

        binding.btnSelectionShare.setOnClickListener {
            val selected = binding.timelineView.selectedIds
            val uris = ArrayList<Uri>()
            for (item in currentItems) {
                if (selected.contains(item.id)) {
                    uris.add(item.contentUri)
                }
            }
            if (uris.isNotEmpty()) {
                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share ${uris.size} photos"))
            }
            exitSelectionMode()
        }

        binding.btnSelectionDownload.visibility = View.GONE

        binding.btnEmptyTrash.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Empty Trash?")
                .setMessage("All items in Trash will be permanently deleted.")
                .setPositiveButton("Empty Trash") { _, _ ->
                    lifecycleScope.launch {
                        val deleteHandler = LocalDeleteHandler(this@AlbumDetailActivity)
                        when (val res = deleteHandler.emptyBin()) {
                            is DeleteResult.Success -> {
                                loadData()
                            }
                            is DeleteResult.RequiresConfirmation -> {
                                val request = IntentSenderRequest.Builder(res.intentSender).build()
                                intentSenderLauncher.launch(request)
                            }
                            is DeleteResult.Error -> {
                                Toast.makeText(this@AlbumDetailActivity, res.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnSelectionRestore.setOnClickListener {
            val selected = binding.timelineView.selectedIds.toList()
            if (selected.isEmpty()) return@setOnClickListener

            lifecycleScope.launch {
                val deleteHandler = LocalDeleteHandler(this@AlbumDetailActivity)
                when (val res = deleteHandler.restore(selected)) {
                    is DeleteResult.Success -> {
                        exitSelectionMode()
                        LocalMediaIndex.getInstance(applicationContext).refreshDelta()
                        loadData()
                    }
                    is DeleteResult.RequiresConfirmation -> {
                        val request = IntentSenderRequest.Builder(res.intentSender).build()
                        intentSenderLauncher.launch(request)
                    }
                    is DeleteResult.Error -> {
                        Toast.makeText(this@AlbumDetailActivity, res.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnSelectionDelete.setOnClickListener {
            val selected = binding.timelineView.selectedIds.toList()
            if (selected.isEmpty()) return@setOnClickListener

            if (albumId != -1L) {
                // In a user album, prompt whether to remove from album or move to trash
                val options = arrayOf("Remove from Album", "Move to Trash")
                AlertDialog.Builder(this)
                    .setTitle("Selected Photos")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> {
                                lifecycleScope.launch {
                                    AlbumsRepository(this@AlbumDetailActivity).removePhotos(albumId, selected)
                                    exitSelectionMode()
                                    loadData()
                                }
                            }
                            1 -> {
                                performTrashOrDelete(selected)
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                performTrashOrDelete(selected)
            }
        }
    }

    private fun performTrashOrDelete(selected: List<Long>) {
        val isTrash = currentCategory.equals("Trash", ignoreCase = true)
        lifecycleScope.launch {
            val deleteHandler = LocalDeleteHandler(this@AlbumDetailActivity)
            val result = if (isTrash) {
                deleteHandler.deletePermanently(selected)
            } else {
                deleteHandler.trash(selected)
            }

            when (result) {
                is DeleteResult.Success -> {
                    exitSelectionMode()
                    LocalMediaIndex.getInstance(applicationContext).refreshDelta()
                    loadData()
                }
                is DeleteResult.RequiresConfirmation -> {
                    val request = IntentSenderRequest.Builder(result.intentSender).build()
                    intentSenderLauncher.launch(request)
                }
                is DeleteResult.Error -> {
                    Toast.makeText(this@AlbumDetailActivity, result.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exitSelectionMode() {
        binding.timelineView.clearSelection()
        binding.btnSelect.text = "Select"
        binding.layoutSelectionBar.visibility = View.GONE
    }

    companion object {
        const val EXTRA_ALBUM_TITLE = "extra_album_title"
        const val EXTRA_ALBUM_COUNT = "extra_album_count"
        const val EXTRA_BUCKET_ID = "extra_bucket_id"
        const val EXTRA_ALBUM_ID = "extra_album_id"

        fun start(
            context: Context,
            title: String,
            count: String,
            bucketId: Long = -1L,
            albumId: Long = -1L
        ) {
            val intent = Intent(context, AlbumDetailActivity::class.java).apply {
                putExtra(EXTRA_ALBUM_TITLE, title)
                putExtra(EXTRA_ALBUM_COUNT, count)
                if (bucketId != -1L) {
                    putExtra(EXTRA_BUCKET_ID, bucketId)
                }
                if (albumId != -1L) {
                    putExtra(EXTRA_ALBUM_ID, albumId)
                }
            }
            context.startActivity(intent)
        }
    }
}
