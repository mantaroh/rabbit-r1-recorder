package com.r1.audioprobe

import android.content.Context

/**
 * Where finished segments go, and when they are allowed to go there.
 *
 * The device holds three credentials because two hops check them: Cloudflare
 * Access at the edge, then the Worker's own bearer. See
 * `cloudflare/lifelog/src/index.ts`.
 */
class UploadSettings(context: Context) {

    companion object {
        private const val PREFS = "upload"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_BEARER = "bearer"
        private const val KEY_ACCESS_ID = "access_client_id"
        private const val KEY_ACCESS_SECRET = "access_client_secret"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_UNMETERED_ONLY = "unmetered_only"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PHOTO_SSID = "photo_ssid"
        private const val KEY_PROMPTED_AT = "prompted_at"
        private const val KEY_ANSWERED_AT = "answered_at"
        private const val KEY_STARTED_AT = "schedule_started_at"
        private const val KEY_RECORDING = "recording"
        private const val KEY_SIGNAGE = "signage_ids"
        private const val KEY_WAKE_LOCK = "wake_lock"
        private const val KEY_VOICE_RECOGNITION = "voice_recognition"
        private const val KEY_OPUS = "opus"
        private const val KEY_INTERPRET_TARGET = "interpret_target"

        private const val DEFAULT_BASE_URL = "https://lifelog.mantaroh.org"
        private const val DEFAULT_DEVICE_ID = "rabbit-r1-01"

        /** Japanese unless told otherwise; this device lives in Japan. */
        private const val DEFAULT_LANGUAGE = "ja"

        /** Offered in the UI, in cycle order. */
        val LANGUAGES = listOf("ja", "en", "auto")
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_ENABLED, v).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().trimEnd('/')
        set(v) = prefs.edit().putString(KEY_BASE_URL, v.trim().trimEnd('/')).apply()

    var bearer: String
        get() = prefs.getString(KEY_BEARER, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_BEARER, v.trim()).apply()

    var accessClientId: String
        get() = prefs.getString(KEY_ACCESS_ID, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_ACCESS_ID, v.trim()).apply()

    var accessClientSecret: String
        get() = prefs.getString(KEY_ACCESS_SECRET, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_ACCESS_SECRET, v.trim()).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, DEFAULT_DEVICE_ID).orEmpty()
        set(v) = prefs.edit().putString(KEY_DEVICE_ID, v.trim()).apply()

    /**
     * Hold the backlog until the link is free. Checked as "not metered" rather
     * than "is Wi-Fi": a tethered hotspot is Wi-Fi and still costs money, and
     * the user can mark a network metered deliberately.
     */
    var unmeteredOnly: Boolean
        get() = prefs.getBoolean(KEY_UNMETERED_ONLY, true)
        set(v) = prefs.edit().putBoolean(KEY_UNMETERED_ONLY, v).apply()

    /**
     * Language told to Whisper, as an ISO 639-1 code, or "auto" to let it
     * guess. Persisted here rather than on the server so it can be changed
     * without a deploy.
     *
     * Guessing drifts: one drive came back as `en`, `ja` and `ko` across
     * consecutive minutes of the same conversation, the Korean stretch being
     * hangul spelling out Japanese. Naming the language removes that entirely,
     * at the cost of mistranscribing anything genuinely spoken in another one.
     */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE).orEmpty()
        set(v) = prefs.edit().putString(KEY_LANGUAGE, v.trim()).apply()

    /**
     * Wi-Fi network the timelapse is allowed to photograph from, by SSID.
     *
     * Empty means anywhere. Audio goes wherever the device does, but a camera
     * is a different proposition: this keeps the photographs to one place that
     * was chosen deliberately, rather than to whatever room the device happens
     * to be carried into.
     */
    var photoSsid: String
        get() = prefs.getString(KEY_PHOTO_SSID, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_PHOTO_SSID, v.trim()).apply()

    /**
     * The day's schedule state, so an explicit "stop for today" survives a
     * restart.
     *
     * Reinstalls and reboots are frequent here, and without this any of them
     * would quietly undo an instruction the user gave deliberately — the
     * device would be recording again minutes after being told not to, with
     * nothing on screen to say so. The morning start clears it anyway, so the
     * decision only ever lasts as long as the night it was made for.
     */
    var scheduleState: Scheduler.State
        get() = Scheduler.State(
            promptedAt = prefs.getLong(KEY_PROMPTED_AT, 0L),
            answeredAt = prefs.getLong(KEY_ANSWERED_AT, 0L),
            startedAt = prefs.getLong(KEY_STARTED_AT, 0L),
        )
        set(v) = prefs.edit()
            .putLong(KEY_PROMPTED_AT, v.promptedAt)
            .putLong(KEY_ANSWERED_AT, v.answeredAt)
            .putLong(KEY_STARTED_AT, v.startedAt)
            .apply()

    /**
     * Whether audio was being written when the service last stopped.
     *
     * Restored on start so a restart does not resurrect a recording the user
     * ended, nor end one they had running.
     */
    var recording: Boolean
        get() = prefs.getBoolean(KEY_RECORDING, true)
        set(v) = prefs.edit().putBoolean(KEY_RECORDING, v).apply()

    /**
     * Which standby screens to cycle through, in order, by id.
     *
     * Stored as ids rather than indices so reordering [Signage.ALL] or adding
     * a screen cannot silently change what somebody chose. Empty means the
     * first one only.
     */
    var signageIds: List<String>
        get() = prefs.getString(KEY_SIGNAGE, null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: Signage.ALL.map { it.id }
        set(v) = prefs.edit().putString(KEY_SIGNAGE, v.joinToString(",")).apply()

    /**
     * Capture options, persisted rather than held in an Activity.
     *
     * They used to be fields on the screen that starts the service, which
     * meant every launch silently reverted them to their defaults — the same
     * shape of bug as the evening stop that expired at midnight. Anything that
     * decides how the recording is made has to outlive the screen that sets
     * it.
     */
    var wakeLock: Boolean
        get() = prefs.getBoolean(KEY_WAKE_LOCK, true)
        set(v) = prefs.edit().putBoolean(KEY_WAKE_LOCK, v).apply()

    var voiceRecognition: Boolean
        get() = prefs.getBoolean(KEY_VOICE_RECOGNITION, false)
        set(v) = prefs.edit().putBoolean(KEY_VOICE_RECOGNITION, v).apply()

    var opus: Boolean
        get() = prefs.getBoolean(KEY_OPUS, true)
        set(v) = prefs.edit().putBoolean(KEY_OPUS, v).apply()

    /**
     * Which language the interpreter translates into, as an ISO 639-1 code.
     *
     * Persisted so the device comes back pointing the way it was last left.
     * The Worker owns the list of what is allowed; this only remembers a
     * choice, and an unrecognised one is refused there rather than here.
     */
    var interpretTarget: String
        get() = prefs.getString(KEY_INTERPRET_TARGET, "ja").orEmpty()
        set(v) = prefs.edit().putString(KEY_INTERPRET_TARGET, v.trim()).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotEmpty() && bearer.isNotEmpty()
}
