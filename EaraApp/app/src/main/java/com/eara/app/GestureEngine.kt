package com.eara.app

import android.os.Handler
import android.os.Looper

/**
 * Turns a stream of REAL raw media-button click events (from the earbud, via
 * Android's media session / AVRCP) into a finalized gesture id:
 * "1tap", "2tap", "3tap", "4tap", or "hold".
 *
 * This never invents a gesture. It only ever reports what was actually detected.
 * If the connected earbud/BT stack does not deliver enough raw clicks to reach
 * a multi-tap (a real, common hardware/OS limitation), onUnsupported() fires
 * instead of pretending the higher tap count happened.
 */
class GestureEngine(
    private val listener: Listener,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    interface Listener {
        fun onGestureDetected(gestureId: String)
        fun onUnsupportedEvent(reason: String)
    }

    companion object {
        private const val MULTI_CLICK_WINDOW_MS = 400L   // gap allowed between clicks
        private const val DUPLICATE_SUPPRESS_MS = 200L   // ignore identical repeat events
        private const val MAX_TAPS = 4
    }

    private var clickCount = 0
    private var lastFiredGesture: String? = null
    private var lastFiredAt = 0L

    private val finalizeRunnable = Runnable { finalizeClicks() }

    /** Call on every real ACTION_DOWN click (not long-press) from the earbud button. */
    fun onClick() {
        clickCount++
        handler.removeCallbacks(finalizeRunnable)
        if (clickCount >= MAX_TAPS) {
            // Don't wait further once we hit the highest supported mapping.
            finalizeClicks()
        } else {
            handler.postDelayed(finalizeRunnable, MULTI_CLICK_WINDOW_MS)
        }
    }

    /** Call when the earbud button is held past the long-press threshold. */
    fun onHold() {
        handler.removeCallbacks(finalizeRunnable)
        clickCount = 0
        emit("hold")
    }

    /** Call when the OS/BT stack pre-aggregates directly to NEXT/PREVIOUS keycodes. */
    fun onDirectSkipNext() {
        handler.removeCallbacks(finalizeRunnable)
        clickCount = 0
        emit("2tap")
    }

    fun onDirectSkipPrevious() {
        handler.removeCallbacks(finalizeRunnable)
        clickCount = 0
        emit("3tap")
    }

    fun onUnknownKeyEvent(keyCodeName: String) {
        listener.onUnsupportedEvent("Unrecognized media event from earbud: $keyCodeName")
    }

    fun reset() {
        handler.removeCallbacks(finalizeRunnable)
        clickCount = 0
    }

    private fun finalizeClicks() {
        val count = clickCount
        clickCount = 0
        if (count <= 0) return
        if (count > MAX_TAPS) {
            listener.onUnsupportedEvent("Earbud sent $count clicks; only up to ${MAX_TAPS}tap is supported")
            return
        }
        emit("${count}tap")
    }

    private fun emit(gestureId: String) {
        val now = System.currentTimeMillis()
        // Debounce: identical gesture arriving again immediately is treated as
        // a duplicate event from the Bluetooth/media stack, not a new action.
        if (gestureId == lastFiredGesture && now - lastFiredAt < DUPLICATE_SUPPRESS_MS) {
            return
        }
        lastFiredGesture = gestureId
        lastFiredAt = now
        listener.onGestureDetected(gestureId)
    }
}
