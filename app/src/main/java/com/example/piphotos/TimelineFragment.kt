package com.example.piphotos

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.piphotos.databinding.FragmentTimelineBinding
import com.example.piphotos.DeleteResult
import com.example.piphotos.LocalDeleteHandler
import com.example.piphotos.media.DebugStatsLauncher
import com.example.piphotos.media.IndexState
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.TimelineCategoryFilter
import com.example.piphotos.media.TimelineGroupMode
import com.example.piphotos.media.TimelineStateMachine
import com.example.piphotos.media.TimelineUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private var currentFilter = TimelineCategoryFilter.ALL
    private var cachedFavoriteIds: Set<Long> = emptySet()
    private var lastRenderedRevision: Long = -1L
    private var filterJob: Job? = null
    private var activeSearchQuery: String = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsAndUpdateState()
    }

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            binding.timelineView.clearSelection()
            viewLifecycleOwner.lifecycleScope.launch {
                LocalMediaIndex.getInstance(requireContext()).refreshDelta()
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (isSearching()) {
                closeSearch()
            } else if (binding.timelineView.isSelectionMode) {
                binding.timelineView.clearSelection()
            }
        }
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
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)

        setupTopHeader()
        setupSearch()
        setupSelectionBar()
        setupCategoryChips()
        setupPartialAccessBanner()
        setupEmptyState()
        setupTimelineView()
        observeMediaIndex()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndUpdateState()
    }

    private fun setupTopHeader() {
        binding.btnZoomSwitcher.text = "Month"
        binding.btnZoomSwitcher.setOnClickListener {
            binding.timelineView.cycleGroupMode()
        }

        binding.btnTimelineOverflow.setOnClickListener { view ->
            showOverflowMenu(view)
        }
    }

    private fun setupSearch() {
        binding.btnTimelineSearch.setOnClickListener {
            binding.layoutTimelineSearchBar.visibility = View.VISIBLE
            binding.topTimelineHeader.visibility = View.GONE
            binding.etSearchQuery.requestFocus()
            backPressedCallback.isEnabled = true
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }

        binding.btnSearchBack.setOnClickListener {
            closeSearch()
        }

        binding.btnSearchClear.setOnClickListener {
            binding.etSearchQuery.setText("")
        }

        binding.etSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                activeSearchQuery = s?.toString()?.trim() ?: ""
                binding.btnSearchClear.visibility = if (activeSearchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                updateUiWithCurrentState()
            }
        })
    }

    private fun isSearching(): Boolean {
        return _binding?.layoutTimelineSearchBar?.visibility == View.VISIBLE
    }

    private fun closeSearch() {
        activeSearchQuery = ""
        binding.etSearchQuery.setText("")
        binding.layoutTimelineSearchBar.visibility = View.GONE
        binding.topTimelineHeader.visibility = View.VISIBLE
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearchQuery.windowToken, 0)
        backPressedCallback.isEnabled = binding.timelineView.isSelectionMode
        updateUiWithCurrentState()
    }

    private fun setupSelectionBar() {
        binding.btnSelectionClose.setOnClickListener {
            binding.timelineView.clearSelection()
        }

        binding.btnSelectionSelectAll.setOnClickListener {
            binding.timelineView.selectAll()
        }

        binding.btnSelectionFavorite.setOnClickListener {
            val selected = binding.timelineView.selectedIds.toList()
            if (selected.isEmpty()) return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch {
                val repo = LocalMediaIndex.getInstance(requireContext()).favoritesRepo
                repo.toggleGroup(selected)
                cachedFavoriteIds = repo.getAllFavoriteIds()
                if (currentFilter == TimelineCategoryFilter.FAVORITES) {
                    updateUiWithCurrentState()
                }
            }
        }

        binding.btnSelectionDelete.setOnClickListener {
            val selected = binding.timelineView.selectedIds.toList()
            if (selected.isEmpty()) return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch {
                val deleteHandler = LocalDeleteHandler(requireContext())
                when (val result = deleteHandler.trash(selected)) {
                    is DeleteResult.Success -> {
                        binding.timelineView.clearSelection()
                        LocalMediaIndex.getInstance(requireContext()).refreshDelta()
                    }
                    is DeleteResult.RequiresConfirmation -> {
                        val request = IntentSenderRequest.Builder(result.intentSender).build()
                        intentSenderLauncher.launch(request)
                    }
                    is DeleteResult.Error -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        binding.btnSelectionShare.setOnClickListener {
            val selected = binding.timelineView.selectedIds
            if (selected.isEmpty()) return@setOnClickListener

            val rawItems = binding.timelineView.currentRawItems
            val uris = ArrayList<Uri>()
            for (item in rawItems) {
                if (selected.contains(item.id)) {
                    uris.add(item.contentUri)
                }
            }

            if (uris.isNotEmpty()) {
                val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share ${uris.size} photos"))
            }
        }
    }

    private fun setupCategoryChips() {
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when {
                checkedIds.contains(R.id.chipCamera) -> TimelineCategoryFilter.CAMERA
                checkedIds.contains(R.id.chipScreenshots) -> TimelineCategoryFilter.SCREENSHOTS
                checkedIds.contains(R.id.chipVideos) -> TimelineCategoryFilter.VIDEOS
                checkedIds.contains(R.id.chipFavorites) -> TimelineCategoryFilter.FAVORITES
                else -> TimelineCategoryFilter.ALL
            }
            binding.timelineView.scrollToTop()
            updateUiWithCurrentState()
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

    private fun setupTimelineView() {
        binding.timelineView.onItemClick = { item ->
            val items = binding.timelineView.currentRawItems
            PhotoViewerActivity.start(
                context = requireContext(),
                startingId = item.id,
                items = items
            )
        }

        binding.timelineView.onModeChanged = { mode ->
            binding.btnZoomSwitcher.text = when (mode) {
                TimelineGroupMode.DAY -> "Day"
                TimelineGroupMode.MONTH -> "Month"
                TimelineGroupMode.YEAR -> "Year"
            }
        }

        binding.timelineView.onSelectionChanged = { selectedIds ->
            val count = selectedIds.size
            if (count > 0) {
                binding.layoutSelectionBar.visibility = View.VISIBLE
                binding.tvSelectionCount.text = "$count selected"
                backPressedCallback.isEnabled = true
            } else {
                binding.layoutSelectionBar.visibility = View.GONE
                backPressedCallback.isEnabled = false
            }
        }
    }

    private fun checkPermissionsAndUpdateState() {
        val context = context ?: return
        val hasFull = PermissionHelper.hasFullMediaAccess(context)
        val hasPartial = PermissionHelper.hasPartialMediaAccess(context)

        binding.bannerPartialAccess.visibility = if (hasPartial) View.VISIBLE else View.GONE

        if (hasFull || hasPartial) {
            viewLifecycleOwner.lifecycleScope.launch {
                LocalMediaIndex.getInstance(context).refreshDelta()
            }
        }
    }

    private fun observeMediaIndex() {
        val index = LocalMediaIndex.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            index.favoritesRepo.observeIds().collectLatest { favIds ->
                cachedFavoriteIds = favIds
                _binding?.timelineView?.updateFavorites(favIds)
                updateUiWithCurrentState()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            index.indexStateFlow.collectLatest {
                updateUiWithCurrentState()
            }
        }
    }

    private fun updateUiWithCurrentState() {
        filterJob?.cancel()
        val context = context ?: return
        val hasAccess = PermissionHelper.hasAnyMediaAccess(context)
        val index = LocalMediaIndex.getInstance(context)
        val state = TimelineStateMachine.computeState(
            hasPermission = hasAccess,
            indexState = index.indexStateFlow.value,
            filter = currentFilter,
            favoriteIds = cachedFavoriteIds,
            searchQuery = activeSearchQuery
        )

        when (state) {
            is TimelineUiState.Loading -> {
                binding.layoutTimelineLoading.visibility = View.VISIBLE
                binding.layoutTimelineEmpty.visibility = View.GONE
                binding.timelineView.visibility = View.GONE
            }
            is TimelineUiState.NoPermission -> {
                binding.layoutTimelineLoading.visibility = View.GONE
                binding.timelineView.visibility = View.GONE
                binding.layoutTimelineEmpty.visibility = View.VISIBLE
                binding.tvEmptyTitle.text = "Welcome to Pi Photos"
                binding.tvEmptySubtitle.text = "Your private, local-first gallery. Allow photo access to view and organize your photos on this device."
                binding.btnAllowAccess.visibility = View.VISIBLE
            }
            is TimelineUiState.Empty -> {
                binding.layoutTimelineLoading.visibility = View.GONE
                binding.timelineView.visibility = View.GONE
                binding.layoutTimelineEmpty.visibility = View.VISIBLE
                binding.tvEmptyTitle.text = TimelineStateMachine.getEmptyTitle(state.filter, activeSearchQuery)
                binding.tvEmptySubtitle.text = TimelineStateMachine.getEmptySubtitle(state.filter, activeSearchQuery)
                binding.btnAllowAccess.visibility = View.GONE
            }
            is TimelineUiState.Ready -> {
                binding.layoutTimelineLoading.visibility = View.GONE
                binding.layoutTimelineEmpty.visibility = View.GONE
                binding.timelineView.visibility = View.VISIBLE

                val anchorId = if (lastRenderedRevision == state.revision) {
                    null
                } else {
                    binding.timelineView.getFirstVisibleItemId()
                }
                lastRenderedRevision = state.revision

                binding.timelineView.submitItems(state.items, anchorItemId = anchorId)
            }
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(0, 1, 0, "Select photos")
        if (BuildConfig.DEBUG) {
            popup.menu.add(0, 2, 1, "Index stats (Debug)")
        }
        popup.menu.add(0, 3, 2, "Settings")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    binding.timelineView.isSelectionMode = true
                }
                2 -> {
                    if (BuildConfig.DEBUG) {
                        try {
                            val clazz = Class.forName("com.example.piphotos.media.DebugStatsLauncherImpl")
                            val instance = clazz.getField("INSTANCE").get(null) as? DebugStatsLauncher
                            instance?.show(childFragmentManager)
                        } catch (_: Exception) {}
                    }
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
        filterJob?.cancel()
        _binding = null
    }

    companion object {
        fun newInstance() = TimelineFragment()
    }
}
