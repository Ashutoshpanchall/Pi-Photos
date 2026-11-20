package com.example.piphotos

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.piphotos.databinding.FragmentBackupBinding
import com.example.piphotos.databinding.ItemBackupQueueBinding
import com.example.piphotos.databinding.ItemServerFolderBinding
import com.example.piphotos.db.AppDatabase
import com.example.piphotos.db.SyncLedgerEntity
import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.net.ServerFolderInfo
import com.example.piphotos.sync.ConnectionState
import com.example.piphotos.sync.ConnectionStateMonitor
import com.example.piphotos.sync.DevicePairingBottomSheet
import com.example.piphotos.sync.MediaSyncWorker
import com.example.piphotos.sync.SyncLedgerState
import com.example.piphotos.sync.SyncNodePreferences
import com.example.piphotos.sync.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private var connectionMonitor: ConnectionStateMonitor? = null
    private var lastObservedConnectionState: ConnectionState = ConnectionState.NotConfigured

    private val queueAdapter = QueueAdapter(
        onRetryItem = { item ->
            val ctx = context ?: return@QueueAdapter
            val syncRepo = SyncRepository.getInstance(ctx)
            syncRepo.retryFailed(item.mediaId)
            MediaSyncWorker.enqueueImmediateSync(ctx, force = true)
            Toast.makeText(ctx, "Retrying media_${item.mediaId}...", Toast.LENGTH_SHORT).show()
        }
    )

    private val folderAdapter = ServerFolderAdapter()

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

        // Activity log button
        binding.btnActivityLog.setOnClickListener {
            startActivity(Intent(requireContext(), BackupActivityLogActivity::class.java))
        }

        // Setup Queue RecyclerView
        binding.rvBackupQueue.layoutManager = LinearLayoutManager(context)
        binding.rvBackupQueue.adapter = queueAdapter

        // Setup Server Folders RecyclerView
        binding.rvFoldersOnServer.layoutManager = LinearLayoutManager(context)
        binding.rvFoldersOnServer.adapter = folderAdapter

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

        binding.btnRetryAllFailed.setOnClickListener {
            val ctx = requireContext()
            SyncRepository.getInstance(ctx).retryAllFailed()
            MediaSyncWorker.enqueueImmediateSync(ctx, force = true)
            Toast.makeText(ctx, "Retrying all failed files...", Toast.LENGTH_SHORT).show()
        }

        binding.btnErrorAction.setOnClickListener {
            val ctx = requireContext()
            val state = SyncRepository.getInstance(ctx).syncState.value
            val err = state.lastError ?: ""
            if (err.contains("quota_exceeded") || err.contains("Vault is full") || err.contains("Storage Limit Reached")) {
                val quotaBytes = SyncNodePreferences.getQuotaBytes(ctx)
                val usedBytes = SyncNodePreferences.getUsedBytes(ctx)
                val quotaMsg = if (quotaBytes <= 0L) {
                    "Vault physical storage is full. Please free up space on the storage node."
                } else {
                    val usedGb = "%.2f".format(usedBytes / (1024.0 * 1024.0 * 1024.0))
                    val quotaGb = "%.2f".format(quotaBytes / (1024.0 * 1024.0 * 1024.0))
                    "You have used $usedGb GB of your $quotaGb GB quota. Please contact the vault owner to increase your quota."
                }
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle("Storage Limit Reached")
                    .setMessage(quotaMsg)
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            if (SyncNodePreferences.isPaired(ctx)) {
                SyncRepository.getInstance(ctx).retryAllFailed()
                MediaSyncWorker.enqueueImmediateSync(ctx, force = true)
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
                refreshServerFolders()
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
        refreshServerFolders()
    }

    override fun onResume() {
        super.onResume()
        updateBackupUiState()
        refreshServerFolders()
        refreshQueue()
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
                val nodeName = paired?.nodeName ?: "Storage Node"
                binding.tvBackupSubtitle.text = "Connected to $nodeName"
            }
            is ConnectionState.ReachableAuthNeeded -> {
                binding.dotConnectionStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.status_amber)
                )
                val nodeName = paired?.nodeName ?: "Storage Node"
                binding.tvBackupSubtitle.text = "$nodeName · Sign-in needed"
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
            }
        }
    }

    private fun updateBackupUiState() {
        val context = context ?: return
        val isPaired = SyncNodePreferences.isPaired(context)

        binding.cardLocalOnly.visibility = if (isPaired) View.GONE else View.VISIBLE
        binding.cardPiOptIn.visibility = if (isPaired) View.GONE else View.VISIBLE
        binding.cardBackupProgress.visibility = if (isPaired) View.VISIBLE else View.GONE
        binding.headerServerFolders.visibility = if (isPaired) View.VISIBLE else View.GONE
        binding.rvFoldersOnServer.visibility = if (isPaired) View.VISIBLE else View.GONE

        if (!isPaired) {
            binding.cardErrorBanner.visibility = View.GONE
            binding.headerBackupQueue.visibility = View.GONE
            binding.rvBackupQueue.visibility = View.GONE
            renderConnectionState(ConnectionState.NotConfigured)
        } else {
            renderConnectionState(lastObservedConnectionState)
        }
    }

    private fun observeSyncRepository() {
        val context = context ?: return
        val syncRepo = SyncRepository.getInstance(context)

        viewLifecycleOwner.lifecycleScope.launch {
            syncRepo.syncState.collectLatest { state ->
                renderSyncState(state)
                refreshQueue()
            }
        }
    }

    private fun renderSyncState(state: SyncLedgerState) {
        val context = context ?: return
        if (!SyncNodePreferences.isPaired(context)) return

        // Freeze upload condition switches during active sync
        binding.switchWifi.isEnabled = !state.isSyncing
        binding.switchCharging.isEnabled = !state.isSyncing
        binding.switchVideos.isEnabled = !state.isSyncing

        // Update progress ring
        binding.cpiBackupProgress.progress = state.percentComplete

        // Byte count & last backup time
        val lastSync = SyncNodePreferences.getLastSyncTimestamp(context)
        val lastSyncText = if (lastSync > 0L) {
            "Last backup: " + DateUtils.getRelativeTimeSpanString(lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        } else {
            "Last backup: Never"
        }
        binding.tvLastBackupTime.text = lastSyncText

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(context)
            val summary = db.syncLedgerDao().summary
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (summary != null) {
                    val uploadedStr = formatBytes(summary.uploadedBytes)
                    val pendingBytes = (summary.totalBytes - summary.uploadedBytes).coerceAtLeast(0L)
                    val pendingStr = formatBytes(pendingBytes)
                    binding.tvBackupBytes.text = "$uploadedStr backed up · $pendingStr pending"
                }
            }
        }

        if (state.isSyncing) {
            binding.tvBackupProgressTitle.text = "Backing Up Photos..."
            binding.layoutBackupMetrics.visibility = View.VISIBLE
            binding.cardErrorBanner.visibility = View.GONE

            if (state.totalCount > 0) {
                binding.cpiBackupProgress.isIndeterminate = false
                val currentFileSnippet = if (!state.currentFileName.isNullOrEmpty()) " • ${state.currentFileName}" else ""
                binding.tvBackupProgressSubtitle.text =
                    "${state.doneCount} of ${state.totalCount} backed up (${state.percentComplete}%)$currentFileSnippet"
            } else {
                binding.cpiBackupProgress.isIndeterminate = true
                binding.tvBackupProgressSubtitle.text = "Scanning & hashing new media..."
            }

            if (state.speedBytesPerSec > 0) {
                val speedMb = state.speedBytesPerSec / (1024.0 * 1024.0)
                binding.tvBackupSpeed.text = String.format(Locale.US, "%.1f MB/s", speedMb)
            } else {
                binding.tvBackupSpeed.text = "Hashing..."
            }

            binding.tvBackupEta.text = formatEta(state.etaSeconds)
        } else {
            binding.cpiBackupProgress.isIndeterminate = false
            binding.layoutBackupMetrics.visibility = View.GONE

            if (!state.lastError.isNullOrEmpty() && state.failedCount > 0) {
                binding.tvBackupProgressTitle.text = "Backup Paused"
                binding.tvBackupProgressSubtitle.text = "${state.failedCount} file(s) failed to back up"

                // Show readable error banner with clear action
                binding.cardErrorBanner.visibility = View.VISIBLE
                val err = state.lastError ?: ""
                if (err.contains("user_disabled") || err.contains("Account disabled")) {
                    binding.tvErrorBannerTitle.text = "Account Disabled"
                    binding.tvErrorBannerMessage.text = "Your account has been disabled by the vault owner."
                    binding.btnErrorAction.visibility = View.GONE
                } else if (err.contains("quota_exceeded") || err.contains("Vault is full")) {
                    binding.tvErrorBannerTitle.text = "Storage Limit Reached"
                    binding.tvErrorBannerMessage.text = "Vault storage quota exceeded. Contact the vault owner."
                    binding.btnErrorAction.visibility = View.VISIBLE
                    binding.btnErrorAction.text = "Check Quota"
                } else {
                    binding.tvErrorBannerTitle.text = "Attention Required"
                    binding.tvErrorBannerMessage.text = state.lastError
                    binding.btnErrorAction.visibility = View.VISIBLE
                    binding.btnErrorAction.text = "Retry Now"
                }
            } else {
                binding.cardErrorBanner.visibility = View.GONE
                val isMember = !SyncNodePreferences.isOwner(context)
                val memberName = SyncNodePreferences.getDisplayName(context).ifEmpty { "Member" }
                val memberQuotaBytes = SyncNodePreferences.getQuotaBytes(context)
                val memberUsedBytes = SyncNodePreferences.getUsedBytes(context)
                val memberQuotaStr = if (memberQuotaBytes == 0L) "Unlimited" else formatBytes(memberQuotaBytes)
                val memberUsageStr = "Joined as $memberName · ${formatBytes(memberUsedBytes)} of $memberQuotaStr used"

                if (state.isAllBackedUp) {
                    binding.tvBackupProgressTitle.text = "All Photos Backed Up"
                    binding.tvBackupProgressSubtitle.text = if (isMember) memberUsageStr else "All ${state.totalCount} items synced with node"
                } else if (state.doneCount > 0) {
                    binding.tvBackupProgressTitle.text = "Ready to Back Up"
                    binding.tvBackupProgressSubtitle.text = if (isMember) memberUsageStr else "${state.doneCount} of ${state.totalCount} backed up"
                } else {
                    binding.tvBackupProgressTitle.text = "Ready to Back Up"
                    binding.tvBackupProgressSubtitle.text = if (isMember) memberUsageStr else "Connected · Ready to sync"
                }
            }
        }
    }

    private fun refreshQueue() {
        val context = context ?: return
        if (!SyncNodePreferences.isPaired(context)) {
            binding.headerBackupQueue.visibility = View.GONE
            binding.rvBackupQueue.visibility = View.GONE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val syncRepo = SyncRepository.getInstance(context)
            val queueItems = syncRepo.getQueueItems(30)
            val hasFailed = queueItems.any { it.status == "FAILED" }

            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (queueItems.isEmpty()) {
                    binding.headerBackupQueue.visibility = View.GONE
                    binding.rvBackupQueue.visibility = View.GONE
                } else {
                    binding.headerBackupQueue.visibility = View.VISIBLE
                    binding.rvBackupQueue.visibility = View.VISIBLE
                    binding.btnRetryAllFailed.visibility = if (hasFailed) View.VISIBLE else View.GONE
                    queueAdapter.submitList(queueItems)
                }
            }
        }
    }

    private fun refreshServerFolders() {
        val context = context ?: return
        if (!SyncNodePreferences.isPaired(context)) return

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val syncRepo = SyncRepository.getInstance(context)
            val result = syncRepo.fetchDeviceFolders()
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                result.onSuccess { folders ->
                    folderAdapter.submitList(folders)
                }.onFailure {
                    // If node offline or error, show monitored local folder defaults
                    val defaults = listOf(
                        ServerFolderInfo("Camera", 0, 0L),
                        ServerFolderInfo("Screenshots", 0, 0L)
                    )
                    folderAdapter.submitList(defaults)
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
        val client = PiHttpClient.getInstance(context)
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
                    "Connected! Free disk: ${String.format(Locale.US, "%.1f", freeGb)} GB, Quota: ${String.format(Locale.US, "%.1f", quotaGb)} GB",
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

    private class QueueAdapter(
        private val onRetryItem: (SyncLedgerEntity) -> Unit
    ) : RecyclerView.Adapter<QueueViewHolder>() {
        private var items: List<SyncLedgerEntity> = emptyList()

        fun submitList(newItems: List<SyncLedgerEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
            val binding = ItemBackupQueueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return QueueViewHolder(binding, onRetryItem)
        }

        override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class QueueViewHolder(
        private val binding: ItemBackupQueueBinding,
        private val onRetryItem: (SyncLedgerEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SyncLedgerEntity) {
            val context = binding.root.context
            binding.tvQueueFileName.text = "media_${item.mediaId}"
            val formattedSize = formatBytes(item.size)

            when (item.status) {
                "UPLOADING" -> {
                    binding.tvQueueDetails.text = "$formattedSize · Streaming..."
                    binding.tvQueueStatusBadge.text = "UPLOADING"
                    binding.tvQueueStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.primary_blue))
                    binding.btnQueueRetry.visibility = View.GONE
                }
                "HASHING" -> {
                    binding.tvQueueDetails.text = "$formattedSize · Calculating SHA-256"
                    binding.tvQueueStatusBadge.text = "HASHING"
                    binding.tvQueueStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_amber))
                    binding.btnQueueRetry.visibility = View.GONE
                }
                "FAILED" -> {
                    val err = item.lastError ?: "Upload error"
                    binding.tvQueueDetails.text = "$formattedSize · $err"
                    binding.tvQueueStatusBadge.text = "FAILED"
                    binding.tvQueueStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.status_red))
                    binding.btnQueueRetry.visibility = View.VISIBLE
                    binding.btnQueueRetry.setOnClickListener { onRetryItem(item) }
                }
                else -> {
                    binding.tvQueueDetails.text = "$formattedSize · Waiting in queue"
                    binding.tvQueueStatusBadge.text = "PENDING"
                    binding.tvQueueStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    binding.btnQueueRetry.visibility = View.GONE
                }
            }
        }
    }

    private class ServerFolderAdapter : RecyclerView.Adapter<ServerFolderViewHolder>() {
        private var items: List<ServerFolderInfo> = emptyList()

        fun submitList(newItems: List<ServerFolderInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerFolderViewHolder {
            val binding = ItemServerFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ServerFolderViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ServerFolderViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class ServerFolderViewHolder(
        private val binding: ItemServerFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: ServerFolderInfo) {
            val context = binding.root.context
            binding.tvServerFolderName.text = folder.name
            val bytesStr = formatBytes(folder.totalBytes)
            binding.tvServerFolderMetrics.text = "${folder.itemCount} items · $bytesStr on node"

            val iconRes = when {
                folder.name.contains("Camera", ignoreCase = true) -> R.drawable.ic_camera
                folder.name.contains("Screenshot", ignoreCase = true) -> R.drawable.ic_phone
                else -> R.drawable.ic_nav_photos
            }
            binding.ivServerFolderIcon.setImageResource(iconRes)

            val isMonitored = when {
                folder.name.equals("Camera", ignoreCase = true) -> BackupPreferences.isCameraFolderEnabled(context)
                folder.name.equals("Screenshots", ignoreCase = true) -> BackupPreferences.isScreenshotsFolderEnabled(context)
                else -> true
            }

            binding.tvFolderStatus.text = if (isMonitored) "Monitored" else "Paused"
            binding.tvFolderStatus.setTextColor(
                ContextCompat.getColor(context, if (isMonitored) R.color.text_secondary else R.color.text_tertiary)
            )
            binding.dotFolderStatus.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (isMonitored) R.color.primary_blue else R.color.text_tertiary)
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = BackupFragment()

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
