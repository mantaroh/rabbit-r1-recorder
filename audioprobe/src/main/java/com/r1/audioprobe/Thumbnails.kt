package com.r1.audioprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The pictures that go with the headlines.
 *
 * Fetched from the publisher directly rather than through the Worker. There is
 * nothing to gain by relaying them — Workers cannot resize an image without a
 * paid Images plan, so a relay would move the same bytes for an extra hop —
 * and the alternative is a Worker with an open fetch of arbitrary URLs in it.
 *
 * Everything here fails quietly. A screen that says it could not get a
 * thumbnail is answering a question nobody asked; the article is what the
 * screen is for, and it is still there without one.
 */
object Thumbnails {

    private const val TAG = "R1AudioProbe"
    private const val TIMEOUT_MS = 8_000

    /** The panel is 480 px across, so this is what a picture has to fill. */
    private const val TARGET_PX = 480

    /**
     * Below this the source is an icon rather than an article.
     *
     * The Worker drops what it can see — a thumbnail that declares 72 pixels,
     * a gravatar — but a great deal of what a feed offers declares no size at
     * all, and the first honest measurement of those is here, after the
     * header has been read.
     */
    private const val MIN_SOURCE_W = 240
    private const val MIN_SOURCE_H = 120

    /** One picture. Anything past this is not a thumbnail. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * Four.
     *
     * One is on screen, one is being read towards, and the two behind cover
     * going back. At 480x288 in ARGB_8888 that is about 2.2 MB, against the
     * ten items the list holds — which would be five and a half, kept for a
     * screen that has already moved past most of them.
     */
    private const val CACHE_SIZE = 4

    /** Bounded for the same reason as the cache: this runs for days. */
    private const val FAILED_SIZE = 64

    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) =
            size > CACHE_SIZE
    }

    private val failed = object : LinkedHashMap<String, Boolean>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > FAILED_SIZE
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "thumbnails").apply { isDaemon = true }
    }

    private val inFlight = HashSet<String>()

    /** What is already decoded, or null. Cheap enough to call every tick. */
    @Synchronized
    fun get(url: String?): Bitmap? = url?.let { cache[it] }

    /**
     * Starts a fetch if there is not one already and the link is free.
     *
     * Called for the item on screen and the one after it, so arriving at a
     * headline usually finds its picture already there.
     */
    @Synchronized
    fun prefetch(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return
        if (cache.containsKey(url) || failed.containsKey(url) || url in inFlight) return

        // The same rule the recordings follow. A standby screen quietly
        // spending a data plan on decoration is the thing to avoid, and the
        // switch for it already exists.
        if (UploadSettings(context).unmeteredOnly && !unmetered(context)) return

        inFlight.add(url)
        worker.execute {
            val bitmap = runCatching { load(url) }.getOrElse {
                Log.w(TAG, "thumbnail fetch failed", it)
                null
            }
            synchronized(this) {
                inFlight.remove(url)
                if (bitmap != null) cache[url] = bitmap else failed[url] = true
            }
        }
    }

    private fun unmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false
    }.getOrDefault(false)

    private fun load(url: String): Bitmap? {
        val bytes = fetch(url) ?: return null
        return decode(bytes)
    }

    private fun fetch(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                // Named for the same reason the crawler is. A request that
                // hides what it is gives the person on the other end no way to
                // complain about it.
                setRequestProperty("user-agent", "r1-reader/0.1 (personal feed reader)")
                setRequestProperty("accept", "image/*")
            }
            if (connection.responseCode !in 200..299) return null

            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            connection.inputStream.use { stream ->
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                    // Checked while reading rather than from the header:
                    // Content-Length is optional and is occasionally a lie.
                    if (buffer.size() > MAX_BYTES) return null
                }
            }
            buffer.toByteArray()
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Two passes: the header for the size, then the pixels at a size worth
     * keeping.
     *
     * Article pictures run 1200 across and the panel is 480, so decoding what
     * arrives would spend four times the memory to throw three quarters of it
     * away on the next line.
     */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        // The last stop for an avatar the Worker could not see the size of.
        // Width alone would refuse a wide banner, which is a real article
        // picture, so the two are checked against different limits.
        if (width < MIN_SOURCE_W || height < MIN_SOURCE_H) return null

        var sample = 1
        while (width / (sample * 2) >= TARGET_PX) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
