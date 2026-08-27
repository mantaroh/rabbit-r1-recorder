package com.r1.audioprobe

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本番 — the screen the device is left on while a talk is happening.
 *
 * It does two jobs, and the second is the one that is easy to forget. The
 * obvious one is to wait for the cue and say the line. The other is to make
 * the device stop doing everything it normally does: away from the home Wi-Fi
 * the timelapse fires every five minutes, and the arm that carries the lens is
 * audible in a quiet room. A five-minute talk would almost certainly have had
 * a motor run through it.
 *
 * So the two are tied together deliberately. While this screen is in front,
 * [RecorderService.onStage] is set and neither the timelapse nor 声かけ runs;
 * leaving the screen puts both back. "Being on stage" is a state with one
 * visible representation rather than a setting somebody has to remember to
 * turn off afterwards.
 *
 * Recording itself is untouched. The talk is worth keeping, and the microphone
 * makes no noise.
 *
 * **While it waits it is a clock, and nothing else.** No title, no status, no
 * preview of the line. The device is in the room with the audience, and a
 * panel captioned with the sentence it is about to say gives the ending away
 * to anyone close enough to read it; a clock on a desk is furniture.
 *
 * **The clock is drawn twice.** `assets/stage/` holds a page that draws both
 * the clock and the character, and the same two states exist here as plain
 * Android views. The page is the one that shows if it loads; the views are
 * what is left if it does not. Drawing the clock in the page as well as the
 * character is what makes the fallback safe to have: the model is loaded and
 * running from the moment the screen opens rather than at the moment of the
 * cue, so by the time the line matters the page has either been working for
 * several minutes or was never used.
 *
 * **The audio does not go through any of this.** [Cue] plays samples compiled
 * into the APK, on a native track, whatever the page is doing. The worst this
 * screen can do is show the wrong picture while the right words come out.
 *
 * **This is for one talk and is meant to be deleted afterwards.** It is not a
 * feature the device is gaining; it is a prop. What has to go with it: this
 * Activity, [Cue], `res/raw/cue_hello.wav`, `assets/stage/`, the entry in 設定,
 * the manifest entry, [RecorderService.onStage] and its two gates, and the
 * Worker's cue routes, table and page. Nothing else refers to any of them.
 *
 * Note what this screen does *not* do: it never calls `Idle.watch`, so the
 * standby display cannot take it over after a minute of nobody touching the
 * device. That is the whole point — the device is sitting on a lectern being
 * ignored, which is exactly what the idle timer exists to notice.
 */
class CueActivity : Activity() {

    companion object {
        private const val TAG = "R1AudioProbe"

        private const val R1_ORANGE = 0xFFFE5000.toInt()
        private const val INK = 0xFFF2F2F2.toInt()

        /** Only ever seen by the person running the talk; see the class note. */
        private const val ALARM = 0xFFD24A2A.toInt()

        /**
         * How long the panel has to be held before the screen closes.
         *
         * Android's own long press is around half a second, which is what a
         * sleeve resting on a lectern does. Two seconds is a decision.
         */
        private const val HOLD_TO_CLOSE_MS = 2_000L

        /**
         * How long the page has to come up before it is written off.
         *
         * Twelve seconds against a model of a few megabytes read from the APK
         * and a renderer starting on a GPU that has never been asked to do
         * this. Being wrong in this direction costs a picture; being wrong in
         * the other costs a blank panel through the end of the talk.
         */
        private const val PAGE_TIMEOUT_MS = 12_000L

        /**
         * A host that does not exist, so that the page has an origin.
         *
         * Assets loaded straight off `file:///android_asset/` are same-origin
         * with nothing, and the page has to `fetch` a model file. Serving them
         * through [WebViewClient.shouldInterceptRequest] under an https host
         * gives it an ordinary origin without adding androidx.webkit — which
         * this app does not have, and `android.useAndroidX=false` says why.
         */
        private const val ASSET_HOST = "stage.local"
        private const val ASSET_DIR = "stage"
    }

    private lateinit var clock: TextView
    private lateinit var greeting: View
    private lateinit var page: WebView
    private lateinit var cue: Cue

    /** Whether the page reported that it has a character on screen. */
    private var pageReady = false

    private val main = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("H:mm", Locale.JAPAN)

    private val tick = object : Runnable {
        override fun run() {
            clock.text = stamp.format(Date())
            main.postDelayed(this, 1_000)
        }
    }

    private val closeAfterHold = Runnable { finish() }

    private val giveUpOnPage = Runnable {
        if (!pageReady) {
            Log.w(TAG, "cue page did not report ready; staying with the views")
            Metrics(this).write("cue_page", mapOf("state" to "timeout"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The panel must not sleep during the talk: a dark screen is a device
        // that has to be woken before it can be told to speak, and the moment
        // will have passed.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        setContentView(buildUi())

        cue = Cue(this, UploadSettings(this), Metrics(this))
        cue.onSpeaking = { speaking -> main.post { showGreeting(speaking) } }

        clock.setTextColor(if (cue.ready) INK else ALARM)
        // Set here rather than through showGreeting, which no longer has a way
        // back to the clock to express.
        clock.visibility = View.VISIBLE
        greeting.visibility = View.GONE

        page.loadUrl("https://$ASSET_HOST/index.html")
        main.postDelayed(giveUpOnPage, PAGE_TIMEOUT_MS)
    }

    override fun onResume() {
        super.onResume()
        // Set before the poll starts, so a cue that arrives in the first two
        // seconds cannot race a timelapse cycle for the arm.
        RecorderService.onStage = true
        cue.watch()
        tick.run()
    }

    override fun onPause() {
        RecorderService.onStage = false
        cue.stop()
        main.removeCallbacks(tick)
        main.removeCallbacks(closeAfterHold)
        main.removeCallbacks(giveUpOnPage)
        super.onPause()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        // A WebView outliving its Activity holds the whole view tree; this one
        // also has a GL context and a few megabytes of model in it.
        runCatching { page.destroy() }
        super.onDestroy()
    }

    /** Called from the page, on the main thread. */
    private fun onPageState(state: String) {
        if (state.startsWith("ready")) {
            pageReady = true
            page.visibility = View.VISIBLE
            clock.visibility = View.GONE
            greeting.visibility = View.GONE
            // The whole line, not the word "ready": the page measures the
            // model it loaded and puts the numbers here, and those numbers are
            // the difference between a renderer that never started and a
            // camera pointed at empty space.
            Metrics(this).write("cue_page", mapOf("state" to state.take(200)))
        } else {
            Log.w(TAG, "cue page said: $state")
            Metrics(this).write("cue_page", mapOf("state" to state.take(120)))
        }
    }

    /**
     * The character arrives and then stays.
     *
     * There is no way back to the clock. The line is the end of the talk, and
     * what follows it is questions and people coming up to look at the device;
     * a panel that reverted to a clock six seconds later would take the ending
     * back. Only the mouth stops.
     */
    private fun showGreeting(speaking: Boolean) {
        if (pageReady) {
            val call = if (speaking) "cueSpeak" else "cueQuiet"
            // Wrapped so the answer says what happened rather than the
            // "null" every void call returns. A page that has loaded and a
            // page whose functions are reachable are two different facts, and
            // the second is the one that matters at the moment the line plays.
            val probe =
                "(function(){try{" +
                    "if(typeof window.$call!=='function')return 'missing';" +
                    "window.$call();" +
                    "return 'ok:'+document.getElementById('clock').style.opacity;" +
                    "}catch(e){return 'err:'+e.message}})()"
            page.evaluateJavascript(probe) { result ->
                Log.i(TAG, "cue page $call -> $result")
            }
            return
        }
        // The same one-way rule for the fallback views.
        if (speaking) {
            clock.visibility = View.GONE
            greeting.visibility = View.VISIBLE
        }
    }

    /**
     * The side button says it. Nothing else does.
     *
     * `repeatCount` matters: this key auto-repeats while held, so without the
     * check a firm press would queue several. [Cue.speak] would refuse the
     * extras anyway, but refusing them here means the log records one press.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_1,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        -> {
            if (event?.repeatCount == 0) cue.speak("button")
            true
        }
        // The wheel is swallowed rather than passed on. There is nothing to
        // scroll here, and a stray turn should not move focus onto something
        // the side button would then activate instead of speaking.
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
        else -> super.onKeyDown(keyCode, event)
    }

    /**
     * Held for two seconds to leave.
     *
     * There is no exit on the panel because there is nothing on the panel, so
     * the gesture has to be one nothing else performs. A tap would be the
     * device brushing a lectern; a long hold is a hand deciding.
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> main.postDelayed(closeAfterHold, HOLD_TO_CLOSE_MS)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> main.removeCallbacks(closeAfterHold)
        }
        return true
    }

    // ------------------------------------------------------------------ ui ---

    private fun buildUi(): View = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)

        page = buildPage()
        addView(page)

        clock = TextView(this@CueActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        addView(clock)

        greeting = buildGreeting()
        addView(greeting)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildPage(): WebView = WebView(this).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        // Invisible rather than gone. A gone view is never laid out, so the
        // page measures itself at zero by zero and the renderer draws into a
        // buffer of that size — which looks exactly like a model that failed
        // to load. It has to be laid out from the start anyway: the whole
        // point is that it is running long before the cue.
        visibility = View.INVISIBLE
        setBackgroundColor(Color.BLACK)

        settings.javaScriptEnabled = true
        // Everything is served from the interceptor below; the page has no
        // reason to reach the network and no way to be told to.
        settings.blockNetworkLoads = true
        settings.mediaPlaybackRequiresUserGesture = false

        addJavascriptInterface(Bridge(), "CueBridge")

        // Console output from the page, in logcat beside everything else. The
        // page is debugged over adb or not at all.
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onConsoleMessage(
                message: android.webkit.ConsoleMessage,
            ): Boolean {
                Log.i(TAG, "cue page console: " + message.message())
                return true
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val url = request.url
                if (!url.host.equals(ASSET_HOST, ignoreCase = true)) return null
                val name = url.path?.trimStart('/').orEmpty()
                if (name.isEmpty() || name.contains("..")) return null

                return runCatching {
                    WebResourceResponse(
                        mimeFor(name),
                        if (name.endsWith(".vrm")) null else "utf-8",
                        assets.open("$ASSET_DIR/$name"),
                    )
                }.getOrElse {
                    Log.w(TAG, "cue page asked for a missing asset: $name")
                    null
                }
            }
        }

        // The page must not eat the hold-to-close gesture, and it has nothing
        // of its own to scroll or tap.
        setOnTouchListener { _, event ->
            onTouchEvent(event)
            true
        }
        isFocusable = false
        isFocusableInTouchMode = false
    }

    private fun mimeFor(name: String) = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".vrm") -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onState(state: String) {
            main.post { onPageState(state) }
        }
    }

    /**
     * What the room sees for the six seconds the device is talking, when the
     * page did not come up.
     *
     * The words are on the panel as well as in the air because the line is the
     * last thing said in the talk and a device speaking Japanese through a
     * small speaker is not always caught first time.
     */
    private fun buildGreeting(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(18), dp(18), dp(18))
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)

        addView(
            TextView(this@CueActivity).apply {
                text = "Hello, World."
                setTextColor(R1_ORANGE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            },
        )
        addView(
            TextView(this@CueActivity).apply {
                text = "素晴らしい世界が\n見えるようになりました。"
                setTextColor(INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dp(14)
                }
            },
        )
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
