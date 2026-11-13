package com.example.piphotos

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
                MediaSyncWorker.enqueueImmediateSync(context)
                binding.layoutAttentionRequired.visibility = View.GONE
                Toast.makeText(context, "Retrying backup...", Toast.LENGTH_SHORT).show()
            } else {
                DevicePairingBottomSheet {
                    updateBackupUiState()
                }.show(childFragmentManager, DevicePairingBottomSheet.TAG)
            }
        }

        binding.btnManageFolders.setOnClickListener {
            ManageFoldersBottomSheet {
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
            val msg = if (isChecked) "Sync constrained to Wi-Fi" else "Cellular sync allowed"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchCharging.setOnCheckedChangeListener { _, isChecked ->
            val context = requireContext()
            BackupPreferences.setChargingOnly(context, isChecked)
            MediaSyncWorker.schedulePeriodicSync(context)
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

    private fun updateBackupUiState() {
        val context = context ?: return
        val pairedNode = SyncNodePreferences.getPairedNode(context)
        val isPaired = pairedNode != null

        binding.cardLocalOnly.visibility = if (isPaired) View.GONE else View.VISIBLE
        binding.cardPiOptIn.visibility = if (isPaired) View.GONE else View.VISIBLE
        binding.cardBackupProgress.visibility = if (isPaired) View.VISIBLE else View.GONE
        binding.layoutAttentionRequired.visibility = View.GONE

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
                binding.tvBackupProgressSubtitle.text = "Connected · Ready to sync"
            }
        } else {
            binding.tvBackupSubtitle.text = "Local Storage · Private & Offline"
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

        when (workInfo.state) {
            WorkInfo.State.RUNNING -> {
                binding.cardBackupProgress.visibility = View.VISIBLE
                val current = workInfo.progress.getInt(MediaSyncWorker.KEY_PROGRESS_CURRENT, 0)
                val total = workInfo.progress.getInt(MediaSyncWorker.KEY_PROGRESS_TOTAL, 0)
                val uploaded = workInfo.progress.getInt(MediaSyncWorker.KEY_PROGRESS_UPLOADED, 0)

                if (total > 0) {
                    binding.pbBackupProgress.isIndeterminate = false
                    binding.pbBackupProgress.max = total
                    binding.pbBackupProgress.progress = current
                    binding.tvBackupProgressSubtitle.text = "Backing up $current of $total ($uploaded uploaded)"
                } else {
                    binding.pbBackupProgress.isIndeterminate = true
                    binding.tvBackupProgressSubtitle.text = "Hashing and verifying media items..."
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                binding.pbBackupProgress.isIndeterminate = false
                binding.pbBackupProgress.progress = binding.pbBackupProgress.max
                val uploaded = workInfo.outputData.getInt(MediaSyncWorker.KEY_RESULT_UPLOADED, 0)
                val skipped = workInfo.outputData.getInt(MediaSyncWorker.KEY_RESULT_SKIPPED, 0)
                binding.tvBackupProgressSubtitle.text = "Up to date · $uploaded uploaded, $skipped already synced"
                binding.layoutAttentionRequired.visibility = View.GONE
            }
            WorkInfo.State.FAILED -> {
                binding.layoutAttentionRequired.visibility = View.VISIBLE
                binding.tvBackupProgressSubtitle.text = "Sync interrupted · Network or node error"
            }
            else -> {
                // ENQUEUED, BLOCKED, CANCELLED
            }
        }
    }

    private fun showPairedNodeActionDialog() {
        val context = requireContext()
        val pairedNode = SyncNodePreferences.getPairedNode(context) ?: return

        val options = arrayOf("Sync Now", "Switch or Unpair Node", "Cancel")
        AlertDialog.Builder(context)
            .setTitle(pairedNode.nodeName)
            .setMessage("Endpoint: ${pairedNode.endpointUrl}")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> {
                        MediaSyncWorker.enqueueImmediateSync(context)
                        Toast.makeText(context, "Sync triggered", Toast.LENGTH_SHORT).show()
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
