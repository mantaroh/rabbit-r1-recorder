package com.r1.audioprobe

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only metrics log.
 *
 * The whole point of this probe is to answer questions hours after the fact —
 * "was it still recording at 03:00?" — so every sample is written to disk as
 * one JSON object per line, pullable over adb. Nothing is aggregated in memory
 * that would be lost with the process.
 */
class Metrics(context: Context) {

    companion object {
        const val FILE_NAME = "probe.jsonl"

        /** Beyond this the file is rotated once; a multi-day run stays bounded. */
        private const val MAX_BYTES = 8L * 1024 * 1024
    }

    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val file = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun write(event: String, fields: Map<String, Any?>) {
        val json = JSONObject()
        json.put("t", stamp.format(Date()))
        json.put("event", event)
        fields.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }

        runCatching {
            if (file.length() > MAX_BYTES) {
                File(file.parentFile, "$FILE_NAME.1").also { previous ->
                    if (previous.exists()) previous.delete()
                    file.renameTo(previous)
                }
            }
            file.appendText(json.toString() + "\n")
        }
    }

    fun path(): String = file.absolutePath

    fun sizeBytes(): Long = file.length()
}
