package com.example.piphotos

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.piphotos.databinding.FragmentBackupBinding
import com.example.piphotos.sync.DevicePairingBottomSheet
import com.example.piphotos.sync.MediaSyncWorker
import com.example.piphotos.sync.SyncNodePreferences

class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        updateBackupUiState()
        updateMonitoredFoldersUi()

        binding.btnSetupPi.setOnClickListener {
            DevicePairingBottomSheet {
                updateBackupUiState()
            }.show(childFragmentManager, DevicePairingBottomSheet.TAG)
        }

        binding.cardBackupProgress.setOnClickListener {
            showPairedNodeActionDialog()
        }

        binding.btnRetry.setOnClickListener {
            val context = requireContext()
            if (SyncNodePreferences.isPaired(context)) {
                MediaSyncWorker.enqueueImmediateSync(context, force = true)
                binding.layoutAttentionRequired.visibility = View.GONE
                Toast.makeText(context, "Retrying media sync...", Toast.LENGTH_SHORT).show()
            } else {
                DevicePairingBottomSheet {
                    updateBackupUiState()
                }.show(childFragmentManager, DevicePairingBottomSheet.TAG)
            }
        }

        binding.btnManageFolders.setOnClickListener {
            ManageFoldersBottomSheet {
                updateMonitoredFoldersUi()
                MediaSyncWorker.enqueueImmediateSync(requireContext())
                Toast.makeText(requireContext(), "Monitored folder backup updated", Toast.LENGTH_SHORT).show()
            }.show(childFragmentManager, ManageFoldersBottomSheet.TAG)
        }

        // Initialize persistent backup preferences
        binding.switchWifi.isChecked = BackupPreferences.isWifiOnly(requireContext())
        binding.switchCharging.isChecked = BackupPreferences.isChargingOnly(requireContext())
        binding.switchVideos.isChecked = BackupPreferences.isIncludeVideos(requireContext())

        binding.switchWifi.setOnCheckedChangeListener { _, isChecked ->
            val context = requireContext()
            BackupPreferences.setWifiOnly(context, isChecked)
            MediaSyncWorker.schedulePeriodicSync(context)
            updateBackupUiState()
            val msg = if (isChecked) "Sync constrained to Wi-Fi" else "Cellular sync allowed"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchCharging.setOnCheckedChangeListener { _, isChecked ->
            val context = requireContext()
            BackupPreferences.setChargingOnly(context, isChecked)
            MediaSyncWorker.schedulePeriodicSync(context)
            updateBackupUiState()
            val msg = if (isChecked) "Sync will pause unless plugged in" else "Sync on battery active"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchVideos.setOnCheckedChangeListener { _, isChecked ->
            val context = requireContext()
            BackupPreferences.setIncludeVideos(context, isChecked)
            MediaSyncWorker.schedulePeriodicSync(context)
            val msg = if (isChecked) "Video upload included (MP4/MOV)" else "Videos excluded from sync"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        observeSyncWork()
    }

    override fun onResume() {
        super.onResume()
        updateBackupUiState()
        updateMonitoredFoldersUi()
    }

    private fun updateMonitoredFoldersUi() {
        val context = context ?: return
        val cameraActive = BackupPreferences.isCameraFolderEnabled(context)
        val screenshotsActive = BackupPreferences.isScreenshotsFolderEnabled(context)

        binding.tvStatusCamera.text = if (cameraActive) "Active" else "Paused"
        binding.tvStatusCamera.setTextColor(
            ContextCompat.getColor(context, if (cameraActive) R.color.text_secondary else R.color.text_tertiary)
        )
        binding.dotStatusCamera.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (cameraActive) R.color.primary_blue else R.color.text_tertiary)
        )

        binding.tvStatusScreenshots.text = if (screenshotsActive) "Active" else "Paused"
        binding.tvStatusScreenshots.setTextColor(
            ContextCompat.getColor(context, if (screenshotsActive) R.color.text_secondary else R.color.text_tertiary)
        )
        binding.dotStatusScreenshots.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (screenshotsActive) R.color.primary_blue else R.color.text_tertiary)
        )
    }

    private fun updateBackupUiState() {
        val context = context ?: return
        val pairedNode = SyncNodePreferences.getPairedNode(context)
        val isPaired = pairedNode != null

        binding.cardLocalOnly.visibility = if (isPaired) View.GONE else View.VISIBLE
        binding.cardPiOptIn.visibility = if (isPaired) View.GONE else View.VISIBLE
        binding.cardBackupProgress.visibility = if (isPaired) View.VISIBLE else View.GONE

        if (isPaired) {
            binding.tvBackupSubtitle.text = "Connected to ${pairedNode!!.nodeName}"
            binding.tvBackupProgressTitle.text = pairedNode.nodeName

            val lastSync = SyncNodePreferences.getLastSyncTimestamp(context)
            if (lastSync > 0L) {
                val formatted = DateUtils.getRelativeTimeSpanString(
                    lastSync,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                )
                binding.tvBackupProgressSubtitle.text = "Last synced $formatted"
            } else {
                val wifiOnly = BackupPreferences.isWifiOnly(context)
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (wifiOnly && !isWifi) {
                    binding.tvBackupProgressSubtitle.text = "Paused · Waiting for Wi-Fi network"
                } else {
                    binding.tvBackupProgressSubtitle.text = "Connected · Ready to sync"
                }
            }
        } else {
            binding.tvBackupSubtitle.text = "Local Storage · Private & Offline"
            binding.layoutAttentionRequired.visibility = View.GONE
        }
    }

    private fun observeSyncWork() {
        val context = context ?: return
        val workManager = WorkManager.getInstance(context)

        workManager.getWorkInfosForUniqueWorkLiveData(MediaSyncWorker.WORK_NAME_IMMEDIATE)
            .observe(viewLifecycleOwner) { workInfoList ->
                handleWorkInfoList(workInfoList)
            }

        workManager.getWorkInfosForUniqueWorkLiveData(MediaSyncWorker.WORK_NAME_PERIODIC)
            .observe(viewLifecycleOwner) { workInfoList ->
                handleWorkInfoList(workInfoList)
            }
    }

    private fun handleWorkInfoList(workInfoList: List<WorkInfo>?) {
        val workInfo = workInfoList?.firstOrNull() ?: return
        val context = context ?: return
        if (!SyncNodePreferences.isPaired(context)) return

        val isRunning = workInfo.state == WorkInfo.State.RUNNING

        // Freeze upload condition switches during active upload
        binding.switchWifi.isEnabled = !isRunning
        binding.switchCharging.isEnabled = !isRunning
        binding.switchVideos.isEnabled = !isRunning

        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                binding.cardBackupProgress.visibility = View.VISIBLE
                binding.pbBackupProgress.visibility = View.VISIBLE
                binding.layoutBackupMetrics.visibility = View.VISIBLE
                binding.layoutAttentionRequired.visibility = View.GONE

                val current = workInfo.progress.getInt(MediaSyncWorker.KEY_PROGRESS_CURRENT, 0)
                val total = workInfo.progress.getInt(MediaSyncWorker.KEY_PROGRESS_TOTAL, 0)
                val uploaded = workInfo.progress.getInt(MediaSyncWorker.KEY_PROGRESS_UPLOADED, 0)
                val speedBps = workInfo.progress.getLong(MediaSyncWorker.KEY_PROGRESS_SPEED_BYTES_PER_SEC, 0L)
                val etaSec = workInfo.progress.getLong(MediaSyncWorker.KEY_PROGRESS_ETA_SECONDS, -1L)

                if (total > 0) {
                    binding.pbBackupProgress.isIndeterminate = false
                    binding.pbBackupProgress.max = total
                    binding.pbBackupProgress.progress = current
                    binding.tvBackupProgressSubtitle.text = "Backing up $current of $total ($uploaded uploaded)"

                    // Dynamic Speed
                    if (speedBps > 0) {
                        val speedMBs = speedBps / (1024.0 * 1024.0)
                        binding.tvBackupSpeed.text = if (speedMBs >= 1.0) {
                            String.format("%.1f MB/s", speedMBs)
                        } else {
                            "${(speedBps / 1024)} KB/s"
                        }
                    } else {
                        binding.tvBackupSpeed.text = "Verifying"
                    }

                    // Dynamic ETA
                    if (etaSec > 0) {
                        val mins = etaSec / 60
                        val secs = etaSec % 60
                        binding.tvBackupEta.text = when {
                            mins >= 60 -> "${mins / 60}h ${mins % 60}m remaining"
                            mins > 0 -> "${mins}m remaining"
                            else -> "${secs}s remaining"
                        }
                    } else {
                        binding.tvBackupEta.text = "${total - current} items left"
                    }
                } else {
                    binding.pbBackupProgress.isIndeterminate = true
                    binding.tvBackupProgressSubtitle.text = "Hashing and verifying media items..."
                    binding.tvBackupSpeed.text = "Scanning"
                    binding.tvBackupEta.text = "Calculating..."
                }
            }

            WorkInfo.State.SUCCEEDED -> {
                binding.pbBackupProgress.visibility = View.GONE
                binding.layoutBackupMetrics.visibility = View.GONE
                binding.layoutAttentionRequired.visibility = View.GONE

                val uploaded = workInfo.outputData.getInt(MediaSyncWorker.KEY_RESULT_UPLOADED, 0)
                val skipped = workInfo.outputData.getInt(MediaSyncWorker.KEY_RESULT_SKIPPED, 0)
                val message = workInfo.outputData.getString(MediaSyncWorker.KEY_RESULT_MESSAGE)

                binding.tvBackupProgressSubtitle.text = if (message != null) {
                    message
                } else {
                    "Up to date · $uploaded uploaded, $skipped already synced"
                }
            }

            WorkInfo.State.FAILED -> {
                binding.pbBackupProgress.visibility = View.GONE
                binding.layoutBackupMetrics.visibility = View.GONE
                binding.layoutAttentionRequired.visibility = View.VISIBLE

                val reason = workInfo.outputData.getString(MediaSyncWorker.KEY_ERROR_REASON)
                    ?: "Could not reach storage node. Ensure your phone is on the same Wi-Fi as your Pi."
                binding.tvErrorTitle.text = "Backup Interrupted"
                binding.tvErrorReason.text = reason
                binding.tvBackupProgressSubtitle.text = "Sync paused · Tap to retry"
            }

            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                binding.pbBackupProgress.visibility = View.GONE
                binding.layoutBackupMetrics.visibility = View.GONE

                val wifiOnly = BackupPreferences.isWifiOnly(context)
                val chargingOnly = BackupPreferences.isChargingOnly(context)
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

                if (wifiOnly && !isWifi) {
                    binding.tvBackupProgressSubtitle.text = "Paused · Waiting for Wi-Fi network"
                } else if (chargingOnly) {
                    binding.tvBackupProgressSubtitle.text = "Paused · Waiting for charger connection"
                } else {
                    binding.tvBackupProgressSubtitle.text = "Sync scheduled · Ready"
                }
            }

            else -> {}
        }
    }

    private fun showPairedNodeActionDialog() {
        val context = requireContext()
        val pairedNode = SyncNodePreferences.getPairedNode(context) ?: return

        val options = arrayOf("Sync Now (Force Over Any Network)", "Switch or Unpair Node", "Cancel")
        AlertDialog.Builder(context)
            .setTitle(pairedNode.nodeName)
            .setMessage("Endpoint: ${pairedNode.endpointUrl}\n\nTap 'Sync Now' to start media transfer immediately.")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        MediaSyncWorker.enqueueImmediateSync(context, force = true)
                        Toast.makeText(context, "Immediate sync started", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        SyncNodePreferences.clearPairedNode(context)
                        MediaSyncWorker.cancelSync(context)
                        updateBackupUiState()
                        Toast.makeText(context, "Storage node disconnected", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = BackupFragment()
    }
}
