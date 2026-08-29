package com.eara.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service. EARA never turns this on for the user —
 * Android requires the user to enable it themselves in
 * Settings > Accessibility, and this class does nothing until they do.
 *
 * Only used for the small set of actions Android has no other legitimate
 * API for: BACK, HOME, and screen SCROLL.
 */
class EaraAccessibilityService : AccessibilityService() {

    companion object {
        // Set only while this service is actually connected/running, i.e. the
        // user has explicitly enabled it. This is the real source of truth —
        // more reliable than just reading the Settings string.
        @Volatile var instance: EaraAccessibilityService? = null
            private set

        val isRunning: Boolean get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // Not used for content reading — EARA never inspects screen content.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }

    override fun onInterrupt() { /* no-op */ }

    fun goBack() = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** Simulates a real upward swipe gesture, like scrolling a feed. */
    fun scrollUp() {
        val metrics: DisplayMetrics = resources.displayMetrics
        val centerX = metrics.widthPixels / 2f
        val startY = metrics.heightPixels * 0.75f
        val endY = metrics.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
