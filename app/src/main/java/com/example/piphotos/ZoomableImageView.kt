package com.example.piphotos

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView
import com.example.piphotos.media.ZoomGestureMath
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pinch / double-tap / pan / fling image view for the photo viewer.
 *
 * Matrix model: finalMatrix = baseMatrix (fit-center, recomputed whenever the drawable or the
 * view size changes) * suppMatrix (what the user did: zoom + pan). At 1x the WHOLE image is
 * visible and centered. The user matrix is clamped so the image can never leave the screen.
 *
 * Touch model: the parent (ViewPager2 / SwipeDismissContainer) is locked out for the whole
 * lifetime of a pinch or a zoomed-in drag. When a zoomed drag reaches the left/right edge of the
 * image, the lock is released so the pager can take over and swipe to the next photo.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private val tmpRect = RectF()

    private val scroller = OverScroller(context)
    private var flingX = 0
    private var flingY = 0
    private var zoomAnimator: ValueAnimator? = null

    private var lastZoomed = false

    // Java/Kotlin: the super constructor can call setImageDrawable() before our fields exist.
    private var ready = false

    var onSingleTapListener: (() -> Unit)? = null
    var onZoomStateChanged: ((isZoomed: Boolean) -> Unit)? = null

    val currentScale: Float
        get() {
            suppMatrix.getValues(values)
            return values[Matrix.MSCALE_X]
        }

    val isZoomed: Boolean
        get() = ZoomGestureMath.shouldLockPager(currentScale)

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.isFinished) return
            if (scroller.computeScrollOffset()) {
                val nx = scroller.currX
                val ny = scroller.currY
                suppMatrix.postTranslate((flingX - nx).toFloat(), (flingY - ny).toFloat())
                flingX = nx
                flingY = ny
                applyAndClamp()
                postOnAnimation(this)
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cancelMotion()
                lockParent(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor.isNaN() || factor.isInfinite()) return false
                val current = currentScale
                val target = (current * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                val f = target / current
                suppMatrix.postScale(f, f, detector.focusX, detector.focusY)
                applyAndClamp()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapListener?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val target = if (isZoomed) MIN_SCALE else DOUBLE_TAP_SCALE
                animateScaleTo(target, e.x, e.y)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (scaleDetector.isInProgress || !isZoomed) return false

                suppMatrix.postTranslate(-distanceX, -distanceY)
                applyAndClamp()

                // Edge hand-off: when the image cannot move any further in the drag direction,
                // let the pager take the gesture so the user swipes to the next/previous photo.
                val r = displayRect()
                if (r != null && abs(distanceX) > abs(distanceY)) {
                    val atLeftEdge = r.left >= -EDGE_EPS
                    val atRightEdge = r.right <= width + EDGE_EPS
                    // distanceX < 0: finger moves right -> image wants to move right
                    val handOff = (distanceX < 0f && atLeftEdge) || (distanceX > 0f && atRightEdge)
                    lockParent(!handOff)
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (scaleDetector.isInProgress || !isZoomed) return false
                startFling(-velocityX.toInt(), -velocityY.toInt())
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
        ready = true
    }

    // --- Image changes: the fit matrix MUST be rebuilt for the NEW drawable -------------------

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        onImageChanged()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        onImageChanged()
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        onImageChanged()
    }

    override fun setImageURI(uri: Uri?) {
        super.setImageURI(uri)
        onImageChanged()
    }

    private fun onImageChanged() {
        if (!ready) return
        cancelMotion()
        suppMatrix.reset()
        updateBaseMatrix()
        applyAndClamp()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!ready) return
        val wasZoomed = isZoomed
        updateBaseMatrix()
        if (!wasZoomed) suppMatrix.reset()
        applyAndClamp()
    }

    override fun onDetachedFromWindow() {
        cancelMotion()
        super.onDetachedFromWindow()
    }

    /** Back to the fully zoomed-out, centered image. */
    fun resetZoom() {
        cancelMotion()
        suppMatrix.reset()
        updateBaseMatrix()
        applyAndClamp()
    }

    // --- Matrix helpers -----------------------------------------------------------------------

    private fun updateBaseMatrix() {
        baseMatrix.reset()
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        baseMatrix.setRectToRect(
            RectF(0f, 0f, dw, dh),
            RectF(0f, 0f, vw, vh),
            Matrix.ScaleToFit.CENTER
        )
    }

    private fun updateDrawMatrix() {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
    }

    /** Current on-screen rectangle of the image, or null if there is no sized drawable. */
    private fun displayRect(): RectF? {
        val d = drawable ?: return null
        val dw = d.intrinsicWidth
        val dh = d.intrinsicHeight
        if (dw <= 0 || dh <= 0) return null
        updateDrawMatrix()
        tmpRect.set(0f, 0f, dw.toFloat(), dh.toFloat())
        drawMatrix.mapRect(tmpRect)
        return tmpRect
    }

    /** Keep the image on screen (centered when smaller than the view) and push it to the view. */
    private fun applyAndClamp() {
        val r = displayRect()
        if (r != null) {
            val vw = width.toFloat()
            val vh = height.toFloat()
            val dx = when {
                r.width() <= vw -> (vw - r.width()) / 2f - r.left
                r.left > 0f -> -r.left
                r.right < vw -> vw - r.right
                else -> 0f
            }
            val dy = when {
                r.height() <= vh -> (vh - r.height()) / 2f - r.top
                r.top > 0f -> -r.top
                r.bottom < vh -> vh - r.bottom
                else -> 0f
            }
            if (dx != 0f || dy != 0f) suppMatrix.postTranslate(dx, dy)
        }
        updateDrawMatrix()
        imageMatrix = drawMatrix
        dispatchZoomState()
    }

    private fun dispatchZoomState() {
        val zoomed = isZoomed
        if (zoomed != lastZoomed) {
            lastZoomed = zoomed
            onZoomStateChanged?.invoke(zoomed)
        }
    }

    // --- Animations ---------------------------------------------------------------------------

    private fun animateScaleTo(target: Float, focusX: Float, focusY: Float) {
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(currentScale, target).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val s = a.animatedValue as Float
                val current = currentScale
                if (current > 0f) {
                    val f = s / current
                    suppMatrix.postScale(f, f, focusX, focusY)
                    applyAndClamp()
                }
            }
            start()
        }
    }

    private fun startFling(velocityX: Int, velocityY: Int) {
        val r = displayRect() ?: return
        val vw = width
        val vh = height

        val startX = (-r.left).roundToInt()
        val startY = (-r.top).roundToInt()
        val minX: Int
        val maxX: Int
        val minY: Int
        val maxY: Int

        if (vw < r.width()) {
            minX = 0
            maxX = (r.width() - vw).roundToInt()
        } else {
            minX = startX
            maxX = startX
        }
        if (vh < r.height()) {
            minY = 0
            maxY = (r.height() - vh).roundToInt()
        } else {
            minY = startY
            maxY = startY
        }

        flingX = startX
        flingY = startY
        if (startX != maxX || startY != maxY) {
            scroller.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY, 0, 0)
            postOnAnimation(flingRunnable)
        }
    }

    private fun cancelMotion() {
        zoomAnimator?.cancel()
        zoomAnimator = null
        removeCallbacks(flingRunnable)
        if (!scroller.isFinished) scroller.abortAnimation()
    }

    // --- Touch --------------------------------------------------------------------------------

    private fun lockParent(lock: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(lock)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelMotion()
                // At 1x leave the parents free so the pager can swipe and the dismiss gesture works.
                // When already zoomed, this view owns the drag until it hits an image edge.
                lockParent(isZoomed)
            }
            // A second finger means a pinch: lock the parents for the REST of this gesture.
            // (The old code released this lock on every scale frame, which let SwipeDismiss /
            // ViewPager2 steal the gesture and cancel the pinch.)
            MotionEvent.ACTION_POINTER_DOWN -> lockParent(true)
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        private const val MIN_SCALE = 1f          // 1 = whole image visible (fit-center)
        private const val MAX_SCALE = 4f          // relative to the fit size
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val EDGE_EPS = 1.5f
    }
}
