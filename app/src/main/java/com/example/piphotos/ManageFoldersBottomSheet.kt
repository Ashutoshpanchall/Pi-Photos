package com.example.piphotos

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.example.piphotos.databinding.BottomSheetManageFoldersBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ManageFoldersBottomSheet(
    private val onFoldersUpdated: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetManageFoldersBinding? = null
    private val binding get() = _binding!!

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                // Ignore if unable to persist
            }
            val folderName = uri.lastPathSegment?.substringAfterLast(":") ?: "Custom Folder"
            saveCustomFolder(uri.toString(), folderName)
            Toast.makeText(requireContext(), "Added folder to sync: $folderName", Toast.LENGTH_SHORT).show()
            onFoldersUpdated()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetManageFoldersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bind initial states from BackupPreferences
        binding.switchCameraFolder.isChecked = BackupPreferences.isCameraFolderEnabled(requireContext())
        binding.switchScreenshotsFolder.isChecked = BackupPreferences.isScreenshotsFolderEnabled(requireContext())
        binding.switchWhatsAppFolder.isChecked = BackupPreferences.isWhatsAppFolderEnabled(requireContext())
        binding.switchDownloadsFolder.isChecked = BackupPreferences.isDownloadsFolderEnabled(requireContext())

        // Folder switches listeners
        binding.switchCameraFolder.setOnCheckedChangeListener { _, isChecked ->
            BackupPreferences.setCameraFolderEnabled(requireContext(), isChecked)
            onFoldersUpdated()
        }

        binding.switchScreenshotsFolder.setOnCheckedChangeListener { _, isChecked ->
            BackupPreferences.setScreenshotsFolderEnabled(requireContext(), isChecked)
            onFoldersUpdated()
        }

        binding.switchWhatsAppFolder.setOnCheckedChangeListener { _, isChecked ->
            BackupPreferences.setWhatsAppFolderEnabled(requireContext(), isChecked)
            onFoldersUpdated()
        }

        binding.switchDownloadsFolder.setOnCheckedChangeListener { _, isChecked ->
            BackupPreferences.setDownloadsFolderEnabled(requireContext(), isChecked)
            onFoldersUpdated()
        }

        renderCustomFolders()

        binding.btnAddCustomFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.btnDoneFolders.setOnClickListener {
            dismiss()
        }
    }

    private fun renderCustomFolders() {
        val container = binding.containerCustomFolders
        container.removeAllViews()

        val customFolders = BackupPreferences.getCustomFolders(requireContext())
        if (customFolders.isEmpty()) return

        for ((uri, name) in customFolders) {
            val row = android.widget.LinearLayout(requireContext()).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                    bottomMargin = 12
                }
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(8, 8, 8, 8)
                setBackgroundResource(R.drawable.bg_card_white)
            }

            val icon = android.widget.ImageView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(40, 40).apply {
                    marginEnd = 16
                }
                setImageResource(R.drawable.ic_nav_albums)
                setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary_blue))
            }

            val tv = android.widget.TextView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                text = name
                textSize = 14f
                setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary))
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val btnDelete = android.widget.ImageView(requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(48, 48)
                setImageResource(R.drawable.ic_trash)
                setColorFilter(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.error_text))
                setPadding(8, 8, 8, 8)
                isClickable = true
                isFocusable = true
                contentDescription = "Remove $name from backup"
                setOnClickListener {
                    BackupPreferences.removeCustomFolder(requireContext(), uri)
                    renderCustomFolders()
                    onFoldersUpdated()
                    Toast.makeText(requireContext(), "Removed folder from sync: $name", Toast.LENGTH_SHORT).show()
                }
            }

            row.addView(icon)
            row.addView(tv)
            row.addView(btnDelete)
            container.addView(row)
        }
    }

    private fun saveCustomFolder(uriString: String, name: String) {
        BackupPreferences.addCustomFolder(requireContext(), uriString, name)
        renderCustomFolders()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ManageFoldersBottomSheet"
        const val PREFS_NAME = "piphotos_backup_folders"
        const val KEY_FOLDER_CAMERA = "key_folder_camera"
        const val KEY_FOLDER_SCREENSHOTS = "key_folder_screenshots"
        const val KEY_FOLDER_WHATSAPP = "key_folder_whatsapp"
        const val KEY_FOLDER_DOWNLOADS = "key_folder_downloads"
        const val KEY_CUSTOM_FOLDER_URI = "key_custom_folder_uri"
        const val KEY_CUSTOM_FOLDER_NAME = "key_custom_folder_name"
    }
}

