package com.eara.app

/**
 * Simple in-process pub/sub between EaraGestureService (which keeps working
 * while the app is backgrounded / screen is locked) and whichever Activity
 * instance is currently alive (handles activity recreation cleanly).
 */
object GestureEventBus {

    interface Listener {
        fun onGestureFired(gestureId: String, action: String)
        fun onGestureUnsupported(reason: String)
    }

    @Volatile private var listener: Listener? = null

    // Holds the most recent event if no listener was attached when it fired,
    // so a newly (re)created Activity can catch up instead of silently missing it.
    @Volatile private var pendingEvent: Pair<String, String>? = null

    fun attach(l: Listener) {
        listener = l
        pendingEvent?.let {
            l.onGestureFired(it.first, it.second)
            pendingEvent = null
        }
    }

    fun detach(l: Listener) {
        if (listener === l) listener = null
    }

    fun publishGesture(gestureId: String, action: String) {
        val l = listener
        if (l != null) {
            l.onGestureFired(gestureId, action)
        } else {
            pendingEvent = gestureId to action
        }
    }

    fun publishUnsupported(reason: String) {
        listener?.onGestureUnsupported(reason)
    }
}
