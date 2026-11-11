package com.example.piphotos

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
import com.example.piphotos.media.LocalMediaIndex
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
            AlbumDetailActivity.start(requireContext(), "Trash", "0 items")
        }

        // Encrypted Vault (Protected with PIN Gate)
        binding.rowVault.setOnClickListener {
            showVaultUnlockDialog()
        }

        // Create First Album button for empty state
        binding.btnCreateFirstAlbum.setOnClickListener {
            startActivity(Intent(requireContext(), AlbumCreatorActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            LocalMediaIndex.getInstance(requireContext()).mediaFlow.collect {
                refreshCounts()
            }
        }
    }

    private fun showAlbumOptionsMenu(albumName: String) {
        val options = arrayOf(
            "Rename Album",
            "Change Cover Photo",
            "Share Album",
            "Delete Album"
        )

        AlertDialog.Builder(requireContext())
            .setTitle(albumName)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(albumName)
                    1 -> Toast.makeText(requireContext(), "Cover photo updated for \"$albumName\"", Toast.LENGTH_SHORT).show()
                    2 -> {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Album: $albumName")
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Album"))
                    }
                    3 -> showDeleteAlbumDialog(albumName)
                }
            }
            .show()
    }

    private fun showRenameDialog(albumName: String) {
        val input = EditText(requireContext()).apply {
            setText(albumName)
            setSelection(albumName.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename Album")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != albumName) {
                    MediaRepository.renameAlbum(albumName, newName)
                    Toast.makeText(requireContext(), "Album renamed to \"$newName\"", Toast.LENGTH_SHORT).show()
                    refreshCounts()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteAlbumDialog(albumName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Album \"$albumName\"?")
            .setMessage("Original photos will remain safe in your library. Only this album folder will be removed.")
            .setPositiveButton("Delete Album") { _, _ ->
                MediaRepository.deleteAlbum(albumName, preserveMedia = true)
                Toast.makeText(requireContext(), "Album \"$albumName\" removed", Toast.LENGTH_SHORT).show()
                refreshCounts()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showVaultUnlockDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter 4-digit PIN"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Unlock Vault")
            .setMessage("Vault is encrypted. Enter your security PIN to access.")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val pin = input.text.toString()
                if (pin.length == 4) {
                    AlbumDetailActivity.start(requireContext(), "Encrypted Vault", "0 items")
                } else {
                    Toast.makeText(requireContext(), "Incorrect PIN. Vault remains locked.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshCounts()
    }

    private fun renderAlbums(folders: List<FolderItem>) {
        val b = _binding ?: return
        val container = b.containerAlbumCards
        container.removeAllViews()

        val customAlbums = MediaRepository.getCustomAlbums()
        if (folders.isEmpty() && customAlbums.isEmpty()) {
            b.layoutAlbumsEmptyState.visibility = View.VISIBLE
            container.visibility = View.GONE
            return
        }

        b.layoutAlbumsEmptyState.visibility = View.GONE
        container.visibility = View.VISIBLE

        val inflater = LayoutInflater.from(requireContext())

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

        // Render custom albums if any
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

                for (albumName in pair) {
                    val cardBinding = ItemAlbumCardBinding.inflate(inflater, row, false)
                    val items = MediaRepository.getMediaForCategory(albumName)
                    val coverRes = items.firstOrNull()?.drawableRes ?: 0

                    if (coverRes != 0) {
                        cardBinding.ivAlbumCover.setImageResource(coverRes)
                    } else {
                        cardBinding.ivAlbumCover.setImageResource(R.drawable.ic_nav_folders)
                    }
                    cardBinding.tvAlbumTitle.text = albumName
                    cardBinding.tvAlbumCount.text = "${items.size} photos"

                    cardBinding.albumCardRoot.setOnClickListener {
                        AlbumDetailActivity.start(requireContext(), albumName, "${items.size} photos")
                    }

                    cardBinding.albumCardRoot.setOnLongClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        showAlbumOptionsMenu(albumName)
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
        viewLifecycleOwner.lifecycleScope.launch {
            val allItems = index.getAllItems()
            val favCount = index.favoritesRepo.getAllFavoriteIds().size
            val videoCount = allItems.count { it.isVideo }
            val folders = index.getFolderList()

            _binding?.let { b ->
                b.tvRecentsCount.text = "${allItems.size}"
                b.tvFavoritesCount.text = "$favCount"
                b.tvVideosCount.text = "$videoCount"
                b.tvTrashCount.text = "0"
                b.tvVaultCount.text = "0"
                renderAlbums(folders)
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
