package com.alexvid.player.player

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

class PlayerGestureHelper(
    context: Context,
    private val targetView: View,
    private val listener: GestureListener
) : View.OnTouchListener {

    interface GestureListener {
        fun isLocked(): Boolean
        fun onSingleTap()
        fun onDoubleTapLeft()
        fun onDoubleTapRight()
        fun onDoubleTapCenter()
        fun onBrightnessStart(): Float // 0f..1f
        fun onBrightnessChange(newBrightness: Float)
        fun onVolumeStart(): Float // 0f..2f (0..1 system, 1..2 software boost)
        fun onVolumeChange(newVolume: Float)
        fun onSeekStart(): Long // current position ms
        fun onSeekPreview(targetMs: Long, deltaMs: Long)
        fun onSeekCommit(targetMs: Long)
        fun getDurationMs(): Long
        fun onPinchZoom(zoomIn: Boolean)
        fun onLongPressStart()
        fun onLongPressEnd()
        fun onGestureEnd()
    }

    private enum class DragMode {
        NONE, BRIGHTNESS, VOLUME, SEEK
    }

    private var dragMode = DragMode.NONE
    private var startX = 0f
    private var startY = 0f
    private var initialBrightness = 0.5f
    private var initialVolume = 0.5f
    private var initialSeekPos = 0L
    private var pendingSeekTarget = 0L
    private var isLongPressing = false
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var accumulatedScale = 1f

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (listener.isLocked()) return false
                isScaling = true
                dragMode = DragMode.NONE
                accumulatedScale = 1f
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (listener.isLocked()) return false
                accumulatedScale *= detector.scaleFactor
                if (accumulatedScale > 1.18f) {
                    listener.onPinchZoom(zoomIn = true)
                    accumulatedScale = 1f
                } else if (accumulatedScale < 0.84f) {
                    listener.onPinchZoom(zoomIn = false)
                    accumulatedScale = 1f
                }
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
                listener.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (listener.isLocked()) {
                    listener.onSingleTap()
                    return true
                }
                val width = targetView.width.coerceAtLeast(1)
                val xRatio = e.x / width.toFloat()
                when {
                    xRatio < 0.35f -> listener.onDoubleTapLeft()
                    xRatio > 0.65f -> listener.onDoubleTapRight()
                    else -> listener.onDoubleTapCenter()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (listener.isLocked() || dragMode != DragMode.NONE || isScaling) return
                isLongPressing = true
                listener.onLongPressStart()
            }
        }
    )

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            if (isLongPressing) {
                isLongPressing = false
                listener.onLongPressEnd()
            }
            return true
        }

        gestureDetector.onTouchEvent(event)

        if (listener.isLocked()) {
            return true
        }

        val width = targetView.width.coerceAtLeast(1).toFloat()
        val height = targetView.height.coerceAtLeast(1).toFloat()
        val edgeMargin = height * 0.08f // Ignore edge pulls (status/nav bar)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                dragMode = DragMode.NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (isScaling || isLongPressing) return true
                val dx = event.x - startX
                val dy = startY - event.y // positive when swiping up

                if (dragMode == DragMode.NONE) {
                    val threshold = 32f
                    if (abs(dx) > threshold || abs(dy) > threshold) {
                        if (abs(dx) > abs(dy) * 1.2f) {
                            if (listener.getDurationMs() > 0L) {
                                dragMode = DragMode.SEEK
                                initialSeekPos = listener.onSeekStart()
                                pendingSeekTarget = initialSeekPos
                            }
                        } else if (startY > edgeMargin && startY < height - edgeMargin) {
                            if (startX < width * 0.5f) {
                                dragMode = DragMode.BRIGHTNESS
                                initialBrightness = listener.onBrightnessStart()
                            } else {
                                dragMode = DragMode.VOLUME
                                initialVolume = listener.onVolumeStart()
                            }
                        }
                    }
                }

                when (dragMode) {
                    DragMode.BRIGHTNESS -> {
                        val delta = dy / (height * 0.65f)
                        val next = (initialBrightness + delta).coerceIn(0.01f, 1.0f)
                        listener.onBrightnessChange(next)
                    }

                    DragMode.VOLUME -> {
                        val delta = dy / (height * 0.65f) * 1.6f
                        val next = (initialVolume + delta).coerceIn(0f, 2.0f)
                        listener.onVolumeChange(next)
                    }

                    DragMode.SEEK -> {
                        val duration = listener.getDurationMs()
                        if (duration > 0L) {
                            // Full width swipe = up to 90 seconds (or full duration for short clips)
                            val maxDeltaMs = minOf(duration, 90_000L).coerceAtLeast(15_000L)
                            val deltaMs = ((dx / width) * maxDeltaMs).toLong()
                            pendingSeekTarget = (initialSeekPos + deltaMs).coerceIn(0L, duration)
                            listener.onSeekPreview(pendingSeekTarget, pendingSeekTarget - initialSeekPos)
                        }
                    }

                    DragMode.NONE -> Unit
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isLongPressing) {
                    isLongPressing = false
                    listener.onLongPressEnd()
                }
                if (dragMode == DragMode.SEEK) {
                    listener.onSeekCommit(pendingSeekTarget)
                }
                if (dragMode != DragMode.NONE) {
                    dragMode = DragMode.NONE
                    listener.onGestureEnd()
                }
            }
        }
        return true
    }
}
