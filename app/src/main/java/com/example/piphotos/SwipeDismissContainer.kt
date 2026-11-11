package com.example.piphotos

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * High-performance downward swipe-to-dismiss container matching
 * the instant, fluid Google Photos gesture physics.
 *
 * Provides responsive flick detection, rapid 110ms exit acceleration,
 * synchronized background alpha fading, and seamless return to gallery grid.
 */
class SwipeDismissContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 0.7f

    private var downX = 0f
    private var downY = 0f
    private var isDragging = false
    private var velocityTracker: VelocityTracker? = null
    private var currentAnimator: ValueAnimator? = null
    private var isDismissing = false

    var isZoomedSupplier: (() -> Boolean)? = null
    var onDismissListener: (() -> Unit)? = null
    var onDragProgressChanged: ((progress: Float) -> Unit)? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (isDismissing) return true

        // When zoomed in, child handles panning; disallow dismiss interception
        if (isZoomedSupplier?.invoke() == true) {
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                isDragging = false
                currentAnimator?.cancel()
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY

                // Intercept only for predominantly downward vertical movement
                if (dy > touchSlop && dy > abs(dx) * 1.3f) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return isDragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isDismissing) return true

        if (isZoomedSupplier?.invoke() == true) {
            return false
        }

        velocityTracker?.addMovement(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                isDragging = false
                currentAnimator?.cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (dy > 0) {
                    isDragging = true
                    translationY = dy

                    // Fluid proportional scaling down to 0.70x
                    val progress = (dy / height.toFloat()).coerceIn(0f, 1f)
                    val scale = 1.0f - (progress * 0.30f)
                    scaleX = scale
                    scaleY = scale

                    onDragProgressChanged?.invoke(progress)
                } else {
                    translationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                    onDragProgressChanged?.invoke(0f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000)
                    val yVelocity = velocityTracker?.yVelocity ?: 0f

                    // Snappy dismiss thresholds: quick flick or >8% screen distance (~60-180px)
                    val dismissHeightThreshold = (height * 0.08f).coerceIn(60f, 180f)
                    val isFlingDismiss = yVelocity > minFlingVelocity && translationY > 20f
                    val isHeightDismiss = translationY > dismissHeightThreshold

                    if (isFlingDismiss || isHeightDismiss) {
                        animateDismiss()
                    } else {
                        animateReset()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    /**
     * Rapid 110ms accelerate-down exit animation.
     * Continuously drives progress to 1f so background alpha fades synchronously.
     */
    fun animateDismiss() {
        if (isDismissing) return
        isDismissing = true
        currentAnimator?.cancel()

        val startY = translationY
        val startScale = scaleX
        val startAlpha = alpha
        val startProgress = if (height > 0) (startY / height.toFloat()).coerceIn(0f, 1f) else 0f
        val targetY = startY + (if (height > 0) height * 0.35f else 500f).coerceAtLeast(400f)

        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 110L // Snappy 110ms exit
            interpolator = AccelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val f = va.animatedFraction
                translationY = startY + (targetY - startY) * f
                val currentScale = startScale + (0.45f - startScale) * f
                scaleX = currentScale
                scaleY = currentScale
                alpha = (startAlpha * (1f - f)).coerceIn(0f, 1f)

                val effectiveProgress = (startProgress + (1f - startProgress) * f).coerceIn(0f, 1f)
                onDragProgressChanged?.invoke(effectiveProgress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    alpha = 0f
                    onDismissListener?.invoke()
                }

                override fun onAnimationEnd(animation: Animator) {
                    alpha = 0f
                    onDismissListener?.invoke()
                }
            })
        }
        currentAnimator?.start()
    }

    /**
     * Fast 140ms bounce-free snap-back if user cancels or releases above threshold.
     */
    fun animateReset() {
        isDismissing = false
        currentAnimator?.cancel()

        val startY = translationY
        val startScale = scaleX
        val startAlpha = alpha
        val startProgress = if (height > 0) (startY / height.toFloat()).coerceIn(0f, 1f) else 0f

        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 140L // Fast 140ms return
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { va ->
                val f = va.animatedFraction
                translationY = startY * (1f - f)
                val currentScale = startScale + (1.0f - startScale) * f
                scaleX = currentScale
                scaleY = currentScale
                alpha = startAlpha + (1.0f - startAlpha) * f

                val effectiveProgress = (startProgress * (1f - f)).coerceIn(0f, 1f)
                onDragProgressChanged?.invoke(effectiveProgress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isDismissing = false
                    translationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                    alpha = 1f
                    onDragProgressChanged?.invoke(0f)
                }

                override fun onAnimationCancel(animation: Animator) {
                    isDismissing = false
                }
            })
        }
        currentAnimator?.start()
    }
}


