package com.example.piphotos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.ActivityAlbumDetailBinding
import com.example.piphotos.DeleteResult
import com.example.piphotos.LocalDeleteHandler
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import kotlinx.coroutines.launch

class AlbumDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumDetailBinding
    private var currentCategory: String = "Recents"
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
        val subtitle = intent.getStringExtra(EXTRA_ALBUM_COUNT) ?: ""

        binding.tvAlbumDetailTitle.text = currentCategory
        binding.tvAlbumDetailCount.text = subtitle

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
                photoId = item.id.toString(),
                drawableRes = 0,
                title = "${if (item.isVideo) "VID" else "IMG"}_${item.id}",
                date = "",
                category = currentCategory,
                isVideo = item.isVideo,
                contentUri = item.contentUri.toString()
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

            currentItems = localItems
            binding.timelineView.submitItems(localItems)

            val unit = if (currentCategory.equals("Videos", ignoreCase = true)) "videos" else "photos"
            binding.tvAlbumDetailCount.text = "${localItems.size} $unit"

            val isTrash = currentCategory.equals("Trash", ignoreCase = true)
            binding.layoutTrashBanner.visibility = if (isTrash && localItems.isNotEmpty()) View.VISIBLE else View.GONE
            binding.btnEmptyTrash.visibility = if (isTrash && localItems.isNotEmpty()) View.VISIBLE else View.GONE
            binding.btnSelect.visibility = if (localItems.isEmpty()) View.GONE else View.VISIBLE

            if (localItems.isEmpty()) {
                showEmptyState()
            } else {
                binding.layoutEmptyState.visibility = View.GONE
                binding.timelineView.visibility = View.VISIBLE
            }
        }
    }

    private fun showEmptyState() {
        binding.timelineView.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.layoutSelectionBar.visibility = View.GONE

        when {
            currentCategory.contains("Vault", ignoreCase = true) -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_lock)
                binding.tvEmptyTitle.text = "Encrypted Vault is Empty"
                binding.tvEmptySubtitle.text = "Items added to Encrypted Vault are encrypted and isolated from your gallery."
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
                binding.tvEmptyTitle.text = "This Album is Empty"
                binding.tvEmptySubtitle.text = "Add photos to this album from your photo collection."
                binding.btnEmptyAction.text = "Return to Albums"
                binding.btnEmptyAction.setOnClickListener { finish() }
            }
        }
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

        // Hide fake download button if present (photos are already on device)
        binding.btnSelectionDownload.visibility = View.GONE

        binding.btnSelectionDelete.setOnClickListener {
            val selected = binding.timelineView.selectedIds.toList()
            if (selected.isEmpty()) return@setOnClickListener

            lifecycleScope.launch {
                val deleteHandler = LocalDeleteHandler(this@AlbumDetailActivity)
                when (val result = deleteHandler.trash(selected)) {
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
