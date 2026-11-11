package com.example.piphotos

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.piphotos.databinding.ActivityAlbumCreatorBinding
import com.example.piphotos.db.AlbumsRepository
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import kotlinx.coroutines.launch

class AlbumCreatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumCreatorBinding
    private val selectedPhotoIds = mutableSetOf<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAlbumCreatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.albumCreatorRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.creatorTopBar,
            extraTopDp = 0
        )

        binding.btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        binding.btnSelectCover.setOnClickListener {
            showPhotoPickerDialog()
        }

        binding.btnSaveAlbum.setOnClickListener {
            val title = binding.etAlbumTitle.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(this, "Please enter an album title", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                AlbumsRepository(this@AlbumCreatorActivity).createAlbum(title, selectedPhotoIds.toList())
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    private fun showPhotoPickerDialog() {
        val allItems = LocalMediaIndex.getInstance(this).getAllItems()
        if (allItems.isEmpty()) {
            Toast.makeText(this, "No photos available to add", Toast.LENGTH_SHORT).show()
            return
        }

        val tempSelected = mutableSetOf<Long>().apply { addAll(selectedPhotoIds) }
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_photo_picker, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.rvPickerGrid)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        val adapter = PhotoPickerAdapter(allItems, tempSelected)
        recyclerView.adapter = adapter

        AlertDialog.Builder(this)
            .setTitle("Select Photos")
            .setView(dialogView)
            .setPositiveButton("Done") { _, _ ->
                selectedPhotoIds.clear()
                selectedPhotoIds.addAll(tempSelected)
                updateCoverAndCount(allItems)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCoverAndCount(allItems: List<LocalMediaItem>) {
        if (selectedPhotoIds.isEmpty()) {
            binding.ivCoverPreview.setImageDrawable(null)
            binding.tvSelectedPhotosBadge.text = "Select Photos"
        } else {
            val firstId = selectedPhotoIds.first()
            val coverItem = allItems.find { it.id == firstId }
            if (coverItem != null) {
                binding.ivCoverPreview.load(coverItem.contentUri) {
                    crossfade(true)
                    size(600)
                }
            }
            binding.tvSelectedPhotosBadge.text = "${selectedPhotoIds.size} selected"
        }
    }

    private class PhotoPickerAdapter(
        private val items: List<LocalMediaItem>,
        private val selectedIds: MutableSet<Long>
    ) : RecyclerView.Adapter<PhotoPickerAdapter.PickerViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PickerViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_picker_tile, parent, false)
            return PickerViewHolder(view)
        }

        override fun onBindViewHolder(holder: PickerViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class PickerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivPhoto: ImageView = itemView.findViewById(R.id.ivPickerPhoto)
            private val ivCheck: ImageView = itemView.findViewById(R.id.ivPickerCheck)

            fun bind(item: LocalMediaItem) {
                ivPhoto.load(item.contentUri) {
                    size(250)
                    crossfade(true)
                }
                val isSelected = selectedIds.contains(item.id)
                ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

                itemView.setOnClickListener {
                    if (selectedIds.contains(item.id)) {
                        selectedIds.remove(item.id)
                        ivCheck.visibility = View.GONE
                    } else {
                        selectedIds.add(item.id)
                        ivCheck.visibility = View.VISIBLE
                    }
                }
            }
        }
    }
}
