package com.example.piphotos

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val matrix = Matrix()
    private val prevMatrix = Matrix()

    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f

    private var scale = 1f
    private val minScale = 1f
    private val maxScale = 4f

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    var onSingleTapListener: (() -> Unit)? = null

    val isZoomed: Boolean
        get() = scale > 1.05f

    init {
        scaleType = ScaleType.MATRIX
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onSingleTapListener?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > minScale + 0.1f) {
                    resetZoom()
                } else {
                    matrix.postScale(2.5f, 2.5f, e.x, e.y)
                    scale = 2.5f
                    imageMatrix = matrix
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }
        })
    }

    fun resetZoom() {
        matrix.reset()
        scale = 1f
        centerImage()
        parent?.requestDisallowInterceptTouchEvent(false)
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
        matrix.reset()
        matrix.postScale(scaleFactor, scaleFactor)
        val dx = (viewWidth - dw * scaleFactor) / 2f
        val dy = (viewHeight - dh * scaleFactor) / 2f
        matrix.postTranslate(dx, dy)
        imageMatrix = matrix
        scale = 1f
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed && scale <= 1.05f) {
            centerImage()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val curr = PointF(event.x, event.y)

        if (isZoomed) {
            parent?.requestDisallowInterceptTouchEvent(true)
        } else {
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        when (event.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                prevMatrix.set(matrix)
                start.set(curr)
                mode = DRAG
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                oldDist = spacing(event)
                if (oldDist > 10f) {
                    prevMatrix.set(matrix)
                    midPoint(mid, event)
                    mode = ZOOM
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                mode = NONE
                if (!isZoomed) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == DRAG && isZoomed) {
                    matrix.set(prevMatrix)
                    matrix.postTranslate(curr.x - start.x, curr.y - start.y)
                    imageMatrix = matrix
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

            matrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
            imageMatrix = matrix
            return true
        }
    }

    companion object {
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
