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

        private const val DEFAULT_BASE_URL = "https://lifelog.mantaroh.org"
        private const val DEFAULT_DEVICE_ID = "rabbit-r1-01"
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

    val isConfigured: Boolean
        get() = baseUrl.isNotEmpty() && bearer.isNotEmpty()
}
