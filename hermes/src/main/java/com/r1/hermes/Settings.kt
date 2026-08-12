package com.r1.hermes

import android.content.Context

/**
 * Connection settings for the Hermes gateway.
 *
 * Two credentials are stored because they authenticate different hops: the
 * Cloudflare Access service token gets past the edge, the Hermes session token
 * gets past the gateway's own gate. See PROTOCOL.md.
 *
 * CarrotOS exposes a root shell to every installed app, so these are readable
 * by any other APK on the device. That is a property of the platform, not of
 * this storage choice — but it means the Access token should be scoped to this
 * one application and rotated if the device leaves your hands.
 */
class Settings(context: Context) {

    companion object {
        private const val PREFS = "hermes"

        /**
         * The gateway hard-wraps its output to this. 11sp monospace across a
         * 480px panel less 8dp padding a side is ~34 columns; sending the
         * default 80 makes every reply wrap twice. See UI-SPEC.md.
         */
        const val COLS = 34

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ACCESS_ID = "access_client_id"
        private const val KEY_ACCESS_SECRET = "access_client_secret"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_PROFILE = "profile"

        private const val DEFAULT_BASE_URL = "https://hermes-api.mantaroh.org"
        private const val DEFAULT_PROFILE = "r1"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().trimEnd('/')
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    var accessClientId: String
        get() = prefs.getString(KEY_ACCESS_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCESS_ID, value.trim()).apply()

    var accessClientSecret: String
        get() = prefs.getString(KEY_ACCESS_SECRET, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCESS_SECRET, value.trim()).apply()

    var sessionToken: String
        get() = prefs.getString(KEY_SESSION_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SESSION_TOKEN, value.trim()).apply()

    /** Hermes profile; `r1` carries the gpt-transcribe STT config. */
    var profile: String
        get() = prefs.getString(KEY_PROFILE, DEFAULT_PROFILE).orEmpty()
        set(value) = prefs.edit().putString(KEY_PROFILE, value.trim()).apply()

    val isConfigured: Boolean
        get() = baseUrl.isNotEmpty() && sessionToken.isNotEmpty()

    fun httpUrl(path: String, query: String? = null): String =
        baseUrl + path + (query?.let { "?$it" } ?: "")

    /**
     * The session token rides in the query string because browsers cannot set
     * headers on a WS upgrade and the gateway reads it there for every client.
     */
    fun webSocketUrl(): String {
        val ws = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$ws/api/ws?token=" + java.net.URLEncoder.encode(sessionToken, "UTF-8")
    }

    /** Cloudflare Access service-token headers, empty when not configured. */
    fun accessHeaders(): Map<String, String> {
        if (accessClientId.isEmpty() || accessClientSecret.isEmpty()) return emptyMap()
        return mapOf(
            "CF-Access-Client-Id" to accessClientId,
            "CF-Access-Client-Secret" to accessClientSecret,
        )
    }
}
