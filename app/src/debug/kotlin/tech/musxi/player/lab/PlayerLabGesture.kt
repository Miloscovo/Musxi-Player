package tech.musxi.player.lab

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlin.math.abs

/** Receives one axis-locked drag. Velocities are px/s along the locked axis (down and right are positive). */
internal interface AxisDragHandler {
    /** Whether a drag on this axis may start right now; false lets the touch pass to children. */
    fun allows(vertical: Boolean): Boolean
    fun onLock(vertical: Boolean)
    fun onDelta(vertical: Boolean, delta: Float)
    fun onEnd(vertical: Boolean, velocity: Float)
    fun onCancel(vertical: Boolean)
}

/**
 * One pointer, one axis. After the touch slop the dominant axis wins and the other is ignored for
 * the rest of the gesture, so a vertical pull and a horizontal cover swipe never fight. A child that
 * consumes the movement first (a scrolling list) keeps the touch; a child that only clicks does not,
 * and its click is cancelled once the drag starts.
 */
internal fun Modifier.axisLockedDrag(handler: AxisDragHandler): Modifier = pointerInput(handler) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var total = Offset.Zero
        var locked: Boolean? = null
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null) {
                locked?.let(handler::onCancel)
                return@awaitEachGesture
            }
            if (locked == null && change.isConsumed) return@awaitEachGesture
            tracker.addPointerInputChange(change)
            if (change.changedToUpIgnoreConsumed()) {
                val axis = locked ?: return@awaitEachGesture
                val velocity = tracker.calculateVelocity()
                handler.onEnd(axis, if (axis) velocity.y else velocity.x)
                return@awaitEachGesture
            }
            val delta = change.positionChange()
            val axis = locked
            if (axis == null) {
                total += delta
                if (total.getDistance() >= viewConfiguration.touchSlop) {
                    val vertical = abs(total.y) > abs(total.x)
                    if (!handler.allows(vertical)) return@awaitEachGesture
                    locked = vertical
                    handler.onLock(vertical)
                    handler.onDelta(vertical, if (vertical) total.y else total.x)
                    change.consume()
                }
            } else {
                handler.onDelta(axis, if (axis) delta.y else delta.x)
                change.consume()
            }
        }
    }
}
