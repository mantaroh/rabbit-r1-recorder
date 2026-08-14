package com.r1.audioprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts recording again after a reboot.
 *
 * An archive that stops because the battery went flat and came back is not an
 * archive, and until now nothing restarted it: the device would sit there
 * looking healthy, recording nothing, until somebody opened the app.
 *
 * It has to go the long way round. A microphone foreground service cannot be
 * started from the background on Android 14 — the while-in-use permission is
 * not held at boot — so this launches the Activity, which is allowed to start
 * the service and does so immediately when handed `autostart`. The Activity is
 * visible for as long as that takes and then simply stays open.
 *
 * Whether the platform permits even the Activity launch from here is a
 * property of the ROM rather than of the documentation, so the outcome is
 * written to the metrics log: if recording does not resume after a reboot,
 * `boot` is the event that says whether this ever ran.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val metrics = Metrics(context)
        val started = runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess

        Log.i("R1AudioProbe", "boot: $action, launch started=$started")
        metrics.write("boot", mapOf("action" to action, "launched" to started))
    }
}
