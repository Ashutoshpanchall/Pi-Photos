package com.example.piphotos

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.piphotos.databinding.ActivityAlbumCreatorBinding

class AlbumCreatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlbumCreatorBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAlbumCreatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Robust edge-to-edge insets on root
        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.albumCreatorRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.creatorTopBar,
            extraTopDp = 0
        )

        binding.btnCancel.setOnClickListener {
            finish()
        }

        binding.btnSaveAlbum.setOnClickListener {
            val title = binding.etAlbumTitle.text.toString().trim()
            if (title.isEmpty()) {
                Toast.makeText(this, "Please enter an album title", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MediaRepository.addCustomAlbum(title)
            Toast.makeText(this, "Album \"$title\" created!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
