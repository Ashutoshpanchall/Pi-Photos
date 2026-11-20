package com.example.piphotos

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.ActivityUsersAndStorageBinding
import com.example.piphotos.databinding.ItemAdminUserCardBinding
import com.example.piphotos.net.AdminOverview
import com.example.piphotos.net.AdminUser
import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.sync.SyncNodePreferences
import kotlinx.coroutines.launch
import java.util.Locale

class UsersAndStorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUsersAndStorageBinding
    private val httpClient by lazy { PiHttpClient.getInstance(this) }
    private var openPairingTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityUsersAndStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.usersAndStorageRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.topBar,
            extraTopDp = 0
        )

        if (!SyncNodePreferences.isOwner(this)) {
            Toast.makeText(this, "Admin access required", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnRefresh.setOnClickListener {
            loadOverviewAndUsers()
        }

        binding.btnAddUser.setOnClickListener {
            AddUserBottomSheet {
                loadOverviewAndUsers()
            }.show(supportFragmentManager, AddUserBottomSheet.TAG)
        }

        binding.btnPairAnotherDevice.setOnClickListener {
            openPairingWindow()
        }

        loadOverviewAndUsers()
    }

    override fun onResume() {
        super.onResume()
        if (SyncNodePreferences.isOwner(this)) {
            loadOverviewAndUsers()
        }
    }

    private fun loadOverviewAndUsers() {
        val serverUrl = SyncNodePreferences.getServerUrl(this) ?: ""
        val authToken = SyncNodePreferences.getAuthToken(this) ?: ""
        val fp = SyncNodePreferences.getTlsFingerprint(this)

        if (serverUrl.isEmpty() || authToken.isEmpty()) {
            Toast.makeText(this, "Storage node not connected", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            // 1. Fetch overview
            val overviewResult = httpClient.getAdminOverview(serverUrl, authToken, fp)
            overviewResult.onSuccess { overview ->
                bindOverview(overview)
            }.onFailure { err ->
                Toast.makeText(this@UsersAndStorageActivity, "Failed to load storage overview: ${err.message}", Toast.LENGTH_SHORT).show()
            }

            // 2. Fetch users list
            val usersResult = httpClient.listAdminUsers(serverUrl, authToken, fp)
            usersResult.onSuccess { users ->
                bindUsers(users)
            }.onFailure { err ->
                Toast.makeText(this@UsersAndStorageActivity, "Failed to load users: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindOverview(overview: AdminOverview) {
        val freeGb = overview.diskFree / (1024.0 * 1024.0 * 1024.0)
        val totalGb = overview.diskTotal / (1024.0 * 1024.0 * 1024.0)
        val reservedGb = overview.diskReserved / (1024.0 * 1024.0 * 1024.0)
        val allocatedGb = overview.allocatedQuotaTotal / (1024.0 * 1024.0 * 1024.0)

        binding.tvOverviewFree.text = String.format(Locale.US, "%.1f GB Free", freeGb)
        binding.tvOverviewTotal.text = String.format(Locale.US, "of %.1f GB", totalGb)
        binding.tvOverviewQuotasSum.text = String.format(Locale.US, "Allocated Quotas: %.1f GB", allocatedGb)
        binding.tvOverviewReserved.text = String.format(Locale.US, "Disk Reserve: %.1f GB", reservedGb)

        val usedDisk = overview.diskTotal - overview.diskFree
        val pct = if (overview.diskTotal > 0) ((usedDisk.toDouble() / overview.diskTotal.toDouble()) * 100).toInt() else 0
        binding.pbOverviewStorage.progress = pct

        if (overview.overAllocated) {
            val excessBytes = overview.allocatedQuotaTotal - overview.diskTotal
            val excessGb = maxOf(0.0, excessBytes / (1024.0 * 1024.0 * 1024.0))
            binding.tvOverAllocatedText.text = String.format(
                Locale.US,
                "Quotas exceed disk space by %.1f GB. Backups may stop early.",
                excessGb
            )
            binding.layoutOverAllocatedBanner.visibility = View.VISIBLE
        } else {
            binding.layoutOverAllocatedBanner.visibility = View.GONE
        }
    }

    private fun bindUsers(users: List<AdminUser>) {
        val container = binding.containerUsers
        container.removeAllViews()

        if (users.isEmpty()) {
            binding.tvEmptyUsers.visibility = View.VISIBLE
            return
        }
        binding.tvEmptyUsers.visibility = View.GONE
        binding.tvUsersHeader.text = "VAULT USERS (${users.size})"

        val inflater = LayoutInflater.from(this)

        for (user in users) {
            val itemBinding = ItemAdminUserCardBinding.inflate(inflater, container, false)
            itemBinding.tvUserName.text = user.displayName

            // Role badge
            val isOwner = user.role.equals("owner", ignoreCase = true)
            itemBinding.badgeRole.text = if (isOwner) "Owner" else "Member"
            itemBinding.badgeRole.setTextColor(
                ContextCompat.getColor(this, if (isOwner) R.color.primary_blue else R.color.text_secondary)
            )

            // Status badge
            val isActive = user.status.equals("active", ignoreCase = true)
            itemBinding.badgeStatus.text = if (isActive) "Active" else "Disabled"
            itemBinding.badgeStatus.setTextColor(
                ContextCompat.getColor(this, if (isActive) R.color.status_green else R.color.status_red)
            )

            // Storage calculation
            val usedGb = user.usedBytes / (1024.0 * 1024.0 * 1024.0)
            if (user.quotaBytes == 0L) {
                itemBinding.tvStorageUsage.text = String.format(Locale.US, "%.1f GB used (Unlimited)", usedGb)
                itemBinding.tvUsagePercent.text = "Unlimited"
                itemBinding.pbUserStorage.progress = 0
            } else {
                val quotaGb = user.quotaBytes / (1024.0 * 1024.0 * 1024.0)
                val pct = ((user.usedBytes.toDouble() / user.quotaBytes.toDouble()) * 100).toInt()
                itemBinding.tvStorageUsage.text = String.format(Locale.US, "%.1f GB of %.1f GB used", usedGb, quotaGb)
                itemBinding.tvUsagePercent.text = "$pct%"
                itemBinding.pbUserStorage.progress = pct.coerceIn(0, 100)
            }

            // Footer
            val lastSeenStr = user.lastSeenAt ?: "Never"
            itemBinding.tvUserFooter.text = "${user.devicesCount} devices · Last seen $lastSeenStr"

            itemBinding.root.setOnClickListener {
                UserDetailActivity.start(this, user.id)
            }

            container.addView(itemBinding.root)
        }
    }

    private fun openPairingWindow() {
        val serverUrl = SyncNodePreferences.getServerUrl(this) ?: return
        val authToken = SyncNodePreferences.getAuthToken(this) ?: return
        val fp = SyncNodePreferences.getTlsFingerprint(this)

        lifecycleScope.launch {
            val res = httpClient.openPairing(serverUrl, authToken, fp)
            res.onSuccess { openResult ->
                showPairingOpenDialog(openResult.code, openResult.expiresInSeconds)
            }.onFailure { err ->
                Toast.makeText(this@UsersAndStorageActivity, "Failed to open pairing: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPairingOpenDialog(code: String, expiresInSeconds: Int) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_add_user, null)
        val containerForm = dialogView.findViewById<View>(R.id.containerForm)
        val containerResult = dialogView.findViewById<View>(R.id.containerResult)
        containerForm.visibility = View.GONE
        containerResult.visibility = View.VISIBLE

        val tvTitle = dialogView.findViewById<TextView>(R.id.tvInviteForUser)
        val tvCode = dialogView.findViewById<TextView>(R.id.tvInviteCode)
        val tvExpiry = dialogView.findViewById<TextView>(R.id.tvExpiryCountdown)
        val btnCopy = dialogView.findViewById<View>(R.id.btnCopyInviteCode)
        val btnShare = dialogView.findViewById<View>(R.id.btnShareInvite)
        val btnDone = dialogView.findViewById<View>(R.id.btnCloseSheet)
        val ivQr = dialogView.findViewById<View>(R.id.ivInviteQr)

        ivQr.visibility = View.GONE
        tvTitle.text = "Pair Another Device (Owner)"
        tvCode.text = code

        var dialog: AlertDialog? = null

        openPairingTimer?.cancel()
        val totalMs = expiresInSeconds * 1000L
        openPairingTimer = object : CountDownTimer(totalMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val mins = millisUntilFinished / 60000
                val secs = (millisUntilFinished % 60000) / 1000
                tvExpiry.text = String.format(Locale.US, "Pairing window closes in %02d:%02d", mins, secs)
            }

            override fun onFinish() {
                tvExpiry.text = "Pairing window expired"
            }
        }.start()

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Pairing Code", code))
            Toast.makeText(this, "Pairing code copied", Toast.LENGTH_SHORT).show()
        }

        btnShare.visibility = View.GONE
        btnDone.setOnClickListener {
            openPairingTimer?.cancel()
            dialog?.dismiss()
        }

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener { openPairingTimer?.cancel() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        openPairingTimer?.cancel()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, UsersAndStorageActivity::class.java))
        }
    }
}
