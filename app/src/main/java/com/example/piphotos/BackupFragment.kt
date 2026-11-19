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
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.piphotos.databinding.FragmentBackupBinding
import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.sync.ConnectionState
import com.example.piphotos.sync.ConnectionStateMonitor
import com.example.piphotos.sync.DevicePairingBottomSheet
import com.example.piphotos.sync.MediaSyncWorker
import com.example.piphotos.sync.SyncLedgerState
import com.example.piphotos.sync.SyncNodePreferences
import com.example.piphotos.sync.SyncRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private var connectionMonitor: ConnectionStateMonitor? = null
    private var lastObservedConnectionState: ConnectionState = ConnectionState.NotConfigured

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

        val context = requireContext()
        connectionMonitor = ConnectionStateMonitor(context)

        // Observe persistent connection state
        viewLifecycleOwner.lifecycleScope.launch {
            connectionMonitor?.connectionState?.collectLatest { state ->
                lastObservedConnectionState = state
                renderConnectionState(state)
            }
        }

        binding.layoutConnectionChip.setOnClickListener {
            val ctx = context
            if (SyncNodePreferences.isPaired(ctx)) {
                showPairedNodeActionDialog()
            } else {
                DevicePairingBottomSheet {
                    updateBackupUiState()
                    viewLifecycleOwner.lifecycleScope.launch {
                        connectionMonitor?.checkNow()
                    }
                }.show(childFragmentManager, DevicePairingBottomSheet.TAG)
            }
        }

        updateBackupUiState()
        updateMonitoredFoldersUi()

        binding.btnSetupPi.setOnClickListener {
            DevicePairingBottomSheet {
                updateBackupUiState()
                viewLifecycleOwner.lifecycleScope.launch {
                    connectionMonitor?.checkNow()
                }
            }.show(childFragmentManager, DevicePairingBottomSheet.TAG)
        }

        binding.cardBackupProgress.setOnClickListener {
            showPairedNodeActionDialog()
        }

        binding.iconCloudUpload.setOnClickListener {
            showPairedNodeActionDialog()
        }

        binding.btnRetry.setOnClickListener {
            val ctx = requireContext()
            if (SyncNodePreferences.isPaired(ctx)) {
                SyncRepository.getInstance(ctx).retryAllFailed()
                MediaSyncWorker.enqueueImmediateSync(ctx, force = true)
                binding.layoutAttentionRequired.visibility = View.GONE
                Toast.makeText(ctx, "Retrying media sync...", Toast.LENGTH_SHORT).show()
            } else {
                DevicePairingBottomSheet {
                    updateBackupUiState()
                    viewLifecycleOwner.lifecycleScope.launch {
                        connectionMonitor?.checkNow()
                    }
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
            val ctx = requireContext()
            BackupPreferences.setWifiOnly(ctx, isChecked)
            MediaSyncWorker.schedulePeriodicSync(ctx)
            updateBackupUiState()
            val msg = if (isChecked) "Sync constrained to Wi-Fi" else "Cellular sync allowed"
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchCharging.setOnCheckedChangeListener { _, isChecked ->
            val ctx = requireContext()
            BackupPreferences.setChargingOnly(ctx, isChecked)
            MediaSyncWorker.schedulePeriodicSync(ctx)
            updateBackupUiState()
            val msg = if (isChecked) "Sync will pause unless plugged in" else "Sync on battery active"
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchVideos.setOnCheckedChangeListener { _, isChecked ->
            val ctx = requireContext()
            BackupPreferences.setIncludeVideos(ctx, isChecked)
            MediaSyncWorker.schedulePeriodicSync(ctx)
            val msg = if (isChecked) "Video upload included (MP4/MOV)" else "Videos excluded from sync"
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        observeSyncRepository()
    }

    override fun onResume() {
        super.onResume()
        updateBackupUiState()
        updateMonitoredFoldersUi()
        connectionMonitor?.start(viewLifecycleOwner.lifecycleScope)
    }

    override fun onPause() {
        connectionMonitor?.stop()
        super.onPause()
    }

    private fun renderConnectionState(state: ConnectionState) {
        val context = context ?: return
        val paired = SyncNodePreferences.getPairedNode(context)

        when (state) {
            is ConnectionState.NotConfigured -> {
                binding.dotConnectionStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.primary_blue)
                )
                binding.tvBackupSubtitle.text = "Local Storage · Private & Offline"
            }
            is ConnectionState.Connected -> {
                binding.dotConnectionStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.status_green)
                )
                val nodeName = paired?.nodeName ?: "Home Vault"
                binding.tvBackupSubtitle.text = "Connected to $nodeName"

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
            }
            is ConnectionState.ReachableAuthNeeded -> {
                binding.dotConnectionStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.status_amber)
                )
                val nodeName = paired?.nodeName ?: "Storage Node"
                binding.tvBackupSubtitle.text = "$nodeName · Sign-in needed"
                binding.tvBackupProgressSubtitle.text = "Authentication required (tap to re-pair)"
            }
            is ConnectionState.Unreachable -> {
                binding.dotConnectionStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.status_red)
                )
                val nodeName = paired?.nodeName ?: "Storage Node"
                val lastSeenText = if (state.lastSeenMs > 0L) {
                    DateUtils.getRelativeTimeSpanString(
                        state.lastSeenMs,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    )
                } else {
                    "offline"
                }
                binding.tvBackupSubtitle.text = "$nodeName · Offline ($lastSeenText)"
                binding.tvBackupProgressSubtitle.text = "Unreachable · Ensure phone & Pi on same Wi-Fi"
            }
        }
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
            binding.tvBackupProgressTitle.text = pairedNode!!.nodeName
            renderConnectionState(lastObservedConnectionState)
        } else {
            renderConnectionState(ConnectionState.NotConfigured)
            binding.layoutAttentionRequired.visibility = View.GONE
        }
    }

    private fun observeSyncRepository() {
        val context = context ?: return
        val syncRepo = com.example.piphotos.sync.SyncRepository.getInstance(context)

        viewLifecycleOwner.lifecycleScope.launch {
            syncRepo.syncState.collectLatest { state ->
                renderSyncState(state)
            }
        }
    }

    private fun renderSyncState(state: com.example.piphotos.sync.SyncLedgerState) {
        val context = context ?: return
        if (!SyncNodePreferences.isPaired(context)) return

        // Freeze upload condition switches during active upload
        binding.switchWifi.isEnabled = !state.isSyncing
        binding.switchCharging.isEnabled = !state.isSyncing
        binding.switchVideos.isEnabled = !state.isSyncing

        if (state.isSyncing) {
            binding.cardBackupProgress.visibility = View.VISIBLE
            binding.pbBackupProgress.visibility = View.VISIBLE
            binding.layoutBackupMetrics.visibility = View.VISIBLE
            binding.layoutAttentionRequired.visibility = View.GONE

            if (state.totalCount > 0) {
                binding.pbBackupProgress.isIndeterminate = false
                binding.pbBackupProgress.max = state.totalCount
                binding.pbBackupProgress.progress = state.doneCount + state.uploadingCount
                val currentFileSnippet = if (!state.currentFileName.isNullOrEmpty()) " • ${state.currentFileName}" else ""
                binding.tvBackupProgressSubtitle.text =
                    "Backing up: ${state.doneCount} of ${state.totalCount} (${state.percentComplete}%)$currentFileSnippet"
            } else {
                binding.pbBackupProgress.isIndeterminate = true
                binding.tvBackupProgressSubtitle.text = "Scanning & hashing new media..."
            }

            if (state.speedBytesPerSec > 0) {
                val speedMb = state.speedBytesPerSec / (1024.0 * 1024.0)
                binding.tvBackupSpeed.text = String.format("%.1f MB/s", speedMb)
            } else {
                binding.tvBackupSpeed.text = "Hashing..."
            }

            binding.tvBackupEta.text = formatEta(state.etaSeconds)
        } else {
            binding.pbBackupProgress.visibility = View.GONE
            binding.layoutBackupMetrics.visibility = View.GONE

            if (!state.lastError.isNullOrEmpty() && state.failedCount > 0) {
                binding.tvErrorReason.text = "${state.lastError} (${state.failedCount} file(s) failed)"
                binding.layoutAttentionRequired.visibility = View.VISIBLE
                binding.tvBackupProgressSubtitle.text = "Backup paused · Attention required"
            } else {
                binding.layoutAttentionRequired.visibility = View.GONE
                val lastSync = SyncNodePreferences.getLastSyncTimestamp(context)
                val relativeTime = if (lastSync > 0L) {
                    DateUtils.getRelativeTimeSpanString(lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                } else "Ready"

                if (state.isAllBackedUp) {
                    binding.tvBackupProgressSubtitle.text = "All ${state.totalCount} items backed up · $relativeTime"
                } else if (state.doneCount > 0) {
                    binding.tvBackupProgressSubtitle.text = "${state.doneCount} of ${state.totalCount} backed up · $relativeTime"
                } else {
                    binding.tvBackupProgressSubtitle.text = "Connected · Ready to sync"
                }
            }
        }
    }

    private fun showPairedNodeActionDialog() {
        val context = requireContext()
        val pairedNode = SyncNodePreferences.getPairedNode(context) ?: return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isWifi = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val isLocalIp = pairedNode.endpointUrl.contains("192.168.") || 
            pairedNode.endpointUrl.contains("://10.") || 
            pairedNode.endpointUrl.contains(".local") || 
            pairedNode.endpointUrl.contains("://127.") ||
            pairedNode.endpointUrl.contains("localhost")

        val wifiNotice = if (!isWifi && isLocalIp) {
            "\n\n⚠️ Your phone is on cellular data. Local nodes are only reachable when connected to the same Wi-Fi network."
        } else ""

        val fpSnippet = if (pairedNode.tlsFingerprint.isNotEmpty()) "\nTLS: ${pairedNode.tlsFingerprint.take(23)}..." else ""
        val stateDesc = when (lastObservedConnectionState) {
            is ConnectionState.Connected -> "Online (Ready to sync)"
            is ConnectionState.ReachableAuthNeeded -> "Reachable (Auth token required)"
            is ConnectionState.Unreachable -> "Offline / Unreachable"
            else -> "Not configured"
        }

        AlertDialog.Builder(context)
            .setTitle(pairedNode.nodeName)
            .setMessage("Status: $stateDesc\nEndpoint: ${pairedNode.endpointUrl}$fpSnippet$wifiNotice")
            .setPositiveButton("Sync Now") { dialog, _ ->
                MediaSyncWorker.enqueueImmediateSync(context, force = true)
                Toast.makeText(context, "Sync started", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNeutralButton("Test Connection") { dialog, _ ->
                dialog.dismiss()
                testNodeConnection(pairedNode)
            }
            .setNegativeButton("Disconnect") { dialog, _ ->
                SyncNodePreferences.clearPairedNode(context)
                MediaSyncWorker.cancelSync(context)
                updateBackupUiState()
                Toast.makeText(context, "Storage node disconnected", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    private fun testNodeConnection(pairedNode: com.example.piphotos.sync.PairedNode) {
        val context = context ?: return
        Toast.makeText(context, "Probing ${pairedNode.nodeName}...", Toast.LENGTH_SHORT).show()
        val client = PiHttpClient(context)
        viewLifecycleOwner.lifecycleScope.launch {
            val res = client.getStatus(
                endpointUrl = pairedNode.endpointUrl,
                authToken = pairedNode.authToken,
                expectedFingerprint = pairedNode.tlsFingerprint.ifEmpty { null }
            )
            if (res.isSuccess) {
                val status = res.getOrThrow()
                val freeGb = status.diskFree / (1024 * 1024 * 1024.0)
                val quotaGb = status.userQuota / (1024 * 1024 * 1024.0)
                SyncNodePreferences.updateLastSeen(context)
                connectionMonitor?.checkNow()
                Toast.makeText(
                    context,
                    "Connected! Free disk: ${String.format("%.1f", freeGb)} GB, Quota: ${String.format("%.1f", quotaGb)} GB",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                connectionMonitor?.checkNow()
                val err = res.exceptionOrNull()?.localizedMessage ?: "Connection failed"
                Toast.makeText(context, "Node unreachable: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatEta(etaSec: Long): String {
        if (etaSec <= 0) return "Estimating..."
        val mins = etaSec / 60
        val secs = etaSec % 60
        return when {
            mins >= 60 -> "${mins / 60}h ${mins % 60}m remaining"
            mins > 0 -> "${mins}m remaining"
            else -> "${secs}s remaining"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = BackupFragment()
    }
}
