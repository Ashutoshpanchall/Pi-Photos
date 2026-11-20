package com.example.piphotos

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.piphotos.databinding.ActivityServerFilesBinding
import com.example.piphotos.net.FileInfo
import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.sync.QuotaStateManager
import com.example.piphotos.sync.SyncNodePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.activity.enableEdgeToEdge

class ServerFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerFilesBinding
    private val httpClient by lazy { PiHttpClient.getInstance(this) }
    private val adapter = ServerFilesAdapter { file -> downloadFile(file) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityServerFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.serverFilesRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.topBar,
            extraTopDp = 0
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { loadServerFiles() }

        binding.rvServerFiles.layoutManager = LinearLayoutManager(this)
        binding.rvServerFiles.adapter = adapter

        loadServerFiles()
    }

    private fun loadServerFiles() {
        val serverUrl = SyncNodePreferences.getServerUrl(this) ?: ""
        val authToken = SyncNodePreferences.getAuthToken(this) ?: ""
        val deviceId = SyncNodePreferences.getDeviceId(this)
        val fp = SyncNodePreferences.getTlsFingerprint(this)

        if (serverUrl.isEmpty() || authToken.isEmpty()) {
            Toast.makeText(this, "Storage node not connected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            // 1. Fetch folder summary
            if (!deviceId.isNullOrEmpty()) {
                val folderResult = httpClient.getDeviceFolders(serverUrl, authToken, deviceId, fp)
                folderResult.onSuccess { res ->
                    val summaryStr = res.folders.joinToString(" · ") { f ->
                        "${f.name}: ${f.itemCount} items (${QuotaStateManager.formatBytes(f.totalBytes)})"
                    }
                    binding.tvFoldersSummary.text = if (summaryStr.isNotEmpty()) summaryStr else "No device folders found"
                }.onFailure {
                    binding.tvFoldersSummary.text = "Device files on storage node"
                }
            }

            // 2. Fetch files list
            val filesResult = httpClient.listFiles(
                endpointUrl = serverUrl,
                authToken = authToken,
                deviceId = deviceId,
                expectedFingerprint = fp
            )

            binding.progressBar.visibility = View.GONE

            filesResult.onSuccess { files ->
                if (files.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvServerFiles.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvServerFiles.visibility = View.VISIBLE
                    adapter.submitList(files)
                }
            }.onFailure { err ->
                Toast.makeText(this@ServerFilesActivity, "Failed to load files: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadFile(file: FileInfo) {
        val serverUrl = SyncNodePreferences.getServerUrl(this) ?: return
        val authToken = SyncNodePreferences.getAuthToken(this) ?: return
        val fp = SyncNodePreferences.getTlsFingerprint(this)

        Toast.makeText(this, "Downloading ${file.relativePath}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val isVideo = file.relativePath.endsWith(".mp4", ignoreCase = true) ||
                    file.relativePath.endsWith(".mov", ignoreCase = true)
                val mime = if (isVideo) "video/mp4" else "image/jpeg"

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.relativePath)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PiPhotos")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val collectionUri = if (isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val itemUri = contentResolver.insert(collectionUri, contentValues)
                    ?: throw java.io.IOException("Failed to create MediaStore entry")

                try {
                    val result = contentResolver.openOutputStream(itemUri)?.use { out ->
                        httpClient.downloadFileContent(serverUrl, authToken, file.id, fp, out)
                    } ?: Result.failure(java.io.IOException("Cannot open output stream"))

                    if (result.isFailure) throw result.exceptionOrNull() ?: java.io.IOException("Download failed")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(itemUri, contentValues, null, null)
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@ServerFilesActivity,
                            "Saved ${file.relativePath} to Pictures/PiPhotos",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    contentResolver.delete(itemUri, null, null)
                    throw e
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ServerFilesActivity, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private class ServerFilesAdapter(
        private val onDownloadClick: (FileInfo) -> Unit
    ) : ListAdapter<FileInfo, ServerFileViewHolder>(DiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerFileViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_server_file, parent, false)
            return ServerFileViewHolder(view, onDownloadClick)
        }

        override fun onBindViewHolder(holder: ServerFileViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            private val DiffCallback = object : DiffUtil.ItemCallback<FileInfo>() {
                override fun areItemsTheSame(oldItem: FileInfo, newItem: FileInfo): Boolean = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: FileInfo, newItem: FileInfo): Boolean = oldItem == newItem
            }
        }
    }

    private class ServerFileViewHolder(
        itemView: View,
        private val onDownloadClick: (FileInfo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvDetails: TextView = itemView.findViewById(R.id.tvFileDetails)
        private val ivType: ImageView = itemView.findViewById(R.id.ivFileType)
        private val btnDownload: ImageView = itemView.findViewById(R.id.btnDownloadFile)
        private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.US)

        fun bind(file: FileInfo) {
            tvName.text = file.relativePath
            val isVideo = file.relativePath.endsWith(".mp4", ignoreCase = true) ||
                file.relativePath.endsWith(".mov", ignoreCase = true)
            ivType.setImageResource(if (isVideo) R.drawable.ic_video_cam else R.drawable.ic_nav_photos)

            val dateStr = if (file.dateTaken != null && file.dateTaken > 0L) {
                dateFormat.format(Date(file.dateTaken * 1000L))
            } else {
                "Recent"
            }
            val sizeStr = QuotaStateManager.formatBytes(file.size)
            val folderStr = if (file.folder.isNotEmpty()) "${file.folder} · " else ""
            tvDetails.text = "$folderStr$sizeStr · $dateStr"

            btnDownload.setOnClickListener { onDownloadClick(file) }
        }
    }
}
