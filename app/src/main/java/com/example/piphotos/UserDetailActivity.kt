package com.example.piphotos

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.ActivityUserDetailBinding
import com.example.piphotos.databinding.ItemUserDeviceBinding
import com.example.piphotos.net.AdminDeviceSummary
import com.example.piphotos.net.AdminUser
import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.net.QrCodeGenerator
import com.example.piphotos.net.UpdateUserRequest
import com.example.piphotos.sync.SyncNodePreferences
import kotlinx.coroutines.launch
import java.util.Locale

class UserDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserDetailBinding
    private var userId: String = ""
    private val httpClient by lazy { PiHttpClient.getInstance(this) }
    private var currentUser: AdminUser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityUserDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.userDetailRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.userDetailTopBar,
            extraTopDp = 0
        )

        userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""

        binding.btnUserDetailBack.setOnClickListener {
            finish()
        }

        binding.btnRenameUser.setOnClickListener {
            showRenameDialog()
        }

        binding.btnAdjustQuota.setOnClickListener {
            showModifyQuotaDialog()
        }

        binding.btnReissueInvite.setOnClickListener {
            reissueInviteCode()
        }

        binding.btnToggleStatus.setOnClickListener {
            toggleUserStatus()
        }

        binding.btnDeleteUser.setOnClickListener {
            showDeleteUserDialog()
        }

        loadUserData()
    }

    override fun onResume() {
        super.onResume()
        loadUserData()
    }

    private fun loadUserData() {
        val serverUrl = SyncNodePreferences.getServerUrl(this) ?: ""
        val authToken = SyncNodePreferences.getAuthToken(this) ?: ""
        val fp = SyncNodePreferences.getTlsFingerprint(this)

        if (serverUrl.isEmpty() || authToken.isEmpty()) {
            loadMockUserData()
            return
        }

        lifecycleScope.launch {
            val usersRes = httpClient.listAdminUsers(serverUrl, authToken, fp)
            usersRes.onSuccess { users ->
                val found = users.find { it.id == userId }
                if (found != null) {
                    currentUser = found
                    bindAdminUser(found)
                    loadDevices(serverUrl, authToken, fp)
                } else {
                    loadMockUserData()
                }
            }.onFailure {
                loadMockUserData()
            }
        }
    }

    private fun loadDevices(serverUrl: String, authToken: String, fp: String?) {
        lifecycleScope.launch {
            val devRes = httpClient.listUserDevices(serverUrl, authToken, userId, fp)
            devRes.onSuccess { devices ->
                bindDevices(devices)
            }.onFailure {
                binding.containerDevicesList.removeAllViews()
                binding.tvNoDevices.visibility = View.VISIBLE
            }
        }
    }

    private fun bindDevices(devices: List<AdminDeviceSummary>) {
        val container = binding.containerDevicesList
        container.removeAllViews()

        if (devices.isEmpty()) {
            binding.tvNoDevices.visibility = View.VISIBLE
            return
        }
        binding.tvNoDevices.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        for (dev in devices) {
            val devBinding = ItemUserDeviceBinding.inflate(inflater, container, false)
            devBinding.tvDeviceName.text = dev.name
            val lastSeen = dev.lastSeenAt ?: "Never"
            devBinding.tvDeviceDetails.text = "${dev.model} · Last seen $lastSeen"

            devBinding.btnRevokeDevice.setOnClickListener {
                confirmRevokeDevice(dev)
            }

            container.addView(devBinding.root)
        }
    }

    private fun confirmRevokeDevice(dev: AdminDeviceSummary) {
        AlertDialog.Builder(this)
            .setTitle("Revoke ${dev.name}?")
            .setMessage("This device token will be revoked immediately. The device will be disconnected from the vault on its next request.")
            .setPositiveButton("Revoke") { _, _ ->
                val serverUrl = SyncNodePreferences.getServerUrl(this) ?: return@setPositiveButton
                val authToken = SyncNodePreferences.getAuthToken(this) ?: return@setPositiveButton
                val fp = SyncNodePreferences.getTlsFingerprint(this)

                lifecycleScope.launch {
                    val res = httpClient.revokeDevice(serverUrl, authToken, dev.id, fp)
                    res.onSuccess {
                        Toast.makeText(this@UserDetailActivity, "Device token revoked", Toast.LENGTH_SHORT).show()
                        loadDevices(serverUrl, authToken, fp)
                    }.onFailure { err ->
                        Toast.makeText(this@UserDetailActivity, "Failed to revoke device: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindAdminUser(user: AdminUser) {
        binding.tvUserDetailName.text = user.displayName
        val initials = user.displayName.split(" ")
            .filter { it.isNotEmpty() }
            .map { it.first().uppercaseChar() }
            .take(2)
            .joinToString("")
            .ifEmpty { "U" }
        binding.tvUserDetailInitials.text = initials
        binding.tvUserDetailUsername.text = "slug: ${user.slug}"

        val isOwner = user.role.equals("owner", ignoreCase = true)
        binding.tvUserDetailRoleBadge.text = if (isOwner) "Owner Account" else "Member Account"

        val isActive = user.status.equals("active", ignoreCase = true)
        if (isActive) {
            binding.tvUserDetailStatusDot.text = "● Active"
            binding.tvUserDetailStatusDot.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            binding.btnToggleStatus.text = "Disable User Access"
            binding.btnToggleStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.tvUserDetailStatusDot.text = "● Disabled"
            binding.tvUserDetailStatusDot.setTextColor(ContextCompat.getColor(this, R.color.status_red))
            binding.btnToggleStatus.text = "Enable User Access"
            binding.btnToggleStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        }

        if (isOwner) {
            binding.layoutAdminProtectedNotice.visibility = View.VISIBLE
            binding.btnDeleteUser.isEnabled = false
            binding.btnDeleteUser.alpha = 0.5f
        } else {
            binding.layoutAdminProtectedNotice.visibility = View.GONE
            binding.btnDeleteUser.isEnabled = true
            binding.btnDeleteUser.alpha = 1.0f
        }

        val usedGb = user.usedBytes / (1024.0 * 1024.0 * 1024.0)
        binding.tvUserDetailUsed.text = String.format(Locale.US, "%.1f GB used", usedGb)

        if (user.quotaBytes == 0L) {
            binding.tvUserDetailLimit.text = "Unlimited Quota"
            binding.tvUserDetailAvailable.text = "Bound by disk reserve"
            binding.tvUserDetailPercent.text = "Unlimited"
            binding.pbUserDetail.progress = 0
            binding.pbUserDetail.progressTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary_blue)
            )
        } else {
            val quotaGb = user.quotaBytes / (1024.0 * 1024.0 * 1024.0)
            val remainingGb = maxOf(0.0, quotaGb - usedGb)
            val pct = ((user.usedBytes.toDouble() / user.quotaBytes.toDouble()) * 100).toInt()

            binding.tvUserDetailLimit.text = String.format(Locale.US, "%.1f GB Quota", quotaGb)
            binding.tvUserDetailAvailable.text = String.format(Locale.US, "%.1f GB remaining", remainingGb)
            binding.tvUserDetailPercent.text = "$pct% used"

            binding.pbUserDetail.progress = pct.coerceIn(0, 100)
            val color = when {
                pct >= 90 -> ContextCompat.getColor(this, R.color.status_red)
                pct >= 75 -> ContextCompat.getColor(this, R.color.status_amber)
                else -> ContextCompat.getColor(this, R.color.primary_blue)
            }
            binding.pbUserDetail.progressTintList = ColorStateList.valueOf(color)
        }

        binding.tvUserStatPhotos.text = user.fileCount.toString()
        binding.tvUserStatVideos.text = user.devicesCount.toString()
        binding.tvUserStatVault.text = "1"
    }

    private fun loadMockUserData() {
        val user = NodeRepository.getUser(userId) ?: run {
            finish()
            return
        }
        binding.tvUserDetailName.text = user.name
        binding.tvUserDetailInitials.text = user.initials
        binding.tvUserDetailUsername.text = "${user.username} · ${user.email}"
        binding.tvUserDetailRoleBadge.text = "${user.role} Account"

        val isAdmin = user.role.equals("Admin", ignoreCase = true) || user.id == "u1"
        if (isAdmin) {
            binding.layoutAdminProtectedNotice.visibility = View.VISIBLE
            binding.btnDeleteUser.isEnabled = false
            binding.btnDeleteUser.alpha = 0.5f
        } else {
            binding.layoutAdminProtectedNotice.visibility = View.GONE
            binding.btnDeleteUser.isEnabled = true
            binding.btnDeleteUser.alpha = 1.0f
        }

        binding.tvUserDetailUsed.text = String.format(Locale.US, "%.1f GB used", user.usedGB)
        binding.tvUserDetailLimit.text = String.format(Locale.US, "%.1f GB Quota", user.quotaGB)
        val remaining = maxOf(0.0, user.quotaGB - user.usedGB)
        binding.tvUserDetailAvailable.text = String.format(Locale.US, "%.1f GB remaining", remaining)
        val pct = if (user.quotaGB > 0) ((user.usedGB / user.quotaGB) * 100).toInt() else 0
        binding.tvUserDetailPercent.text = "$pct% used"
        binding.pbUserDetail.progress = pct
    }

    private fun showRenameDialog() {
        val user = currentUser ?: return
        val input = EditText(this).apply {
            setText(user.displayName)
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Rename User")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    updateUser(UpdateUserRequest(displayName = newName))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showModifyQuotaDialog() {
        val user = currentUser ?: return
        val options = arrayOf("10 GB", "50 GB", "100 GB", "Unlimited", "Custom...")
        AlertDialog.Builder(this)
            .setTitle("Set Quota for ${user.displayName}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> applyQuotaBytes(10L * 1024 * 1024 * 1024)
                    1 -> applyQuotaBytes(50L * 1024 * 1024 * 1024)
                    2 -> applyQuotaBytes(100L * 1024 * 1024 * 1024)
                    3 -> applyQuotaBytes(0L) // Unlimited
                    4 -> showCustomQuotaDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomQuotaDialog() {
        val input = EditText(this).apply {
            hint = "Quota in GB (e.g. 25)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Custom Quota")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val gb = input.text.toString().trim().toDoubleOrNull()
                if (gb != null && gb > 0.0) {
                    applyQuotaBytes((gb * 1024L * 1024L * 1024L).toLong())
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyQuotaBytes(newQuotaBytes: Long) {
        val user = currentUser ?: return
        if (newQuotaBytes in 1 until user.usedBytes) {
            val usedGb = user.usedBytes / (1024.0 * 1024.0 * 1024.0)
            val newGb = newQuotaBytes / (1024.0 * 1024.0 * 1024.0)
            AlertDialog.Builder(this)
                .setTitle("Quota Warning")
                .setMessage(
                    String.format(
                        Locale.US,
                        "The new quota (%.1f GB) is below the current usage (%.1f GB). Uploads for this user will be blocked until usage drops.\n\nForce this quota change?",
                        newGb, usedGb
                    )
                )
                .setPositiveButton("Force") { _, _ ->
                    updateUser(UpdateUserRequest(quotaBytes = newQuotaBytes), force = true)
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            updateUser(UpdateUserRequest(quotaBytes = newQuotaBytes), force = false)
        }
    }

    private fun updateUser(req: UpdateUserRequest, force: Boolean = false) {
        val serverUrl = SyncNodePreferences.getServerUrl(this) ?: return
        val authToken = SyncNodePreferences.getAuthToken(this) ?: return
        val fp = SyncNodePreferences.getTlsFingerprint(this)

        lifecycleScope.launch {
            val res = httpClient.updateAdminUser(serverUrl, authToken, userId, req, force, fp)
            res.onSuccess { updated ->
                currentUser = updated
                bindAdminUser(updated)
                Toast.makeText(this@UserDetailActivity, "User updated", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                val msg = if (err.message?.contains("last_owner_protected") == true) {
                    "Cannot disable or demote the last active owner."
                } else {
                    err.localizedMessage ?: "Update failed"
                }
                Toast.makeText(this@UserDetailActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleUserStatus() {
        val user = currentUser ?: return
        val newStatus = if (user.status.equals("active", ignoreCase = true)) "disabled" else "active"
        updateUser(UpdateUserRequest(status = newStatus))
    }

    private fun reissueInviteCode() {
        val user = currentUser ?: return
        val serverUrl = SyncNodePreferences.getServerUrl(this) ?: return
        val authToken = SyncNodePreferences.getAuthToken(this) ?: return
        val fp = SyncNodePreferences.getTlsFingerprint(this)

        lifecycleScope.launch {
            val res = httpClient.reissueInvite(serverUrl, authToken, userId, fp)
            res.onSuccess { inviteResult ->
                showReissuedInviteDialog(user.displayName, inviteResult.inviteCode, inviteResult.qrPayload)
            }.onFailure { err ->
                Toast.makeText(this@UserDetailActivity, "Failed to reissue invite: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showReissuedInviteDialog(displayName: String, code: String, qrPayload: String) {
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
        val ivQr = dialogView.findViewById<ImageView>(R.id.ivInviteQr)

        tvTitle.text = "New Invite for $displayName"
        tvCode.text = code
        tvExpiry.text = "Expires in 24 hours · Single use"

        lifecycleScope.launch {
            val qrBitmap = QrCodeGenerator.generateQrBitmap(qrPayload, size = 512)
            if (qrBitmap != null) {
                ivQr.setImageBitmap(qrBitmap)
                ivQr.visibility = View.VISIBLE
            } else {
                ivQr.visibility = View.GONE
            }
        }

        var dialog: AlertDialog? = null

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Invite Code", code))
            Toast.makeText(this, "Invite code copied", Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            val shareText = "Join my private photo vault!\n\n" +
                "1. Open PiPhotos on your phone.\n" +
                "2. In Backup -> Connect, enter code: $code\n\n" +
                "Pairing Link: $qrPayload"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PiPhotos Vault Invite")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(sendIntent, "Share Vault Invite"))
        }

        btnDone.setOnClickListener {
            dialog?.dismiss()
        }

        dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .show()
    }

    private fun showDeleteUserDialog() {
        val user = currentUser ?: return
        val cbPermanent = CheckBox(this).apply {
            text = "Delete backed-up files permanently"
            setPadding(32, 16, 32, 16)
        }

        AlertDialog.Builder(this)
            .setTitle("Remove ${user.displayName} from Node?")
            .setMessage("Revokes vault access for this user. By default, files are safely kept in the archive folder. Check below to permanently delete files.")
            .setView(cbPermanent)
            .setPositiveButton("Remove User") { _, _ ->
                val serverUrl = SyncNodePreferences.getServerUrl(this) ?: return@setPositiveButton
                val authToken = SyncNodePreferences.getAuthToken(this) ?: return@setPositiveButton
                val fp = SyncNodePreferences.getTlsFingerprint(this)

                lifecycleScope.launch {
                    val res = httpClient.deleteAdminUser(
                        endpointUrl = serverUrl,
                        authToken = authToken,
                        userId = userId,
                        deleteFiles = cbPermanent.isChecked,
                        expectedFingerprint = fp
                    )
                    res.onSuccess {
                        Toast.makeText(this@UserDetailActivity, "User removed from node", Toast.LENGTH_SHORT).show()
                        finish()
                    }.onFailure { err ->
                        val msg = if (err.message?.contains("last_owner_protected") == true) {
                            "Cannot delete the last active owner."
                        } else {
                            err.localizedMessage ?: "Delete failed"
                        }
                        Toast.makeText(this@UserDetailActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        const val EXTRA_USER_ID = "extra_user_id"
        const val EXTRA_OPEN_QUOTA = "extra_open_quota"

        fun start(context: Context, userId: String, openQuota: Boolean = false) {
            val intent = Intent(context, UserDetailActivity::class.java).apply {
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_OPEN_QUOTA, openQuota)
            }
            context.startActivity(intent)
        }
    }
}
