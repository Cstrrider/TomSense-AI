package org.tomsense.android.feed

import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Swipe-to-dismiss for the overlay panel, intercepted before Compose sees it.
 *
 * A Compose `pointerInput` on the panel root does NOT work here. Pointer
 * events reach a parent only after its children in the main pass, and the
 * LazyColumn inside claims the drag, so the gesture that should close the
 * panel is swallowed by the list — leaving the back gesture as the only way
 * out. Intercepting at the view level is the standard answer and does not care
 * what the content is.
 *
 * Only LEFTWARD drags are taken, and only when the movement is more horizontal
 * than vertical, so the list keeps its own scrolling.
 */
class DismissFrameLayout(context: Context) : FrameLayout(context) {

    /** Progress 1..0 as the panel is dragged away; drives the window alpha. */
    var onDragTo: (Float) -> Unit = {}

    /**
     * Release, with both the distance travelled and the horizontal velocity in
     * px/s (negative is leftward).
     *
     * Velocity is reported because distance alone makes dismissal feel stuck:
     * a quick flick is the gesture people actually make, and it covers very
     * little of the screen before the finger lifts.
     */
    var onDragSettled: (Float, Float) -> Unit = { _, _ -> }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Track from the very first event. Velocity measured only from where
        // the drag was recognised would miss the fastest part of a flick.
        trackerFor(ev).addMovement(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && dx < -slop && abs(dx) > abs(dy)) {
                    dragging = true
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseTracker()
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val span = width.coerceAtLeast(1)
        trackerFor(ev).addMovement(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    onDragTo(progressOf(ev, span))
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val velocity = tracker?.let {
                    it.computeCurrentVelocity(1000)
                    it.xVelocity
                } ?: 0f
                releaseTracker()

                if (dragging) {
                    onDragSettled(progressOf(ev, span), velocity)
                    dragging = false
                    return true
                }
            }
        }
        return dragging
    }

    private fun trackerFor(ev: MotionEvent): VelocityTracker {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) releaseTracker()
        return tracker ?: VelocityTracker.obtain().also { tracker = it }
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }

    /**
     * Dragging right must never push progress past fully-open, or the panel
     * would brighten beyond its own opacity on a rightward wobble.
     */
    private fun progressOf(ev: MotionEvent, span: Int): Float {
        val dx = (ev.x - downX).coerceAtMost(0f)
        return (1f + dx / span).coerceIn(0f, 1f)
    }
}
