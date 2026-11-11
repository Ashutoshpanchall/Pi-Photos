package com.example.piphotos

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.piphotos.databinding.FragmentTimelineBinding
import com.example.piphotos.DeleteResult
import com.example.piphotos.LocalDeleteHandler
import com.example.piphotos.media.DebugStatsLauncher
import com.example.piphotos.media.LocalMediaIndex
import com.example.piphotos.media.LocalMediaItem
import com.example.piphotos.media.TimelineAdapter
import com.example.piphotos.media.TimelineCategoryFilter
import com.example.piphotos.media.TimelineGroupMode
import com.example.piphotos.media.TimelineGrouper
import com.example.piphotos.media.TimelineRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimelineFragment : Fragment() {

    private var _binding: FragmentTimelineBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TimelineAdapter
    private lateinit var gridLayoutManager: GridLayoutManager
    private var currentMode = TimelineGroupMode.MONTH
    private var currentFilter = TimelineCategoryFilter.ALL

    private var rawMediaItems: List<LocalMediaItem> = emptyList()
    private var cachedFavoriteIds: Set<Long> = emptySet()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsAndUpdateState()
    }

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(requireContext(), "Items deleted", Toast.LENGTH_SHORT).show()
            adapter.clearSelection()
            viewLifecycleOwner.lifecycleScope.launch {
                LocalMediaIndex.getInstance(requireContext()).refreshDelta()
            }
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (adapter.isSelectionMode) {
                adapter.clearSelection()
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
        setupSelectionBar()
        setupCategoryChips()
        setupPartialAccessBanner()
        setupEmptyState()
        setupTimelineRecyclerView()
        observeMediaIndex()
    }

    override fun onResume() {
        super.onResume()
        checkPermissionsAndUpdateState()
    }

    private fun setupTopHeader() {
        binding.btnZoomSwitcher.text = "Month"
        binding.btnZoomSwitcher.setOnClickListener {
            // Cycle: Month -> Day -> Year -> Month
            val nextMode = when (currentMode) {
                TimelineGroupMode.MONTH -> TimelineGroupMode.DAY
                TimelineGroupMode.DAY -> TimelineGroupMode.YEAR
                TimelineGroupMode.YEAR -> TimelineGroupMode.MONTH
            }
            switchGroupMode(nextMode)
        }

        binding.btnTimelineSearch.setOnClickListener {
            Toast.makeText(requireContext(), "Search will be available in Phase G6", Toast.LENGTH_SHORT).show()
        }

        binding.btnTimelineOverflow.setOnClickListener { view ->
            showOverflowMenu(view)
        }
    }

    private fun switchGroupMode(mode: TimelineGroupMode) {
        if (currentMode == mode) return
        currentMode = mode
        binding.btnZoomSwitcher.text = when (mode) {
            TimelineGroupMode.DAY -> "Day"
            TimelineGroupMode.MONTH -> "Month"
            TimelineGroupMode.YEAR -> "Year"
        }
        gridLayoutManager.spanCount = mode.spanCount
        recomputeAndSubmitTimeline()
    }

    private fun setupSelectionBar() {
        binding.btnSelectionClose.setOnClickListener {
            adapter.clearSelection()
        }

        binding.btnSelectionSelectAll.setOnClickListener {
            adapter.selectAll()
        }

        binding.btnSelectionFavorite.setOnClickListener {
            val selected = adapter.selectedIds.toList()
            if (selected.isEmpty()) return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch {
                val repo = LocalMediaIndex.getInstance(requireContext()).favoritesRepo
                for (id in selected) {
                    repo.toggleFavorite(id)
                }
                cachedFavoriteIds = repo.getAllFavoriteIds()
                Toast.makeText(requireContext(), "Favorites updated for ${selected.size} items", Toast.LENGTH_SHORT).show()
                if (currentFilter == TimelineCategoryFilter.FAVORITES) {
                    recomputeAndSubmitTimeline()
                }
            }
        }

        binding.btnSelectionDelete.setOnClickListener {
            val selected = adapter.selectedIds.toList()
            if (selected.isEmpty()) return@setOnClickListener

            viewLifecycleOwner.lifecycleScope.launch {
                val deleteHandler = LocalDeleteHandler(requireContext())
                when (val result = deleteHandler.trash(selected)) {
                    is DeleteResult.Success -> {
                        Toast.makeText(requireContext(), "Deleted ${selected.size} item(s)", Toast.LENGTH_SHORT).show()
                        adapter.clearSelection()
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
            val selected = adapter.selectedIds.toSet()
            if (selected.isEmpty()) return@setOnClickListener

            val uris = ArrayList<Uri>()
            for (row in adapter.currentList) {
                if (row is TimelineRow.MediaRow && selected.contains(row.item.id)) {
                    uris.add(row.item.contentUri)
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
            recomputeAndSubmitTimeline()
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

    private fun setupTimelineRecyclerView() {
        adapter = TimelineAdapter(
            onItemClick = { item ->
                PhotoViewerActivity.start(
                    context = requireContext(),
                    photoId = item.id.toString(),
                    drawableRes = 0,
                    title = "${if (item.isVideo) "VID" else "IMG"}_${item.id}",
                    date = "",
                    category = item.bucketName,
                    isVideo = item.isVideo,
                    contentUri = item.contentUri.toString()
                )
            },
            onSelectionChanged = { selectedIds ->
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
        )

        gridLayoutManager = GridLayoutManager(requireContext(), currentMode.spanCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    TimelineAdapter.VIEW_TYPE_HEADER -> gridLayoutManager.spanCount
                    else -> 1
                }
            }
        }

        binding.rvTimeline.layoutManager = gridLayoutManager
        binding.rvTimeline.adapter = adapter

        // Pinch-To-Zoom Gesture Detector
        val scaleDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val scale = detector.scaleFactor
                if (scale > 1.2f) {
                    // Zoom in (larger items, fewer columns): YEAR -> MONTH -> DAY
                    when (currentMode) {
                        TimelineGroupMode.YEAR -> switchGroupMode(TimelineGroupMode.MONTH)
                        TimelineGroupMode.MONTH -> switchGroupMode(TimelineGroupMode.DAY)
                        TimelineGroupMode.DAY -> {}
                    }
                } else if (scale < 0.8f) {
                    // Zoom out (smaller items, more columns): DAY -> MONTH -> YEAR
                    when (currentMode) {
                        TimelineGroupMode.DAY -> switchGroupMode(TimelineGroupMode.MONTH)
                        TimelineGroupMode.MONTH -> switchGroupMode(TimelineGroupMode.YEAR)
                        TimelineGroupMode.YEAR -> {}
                    }
                }
            }
        })

        binding.rvTimeline.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false
        }

        // Sticky Date Header Overlay on scroll
        binding.rvTimeline.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val firstVisible = gridLayoutManager.findFirstVisibleItemPosition()
                if (firstVisible >= 0 && firstVisible < adapter.itemCount) {
                    // Find active header for the visible row
                    val headerRow = findActiveHeader(firstVisible)
                    if (headerRow != null) {
                        binding.tvStickyHeaderTitle.text = headerRow.title
                        binding.stickyHeaderOverlay.visibility = View.VISIBLE
                    } else {
                        binding.stickyHeaderOverlay.visibility = View.GONE
                    }
                    updateFastScrollThumb(recyclerView)
                } else {
                    binding.stickyHeaderOverlay.visibility = View.GONE
                }
            }
        })

        setupFastScroller()
    }

    private fun findActiveHeader(visiblePosition: Int): TimelineRow.HeaderRow? {
        val list = adapter.currentList
        for (i in visiblePosition downTo 0) {
            val row = list.getOrNull(i)
            if (row is TimelineRow.HeaderRow) {
                return row
            }
        }
        return null
    }

    private fun updateFastScrollThumb(recyclerView: RecyclerView) {
        val offset = recyclerView.computeVerticalScrollOffset()
        val range = recyclerView.computeVerticalScrollRange() - recyclerView.computeVerticalScrollExtent()
        if (range <= 0) {
            binding.layoutFastScroll.visibility = View.GONE
            return
        }
        binding.layoutFastScroll.visibility = View.VISIBLE
        val trackHeight = binding.layoutFastScroll.height - binding.viewFastScrollThumb.height
        if (trackHeight > 0) {
            val fraction = offset.toFloat() / range.toFloat()
            binding.viewFastScrollThumb.translationY = fraction * trackHeight
        }
    }

    private fun setupFastScroller() {
        binding.layoutFastScroll.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val trackHeight = v.height - binding.viewFastScrollThumb.height
                    if (trackHeight > 0 && adapter.itemCount > 0) {
                        val clampedY = event.y.coerceIn(0f, trackHeight.toFloat())
                        binding.viewFastScrollThumb.translationY = clampedY
                        val fraction = clampedY / trackHeight.toFloat()
                        val targetPos = (fraction * (adapter.itemCount - 1)).toInt()
                        gridLayoutManager.scrollToPositionWithOffset(targetPos, 0)

                        val activeHeader = findActiveHeader(targetPos)
                        if (activeHeader != null) {
                            binding.tvFastScrollBubble.text = activeHeader.title
                            binding.tvFastScrollBubble.visibility = View.VISIBLE
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.tvFastScrollBubble.visibility = View.GONE
                    true
                }
                else -> false
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
            cachedFavoriteIds = index.favoritesRepo.getAllFavoriteIds()
            index.mediaFlow.collectLatest { items ->
                rawMediaItems = items
                val hasAccess = PermissionHelper.hasAnyMediaAccess(requireContext())
                if (hasAccess && items.isNotEmpty()) {
                    binding.layoutTimelineContent.visibility = View.VISIBLE
                    binding.layoutTimelineEmpty.visibility = View.GONE
                    recomputeAndSubmitTimeline()
                } else if (!hasAccess) {
                    binding.layoutTimelineContent.visibility = View.GONE
                    binding.layoutTimelineEmpty.visibility = View.VISIBLE
                    binding.tvEmptyTitle.text = "Welcome to Pi Photos"
                    binding.tvEmptySubtitle.text = "Your private, local-first gallery. Allow photo access to view and organize your photos on this device."
                    binding.btnAllowAccess.visibility = View.VISIBLE
                } else {
                    binding.layoutTimelineContent.visibility = View.GONE
                    binding.layoutTimelineEmpty.visibility = View.VISIBLE
                    binding.tvEmptyTitle.text = "No Photos Found"
                    binding.tvEmptySubtitle.text = "No pictures or videos were detected in your device storage."
                    binding.btnAllowAccess.visibility = View.GONE
                }
            }
        }
    }

    private fun recomputeAndSubmitTimeline() {
        val items = rawMediaItems
        val filter = currentFilter
        val mode = currentMode
        val favIds = cachedFavoriteIds

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.Default) {
                val filtered = TimelineGrouper.filterItems(items, filter, favIds)
                TimelineGrouper.group(filtered, mode)
            }
            adapter.submitList(rows)
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
                    adapter.isSelectionMode = true
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
        _binding = null
    }

    companion object {
        fun newInstance() = TimelineFragment()
    }
}
