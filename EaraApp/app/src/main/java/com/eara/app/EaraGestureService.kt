package com.eara.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts a real, active MediaSession so genuine AVRCP media-button events sent
 * by connected earbuds keep arriving even while EARA is backgrounded or the
 * screen is off. This does NOT play audio and does NOT scan/pair devices —
 * it only listens for button events Android already legitimately delivers
 * to the active media session.
 */
class EaraGestureService : Service(), GestureEngine.Listener {

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var gestureEngine: GestureEngine
    private lateinit var configStore: GestureConfigStore
    private lateinit var actionExecutor: ActionExecutor
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    @Volatile private var currentConfig: Map<String, String> = GestureConfigStore.DEFAULT_CONFIG

    override fun onCreate() {
        super.onCreate()
        configStore = GestureConfigStore(applicationContext)
        gestureEngine = GestureEngine(this)
        actionExecutor = ActionExecutor(applicationContext)

        serviceScope.launch {
            configStore.configFlow.collect { currentConfig = it }
        }

        setupMediaSession()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "EaraGestureSession").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
            isActive = true
            setPlaybackState(activePlaybackState())
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val event = mediaButtonIntent
                        .getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false

                    // Only react to ACTION_DOWN to avoid double-counting DOWN+UP as two clicks.
                    if (event.action != KeyEvent.ACTION_DOWN) return true

                    when (event.keyCode) {
                        KeyEvent.KEYCODE_HEADSETHOOK,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (event.isLongPress) gestureEngine.onHold() else gestureEngine.onClick()
                        }
                        // Some earbud/BT stacks pre-aggregate double/triple click into these
                        // directly, bypassing raw HEADSETHOOK clicks entirely.
                        KeyEvent.KEYCODE_MEDIA_NEXT -> gestureEngine.onDirectSkipNext()
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> gestureEngine.onDirectSkipPrevious()
                        else -> gestureEngine.onUnknownKeyEvent(KeyEvent.keyCodeToString(event.keyCode))
                    }
                    // Re-touch our playback state right after handling a real event.
                    // Android generally routes the next hardware media-button event to
                    // whichever session most recently reported an active state — this
                    // keeps EARA at the front of that queue instead of losing priority
                    // to another app the moment it starts playing something.
                    setPlaybackState(activePlaybackState())
                    // Returning true tells the framework WE fully handled it, so it
                    // won't also invoke onPlay/onPause/onSkipToNext and double-fire.
                    return true
                }
            })
        }
    }

    /**
     * A minimal "alive" playback state — no audio is played, nothing is queued.
     * This exists purely so Android's media-button dispatcher sees EARA as an
     * active session eligible to receive hardware button events, instead of an
     * idle/unknown one it may skip in favor of a real media app.
     *
     * Honest limitation: if another app (Spotify, YouTube Music, etc.) is
     * actively playing audio *at the same moment* a button is pressed, Android
     * may still route that specific event to the actively-playing app instead
     * of EARA — this is a platform-level, most-recently-active heuristic that
     * no app can unconditionally override. This fix makes EARA compete fairly
     * for priority; it does not guarantee it always wins.
     */
    private fun activePlaybackState(): PlaybackStateCompat =
        PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(
                PlaybackStateCompat.STATE_PLAYING,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f
            )
            .build()

    override fun onGestureDetected(gestureId: String) {
        val action = currentConfig[gestureId] ?: run {
            GestureEventBus.publishUnsupported("No action configured for $gestureId")
            return
        }
        when (val result = actionExecutor.execute(action)) {
            is ActionResult.Executed ->
                GestureEventBus.publishGesture(gestureId, result.action)
            is ActionResult.Unavailable ->
                GestureEventBus.publishUnsupported("${result.action}: ${result.reason}")
        }
    }

    override fun onUnsupportedEvent(reason: String) {
        GestureEventBus.publishUnsupported(reason)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        // Sticky: if the system kills the process, restart and keep listening.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        gestureEngine.reset()
        mediaSession.isActive = false
        mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "eara_gesture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "EARA Earbud Control", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps earbud tap/hold detection active" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val builder = Notification.Builder(this, channelId)
            .setContentTitle("EARA")
            .setContentText("Listening for earbud controls")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)

        // Guard: getLaunchIntentForPackage can legitimately return null on some
        // devices/states. Never crash the foreground service over a tap target.
        if (openAppIntent != null) {
            val contentIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(contentIntent)
        }

        return builder.build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.eara.app.action.STOP_GESTURE_SERVICE"
    }
}
