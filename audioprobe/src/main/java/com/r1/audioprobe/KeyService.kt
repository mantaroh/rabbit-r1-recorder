package com.r1.audioprobe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Sees the side button.
 *
 * An ordinary app cannot: the CarrotOS launcher takes the key from its own
 * AccessibilityService (`com.r1.launcher/.PowerService`, which logs as
 * `R1Power`), so it is handled before window focus matters and keeps working
 * with the screen off. Key filtering is the one documented way for a second
 * app to observe the same events.
 *
 * Whether two services both receive a filtered key is exactly what this is here
 * to establish — the framework dispatches to every filtering service, but only
 * the device can say whether the launcher's handling interferes.
 */
class KeyService : AccessibilityService() {

    companion object {
        private const val TAG = "R1AudioProbe"

        /** Matches the launcher's own window for what counts as a double press. */
        private const val DOUBLE_PRESS_MS = 400L

        @Volatile var connected = false; private set
        @Volatile var lastKeyAt = 0L; private set
        @Volatile var doublePresses = 0; private set
    }

    private lateinit var metrics: Metrics
    private var lastDownAt = 0L

    override fun onServiceConnected() {
        metrics = Metrics(this)
        // Also set at runtime: the manifest flag alone has been unreliable
        // across versions, and without it onKeyEvent is never called.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        connected = true
        Log.i(TAG, "KeyService connected, flags=${serviceInfo.flags}")
        metrics.write("keyservice", mapOf("state" to "connected", "flags" to serviceInfo.flags))
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val name = KeyEvent.keyCodeToString(event.keyCode)
        lastKeyAt = System.currentTimeMillis()

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = lastKeyAt
            // Null on the first press of a session, rather than the epoch
            // arithmetic that made the log unreadable.
            val since = if (lastDownAt == 0L) null else now - lastDownAt
            val isDouble = event.keyCode == KeyEvent.KEYCODE_BUTTON_1 &&
                since != null && since in 1..DOUBLE_PRESS_MS
            if (isDouble) {
                doublePresses += 1
                lastDownAt = 0 // a third press starts a new pair, not a chain
            } else {
                lastDownAt = now
            }
            Log.i(TAG, "KeyService key=$name gap=${since ?: "-"}ms double=$isDouble")
            metrics.write(
                "keyservice_key",
                mapOf("key" to name, "code" to event.keyCode, "gap_ms" to since, "double" to isDouble),
            )
        }

        // Never consume: the launcher must keep behaving exactly as before.
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "KeyService interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        return super.onUnbind(intent)
    }
}
