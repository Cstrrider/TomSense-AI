package org.tomsense.android.feed

import android.content.Context
import android.view.MotionEvent
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

    /** Final progress on release — close below the threshold, snap open above. */
    var onDragSettled: (Float) -> Unit = {}

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
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
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val span = width.coerceAtLeast(1)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    onDragTo(progressOf(ev, span))
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    onDragSettled(progressOf(ev, span))
                    dragging = false
                    return true
                }
            }
        }
        return dragging
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
