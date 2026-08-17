package com.r1.audioprobe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the device is, every few minutes.
 *
 * **There is no network location on this device.** `dumpsys location` lists
 * passive, fused and gps and nothing else — CarrotOS ships no Play Services, so
 * the AOSP fused provider is a thin wrapper over GPS and the passive one only
 * repeats what somebody else asked for. The practical consequence is that
 * indoors there is usually no fix at all, however long you wait, and waiting
 * costs the GPS radio.
 *
 * So the cadence follows the same rule the timelapse already uses: rare at
 * home, frequent away. Not because home positions are less interesting in
 * principle, but because at home they are both known and unobtainable, and
 * spending radio to fail every five minutes buys nothing.
 *
 * A failed fix is written down. A gap in the track otherwise reads as "the
 * device was off", which is a different fact from "the device was indoors".
 */
class Positions(
    private val context: Context,
    private val metrics: Metrics,
    private val settings: UploadSettings,
    private val dir: File,
) {

    companion object {
        private const val TAG = "R1AudioProbe"

        /** At home the answer is known, and the radio would mostly fail anyway. */
        private const val HOME_INTERVAL_MS = 15 * 60_000L

        /** Out, five minutes is a street corner. */
        private const val AWAY_INTERVAL_MS = 5 * 60_000L

        /**
         * How long to leave the receiver running for one fix.
         *
         * A cold GPS start outdoors is tens of seconds; indoors it is forever.
         * This is the line between the two, and it is the whole power budget of
         * the feature — the radio is on for at most this long per interval.
         */
        private const val FIX_TIMEOUT_MS = 45_000L

        /**
         * A fix this stale is reported rather than requested. `getLastKnown`
         * costs nothing, and if something else on the device got a fix a minute
         * ago there is no reason to warm the receiver for a second one.
         */
        private const val FRESH_ENOUGH_MS = 90_000L

        /** Positions waiting to go up. One line of JSON each, about eighty bytes. */
        private const val PENDING = "positions.jsonl"
    }

    private val manager = context.getSystemService(LocationManager::class.java)
    private var lastRunAt = 0L
    private var inFlight = false

    private fun stamp(millis: Long) =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(millis))

    /** Driven from the recorder's loop, like the timelapse. */
    fun tick(nowMs: Long) {
        if (inFlight) return
        val home = Timelapse.isHome(Timelapse.currentSsid(context), settings.photoSsid)
        val interval = if (home) HOME_INTERVAL_MS else AWAY_INTERVAL_MS
        if (nowMs - lastRunAt < interval) return
        lastRunAt = nowMs

        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            metrics.write("position", mapOf("outcome" to "no_permission"))
            return
        }

        val provider = provider() ?: run {
            metrics.write("position", mapOf("outcome" to "no_provider"))
            return
        }

        val recent = lastKnown()
        if (recent != null && nowMs - recent.time < FRESH_ENOUGH_MS) {
            record(recent, "cached")
            return
        }

        inFlight = true
        runCatching {
            manager.getCurrentLocation(
                provider,
                CancellationSignal().also { signal ->
                    // The platform honours its own timeout, but only from
                    // Android 12 and only sometimes; this is the belt.
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ runCatching { signal.cancel() } }, FIX_TIMEOUT_MS)
                },
                context.mainExecutor,
            ) { location ->
                inFlight = false
                if (location == null) {
                    // Not an error. Indoors this is the expected answer, and
                    // recording it is what separates "no sky" from "no device".
                    metrics.write("position", mapOf("outcome" to "no_fix", "provider" to provider))
                } else {
                    record(location, provider)
                }
            }
        }.onFailure {
            inFlight = false
            Log.w(TAG, "position request failed", it)
            metrics.write("position", mapOf("outcome" to "error", "detail" to it.message))
        }
    }

    /**
     * FUSED where it exists — it is the one the platform keeps warm — falling
     * back to GPS. Neither is a network fix; see the note at the top.
     */
    private fun provider(): String? {
        val wanted = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
        return wanted.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    private fun lastKnown(): Location? = runCatching {
        listOfNotNull(
            manager.getLastKnownLocation(LocationManager.FUSED_PROVIDER),
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER),
            manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER),
        ).maxByOrNull { it.time }
    }.getOrNull()

    private fun record(location: Location, provider: String) {
        val row = JSONObject().apply {
            put("device_id", settings.deviceId)
            put("recorded_at", stamp(location.time))
            put("lat", location.latitude)
            put("lon", location.longitude)
            if (location.hasAccuracy()) put("accuracy_m", location.accuracy.toDouble())
            if (location.hasAltitude()) put("altitude_m", location.altitude)
            if (location.hasSpeed()) put("speed_mps", location.speed.toDouble())
            if (location.hasBearing()) put("bearing_deg", location.bearing.toDouble())
            put("provider", provider)
        }

        synchronized(this) {
            runCatching { File(dir, PENDING).appendText(row.toString() + "\n") }
                .onFailure { Log.w(TAG, "could not queue position", it) }
        }

        metrics.write(
            "position",
            mapOf(
                "outcome" to "fix",
                "provider" to provider,
                "accuracy_m" to if (location.hasAccuracy()) location.accuracy.toInt() else null,
            ),
        )
    }

    /**
     * Hands the queue to the caller and clears it, by rename rather than by
     * truncate.
     *
     * Truncating a file that [record] may be appending to loses whatever landed
     * between the read and the write. Renaming cannot: a fix taken during the
     * upload starts a fresh file and goes out next time.
     */
    fun takePending(): File? = synchronized(this) {
        val pending = File(dir, PENDING)
        if (!pending.exists() || pending.length() == 0L) return null
        val handoff = File(dir, "positions-${System.currentTimeMillis()}.jsonl")
        if (!pending.renameTo(handoff)) return null
        handoff
    }
}
