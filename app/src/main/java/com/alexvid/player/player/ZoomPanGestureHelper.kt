package com.alexvid.player.player

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

/**
 * Dedicated Zoom & Pan gesture controller active only in Zoom / Review Mode.
 * Prevents any interference with brightness, volume, or seek swipe gestures.
 */
class ZoomPanGestureHelper(
    context: Context,
    private val containerView: View,
    private val videoView: View,
    private val callback: Callback
) : View.OnTouchListener {

    interface Callback {
        fun onTransformChanged(scale: Float, translationX: Float, translationY: Float)
        fun onSingleTap()
    }

    companion object {
        const val MIN_SCALE = 1.0f
        const val MAX_SCALE = 8.0f
    }

    var scale: Float = 1.0f
        private set
    var translationX: Float = 0f
        private set
    var translationY: Float = 0f
        private set

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (!factor.isFinite() || factor <= 0f) return false

                val prevScale = scale
                val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
                val appliedFactor = newScale / prevScale

                val cx = containerView.width * 0.5f
                val cy = containerView.height * 0.5f
                val focusOffsetX = detector.focusX - cx
                val focusOffsetY = detector.focusY - cy

                // Focal zoom around pinch center so the targeted region stays under fingers
                translationX = (translationX - focusOffsetX) * appliedFactor + focusOffsetX
                translationY = (translationY - focusOffsetY) * appliedFactor + focusOffsetY
                scale = newScale

                applyTransform()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                callback.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.25f) {
                    reset()
                } else {
                    zoomToPoint(targetScale = 3.0f, focusX = e.x, focusY = e.y)
                }
                return true
            }
        }
    )

    fun zoomBy(factor: Float) {
        val prevScale = scale
        val newScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val applied = newScale / prevScale
        scale = newScale
        translationX *= applied
        translationY *= applied
        applyTransform()
    }

    fun zoomToPoint(targetScale: Float, focusX: Float, focusY: Float) {
        val prevScale = scale
        val newScale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val applied = newScale / prevScale

        val cx = containerView.width * 0.5f
        val cy = containerView.height * 0.5f
        val focusOffsetX = focusX - cx
        val focusOffsetY = focusY - cy

        translationX = (translationX - focusOffsetX) * applied + focusOffsetX
        translationY = (translationY - focusOffsetY) * applied + focusOffsetY
        scale = newScale
        applyTransform()
    }

    fun reset() {
        scale = MIN_SCALE
        translationX = 0f
        translationY = 0f
        applyTransform()
    }

    private fun clampTranslation() {
        if (scale <= MIN_SCALE + 0.001f) {
            translationX = 0f
            translationY = 0f
            return
        }
        val vw = videoView.width.toFloat()
        val vh = videoView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        val maxDx = max(0f, (vw * (scale - 1f)) * 0.5f)
        val maxDy = max(0f, (vh * (scale - 1f)) * 0.5f)
        translationX = translationX.coerceIn(-maxDx, maxDx)
        translationY = translationY.coerceIn(-maxDy, maxDy)
    }

    private fun applyTransform() {
        clampTranslation()
        videoView.pivotX = videoView.width * 0.5f
        videoView.pivotY = videoView.height * 0.5f
        videoView.scaleX = scale
        videoView.scaleY = scale
        videoView.translationX = translationX
        videoView.translationY = translationY
        callback.onTransformChanged(scale, translationX, translationY)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isScaling && scale > MIN_SCALE + 0.01f && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY
                        translationX += dx
                        translationY += dy
                        lastTouchX = x
                        lastTouchY = y
                        applyTransform()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        lastTouchX = event.getX(newIndex)
                        lastTouchY = event.getY(newIndex)
                        activePointerId = event.getPointerId(newIndex)
                    } else {
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }
}
