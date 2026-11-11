package com.example.piphotos.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.piphotos.databinding.ViewTimelineBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding = ViewTimelineBinding.inflate(LayoutInflater.from(context), this, true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var groupingJob: Job? = null

    val adapter: TimelineAdapter
    val gridLayoutManager: GridLayoutManager

    var currentMode: TimelineGroupMode = TimelineGroupMode.MONTH
        private set

    var currentRawItems: List<LocalMediaItem> = emptyList()
        private set

    var onItemClick: ((LocalMediaItem) -> Unit)? = null
    var onSelectionChanged: ((Set<Long>) -> Unit)? = null
    var onModeChanged: ((TimelineGroupMode) -> Unit)? = null

    val selectedIds: Set<Long> get() = adapter.selectedIds
    var isSelectionMode: Boolean
        get() = adapter.isSelectionMode
        set(value) {
            adapter.isSelectionMode = value
        }

    private var isDraggingFastScroll = false
    private var isFastScrolling = false
    private var lastTouchY = 0f

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideFastScrollRunnable = Runnable {
        binding.layoutFastScroll.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { binding.layoutFastScroll.visibility = View.INVISIBLE }
            .start()
    }

    init {
        adapter = TimelineAdapter(
            onItemClick = { item -> onItemClick?.invoke(item) },
            onSelectionChanged = { set -> onSelectionChanged?.invoke(set) }
        )

        gridLayoutManager = GridLayoutManager(context, currentMode.spanCount)
        val lookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (adapter.getItemViewType(position)) {
                    TimelineAdapter.VIEW_TYPE_HEADER -> gridLayoutManager.spanCount
                    else -> 1
                }
            }
        }
        lookup.isSpanIndexCacheEnabled = true
        gridLayoutManager.spanSizeLookup = lookup

        binding.rvTimelineGrid.layoutManager = gridLayoutManager
        binding.rvTimelineGrid.adapter = adapter

        setupPinchToZoom()
        setupFastScroller()
        setupScrollListener()
    }

    private fun setupPinchToZoom() {
        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var isScaling = false

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (isFastScrolling || adapter.isSelectionMode) {
                    isScaling = false
                    return false
                }
                isScaling = true
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (!isScaling) return
                isScaling = false
                val factor = detector.scaleFactor
                if (factor > 1.25f) {
                    // Zoom in (larger items, fewer columns): YEAR -> MONTH -> DAY
                    when (currentMode) {
                        TimelineGroupMode.YEAR -> switchGroupMode(TimelineGroupMode.MONTH)
                        TimelineGroupMode.MONTH -> switchGroupMode(TimelineGroupMode.DAY)
                        TimelineGroupMode.DAY -> {}
                    }
                } else if (factor < 0.8f) {
                    // Zoom out (smaller items, more columns): DAY -> MONTH -> YEAR
                    when (currentMode) {
                        TimelineGroupMode.DAY -> switchGroupMode(TimelineGroupMode.MONTH)
                        TimelineGroupMode.MONTH -> switchGroupMode(TimelineGroupMode.YEAR)
                        TimelineGroupMode.YEAR -> {}
                    }
                }
            }
        })

        binding.rvTimelineGrid.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (!isFastScrolling && !adapter.isSelectionMode && e.pointerCount > 1) {
                    scaleDetector.onTouchEvent(e)
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                if (!isFastScrolling && !adapter.isSelectionMode) {
                    scaleDetector.onTouchEvent(e)
                }
            }
        })
    }

    private fun setupScrollListener() {
        binding.rvTimelineGrid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy != 0) {
                    showFastScrollTrack()
                }
                updateFastScrollThumb()
                updateStickyHeader()
            }
        })
    }

    private fun showFastScrollTrack() {
        hideHandler.removeCallbacks(hideFastScrollRunnable)
        if (binding.layoutFastScroll.visibility != View.VISIBLE) {
            binding.layoutFastScroll.visibility = View.VISIBLE
            binding.layoutFastScroll.alpha = 1f
        }
        hideHandler.postDelayed(hideFastScrollRunnable, 1500L)
    }

    private fun updateFastScrollThumb() {
        val offset = binding.rvTimelineGrid.computeVerticalScrollOffset()
        val range = binding.rvTimelineGrid.computeVerticalScrollRange() - binding.rvTimelineGrid.computeVerticalScrollExtent()
        val trackHeight = binding.layoutFastScroll.height - binding.viewFastScrollThumb.height
        if (range > 0 && trackHeight > 0) {
            val translationY = FastScrollerMath.computeThumbTranslationY(offset, range, trackHeight)
            binding.viewFastScrollThumb.translationY = translationY
        }
    }

    private fun updateStickyHeader() {
        val firstVisible = gridLayoutManager.findFirstVisibleItemPosition()
        if (firstVisible >= 0 && firstVisible < adapter.itemCount) {
            val headerRow = findActiveHeader(firstVisible)
            if (headerRow != null) {
                binding.tvStickyHeaderTitle.text = headerRow.title
                binding.stickyHeaderOverlay.visibility = View.VISIBLE
            } else {
                binding.stickyHeaderOverlay.visibility = View.GONE
            }
        } else {
            binding.stickyHeaderOverlay.visibility = View.GONE
        }
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

    private fun setupFastScroller() {
        val slopPx = 20 * resources.displayMetrics.density

        binding.layoutFastScroll.setOnTouchListener { v, event ->
            val thumbY = binding.viewFastScrollThumb.translationY
            val thumbHeight = binding.viewFastScrollThumb.height.toFloat().coerceAtLeast(1f)
            val touchY = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (binding.layoutFastScroll.visibility == View.VISIBLE &&
                        FastScrollerMath.isHitThumb(touchY, thumbY, thumbHeight, slopPx)
                    ) {
                        isDraggingFastScroll = true
                        isFastScrolling = true
                        lastTouchY = event.rawY
                        hideHandler.removeCallbacks(hideFastScrollRunnable)
                        binding.tvFastScrollBubble.visibility = View.VISIBLE
                        updateBubbleForCurrentPosition()
                        parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    } else {
                        // Pass touch to RecyclerView!
                        false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingFastScroll) return@setOnTouchListener false
                    val deltaY = event.rawY - lastTouchY
                    lastTouchY = event.rawY

                    val trackHeight = v.height - binding.viewFastScrollThumb.height
                    val scrollRange = binding.rvTimelineGrid.computeVerticalScrollRange() - binding.rvTimelineGrid.computeVerticalScrollExtent()

                    if (trackHeight > 0 && scrollRange > 0) {
                        val scrollDelta = FastScrollerMath.computeScrollDelta(deltaY, trackHeight, scrollRange)
                        binding.rvTimelineGrid.scrollBy(0, scrollDelta)
                    }
                    updateBubbleForCurrentPosition()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingFastScroll) {
                        isDraggingFastScroll = false
                        isFastScrolling = false
                        binding.tvFastScrollBubble.visibility = View.GONE
                        hideHandler.postDelayed(hideFastScrollRunnable, 1500L)
                        parent?.requestDisallowInterceptTouchEvent(false)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun updateBubbleForCurrentPosition() {
        val firstVisible = gridLayoutManager.findFirstVisibleItemPosition()
        if (firstVisible >= 0) {
            val header = findActiveHeader(firstVisible)
            if (header != null) {
                binding.tvFastScrollBubble.text = header.title
            }
        }
    }

    fun getFirstVisibleItemId(): Long? {
        val firstVisible = gridLayoutManager.findFirstVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION) return null
        val list = adapter.currentList
        for (i in firstVisible until list.size) {
            val row = list.getOrNull(i)
            if (row is TimelineRow.MediaRow) {
                return row.item.id
            }
        }
        return null
    }

    fun switchGroupMode(mode: TimelineGroupMode) {
        if (currentMode == mode) return
        val anchorId = getFirstVisibleItemId()
        currentMode = mode
        gridLayoutManager.spanCount = mode.spanCount
        onModeChanged?.invoke(mode)
        recomputeAndSubmit(anchorItemId = anchorId)
    }

    fun cycleGroupMode() {
        val nextMode = when (currentMode) {
            TimelineGroupMode.MONTH -> TimelineGroupMode.DAY
            TimelineGroupMode.DAY -> TimelineGroupMode.YEAR
            TimelineGroupMode.YEAR -> TimelineGroupMode.MONTH
        }
        switchGroupMode(nextMode)
    }

    fun submitItems(
        items: List<LocalMediaItem>,
        anchorItemId: Long? = null,
        onSubmitted: (() -> Unit)? = null
    ) {
        currentRawItems = items
        recomputeAndSubmit(anchorItemId, onSubmitted)
    }

    private fun recomputeAndSubmit(anchorItemId: Long? = null, onSubmitted: (() -> Unit)? = null) {
        groupingJob?.cancel()
        val raw = currentRawItems
        val mode = currentMode
        groupingJob = scope.launch {
            val rows = withContext(Dispatchers.Default) {
                TimelineGrouper.group(raw, mode)
            }
            adapter.submitList(rows) {
                if (anchorItemId != null) {
                    val targetPos = rows.indexOfFirst { (it as? TimelineRow.MediaRow)?.item?.id == anchorItemId }
                    if (targetPos >= 0) {
                        gridLayoutManager.scrollToPositionWithOffset(targetPos, 0)
                    }
                }
                onSubmitted?.invoke()
            }
        }
    }

    fun updateFavorites(favoriteIds: Set<Long>) {
        adapter.updateFavorites(favoriteIds)
    }

    fun scrollToTop() {
        gridLayoutManager.scrollToPositionWithOffset(0, 0)
    }

    fun selectAll() = adapter.selectAll()

    fun clearSelection() = adapter.clearSelection()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hideHandler.removeCallbacks(hideFastScrollRunnable)
        scope.cancel()
    }
}

