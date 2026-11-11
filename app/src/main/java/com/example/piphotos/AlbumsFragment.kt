package com.example.piphotos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.example.piphotos.databinding.FragmentAlbumsBinding
import com.example.piphotos.databinding.ItemAlbumCardBinding
import com.example.piphotos.db.AlbumsRepository
import com.example.piphotos.db.RoomAlbum
import com.example.piphotos.media.LocalMediaIndex
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AlbumsFragment : Fragment() {

    private var _binding: FragmentAlbumsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlbumsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Hide Encrypted Vault row completely until dedicated Vault phase
        binding.rowVault.visibility = View.GONE
        binding.dividerVault.visibility = View.GONE

        // New Album Creator
        binding.btnNewAlbum.setOnClickListener {
            startActivity(Intent(requireContext(), AlbumCreatorActivity::class.java))
        }

        // Quick Category List with dynamic counts
        binding.rowRecents.setOnClickListener {
            val count = LocalMediaIndex.getInstance(requireContext()).getAllItems().size
            AlbumDetailActivity.start(requireContext(), "Recents", "$count items")
        }

        binding.rowFavorites.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val favCount = LocalMediaIndex.getInstance(requireContext()).favoritesRepo.getAllFavoriteIds().size
                AlbumDetailActivity.start(requireContext(), "Favorites", "$favCount items")
            }
        }

        binding.rowVideos.setOnClickListener {
            val count = LocalMediaIndex.getInstance(requireContext()).getVideoItems().size
            AlbumDetailActivity.start(requireContext(), "Videos", "$count videos")
        }

        binding.rowTrash.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val binCount = LocalDeleteHandler(requireContext()).getBinCount()
                AlbumDetailActivity.start(requireContext(), "Trash", "$binCount items")
            }
        }

        // Create First Album button for empty state
        binding.btnCreateFirstAlbum.setOnClickListener {
            startActivity(Intent(requireContext(), AlbumCreatorActivity::class.java))
        }

        // Live observations
        val index = LocalMediaIndex.getInstance(requireContext())
        val albumsRepo = AlbumsRepository(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            index.mediaFlow.collectLatest {
                refreshCounts()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            index.favoritesRepo.observeIds().collectLatest {
                refreshCounts()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            albumsRepo.observeAlbums().collectLatest {
                refreshCounts()
            }
        }
    }

    private fun showAlbumOptionsMenu(album: RoomAlbum) {
        val options = arrayOf(
            "Rename Album",
            "Delete Album"
        )

        AlertDialog.Builder(requireContext())
            .setTitle(album.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(album)
                    1 -> showDeleteAlbumDialog(album)
                }
            }
            .show()
    }

    private fun showRenameDialog(album: RoomAlbum) {
        val input = EditText(requireContext()).apply {
            setText(album.name)
            setSelection(album.name.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename Album")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != album.name) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        AlbumsRepository(requireContext()).renameAlbum(album.id, newName)
                        refreshCounts()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAlbumDialog(album: RoomAlbum) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Album \"${album.name}\"?")
            .setMessage("Original photos will remain safe in your library. Only this album will be removed.")
            .setPositiveButton("Delete Album") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    AlbumsRepository(requireContext()).deleteAlbum(album.id)
                    refreshCounts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshCounts()
    }

    private fun renderAlbums(folders: List<FolderItem>, customAlbums: List<RoomAlbum>) {
        val b = _binding ?: return
        val container = b.containerAlbumCards
        container.removeAllViews()

        if (folders.isEmpty() && customAlbums.isEmpty()) {
            b.layoutAlbumsEmptyState.visibility = View.VISIBLE
            container.visibility = View.GONE
            return
        }

        b.layoutAlbumsEmptyState.visibility = View.GONE
        container.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(requireContext())
        val index = LocalMediaIndex.getInstance(requireContext())

        // Render device folders
        val folderPairs = folders.chunked(2)
        for (pair in folderPairs) {
            val row = LinearLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(8)
                }
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpToPx(16), 0, dpToPx(16), 0)
            }

            for (folder in pair) {
                val cardBinding = ItemAlbumCardBinding.inflate(inflater, row, false)
                if (!folder.coverUri.isNullOrEmpty()) {
                    cardBinding.ivAlbumCover.scaleType = ImageView.ScaleType.CENTER_CROP
                    cardBinding.ivAlbumCover.load(Uri.parse(folder.coverUri)) {
                        crossfade(true)
                        error(R.drawable.ic_nav_folders)
                        fallback(R.drawable.ic_nav_folders)
                    }
                } else {
                    cardBinding.ivAlbumCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    cardBinding.ivAlbumCover.setImageResource(R.drawable.ic_nav_folders)
                }
                cardBinding.tvAlbumTitle.text = folder.name
                cardBinding.tvAlbumCount.text = "${folder.itemCount} items"

                cardBinding.albumCardRoot.setOnClickListener {
                    val bId = folder.bucketId.toLongOrNull() ?: -1L
                    AlbumDetailActivity.start(
                        requireContext(),
                        folder.name,
                        "${folder.itemCount} items",
                        bucketId = bId
                    )
                }

                row.addView(cardBinding.root)
            }

            if (pair.size == 1) {
                val spacer = View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f).apply {
                        setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                    }
                }
                row.addView(spacer)
            }

            container.addView(row)
        }

        // Render custom Room albums
        if (customAlbums.isNotEmpty()) {
            val customPairs = customAlbums.chunked(2)
            for (pair in customPairs) {
                val row = LinearLayout(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(8)
                    }
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dpToPx(16), 0, dpToPx(16), 0)
                }

                for (album in pair) {
                    val cardBinding = ItemAlbumCardBinding.inflate(inflater, row, false)
                    val coverItem = if (album.coverMediaStoreId != null) index.getItem(album.coverMediaStoreId) else null

                    if (coverItem != null) {
                        cardBinding.ivAlbumCover.scaleType = ImageView.ScaleType.CENTER_CROP
                        cardBinding.ivAlbumCover.load(coverItem.contentUri) {
                            crossfade(true)
                            error(R.drawable.ic_nav_albums)
                            fallback(R.drawable.ic_nav_albums)
                        }
                    } else {
                        cardBinding.ivAlbumCover.scaleType = ImageView.ScaleType.CENTER_INSIDE
                        cardBinding.ivAlbumCover.setImageResource(R.drawable.ic_nav_albums)
                    }
                    cardBinding.tvAlbumTitle.text = album.name
                    cardBinding.tvAlbumCount.text = "${album.itemCount} items"

                    cardBinding.albumCardRoot.setOnClickListener {
                        AlbumDetailActivity.start(
                            context = requireContext(),
                            title = album.name,
                            count = "${album.itemCount} items",
                            albumId = album.id
                        )
                    }

                    cardBinding.albumCardRoot.setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showAlbumOptionsMenu(album)
                        true
                    }

                    row.addView(cardBinding.root)
                }

                if (pair.size == 1) {
                    val spacer = View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(0, 0, 1.0f).apply {
                            setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                        }
                    }
                    row.addView(spacer)
                }

                container.addView(row)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun refreshCounts() {
        val ctx = context ?: return
        val index = LocalMediaIndex.getInstance(ctx)
        val albumsRepo = AlbumsRepository(ctx)

        viewLifecycleOwner.lifecycleScope.launch {
            val allItems = index.getAllItems()
            val favCount = index.favoritesRepo.getAllFavoriteIds().size
            val videoCount = allItems.count { it.isVideo }
            val folders = index.getFolderList()
            val trashCount = LocalDeleteHandler(ctx).getBinCount()
            val customAlbums = albumsRepo.getAllAlbums()

            _binding?.let { b ->
                b.tvRecentsCount.text = "${allItems.size}"
                b.tvFavoritesCount.text = "$favCount"
                b.tvVideosCount.text = "$videoCount"
                b.tvTrashCount.text = "$trashCount"
                renderAlbums(folders, customAlbums)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = AlbumsFragment()
    }
}
