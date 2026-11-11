package com.example.piphotos.media

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.SheetIndexStatsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IndexStatsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetIndexStatsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetIndexStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val index = LocalMediaIndex.getInstance(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            index.statsFlow.collectLatest { stats ->
                updateUi(stats)
            }
        }

        binding.btnTriggerRescan.setOnClickListener {
            binding.btnTriggerRescan.isEnabled = false
            binding.btnTriggerRescan.text = "Rebuilding Index..."
            viewLifecycleOwner.lifecycleScope.launch {
                val newStats = index.rebuildIndex()
                updateUi(newStats)
                binding.btnTriggerRescan.isEnabled = true
                binding.btnTriggerRescan.text = "Run Full Index Rebuild"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateUi(stats: IndexStats) {
        if (_binding == null) return
        binding.tvStatTotalItems.text = "${stats.totalItems} items"
        binding.tvStatBreakdown.text = "${stats.totalPhotos} photos · ${stats.totalVideos} videos"
        binding.tvStatFolders.text = "${stats.totalFolders} folders"
        binding.tvStatBuildTime.text = "${stats.buildTimeMs} ms"
        binding.tvStatGeneration.text = "Gen ${stats.generation} · v${stats.mediaStoreVersion}"

        if (stats.folderBreakdown.isNotEmpty()) {
            val breakdownText = stats.folderBreakdown.entries
                .sortedByDescending { it.value }
                .take(6)
                .joinToString("\n") { "• ${it.key}: ${it.value} items" }
            binding.tvFolderBreakdownList.text = breakdownText
        } else {
            binding.tvFolderBreakdownList.text = "No folders indexed yet"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "IndexStatsBottomSheet"
        fun newInstance() = IndexStatsBottomSheet()
    }
}

