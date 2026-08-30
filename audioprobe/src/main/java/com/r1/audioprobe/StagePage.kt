package com.r1.audioprobe

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * The page that draws the character, and the traps that come with it.
 *
 * Two screens want it now — the talk prop and the standby display — and the
 * knowledge of how to bring it up is worth more than the seventy lines it
 * costs. Four failures here look identical from the outside, each of them an
 * hour: a `GONE` WebView is never laid out and the page measures itself at
 * zero by zero; assets off `file:///android_asset/` are same-origin with
 * nothing, so `fetch` of the model is refused; the model needs a MIME type and
 * no encoding; and a WebView outliving its Activity keeps a GL context and a
 * few megabytes of model with it. Every one of those is handled once, here.
 *
 * The page itself lives in `assets/stage/`, built from `audioprobe/stage/`.
 */
object StagePage {

    private const val TAG = "R1AudioProbe"

    /**
     * A host that does not exist, so that the page has an origin.
     *
     * Serving assets through [WebViewClient.shouldInterceptRequest] under an
     * https host gives the page an ordinary origin without adding
     * androidx.webkit — which this app does not have, and
     * `android.useAndroidX=false` says why.
     */
    private const val HOST = "stage.local"
    private const val DIR = "stage"

    /** What either caller loads. There is one page and it has two modes. */
    const val URL = "https://$HOST/index.html"

    /**
     * A WebView with the character page ready to load.
     *
     * [onState] is called on the main thread with whatever the page reports —
     * `ready:{...}` carrying the numbers it measured off the model, or
     * `failed:...`. Callers are expected to keep their own views until a
     * `ready` arrives: `assets/stage/character.vrm` is not committed, so a
     * fresh clone has no model and every screen here has to survive that.
     *
     * The view is returned [View.INVISIBLE] and laid out. Not `GONE`: a view
     * that is never laid out gives the renderer a buffer of zero by zero,
     * which looks exactly like a model that failed to load.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun build(activity: Activity, onState: (String) -> Unit): WebView =
        WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = View.INVISIBLE
            setBackgroundColor(Color.BLACK)

            settings.javaScriptEnabled = true
            // Everything is served from the interceptor below; the page has no
            // reason to reach the network and no way to be told to.
            settings.blockNetworkLoads = true
            settings.mediaPlaybackRequiresUserGesture = false

            addJavascriptInterface(Bridge(onState), "StageBridge")

            // Console output from the page, in logcat beside everything else.
            // The page is debugged over adb or not at all.
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.i(TAG, "stage page console: " + message.message())
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val url = request.url
                    if (!url.host.equals(HOST, ignoreCase = true)) return null
                    val name = url.path?.trimStart('/').orEmpty()
                    if (name.isEmpty() || name.contains("..")) return null

                    return runCatching {
                        WebResourceResponse(
                            mimeFor(name),
                            // A binary body with a charset on it is a body the
                            // WebView tries to decode as text.
                            if (name.endsWith(".vrm")) null else "utf-8",
                            activity.assets.open("$DIR/$name"),
                        )
                    }.getOrElse {
                        Log.w(TAG, "stage page asked for a missing asset: $name")
                        null
                    }
                }
            }

            // The page must not eat the gestures the screen above it is
            // listening for, and it has nothing of its own to scroll or tap.
            setOnTouchListener { _, event ->
                activity.onTouchEvent(event)
                true
            }
            isFocusable = false
            isFocusableInTouchMode = false
        }

    /**
     * Calls a function on the page and logs what came back.
     *
     * Wrapped so the answer says what happened rather than the `null` every
     * void call returns. A page that has loaded and a page whose functions are
     * reachable are two different facts, and it is the second that matters at
     * the moment something depends on it.
     */
    fun call(page: WebView, script: String, label: String) {
        val probe = "(function(){try{return 'ok:'+($script)}catch(e){return 'err:'+e.message}})()"
        page.evaluateJavascript(probe) { result -> Log.i(TAG, "stage page $label -> $result") }
    }

    private fun mimeFor(name: String) = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".js") -> "application/javascript"
        else -> "application/octet-stream"
    }

    private class Bridge(private val onState: (String) -> Unit) {
        @JavascriptInterface
        fun onState(state: String) {
            // Arrives on a WebView thread of its own; every caller here works
            // in views.
            android.os.Handler(android.os.Looper.getMainLooper()).post { onState.invoke(state) }
        }
    }
}
