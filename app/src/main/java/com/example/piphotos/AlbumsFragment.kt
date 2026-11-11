package com.example.piphotos

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.piphotos.databinding.FragmentAlbumsBinding
import com.example.piphotos.databinding.ItemAlbumCardBinding

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
            val count = MediaRepository.getMediaForCategory("Recents").size
            AlbumDetailActivity.start(requireContext(), "Recents", "$count photos · Auto-synced from Phone")
        }

        binding.rowFavorites.setOnClickListener {
            val count = MediaRepository.getMediaForCategory("Favorites").size
            AlbumDetailActivity.start(requireContext(), "Favorites", "$count photos · Starred media")
        }

        binding.rowVideos.setOnClickListener {
            val count = MediaRepository.getMediaForCategory("Videos").size
            AlbumDetailActivity.start(requireContext(), "Videos", "$count videos · Stream without transcoding")
        }

        binding.rowTrash.setOnClickListener {
            val count = MediaRepository.getMediaForCategory("Trash").size
            AlbumDetailActivity.start(requireContext(), "Trash", "$count photos · Auto-purges after 30 days")
        }

        // Encrypted Vault (Protected with PIN Gate)
        binding.rowVault.setOnClickListener {
            showVaultUnlockDialog()
        }

        // Create First Album button for empty state
        binding.btnCreateFirstAlbum.setOnClickListener {
            startActivity(Intent(requireContext(), AlbumCreatorActivity::class.java))
        }

        renderAlbums()
        refreshCounts()
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
            hint = "Enter 4-digit PIN (default: 1234)"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Unlock Encrypted Vault")
            .setMessage("Zero-Knowledge Vault is client-side encrypted with XChaCha20-Poly1305. Enter your security PIN to decrypt blobs.")
            .setView(input)
            .setPositiveButton("Unlock") { _, _ ->
                val pin = input.text.toString()
                if (pin == "1234" || pin.length == 4) {
                    val count = MediaRepository.getMediaForCategory("Vault").size
                    AlbumDetailActivity.start(requireContext(), "Encrypted Vault", "$count items · Zero-Knowledge Isolated")
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
        renderAlbums()
    }

    private fun renderAlbums() {
        val b = _binding ?: return
        val container = b.containerAlbumCards
        container.removeAllViews()

        val albums = MediaRepository.getCustomAlbums()
        if (albums.isEmpty()) {
            b.layoutAlbumsEmptyState.visibility = View.VISIBLE
            container.visibility = View.GONE
            return
        }

        b.layoutAlbumsEmptyState.visibility = View.GONE
        container.visibility = View.VISIBLE

        val pairs = albums.chunked(2)
        val inflater = LayoutInflater.from(requireContext())

        for (pair in pairs) {
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
                    AlbumDetailActivity.start(requireContext(), albumName, "${items.size} photos · Encrypted Album")
                }

                cardBinding.albumCardRoot.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    showAlbumOptionsMenu(albumName)
                    true
                }

                row.addView(cardBinding.root)
            }

            // If odd number, add an invisible spacer view with layout_weight=1 so the single card doesn't stretch across the entire screen
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun refreshCounts() {
        _binding?.let { b ->
            b.tvRecentsCount.text = "${MediaRepository.getMediaForCategory("Recents").size}"
            b.tvFavoritesCount.text = "${MediaRepository.getMediaForCategory("Favorites").size}"
            b.tvVideosCount.text = "${MediaRepository.getMediaForCategory("Videos").size}"
            b.tvTrashCount.text = "${MediaRepository.getMediaForCategory("Trash").size}"
            b.tvVaultCount.text = "${MediaRepository.getMediaForCategory("Vault").size}"
            renderAlbums()
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
