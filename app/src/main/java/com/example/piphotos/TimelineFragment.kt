package com.example.piphotos

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.FragmentTimelineBinding
import com.example.piphotos.media.IndexStatsBottomSheet
import com.example.piphotos.media.LocalMediaIndex
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsAndUpdateState()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimelineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTopHeader()
        setupPartialAccessBanner()
        setupEmptyState()
        observeMediaIndex()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndUpdateState()
    }

    private fun setupTopHeader() {
        binding.btnTimelineSearch.setOnClickListener {
            Toast.makeText(requireContext(), "Search will be available in Phase G6", Toast.LENGTH_SHORT).show()
        }

        binding.btnTimelineOverflow.setOnClickListener { view ->
            showOverflowMenu(view)
        }
    }

    private fun setupPartialAccessBanner() {
        binding.btnPartialSelectMore.setOnClickListener {
            val permissions = PermissionHelper.getRequiredPermissions()
            permissionLauncher.launch(permissions)
        }

        binding.btnPartialAllowAll.setOnClickListener {
            PermissionHelper.openAppSettings(requireContext())
        }
    }

    private fun setupEmptyState() {
        binding.btnAllowAccess.setOnClickListener {
            val permissions = PermissionHelper.getRequiredPermissions()
            permissionLauncher.launch(permissions)
        }
    }

    private fun checkPermissionsAndUpdateState() {
        val context = context ?: return
        val hasFull = PermissionHelper.hasFullMediaAccess(context)
        val hasPartial = PermissionHelper.hasPartialMediaAccess(context)

        binding.bannerPartialAccess.visibility = if (hasPartial) View.VISIBLE else View.GONE

        if (hasFull || hasPartial) {
            // Trigger index sync if needed
            viewLifecycleOwner.lifecycleScope.launch {
                LocalMediaIndex.getInstance(context).refreshDelta()
            }
        }
    }

    private fun observeMediaIndex() {
        val index = LocalMediaIndex.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            index.mediaFlow.collectLatest { items ->
                val hasAccess = PermissionHelper.hasAnyMediaAccess(requireContext())
                if (hasAccess && items.isNotEmpty()) {
                    binding.tvEmptyTitle.text = "${items.size} Photos Indexed"
                    binding.tvEmptySubtitle.text = "Timeline grid renderer will be wired in Phase G3."
                    binding.btnAllowAccess.visibility = View.GONE
                } else if (!hasAccess) {
                    binding.tvEmptyTitle.text = "Welcome to Pi Photos"
                    binding.tvEmptySubtitle.text = "Your private, local-first gallery. Allow photo access to view and organize your photos on this device."
                    binding.btnAllowAccess.visibility = View.VISIBLE
                } else {
                    binding.tvEmptyTitle.text = "No Photos Found"
                    binding.tvEmptySubtitle.text = "No pictures or videos were detected in your device storage."
                    binding.btnAllowAccess.visibility = View.GONE
                }
            }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Select photos")
        popup.menu.add(0, 2, 1, "Index stats (Debug)")
        popup.menu.add(0, 3, 2, "Settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> Toast.makeText(requireContext(), "Multi-selection available in Phase G3", Toast.LENGTH_SHORT).show()
                2 -> {
                    IndexStatsBottomSheet.newInstance().show(childFragmentManager, IndexStatsBottomSheet.TAG)
                }
                3 -> {
                    (activity as? MainActivity)?.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottomNavigation)?.selectedItemId = R.id.navigation_you
                }
            }
            true
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = TimelineFragment()
    }
}
