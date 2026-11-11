package com.example.piphotos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.piphotos.databinding.FragmentBackupBinding

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

        // Bind BackupState (defaults to LocalOnly in Phase G1)
        val isLocalOnly = MediaRepository.backupState is BackupState.LocalOnly
        binding.cardLocalOnly.visibility = if (isLocalOnly) View.VISIBLE else View.GONE
        binding.cardPiOptIn.visibility = if (isLocalOnly) View.VISIBLE else View.GONE
        binding.cardBackupProgress.visibility = if (isLocalOnly) View.GONE else View.VISIBLE
        binding.layoutAttentionRequired.visibility = if (isLocalOnly) View.GONE else View.VISIBLE

        binding.btnSetupPi.setOnClickListener {
            Toast.makeText(requireContext(), "Pi Vault onboarding will be available in Phase G7", Toast.LENGTH_SHORT).show()
        }

        binding.btnRetry.setOnClickListener {
            Toast.makeText(requireContext(), "Upload retry will be available when connected to a Pi Node", Toast.LENGTH_SHORT).show()
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
            BackupPreferences.setWifiOnly(requireContext(), isChecked)
            val msg = if (isChecked) "Sync constrained to Wi-Fi" else "Cellular sync allowed"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchCharging.setOnCheckedChangeListener { _, isChecked ->
            BackupPreferences.setChargingOnly(requireContext(), isChecked)
            val msg = if (isChecked) "Sync will pause unless plugged in" else "Sync on battery active"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchVideos.setOnCheckedChangeListener { _, isChecked ->
            BackupPreferences.setIncludeVideos(requireContext(), isChecked)
            val msg = if (isChecked) "Video upload included (MP4/MOV)" else "Videos excluded from sync"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
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

