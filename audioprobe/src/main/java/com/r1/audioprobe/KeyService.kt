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

        /**
         * Set by the recorder. A plain callback rather than a broadcast: both
         * live in the same process, and the gesture should reach the state
         * machine without a trip through the system.
         */
        @Volatile var onDoublePress: ((Long) -> Unit)? = null

        /**
         * A press that turned out to be on its own, reported once the window
         * for a second one has passed, with the time the button went down.
         *
         * Deciding "single" costs [DOUBLE_PRESS_MS] of waiting, which is why
         * this is a separate callback rather than something the double-press
         * path can infer. It is what dismisses the standby display — and the
         * delay is why the handler is told *when* the press was: by the time
         * it arrives the screen may have changed because of that same press.
         */
        @Volatile var onSinglePress: ((Long) -> Unit)? = null
    }

    private lateinit var metrics: Metrics
    private var lastDownAt = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var pendingDownAt = 0L
    private val singlePress = Runnable {
        val at = pendingDownAt
        runCatching { onSinglePress?.invoke(at) }
            .onFailure { Log.e(TAG, "single-press handler failed", it) }
    }

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

            // Recorded before dispatching, so the log reads in causal order:
            // the handler writes its own events, and firing first made the
            // state change appear ahead of the press that caused it.
            Log.i(TAG, "KeyService key=$name gap=${since ?: "-"}ms double=$isDouble")
            metrics.write(
                "keyservice_key",
                mapOf("key" to name, "code" to event.keyCode, "gap_ms" to since, "double" to isDouble),
            )

            if (isDouble) {
                // The pending "this was a single press" never happens.
                handler.removeCallbacks(singlePress)
                runCatching { onDoublePress?.invoke(now) }
                    .onFailure { Log.e(TAG, "double-press handler failed", it) }
            } else if (event.keyCode == KeyEvent.KEYCODE_BUTTON_1) {
                // Might still become the first half of a pair; wait out the
                // window before calling it a single.
                handler.removeCallbacks(singlePress)
                pendingDownAt = now
                handler.postDelayed(singlePress, DOUBLE_PRESS_MS + 40)
            }
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
