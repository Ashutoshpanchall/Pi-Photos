package com.example.piphotos

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.piphotos.databinding.ItemNodeUserBinding
import com.example.piphotos.databinding.SheetStorageDistributionBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class StorageDistributionBottomSheet(
    private val onUpdated: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: SheetStorageDistributionBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetStorageDistributionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindData()

        binding.btnStrategyEqual.setOnClickListener {
            NodeRepository.applyEqualSplitStrategy()
            Toast.makeText(requireContext(), "Applied Equal Split across all users", Toast.LENGTH_SHORT).show()
            bindData()
            onUpdated()
        }

        binding.btnStrategyAdmin.setOnClickListener {
            NodeRepository.applyAdminTierStrategy()
            Toast.makeText(requireContext(), "Applied Admin Tier strategy", Toast.LENGTH_SHORT).show()
            bindData()
            onUpdated()
        }

        binding.btnStrategyFair.setOnClickListener {
            NodeRepository.applyFairShareStrategy()
            Toast.makeText(requireContext(), "Applied Fair Share (Usage + Headroom)", Toast.LENGTH_SHORT).show()
            bindData()
            onUpdated()
        }

        binding.btnDoneDistribution.setOnClickListener {
            dismiss()
        }
    }

    private fun bindData() {
        val users = NodeRepository.getUsers()
        val n = users.size
        val totalAllocated = NodeRepository.getTotalAllocatedGB()
        val usablePool = NodeRepository.usableDiskGB
        val unallocated = NodeRepository.getUnallocatedCapacityGB()
        val isOverAllocated = NodeRepository.isOverAllocated()

        binding.tvSheetSubtitle.text = "${String.format("%.1f", NodeRepository.totalDiskGB)} GB Attached SSD · $n Users Managed"
        binding.tvSummaryAllocated.text = "${String.format("%.1f", totalAllocated)} GB Allocated"
        binding.tvSummaryUnallocated.text = "${String.format("%.1f", unallocated)} GB Free Pool"

        val percent = if (usablePool > 0) ((totalAllocated / usablePool) * 100).toInt() else 0
        binding.pbSheetAllocation.progress = Math.min(100, percent)

        if (isOverAllocated) {
            val overGB = NodeRepository.getOverAllocatedGB()
            val overPercent = NodeRepository.getOverAllocatedPercent()
            binding.layoutOverAllocatedWarning.visibility = View.VISIBLE
            binding.tvOverAllocatedText.text = "⚠️ Over-allocated by ${String.format("%.1f", overGB)} GB ($overPercent% of usable SSD)"
            binding.pbSheetAllocation.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.status_red))
        } else {
            binding.layoutOverAllocatedWarning.visibility = View.GONE
            binding.pbSheetAllocation.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary_blue))
        }

        // Update strategy button subtitles dynamically based on N users
        val equalPerUser = Math.round((usablePool / maxOf(1, n)) * 10.0) / 10.0
        binding.tvEqualSubtitle.text = "${equalPerUser.toInt()} GB / user"

        // Render dynamic users list
        binding.containerSheetUserList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        for (user in users) {
            val itemBinding = ItemNodeUserBinding.inflate(inflater, binding.containerSheetUserList, false)
            itemBinding.tvUserInitials.text = user.initials
            itemBinding.tvUserName.text = user.name
            itemBinding.badgeUserRole.text = user.role
            itemBinding.tvUserStorageSummary.text = "${String.format("%.1f", user.usedGB)} GB used of ${String.format("%.1f", user.quotaGB)} GB limit"

            itemBinding.rowUserRoot.setOnClickListener {
                dismiss()
                UserDetailActivity.start(requireContext(), user.id)
            }

            binding.containerSheetUserList.addView(itemBinding.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "StorageDistributionBottomSheet"
    }
}

