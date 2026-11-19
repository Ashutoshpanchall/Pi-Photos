package com.example.piphotos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.piphotos.databinding.ActivityBackupActivityLogBinding
import com.example.piphotos.databinding.ItemActivityLogBinding
import com.example.piphotos.db.AppDatabase
import com.example.piphotos.db.SyncLedgerEntity
import com.example.piphotos.sync.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivityLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupActivityLogBinding
    private val adapter = LogAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBackupActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.activityLogRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.topBarLog,
            extraTopDp = 0
        )

        binding.btnLogBack.setOnClickListener {
            finish()
        }

        binding.btnLogRefresh.setOnClickListener {
            loadActivityLogs()
        }

        binding.rvActivityLog.layoutManager = LinearLayoutManager(this)
        binding.rvActivityLog.adapter = adapter

        loadActivityLogs()
    }

    private fun loadActivityLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            val syncRepo = SyncRepository.getInstance(this@BackupActivityLogActivity)
            val db = AppDatabase.getInstance(this@BackupActivityLogActivity)
            val summary = db.syncLedgerDao().summary
            val items = syncRepo.getRecentActivity(100)

            withContext(Dispatchers.Main) {
                if (summary != null) {
                    val formattedBytes = formatBytes(summary.uploadedBytes)
                    binding.tvLogSummaryStats.text = "${summary.doneCount} files backed up · $formattedBytes"
                }

                if (items.isEmpty()) {
                    binding.tvLogEmpty.visibility = View.VISIBLE
                    binding.rvActivityLog.visibility = View.GONE
                } else {
                    binding.tvLogEmpty.visibility = View.GONE
                    binding.rvActivityLog.visibility = View.VISIBLE
                    adapter.submitList(items)
                }
            }
        }
    }

    private class LogAdapter : RecyclerView.Adapter<LogViewHolder>() {
        private var items: List<SyncLedgerEntity> = emptyList()

        fun submitList(newItems: List<SyncLedgerEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val binding = ItemActivityLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return LogViewHolder(binding)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class LogViewHolder(private val binding: ItemActivityLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SyncLedgerEntity) {
            val context = binding.root.context
            val filename = "media_${item.mediaId}"
            binding.tvLogFileName.text = filename

            val formattedSize = formatBytes(item.size)
            val upAt = item.uploadedAt ?: 0L
            val timestamp = if (upAt > 0L) upAt else System.currentTimeMillis()
            val timeStr = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))

            if (item.status == "DONE") {
                binding.tvLogDetails.text = "$formattedSize · $timeStr"
                val hash = item.sha256
                val shortHash = if (!hash.isNullOrEmpty()) "SHA-256: ${hash.take(12)}..." else ""
                binding.tvLogHashOrError.text = shortHash
                binding.tvLogBadge.text = "SYNCED"
                binding.tvLogBadge.setTextColor(ContextCompat.getColor(context, R.color.status_green))
                binding.ivLogStatusIcon.setImageResource(R.drawable.ic_check)
                binding.ivLogStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_green))
            } else {
                binding.tvLogDetails.text = "$formattedSize · Failed (${item.attempts} attempts)"
                binding.tvLogHashOrError.text = item.lastError ?: "Upload error"
                binding.tvLogBadge.text = "FAILED"
                binding.tvLogBadge.setTextColor(ContextCompat.getColor(context, R.color.status_red))
                binding.ivLogStatusIcon.setImageResource(R.drawable.ic_alert)
                binding.ivLogStatusIcon.setColorFilter(ContextCompat.getColor(context, R.color.status_red))
            }
        }
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var size = bytes.toDouble()
            var unitIndex = 0
            while (size >= 1024 && unitIndex < units.size - 1) {
                size /= 1024
                unitIndex++
            }
            return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
        }
    }
}
