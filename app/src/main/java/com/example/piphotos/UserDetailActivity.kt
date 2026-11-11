package com.example.piphotos

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.example.piphotos.databinding.ActivityUserDetailBinding
import com.google.android.material.checkbox.MaterialCheckBox

class UserDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUserDetailBinding
    private var userId: String = "u2"

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

        userId = intent.getStringExtra(EXTRA_USER_ID) ?: "u2"

        binding.btnUserDetailBack.setOnClickListener {
            finish()
        }

        bindUserData()

        if (intent.getBooleanExtra(EXTRA_OPEN_QUOTA, false)) {
            showModifyQuotaDialog()
        }

        binding.btnAdjustQuota.setOnClickListener {
            showModifyQuotaDialog()
        }

        binding.btnToggleStatus.setOnClickListener {
            val user = NodeRepository.getUser(userId) ?: return@setOnClickListener
            val action = if (user.isActive) "Disable" else "Enable"
            AlertDialog.Builder(this)
                .setTitle("$action ${user.name}?")
                .setMessage("When disabled, this user cannot access their vault or stream media over private network.")
                .setPositiveButton(action) { _, _ ->
                    NodeRepository.toggleUserStatus(userId)
                    Toast.makeText(this, "User access updated", Toast.LENGTH_SHORT).show()
                    bindUserData()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnDeleteUser.setOnClickListener {
            val user = NodeRepository.getUser(userId) ?: return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Remove ${user.name} from Node?")
                .setMessage("Are you sure? This will revoke the user's vault access and purge their encrypted blobs (${user.usedGB} GB) from the server storage. This action cannot be undone.")
                .setPositiveButton("Remove Permanently") { _, _ ->
                    NodeRepository.removeUser(userId)
                    Toast.makeText(this, "${user.name} removed from node", Toast.LENGTH_SHORT).show()
                    finish()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun bindUserData() {
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
            binding.tvUserDetailStatusDot.text = "● Primary Admin"
            binding.tvUserDetailStatusDot.setTextColor(ContextCompat.getColor(this, R.color.primary_blue))
            binding.btnToggleStatus.visibility = View.GONE
            binding.btnDeleteUser.visibility = View.GONE
            binding.layoutAdminProtectedNotice.visibility = View.VISIBLE
        } else {
            binding.layoutAdminProtectedNotice.visibility = View.GONE
            binding.btnToggleStatus.visibility = View.VISIBLE
            binding.btnDeleteUser.visibility = View.VISIBLE

            if (user.isActive) {
                binding.tvUserDetailStatusDot.text = "● Active"
                binding.tvUserDetailStatusDot.setTextColor(ContextCompat.getColor(this, R.color.status_green))
                binding.btnToggleStatus.text = "Disable User Access"
                binding.btnToggleStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            } else {
                binding.tvUserDetailStatusDot.text = "● Suspended"
                binding.tvUserDetailStatusDot.setTextColor(ContextCompat.getColor(this, R.color.status_red))
                binding.btnToggleStatus.text = "Enable User Access"
                binding.btnToggleStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
            }
        }

        // Storage calculations
        val usedFormatted = String.format("%.1f", user.usedGB)
        val quotaFormatted = String.format("%.1f", user.quotaGB)
        val remaining = user.quotaGB - user.usedGB
        val remainingFormatted = String.format("%.1f", if (remaining > 0) remaining else 0.0)
        val usagePercent = if (user.quotaGB > 0) ((user.usedGB / user.quotaGB) * 100).toInt() else 0

        binding.tvUserDetailUsed.text = "$usedFormatted GB used"
        binding.tvUserDetailLimit.text = "$quotaFormatted GB Quota"
        binding.tvUserDetailAvailable.text = "$remainingFormatted GB remaining"
        binding.tvUserDetailPercent.text = "$usagePercent% used"

        binding.pbUserDetail.progress = usagePercent

        // Color coding progress bar based on threshold
        val progressColor = when {
            usagePercent > 90 -> ContextCompat.getColor(this, R.color.status_red)
            usagePercent > 75 -> ContextCompat.getColor(this, R.color.status_amber)
            else -> ContextCompat.getColor(this, R.color.primary_blue)
        }
        binding.pbUserDetail.progressTintList = ColorStateList.valueOf(progressColor)

        // Media Statistics
        binding.tvUserStatPhotos.text = user.photosCount.toString()
        binding.tvUserStatVideos.text = user.videosCount.toString()
        binding.tvUserStatVault.text = user.vaultCount.toString()
    }

    private fun showModifyQuotaDialog() {
        val user = NodeRepository.getUser(userId) ?: return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_modify_quota, null)

        val tvCurrentUsed = dialogView.findViewById<TextView>(R.id.tvDialogCurrentUsed)
        val tvCurrentQuota = dialogView.findViewById<TextView>(R.id.tvDialogCurrentQuota)
        val tvAvailable = dialogView.findViewById<TextView>(R.id.tvDialogAvailableCapacity)
        val btnPreset1 = dialogView.findViewById<TextView>(R.id.btnPreset1)
        val btnPreset2 = dialogView.findViewById<TextView>(R.id.btnPreset2)
        val btnPreset3 = dialogView.findViewById<TextView>(R.id.btnPreset3)
        val btnPreset4 = dialogView.findViewById<TextView>(R.id.btnPreset4)
        val etCustom = dialogView.findViewById<EditText>(R.id.etCustomQuota)
        val btnUnit = dialogView.findViewById<TextView>(R.id.btnUnitToggle)
        val tvError = dialogView.findViewById<TextView>(R.id.tvQuotaError)
        val tvNotice = dialogView.findViewById<TextView>(R.id.tvRebalanceNotice)
        val cbAutoRebalance = dialogView.findViewById<MaterialCheckBox>(R.id.cbAutoRebalance)
        val tvBreakdown = dialogView.findViewById<TextView>(R.id.tvRebalanceBreakdown)

        tvCurrentUsed.text = "${String.format("%.1f", user.usedGB)} GB"
        tvCurrentQuota.text = "${String.format("%.1f", user.quotaGB)} GB"
        tvAvailable.text = "${String.format("%.1f", NodeRepository.getUnallocatedCapacityGB())} GB Free"

        var selectedQuotaGB = user.quotaGB
        var isTB = false
        var activePlan: RebalancePlan = NodeRepository.planAutoRebalance(userId, selectedQuotaGB)

        // Generate dynamic presets tailored to N users and storage size
        val presets = NodeRepository.getDynamicPresetsForUser(userId)
        val presetButtons = listOf(btnPreset1, btnPreset2, btnPreset3, btnPreset4)

        for (i in 0 until Math.min(presets.size, presetButtons.size)) {
            presetButtons[i].text = presets[i].title
        }

        fun updatePresetStyles(target: Double) {
            val pill = R.drawable.bg_pill_capsule
            val normal = R.drawable.bg_invite_button
            for (i in 0 until Math.min(presets.size, presetButtons.size)) {
                val matches = Math.abs(target - presets[i].quotaGB) < 0.05
                presetButtons[i].setBackgroundResource(if (matches) pill else normal)
            }
        }

        fun evaluateQuota(quota: Double) {
            selectedQuotaGB = quota
            activePlan = NodeRepository.planAutoRebalance(userId, quota)

            if (!activePlan.isFeasible) {
                tvError.text = activePlan.summaryMessage
                tvError.visibility = View.VISIBLE
                tvNotice.text = "Invalid allocation requested."
                tvNotice.setTextColor(ContextCompat.getColor(this@UserDetailActivity, R.color.status_red))
                cbAutoRebalance.visibility = View.GONE
                tvBreakdown.visibility = View.GONE
                return
            }

            tvError.visibility = View.GONE

            if (activePlan.cutoffRequiredGB > 0) {
                cbAutoRebalance.visibility = View.VISIBLE
                tvBreakdown.visibility = View.VISIBLE

                if (activePlan.isOverAllocated) {
                    tvNotice.text = "⚠️ Over-allocates node capacity by ${String.format("%.1f", activePlan.overAllocatedByGB)} GB"
                    tvNotice.setTextColor(ContextCompat.getColor(this@UserDetailActivity, R.color.status_red))
                } else {
                    tvNotice.text = "Auto-rebalancing required: ${String.format("%.1f", activePlan.cutoffRequiredGB)} GB borrowed from other users."
                    tvNotice.setTextColor(ContextCompat.getColor(this@UserDetailActivity, R.color.status_amber))
                }

                val breakdownText = activePlan.userCutoffs.entries
                    .filter { it.value > 0 }
                    .joinToString("\n") { entry ->
                        val otherName = NodeRepository.getUser(entry.key)?.name ?: entry.key
                        "• $otherName: -${String.format("%.1f", entry.value)} GB unused buffer"
                    }
                tvBreakdown.text = breakdownText
            } else {
                cbAutoRebalance.visibility = View.GONE
                tvBreakdown.visibility = View.GONE
                tvNotice.text = "✓ Fits within unallocated capacity (${String.format("%.1f", activePlan.unallocatedCoveredGB)} GB free pool used)."
                tvNotice.setTextColor(ContextCompat.getColor(this@UserDetailActivity, R.color.status_green))
            }
        }

        updatePresetStyles(selectedQuotaGB)
        evaluateQuota(selectedQuotaGB)

        for (i in 0 until Math.min(presets.size, presetButtons.size)) {
            val preset = presets[i]
            presetButtons[i].setOnClickListener {
                etCustom.setText("")
                updatePresetStyles(preset.quotaGB)
                evaluateQuota(preset.quotaGB)
            }
        }

        etCustom.doAfterTextChanged { s ->
            val text = s?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                val num = text.toDoubleOrNull()
                if (num == null || num <= 0.0) {
                    tvError.text = "Please enter a valid numeric storage quota"
                    tvError.visibility = View.VISIBLE
                    tvNotice.text = "Invalid number format."
                    tvNotice.setTextColor(ContextCompat.getColor(this@UserDetailActivity, R.color.status_red))
                    cbAutoRebalance.visibility = View.GONE
                    tvBreakdown.visibility = View.GONE
                    return@doAfterTextChanged
                }
                val targetGB = if (isTB) num * 1024.0 else num
                updatePresetStyles(targetGB)
                evaluateQuota(targetGB)
            } else {
                tvError.visibility = View.GONE
                updatePresetStyles(selectedQuotaGB)
                evaluateQuota(selectedQuotaGB)
            }
        }

        btnUnit.setOnClickListener {
            isTB = !isTB
            btnUnit.text = if (isTB) "TB" else "GB"
            val text = etCustom.text.toString().trim()
            if (text.isNotEmpty()) {
                val num = text.toDoubleOrNull()
                if (num != null && num > 0.0) {
                    val targetGB = if (isTB) num * 1024.0 else num
                    updatePresetStyles(targetGB)
                    evaluateQuota(targetGB)
                }
            } else {
                evaluateQuota(selectedQuotaGB)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Modify Storage Limit for ${user.name}")
            .setView(dialogView)
            .setPositiveButton("Confirm Changes", null)
            .setNegativeButton("Cancel", null)
            .create().apply {
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val customStr = etCustom.text.toString().trim()
                    val candidateQuota = if (customStr.isNotEmpty()) {
                        val num = customStr.toDoubleOrNull()
                        if (num == null || num <= 0.0) {
                            tvError.text = "Please enter a valid numeric storage quota"
                            tvError.visibility = View.VISIBLE
                            return@setOnClickListener
                        }
                        if (isTB) num * 1024.0 else num
                    } else {
                        selectedQuotaGB
                    }

                    evaluateQuota(candidateQuota)

                    if (!activePlan.isFeasible) {
                        tvError.text = activePlan.summaryMessage
                        tvError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }

                    if (activePlan.cutoffRequiredGB > 0 && !cbAutoRebalance.isChecked) {
                        tvError.text = "Please enable auto-rebalance checkbox to borrow unused headroom from other users."
                        tvError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }

                    // Confirmation summary dialog
                    val delta = candidateQuota - user.quotaGB
                    val deltaStr = if (delta >= 0) "+${String.format("%.1f", delta)} GB" else "${String.format("%.1f", delta)} GB"
                    AlertDialog.Builder(this@UserDetailActivity)
                        .setTitle("Confirm Storage Adjustment")
                        .setMessage(
                            "User: ${user.name}\n" +
                            "New Quota: ${String.format("%.1f", candidateQuota)} GB ($deltaStr)\n\n" +
                            activePlan.summaryMessage + "\n\n" +
                            "Apply this intelligent distribution to the server storage?"
                        )
                        .setPositiveButton("Apply") { _, _ ->
                            NodeRepository.applyRebalancePlan(activePlan)
                            Toast.makeText(this@UserDetailActivity, "Storage quotas rebalanced successfully", Toast.LENGTH_SHORT).show()
                            dismiss()
                            bindUserData()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
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
