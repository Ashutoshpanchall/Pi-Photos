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
import com.example.piphotos.databinding.BottomSheetAddToAlbumBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AddToAlbumBottomSheet(
    private val onAlbumChosen: (albumName: String) -> Unit
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
        binding.layoutAlbumsList.removeAllViews()
        val albums = MediaRepository.getCustomAlbums()

        albums.forEach { albumName ->
            val row = createAlbumRow(requireContext(), albumName) {
                onAlbumChosen(albumName)
                dismiss()
            }
            binding.layoutAlbumsList.addView(row)
        }
    }

    private fun createAlbumRow(context: Context, albumName: String, onClick: () -> Unit): View {
        val count = MediaRepository.getMediaForCategory(albumName).size
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
            text = albumName
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val tvSubtitle = TextView(context).apply {
            text = "$count photos · Pi SSD"
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
            .setMessage("Create a new encrypted photo album on your Pi Zero 2 W.")
            .setView(input)
            .setPositiveButton("Create & Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    MediaRepository.addCustomAlbum(name)
                    onAlbumChosen(name)
                    dismiss()
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

