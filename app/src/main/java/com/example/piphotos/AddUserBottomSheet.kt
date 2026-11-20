package com.example.piphotos

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.BottomSheetAddUserBinding
import com.example.piphotos.net.AdminUser
import com.example.piphotos.net.CreateUserResult
import com.example.piphotos.net.PiHttpClient
import com.example.piphotos.net.QrCodeGenerator
import com.example.piphotos.sync.SyncNodePreferences
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

class AddUserBottomSheet(
    private val onUserCreated: ((AdminUser) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddUserBinding? = null
    private val binding get() = _binding!!

    private var selectedQuotaGb: Double? = 50.0 // null means unlimited
    private var lastResult: CreateUserResult? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddUserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupQuotaButtons()

        binding.btnSubmitCreate.setOnClickListener {
            submitNewUser()
        }

        binding.btnCopyInviteCode.setOnClickListener {
            val code = lastResult?.inviteCode ?: return@setOnClickListener
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Invite Code", code))
            Toast.makeText(requireContext(), "Invite code copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareInvite.setOnClickListener {
            val res = lastResult ?: return@setOnClickListener
            val shareText = "Join my private photo vault!\n\n" +
                "1. Open PiPhotos on your phone.\n" +
                "2. In Backup -> Connect, enter code: ${res.inviteCode}\n\n" +
                "Pairing Link: ${res.qrPayload}"
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "PiPhotos Vault Invite")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(sendIntent, "Share Vault Invite"))
        }

        binding.btnCloseSheet.setOnClickListener {
            dismiss()
        }
    }

    private fun setupQuotaButtons() {
        fun select(gb: Double?) {
            selectedQuotaGb = gb
            binding.etCustomQuota.setText("")
            binding.tvErrorMessage.visibility = View.GONE

            binding.btnPreset10.isSelected = (gb == 10.0)
            binding.btnPreset50.isSelected = (gb == 50.0)
            binding.btnPreset100.isSelected = (gb == 100.0)
            binding.btnPresetUnlimited.isSelected = (gb == null)
        }

        select(50.0)

        binding.btnPreset10.setOnClickListener { select(10.0) }
        binding.btnPreset50.setOnClickListener { select(50.0) }
        binding.btnPreset100.setOnClickListener { select(100.0) }
        binding.btnPresetUnlimited.setOnClickListener { select(null) }
    }

    private fun submitNewUser() {
        val name = binding.etUserName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etUserName.error = "Display name is required"
            return
        }

        val customStr = binding.etCustomQuota.text.toString().trim()
        val (quotaBytes, unlimited) = if (customStr.isNotEmpty()) {
            val customGb = customStr.toDoubleOrNull()
            if (customGb == null || customGb <= 0.0) {
                binding.tvErrorMessage.text = "Please enter a valid numeric quota."
                binding.tvErrorMessage.visibility = View.VISIBLE
                return
            }
            Pair((customGb * 1024L * 1024L * 1024L).toLong(), false)
        } else if (selectedQuotaGb == null) {
            Pair(null, true)
        } else {
            val quotaGb = selectedQuotaGb ?: 50.0
            Pair((quotaGb * 1024L * 1024L * 1024L).toLong(), false)
        }

        val ctx = requireContext()
        val serverUrl = SyncNodePreferences.getServerUrl(ctx) ?: ""
        val authToken = SyncNodePreferences.getAuthToken(ctx) ?: ""
        val fp = SyncNodePreferences.getTlsFingerprint(ctx)

        if (serverUrl.isEmpty() || authToken.isEmpty()) {
            Toast.makeText(ctx, "Storage node not connected", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnSubmitCreate.isEnabled = false
        binding.tvErrorMessage.visibility = View.GONE

        val client = PiHttpClient.getInstance(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            val res = client.createAdminUser(
                endpointUrl = serverUrl,
                authToken = authToken,
                displayName = name,
                quotaBytes = quotaBytes,
                unlimited = unlimited,
                expectedFingerprint = fp
            )

            binding.btnSubmitCreate.isEnabled = true

            res.onSuccess { result ->
                lastResult = result
                onUserCreated?.invoke(result.user)

                binding.containerForm.visibility = View.GONE
                binding.containerResult.visibility = View.VISIBLE

                binding.tvInviteForUser.text = "Created member: ${result.user.displayName}"
                binding.tvInviteCode.text = result.inviteCode

                val qrBitmap = QrCodeGenerator.generateQrBitmap(result.qrPayload, size = 512)
                if (qrBitmap != null) {
                    binding.ivInviteQr.setImageBitmap(qrBitmap)
                    binding.ivInviteQr.visibility = View.VISIBLE
                } else {
                    binding.ivInviteQr.visibility = View.GONE
                }
            }.onFailure { err ->
                binding.tvErrorMessage.text = err.localizedMessage ?: "Failed to create user"
                binding.tvErrorMessage.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddUserBottomSheet"
    }
}
