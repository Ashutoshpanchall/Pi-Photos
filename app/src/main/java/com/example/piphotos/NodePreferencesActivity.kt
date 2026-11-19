package com.example.piphotos

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.ActivityNodePreferencesBinding
import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.sync.SyncNodePreferences
import kotlinx.coroutines.launch
import java.util.Locale

class NodePreferencesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodePreferencesBinding
    private val httpClient by lazy { PiHttpClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityNodePreferencesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.nodePrefsRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.nodePrefsTopBar,
            extraTopDp = 0
        )

        binding.btnNodeBack.setOnClickListener {
            finish()
        }

        binding.btnNodeRefresh.setOnClickListener {
            loadNodeMetrics()
        }

        binding.btnTestConnection.setOnClickListener {
            loadNodeMetrics()
        }

        binding.btnUnpairNode.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Unpair Storage Node?")
                .setMessage("Your local photos will remain on this device. Automated cloud backup will be disabled until you pair again.")
                .setPositiveButton("Unpair") { _, _ ->
                    SyncNodePreferences.clear(this)
                    Toast.makeText(this, "Storage node unpaired", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadNodeMetrics()
    }

    private fun loadNodeMetrics() {
        val isConfigured = SyncNodePreferences.isConfigured(this)
        if (!isConfigured) {
            binding.layoutUnpairedState.visibility = View.VISIBLE
            binding.layoutPairedContent.visibility = View.GONE
            return
        }

        binding.layoutUnpairedState.visibility = View.GONE
        binding.layoutPairedContent.visibility = View.VISIBLE

        val serverUrl = SyncNodePreferences.getServerUrl(this) ?: ""
        val nodeName = SyncNodePreferences.getNodeName(this) ?: "Storage Node"
        val fingerprint = SyncNodePreferences.getTlsFingerprint(this)
        val authToken = SyncNodePreferences.getAuthToken(this) ?: ""

        binding.tvNodeName.text = nodeName
        binding.tvNodeAddress.text = serverUrl
        binding.tvCertificateFingerprint.text = fingerprint ?: "Pinned on first use"

        binding.tvNodeStatus.text = "Checking..."
        binding.dotNodeStatus.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.status_amber)
        )

        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            val infoResult = httpClient.getInfo(serverUrl, fingerprint)
            val elapsedMs = System.currentTimeMillis() - startMs

            infoResult.onSuccess { (info, _) ->
                binding.tvNodeName.text = info.name
                binding.tvProtocolVersion.text = "v${info.version}"
                binding.tvNodeStatus.text = "Connected (${elapsedMs} ms)"
                binding.dotNodeStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this@NodePreferencesActivity, R.color.status_green)
                )
            }.onFailure {
                binding.tvNodeStatus.text = "Unreachable"
                binding.dotNodeStatus.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this@NodePreferencesActivity, R.color.status_red)
                )
            }

            if (authToken.isNotEmpty()) {
                val statusResult = httpClient.getStatus(serverUrl, authToken, fingerprint)
                statusResult.onSuccess { status ->
                    binding.tvDiskTotal.text = formatBytes(status.diskTotal)
                    binding.tvDiskUsed.text = formatBytes(status.diskUsed)
                    binding.tvDiskFree.text = formatBytes(status.diskFree)
                    binding.tvDiskReserved.text = formatBytes(status.diskReserved)

                    binding.tvUserQuota.text = formatBytes(status.userQuota)
                    binding.tvUserUsed.text = formatBytes(status.userUsed)
                    binding.tvUserFree.text = formatBytes(status.userFree)
                    binding.tvUserFiles.text = "${status.userFileCount} items backed up"
                }.onFailure { err ->
                    binding.tvDiskTotal.text = "Unavailable"
                    binding.tvDiskUsed.text = "Unavailable"
                    binding.tvDiskFree.text = "Unavailable"
                    binding.tvDiskReserved.text = "Unavailable"

                    binding.tvUserQuota.text = "Unavailable"
                    binding.tvUserUsed.text = "Unavailable"
                    binding.tvUserFree.text = "Unavailable"
                    binding.tvUserFiles.text = "Unavailable"
                    Toast.makeText(
                        this@NodePreferencesActivity,
                        "Failed to fetch status: ${err.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
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
