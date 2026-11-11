package com.example.piphotos

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.piphotos.databinding.FragmentProfileBinding
import com.example.piphotos.databinding.ItemNodeUserBinding

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnProfileSetupPi.setOnClickListener {
            Toast.makeText(requireContext(), "Pi Vault onboarding will be available in Phase G7", Toast.LENGTH_SHORT).show()
        }

        // Dynamic People on this Node list (only if connected to a node with users)
        renderUsersList()

        // Storage Distribution Rebalance Sheet
        binding.btnRebalanceStorage.setOnClickListener {
            StorageDistributionBottomSheet {
                bindDeviceStorage()
                renderUsersList()
            }.show(childFragmentManager, StorageDistributionBottomSheet.TAG)
        }

        // Invite User
        binding.btnInviteUser.setOnClickListener {
            startActivity(Intent(requireContext(), InviteUserActivity::class.java))
        }

        // Node & Network Preferences
        binding.rowTailscale.setOnClickListener {
            startActivity(Intent(requireContext(), NetworkPreferencesActivity::class.java))
        }

        binding.rowPipeline.setOnClickListener {
            startActivity(Intent(requireContext(), NodePreferencesActivity::class.java))
        }

        binding.btnSignOut.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Sign Out of Node?")
                .setMessage("This will disconnect this device from your Raspberry Pi Zero 2 W vault over Tailscale. Encrypted offline cache will be retained securely.")
                .setPositiveButton("Sign Out") { _, _ ->
                    NodeRepository.signOut()
                    Toast.makeText(requireContext(), "Signed out of node. Re-authenticate to access vault.", Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        setupThemeToggle()
        bindDeviceStorage()
    }

    override fun onResume() {
        super.onResume()
        bindDeviceStorage()
        renderUsersList()
    }

    private fun bindDeviceStorage() {
        _binding?.let { b ->
            val users = NodeRepository.getUsers()
            if (users.isNotEmpty()) {
                val adminUser = users.first()
                val usedFormatted = String.format("%.1f", adminUser.usedGB)
                val quotaFormatted = String.format("%.0f", adminUser.quotaGB)
                val remaining = adminUser.quotaGB - adminUser.usedGB
                val remainingFormatted = String.format("%.1f", if (remaining > 0) remaining else 0.0)
                val percent = if (adminUser.quotaGB > 0) ((adminUser.usedGB / adminUser.quotaGB) * 100).toInt() else 0

                b.tvQuotaUsed.text = "$usedFormatted GB of $quotaFormatted GB used"
                b.tvQuotaFree.text = "$remainingFormatted GB free"
                b.pbPersonalQuota.progress = percent
            } else {
                try {
                    val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
                    val blockSize = stat.blockSizeLong
                    val totalBlocks = stat.blockCountLong
                    val availableBlocks = stat.availableBlocksLong

                    val totalGB = (totalBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
                    val freeGB = (availableBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
                    val usedGB = maxOf(0.0, totalGB - freeGB)
                    val percent = if (totalGB > 0) ((usedGB / totalGB) * 100).toInt() else 0

                    b.tvQuotaUsed.text = "${String.format("%.1f", usedGB)} GB of ${String.format("%.0f", totalGB)} GB used"
                    b.tvQuotaFree.text = "${String.format("%.1f", freeGB)} GB free"
                    b.pbPersonalQuota.progress = percent
                } catch (_: Exception) {
                    b.tvQuotaUsed.text = "Internal Storage"
                    b.tvQuotaFree.text = "Available"
                    b.pbPersonalQuota.progress = 10
                }
            }
        }
    }

    private fun renderUsersList() {
        val b = _binding ?: return
        val users = NodeRepository.getUsers()
        b.layoutPeopleSection.visibility = if (users.isNotEmpty()) View.VISIBLE else View.GONE
        b.layoutNodePill.visibility = if (NodeRepository.nodeName.isNotEmpty()) View.VISIBLE else View.GONE
        b.rowTailscale.visibility = if (NodeRepository.tailscaleDomain.isNotEmpty()) View.VISIBLE else View.GONE
        b.btnSignOut.visibility = if (users.isNotEmpty()) View.VISIBLE else View.GONE

        val container = b.containerUsersList
        container.removeAllViews()
        if (users.isEmpty()) return

        val inflater = LayoutInflater.from(requireContext())

        for ((index, user) in users.withIndex()) {
            val userBinding = ItemNodeUserBinding.inflate(inflater, container, false)
            userBinding.tvUserInitials.text = user.initials
            userBinding.tvUserName.text = user.name
            userBinding.badgeUserRole.text = user.role
            userBinding.tvUserStorageSummary.text = "${String.format("%.1f", user.usedGB)} GB / ${String.format("%.1f", user.quotaGB)} GB (${user.role})"

            userBinding.dividerUserRow.visibility = if (index == users.size - 1) View.GONE else View.VISIBLE

            setupUserRow(userBinding.root, user.id, user.name)
            container.addView(userBinding.root)
        }
    }

    private fun setupUserRow(row: View, userId: String, userName: String) {
        row.setOnClickListener {
            UserDetailActivity.start(requireContext(), userId)
        }

        row.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showUserAdminQuickMenu(userId, userName)
            true
        }
    }

    private fun showUserAdminQuickMenu(userId: String, userName: String) {
        val user = NodeRepository.getUser(userId) ?: return
        val toggleAction = if (user.isActive) "Suspend Access" else "Enable Access"

        val options = arrayOf(
            "View Full Profile & Storage",
            "Modify Quota Limit (${String.format("%.0f", user.quotaGB)} GB)",
            toggleAction,
            "Remove from Node"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("User Management: $userName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> UserDetailActivity.start(requireContext(), userId, openQuota = false)
                    1 -> UserDetailActivity.start(requireContext(), userId, openQuota = true)
                    2 -> {
                        if (user.role.equals("Admin", ignoreCase = true) || user.id == "u1") {
                            Toast.makeText(requireContext(), "Primary admin account cannot be suspended", Toast.LENGTH_SHORT).show()
                        } else {
                            val active = NodeRepository.toggleUserStatus(userId)
                            renderUsersList()
                            val msg = if (active) "Access restored for ${user.name}" else "Access suspended for ${user.name}"
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    3 -> {
                        if (user.role.equals("Admin", ignoreCase = true) || user.id == "u1") {
                            Toast.makeText(requireContext(), "Primary admin account cannot be removed", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(requireContext())
                                .setTitle("Remove $userName?")
                                .setMessage("Revoke Tailscale access and purge ${user.usedGB} GB of encrypted media from Pi SSD?")
                                .setPositiveButton("Remove") { _, _ ->
                                    val removed = NodeRepository.removeUser(userId)
                                    if (removed) {
                                        renderUsersList()
                                        bindDeviceStorage()
                                        Toast.makeText(requireContext(), "$userName removed from node", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(requireContext(), "Failed to remove user", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                }
            }
            .show()
    }

    private fun setupThemeToggle() {
        val currentMode = ThemeManager.getThemeMode(requireContext())
        updateThemePills(currentMode)

        binding.btnThemeLight.setOnClickListener {
            if (ThemeManager.getThemeMode(requireContext()) != ThemeManager.THEME_LIGHT) {
                ThemeManager.setThemeMode(requireContext(), ThemeManager.THEME_LIGHT)
                updateThemePills(ThemeManager.THEME_LIGHT)
            }
        }

        binding.btnThemeDark.setOnClickListener {
            if (ThemeManager.getThemeMode(requireContext()) != ThemeManager.THEME_DARK) {
                ThemeManager.setThemeMode(requireContext(), ThemeManager.THEME_DARK)
                updateThemePills(ThemeManager.THEME_DARK)
            }
        }

        binding.btnThemeSystem.setOnClickListener {
            if (ThemeManager.getThemeMode(requireContext()) != ThemeManager.THEME_SYSTEM) {
                ThemeManager.setThemeMode(requireContext(), ThemeManager.THEME_SYSTEM)
                updateThemePills(ThemeManager.THEME_SYSTEM)
            }
        }
    }

    private fun updateThemePills(mode: String) {
        val activeBg = R.drawable.bg_pill_capsule
        val activeText = ContextCompat.getColor(requireContext(), R.color.text_primary)
        val inactiveText = ContextCompat.getColor(requireContext(), R.color.text_tertiary)

        binding.btnThemeLight.background = if (mode == ThemeManager.THEME_LIGHT) ContextCompat.getDrawable(requireContext(), activeBg) else null
        binding.btnThemeLight.setTextColor(if (mode == ThemeManager.THEME_LIGHT) activeText else inactiveText)

        binding.btnThemeDark.background = if (mode == ThemeManager.THEME_DARK) ContextCompat.getDrawable(requireContext(), activeBg) else null
        binding.btnThemeDark.setTextColor(if (mode == ThemeManager.THEME_DARK) activeText else inactiveText)

        binding.btnThemeSystem.background = if (mode == ThemeManager.THEME_SYSTEM) ContextCompat.getDrawable(requireContext(), activeBg) else null
        binding.btnThemeSystem.setTextColor(if (mode == ThemeManager.THEME_SYSTEM) activeText else inactiveText)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = ProfileFragment()
    }
}
