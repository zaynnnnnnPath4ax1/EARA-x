package com.eara.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent

sealed class ActionResult {
    data class Executed(val action: String) : ActionResult()
    data class Unavailable(val action: String, val reason: String) : ActionResult()
}

/**
 * Executes phone actions using only legitimate, public Android APIs:
 * - Media transport (next/prev/play/pause) via AudioManager media-key dispatch,
 *   which the currently active media app (e.g. a video/music app) receives —
 *   this is the standard way headset buttons control media system-wide.
 * - Volume via AudioManager.
 * - Voice assistant via the public ACTION_VOICE_COMMAND intent.
 * - BACK / HOME / SCROLL via AccessibilityService — ONLY if the user has
 *   already enabled it themselves in Android Settings. Never enabled here.
 * - Anything Android doesn't expose an API for (e.g. tapping a specific
 *   app's "Like" button) is reported as unavailable, never faked.
 */
class ActionExecutor(private val context: Context) {

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    fun execute(rawLabel: String): ActionResult {
        val label = rawLabel.trim().uppercase()

        return when (label) {
            "NEXT REEL", "NEXT MEDIA", "NEXT" ->
                mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT, label)

            "PREVIOUS REEL", "PREVIOUS MEDIA", "PREVIOUS" ->
                mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, label)

            "PLAY" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, label)
            "PAUSE" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, label)
            "PLAY/PAUSE", "PLAY PAUSE" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, label)

            "VOLUME UP" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                )
                ActionResult.Executed(label)
            }

            "VOLUME DOWN" -> {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
                )
                ActionResult.Executed(label)
            }

            "VOICE ASSISTANT" -> {
                try {
                    val intent = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ActionResult.Executed(label)
                } catch (e: Exception) {
                    ActionResult.Unavailable(label, "No voice assistant app available to handle this")
                }
            }

            "BACK" -> withAccessibility(label) { it.goBack() }
            "HOME" -> withAccessibility(label) { it.goHome() }
            "SCROLL" -> withAccessibility(label) { it.scrollUp() }

            "LIKE" -> ActionResult.Unavailable(
                label, "Android has no public API to tap another app's Like button"
            )

            "CUSTOM ACTION" -> ActionResult.Unavailable(
                label, "Custom action has no system behavior defined yet"
            )

            "NONE", "" -> ActionResult.Executed(label)

            else -> ActionResult.Unavailable(label, "Unrecognized action")
        }
    }

    private fun mediaKey(keyCode: Int, label: String): ActionResult {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val up = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        audioManager.dispatchMediaKeyEvent(down)
        audioManager.dispatchMediaKeyEvent(up)
        return ActionResult.Executed(label)
    }

    private fun withAccessibility(label: String, block: (EaraAccessibilityService) -> Unit): ActionResult {
        val service = EaraAccessibilityService.instance
        return if (service != null) {
            block(service)
            ActionResult.Executed(label)
        } else {
            ActionResult.Unavailable(
                label,
                "Enable EARA in Settings > Accessibility to use $label"
            )
        }
    }
}
