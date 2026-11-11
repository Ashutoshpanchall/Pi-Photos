package com.example.piphotos

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.example.piphotos.media.ZoomGestureMath

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val currentMatrix = Matrix()
    private val prevMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    var scale = 1f
        private set
    private val minScale = 1f
    private val maxScale = 4.5f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    var onSingleTapListener: (() -> Unit)? = null
    var onZoomStateChanged: ((isZoomed: Boolean) -> Unit)? = null

    val isZoomed: Boolean
        get() = ZoomGestureMath.shouldLockPager(scale)

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapListener?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > minScale + 0.1f) {
                    resetZoom()
                } else {
                    scale = 2.5f
                    currentMatrix.postScale(2.5f, 2.5f, e.x, e.y)
                    clampTranslation()
                    imageMatrix = currentMatrix
                    notifyZoomState()
                }
                return true
            }
        })
    }

    fun resetZoom() {
        scale = 1f
        centerImage()
        notifyZoomState()
    }

    private fun notifyZoomState() {
        val zoomed = isZoomed
        onZoomStateChanged?.invoke(zoomed)
        parent?.requestDisallowInterceptTouchEvent(zoomed)
    }

    fun centerImage() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (dw <= 0f || dh <= 0f) return

        val scaleFactor = minOf(viewWidth / dw, viewHeight / dh)
        currentMatrix.reset()
        currentMatrix.postScale(scaleFactor, scaleFactor)
        val dx = (viewWidth - dw * scaleFactor) / 2f
        val dy = (viewHeight - dh * scaleFactor) / 2f
        currentMatrix.postTranslate(dx, dy)
        imageMatrix = currentMatrix
        scale = 1f
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && scale <= 1.05f) {
            centerImage()
        }
    }

    private fun clampTranslation() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        currentMatrix.getValues(matrixValues)
        val curScale = matrixValues[Matrix.MSCALE_X]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        val contentW = d.intrinsicWidth * curScale
        val contentH = d.intrinsicHeight * curScale

        val clampedX = ZoomGestureMath.clampTranslation(viewWidth, contentW, transX)
        val clampedY = ZoomGestureMath.clampTranslation(viewHeight, contentH, transY)

        matrixValues[Matrix.MTRANS_X] = clampedX
        matrixValues[Matrix.MTRANS_Y] = clampedY
        currentMatrix.setValues(matrixValues)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val curr = PointF(event.x, event.y)

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                prevMatrix.set(currentMatrix)
                start.set(curr)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    prevMatrix.set(currentMatrix)
                    midPoint(mid, event)
                    mode = ZOOM
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                if (scale < minScale + 0.05f) {
                    resetZoom()
                } else {
                    clampTranslation()
                    imageMatrix = currentMatrix
                }
                notifyZoomState()
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && isZoomed) {
                    currentMatrix.set(prevMatrix)
                    currentMatrix.postTranslate(curr.x - start.x, curr.y - start.y)
                    clampTranslation()
                    imageMatrix = currentMatrix
                }
            }
        }
        return true
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return kotlin.math.sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        val x = event.getX(0) + event.getX(1)
        val y = event.getY(0) + event.getY(1)
        point.set(x / 2, y / 2)
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var mScaleFactor = detector.scaleFactor
            val origScale = scale
            scale *= mScaleFactor

            if (scale > maxScale) {
                scale = maxScale
                mScaleFactor = maxScale / origScale
            } else if (scale < minScale) {
                scale = minScale
                mScaleFactor = minScale / origScale
            }

            currentMatrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
            clampTranslation()
            imageMatrix = currentMatrix
            notifyZoomState()
            return true
        }
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
