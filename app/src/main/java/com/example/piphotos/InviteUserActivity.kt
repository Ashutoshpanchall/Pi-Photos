package com.example.piphotos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.piphotos.databinding.ActivityInviteUserBinding

class InviteUserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInviteUserBinding
    private var selectedQuotaGB = 50.0
    private var isTB = false
    private var generatedInviteUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityInviteUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowInsetsHelper.applyEdgeToEdge(
            activity = this,
            rootView = binding.inviteUserRoot,
            statusBarSpacer = binding.statusBarSpacer,
            topBar = binding.inviteTopBar,
            extraTopDp = 0
        )

        binding.btnInviteBack.setOnClickListener {
            finish()
        }

        binding.tvInviteAvailableCapacity.text = "${String.format("%.1f", NodeRepository.getUnallocatedCapacityGB())} GB Free of ${String.format("%.0f", NodeRepository.totalDiskGB)} GB Storage"

        setupQuotaPresets()

        binding.btnUnitToggleInvite.setOnClickListener {
            isTB = !isTB
            binding.btnUnitToggleInvite.text = if (isTB) "TB" else "GB"
        }

        binding.btnGenerateInvite.setOnClickListener {
            generateInvitation()
        }

        binding.btnCopyInvite.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Vault Invite", generatedInviteUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Invite link copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupQuotaPresets() {
        val presets = NodeRepository.getDynamicPresetsForNewUser()
        val buttons = listOf(binding.btnQuota10, binding.btnQuota25, binding.btnQuota50, binding.btnQuota100)

        for (i in 0 until Math.min(presets.size, buttons.size)) {
            buttons[i].text = presets[i].title
        }

        selectedQuotaGB = presets.getOrNull(1)?.quotaGB ?: 25.0

        fun updateStyles(target: Double) {
            val pill = R.drawable.bg_pill_capsule
            val normal = R.drawable.bg_invite_button
            for (i in 0 until Math.min(presets.size, buttons.size)) {
                val matches = Math.abs(target - presets[i].quotaGB) < 0.05
                buttons[i].setBackgroundResource(if (matches) pill else normal)
            }
        }

        updateStyles(selectedQuotaGB)

        for (i in 0 until Math.min(presets.size, buttons.size)) {
            val preset = presets[i]
            buttons[i].setOnClickListener {
                selectedQuotaGB = preset.quotaGB
                binding.etCustomQuotaInvite.setText("")
                binding.tvInviteError.visibility = View.GONE
                updateStyles(preset.quotaGB)
            }
        }
    }

    private fun generateInvitation() {
        val email = binding.etInviteEmail.text.toString().trim()
        val name = binding.etInviteName.text.toString().trim()

        if (email.isEmpty()) {
            Toast.makeText(this, "Please enter an email address or username", Toast.LENGTH_SHORT).show()
            return
        }

        val customStr = binding.etCustomQuotaInvite.text.toString().trim()
        val effectiveQuota = if (customStr.isNotEmpty()) {
            val num = customStr.toDoubleOrNull()
            if (num == null || num <= 0.0) {
                binding.tvInviteError.text = "Please enter a valid numeric storage quota."
                binding.tvInviteError.visibility = View.VISIBLE
                return
            }
            if (isTB) num * 1024.0 else num
        } else {
            selectedQuotaGB
        }

        val validation = NodeRepository.canInviteUser(effectiveQuota)
        if (!validation.first) {
            binding.tvInviteError.text = validation.second
            binding.tvInviteError.visibility = View.VISIBLE
            return
        }

        binding.tvInviteError.visibility = View.GONE

        // Prevent double-tap by disabling the generate button
        binding.btnGenerateInvite.isEnabled = false
        binding.btnGenerateInvite.alpha = 0.5f

        // Create Pending Invite (user is NOT created until redeemed)
        val invite = NodeRepository.createPendingInvite(
            email = email,
            displayName = if (name.isNotEmpty()) name else email.substringBefore("@"),
            role = "User",
            quotaGB = effectiveQuota
        )

        generatedInviteUrl = "https://${NodeRepository.REMOTE_DOMAIN}/invite?token=${invite.token}"
        binding.tvInviteUrl.text = generatedInviteUrl
        binding.layoutInviteResult.visibility = View.VISIBLE

        Toast.makeText(
            this,
            "Pending invite created for ${invite.displayName} (Expires in 48h)",
            Toast.LENGTH_LONG
        ).show()
    }
}
