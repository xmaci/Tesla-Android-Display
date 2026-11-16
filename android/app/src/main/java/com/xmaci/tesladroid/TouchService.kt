package com.xmaci.tesladroid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

/**
 * Injects taps and swipes coming from the car screen.
 *
 * M1a sends each touch as one complete gesture when the finger lifts:
 * a tap if it barely moved, otherwise a swipe along the recorded path.
 * Live dragging (continueStroke chains) comes in M1.
 */
class TouchService : AccessibilityService() {

    companion object {
        @Volatile var instance: TouchService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** points are absolute screen pixels; durationMs is how long the finger was down. */
    fun stroke(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        val (x0, y0) = points.first()
        val (x1, y1) = points.last()
        val moved = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
        val path = Path().apply { moveTo(x0, y0) }
        val duration: Long
        if (moved < 12) {
            // Tap, or long press if held.
            path.lineTo(x0 + 1, y0 + 1)
            duration = durationMs.coerceIn(40, 1500)
        } else {
            for (i in 1 until points.size) path.lineTo(points[i].first, points[i].second)
            duration = durationMs.coerceIn(80, 3000)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Hotspot on/off by pressing the switch on the Wi‑Fi hotspot settings page (already opened by the
     * caller), then going back. Android 17 refuses programmatic tethering to all but system apps (error 14),
     * so this does what the user would do. Call off the main thread. Returns a short result.
     */
    fun setHotspot(on: Boolean): String {
        repeat(25) {
            val root = rootInActiveWindow
            if (root != null && root.packageName == "com.android.settings") {
                val sw = findSwitch(root)
                if (sw != null) {
                    val was = sw.isChecked
                    if (was != on) {
                        var n: android.view.accessibility.AccessibilityNodeInfo? = sw
                        while (n != null && !n.isClickable) n = n.parent
                        (n ?: sw).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(800)
                    }
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return if (was == on) "already ${if (on) "on" else "off"}" else "switched ${if (on) "on" else "off"}"
                }
            }
            Thread.sleep(200)
        }
        return "hotspot switch not found"
    }

    private fun findSwitch(root: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        val q = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            val cls = n.className?.toString() ?: ""
            if (n.isCheckable && (cls.contains("Switch") || cls.contains("CompoundButton") || cls.contains("Toggle"))) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    fun key(name: String): Boolean = when (name) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }
}
