package app.shiftlit.driver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/** Performs taps inside the target app via the accessibility gesture API. */
object GesturePerformer {

    /**
     * True when the point is clear of the status bar and the navigation /
     * gesture area. A control laid out off screen (Spark's list card extends
     * below the fold) would otherwise be "tapped" at the screen edge, which
     * lands on Recents or Home and yanks the driver out of the app.
     */
    fun onScreen(service: AccessibilityService, x: Int, y: Int): Boolean {
        val dm = service.resources.displayMetrics
        val insetTop = (24 * dm.density).toInt()
        val insetBottom = (64 * dm.density).toInt()
        return x in 0 until dm.widthPixels &&
            y in insetTop until (dm.heightPixels - insetBottom)
    }

    fun tap(service: AccessibilityService, bounds: Bounds, done: (Boolean) -> Unit = {}) {
        if (!onScreen(service, bounds.centerX(), bounds.centerY())) {
            done(false)
            return
        }
        val path = Path().apply {
            moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        // dispatchGesture returns false when it can't dispatch at all —
        // neither callback fires then, so report failure ourselves.
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = done(true)
                override fun onCancelled(g: GestureDescription?) = done(false)
            },
            null,
        )
        if (!dispatched) done(false)
    }

    /** Left→right swipe across [bounds] — Amazon Flex uses a "swipe to
     *  accept" slider where a plain tap does nothing. */
    fun swipeRight(service: AccessibilityService, bounds: Bounds, done: (Boolean) -> Unit = {}) {
        val path = Path().apply {
            moveTo(bounds.left + bounds.width() * 0.15f, bounds.centerY().toFloat())
            lineTo(bounds.right - bounds.width() * 0.10f, bounds.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = done(true)
                override fun onCancelled(g: GestureDescription?) = done(false)
            },
            null,
        )
        if (!dispatched) done(false)
    }
}
