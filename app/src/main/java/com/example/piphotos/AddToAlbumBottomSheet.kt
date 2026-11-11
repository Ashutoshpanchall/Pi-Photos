package com.example.piphotos

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.BottomSheetAddToAlbumBinding
import com.example.piphotos.db.AlbumsRepository
import com.example.piphotos.db.RoomAlbum
import com.example.piphotos.media.LocalMediaIndex
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class AddToAlbumBottomSheet(
    private val itemIds: List<Long> = emptyList(),
    private val onAlbumChosen: ((albumName: String) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddToAlbumBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddToAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCreateNewAlbumInSheet.setOnClickListener {
            showCreateNewAlbumDialog()
        }

        populateAlbums()
    }

    private fun populateAlbums() {
        val ctx = context ?: return
        binding.layoutAlbumsList.removeAllViews()
        val repo = AlbumsRepository(ctx)

        viewLifecycleOwner.lifecycleScope.launch {
            val albums = repo.getAllAlbums()
            if (albums.isEmpty()) {
                val emptyTv = TextView(ctx).apply {
                    text = "No custom albums yet. Create one above!"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    val p = (16 * resources.displayMetrics.density).toInt()
                    setPadding(p, p, p, p)
                }
                binding.layoutAlbumsList.addView(emptyTv)
                return@launch
            }
            albums.forEach { album ->
                val row = createAlbumRow(ctx, album) {
                    if (itemIds.isNotEmpty()) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val index = LocalMediaIndex.getInstance(ctx)
                            val itemsToAdd = itemIds.mapNotNull { index.getItem(it) }
                            repo.addPhotos(album.id, itemsToAdd)
                            onAlbumChosen?.invoke(album.name)
                            dismiss()
                        }
                    } else {
                        onAlbumChosen?.invoke(album.name)
                        dismiss()
                    }
                }
                binding.layoutAlbumsList.addView(row)
            }
        }
    }

    private fun createAlbumRow(context: Context, album: RoomAlbum, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * context.resources.displayMetrics.density).toInt()
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (24 * context.resources.displayMetrics.density).toInt(),
                (24 * context.resources.displayMetrics.density).toInt()
            ).apply {
                marginEnd = (14 * context.resources.displayMetrics.density).toInt()
            }
            setImageResource(R.drawable.ic_nav_albums)
            setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
        }

        val textContainer = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
        }

        val tvTitle = TextView(context).apply {
            text = album.name
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val tvSubtitle = TextView(context).apply {
            text = "${album.itemCount} items"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }

        textContainer.addView(tvTitle)
        textContainer.addView(tvSubtitle)

        val chevron = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (18 * context.resources.displayMetrics.density).toInt(),
                (18 * context.resources.displayMetrics.density).toInt()
            )
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(ContextCompat.getColor(context, R.color.text_tertiary))
        }

        row.addView(icon)
        row.addView(textContainer)
        row.addView(chevron)

        return row
    }

    private fun showCreateNewAlbumDialog() {
        val input = EditText(requireContext()).apply {
            hint = "Album Name (e.g. Vacation, Family)"
            setSingleLine(true)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("New Album")
            .setMessage("Create a new photo album.")
            .setView(input)
            .setPositiveButton("Create & Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        AlbumsRepository(requireContext()).createAlbum(name, itemIds)
                        onAlbumChosen?.invoke(name)
                        dismiss()
                    }
                } else {
                    Toast.makeText(requireContext(), "Album name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddToAlbumBottomSheet"
    }
}
