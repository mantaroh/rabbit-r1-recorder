package com.r1.audioprobe

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import com.r1.core.Motion
import kotlin.random.Random

/**
 * The standby display: what the device shows when nobody is using it.
 *
 * Appears after a minute of no interaction and leaves on the first touch or
 * turn of the wheel, so it is never in the way. The wheel also steps between
 * screens without dismissing it, because flicking through them is the one
 * interaction that belongs here.
 *
 * The screen is only held awake while charging. Recording alone costs about
 * 62 mA against a 1010 mAh battery; leaving the panel lit on top of that turns
 * a day of runtime into a few hours, which is the wrong trade for something
 * carried around. On a desk with a cable it is free.
 */
class SignageActivity : Activity() {

    companion object {
        private const val TAG = "R1AudioProbe"

        /** How often the visible screen is asked to redraw. */
        private const val TICK_MS = 1_000L

        /** How often the server is asked what today looks like. */
        private const val FETCH_MS = 120_000L

        /**
         * How much of the panel the screens get when the character is up.
         *
         * The rest is the character's, and the two must agree: `STANDBY_CROP`
         * in `stage/entry.js` puts the figure in the bottom half or so, and
         * this keeps the text out of it. Centring a screen in the whole panel
         * would put a headline across the character's face.
         *
         * Both numbers were settled by looking at the panel. There is no
         * measurement that would have given either.
         */
        private const val CONTENT_FRACTION = 0.52f

        /**
         * How long after a step the opposite one is treated as the wheel
         * settling rather than as a turn.
         *
         * The wheel is not a mechanical encoder. It is read by a Hall sensor —
         * `och1970_holl_key`, `/dev/input/event2`, the only device on this
         * phone that emits `KEY_UP`/`KEY_DOWN` — and after a downward turn the
         * magnet can come to rest on a detent boundary and drift back across
         * it. The driver reports that as an ordinary key: press and release
         * both present, 119 µs apart, against 109, 167 and 363 µs for turns
         * made by hand. Nothing in the shape of the event separates them, so
         * nothing that looks only at the event can.
         *
         * When they arrive does separate them. Every one measured on
         * 2026-09-01 came 6.0, 7.3, 11.7 and 25.6 seconds after a *downward*
         * turn and never after an upward one — the asymmetry being the stand,
         * which holds the device 24.7 degrees back and gives the magnet a
         * direction to fall in. Left alone the wheel stayed silent for minutes
         * at a stretch, so this is a wheel settling and not a wheel that
         * chatters.
         *
         * Two seconds at the bottom because a reversal that quick is somebody
         * who overshot, and they are never wrong about it. Thirty at the top
         * because 25.6 is the longest yet seen and a window that ends inside
         * the measurements would only look like it worked.
         */
        private const val SETTLE_MIN_MS = 2_000L
        private const val SETTLE_MAX_MS = 30_000L

        /**
         * How long the character stands still between motions of its own.
         *
         * A clip runs about four seconds. Two minutes at the bottom because
         * anything quicker is a thing happening on a screen that is glanced at
         * rather than watched; five at the top because past that the display
         * has stopped looking alive at all.
         *
         * Drawn afresh after every clip rather than fixed. A standby display
         * has a fifteen-second headline rotation and a two-minute fetch
         * already, and a third exact period would line up with them and read
         * as a mechanism.
         */
        private const val IDLE_MOVE_MIN_MS = 120_000L
        private const val IDLE_MOVE_MAX_MS = 300_000L

        /**
         * The instance currently on screen, or null.
         *
         * Held so a side-button press — which arrives at the accessibility
         * service, not at any window — can dismiss it. Cleared in onDestroy so
         * a stale reference cannot be told to finish twice.
         */
        @Volatile private var showing: SignageActivity? = null

        /**
         * True if the press was used to leave standby.
         *
         * [pressedAt] is when the button went down. A press that *opened*
         * standby must not also close it: on the home menu the same button
         * both activates the 待受 row and reaches KeyService, and deciding a
         * press was single costs 440 ms of waiting — long enough for standby
         * to be on screen by the time its own cause is delivered. Standby
         * appeared and bounced straight back to the menu.
         */
        fun dismiss(pressedAt: Long): Boolean {
            val activity = showing ?: return false
            if (activity.shownAt >= pressedAt) return false
            activity.runOnUiThread { activity.finish() }
            return true
        }
    }

    /** When this instance reached the screen; see [dismiss]. */
    @Volatile private var shownAt = 0L

    private lateinit var frame: FrameLayout

    /**
     * Where the screens go, over the character rather than instead of it.
     *
     * The character is the standby display's frame and not one more screen in
     * the rotation: built once, kept through every turn of the wheel. As a
     * screen it would have re-parsed nineteen megabytes of model each time the
     * wheel moved, which is the whole reason this Activity is shaped this way.
     */
    private lateinit var content: FrameLayout

    /** The page drawing the character, or null when it is switched off. */
    private var stage: WebView? = null

    /** Whether the page has reported a character actually on screen. */
    private var stageReady = false

    /** When the character next moves on its own; see [maybeMove]. */
    private var nextMoveAt = 0L

    /**
     * Set once the page has said it cannot read a clip.
     *
     * `assets/stage/motion/VRMA_*.vrma` are not committed, so a device without
     * them is an ordinary case rather than a fault. Asking every few minutes
     * anyway would write a metric and a warning for the rest of the day to
     * learn the same thing each time, so the timer gives up after the first
     * answer. A tap still asks: a person pressing the panel is owed the
     * attempt, and the report that comes back is how the absence gets noticed.
     */
    private var movesMissing = false

    /**
     * Whether the device is on a cable, as of the last [applyKeepAwake].
     *
     * Kept rather than read on demand because the tick asks every second and
     * this is a system call. Charging state is already watched, so nothing has
     * to poll for it.
     */
    private var charging = false

    /** The last wheel step acted on, and when; see [settling]. */
    private var lastStep = 0
    private var lastStepAt = 0L

    private lateinit var settings: UploadSettings
    private var screens: List<SignageScreen> = emptyList()
    private var index = 0
    private var lastFetch = 0L

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if (now - lastFetch >= FETCH_MS) {
                lastFetch = now
                LifelogSummary.refresh(settings)
                HermesStatus.refresh(this@SignageActivity)
                Headlines.refresh(settings)
            }
            // The screen only changes when the wheel is turned. A display that
            // rotates on its own is one you have to wait for when you want a
            // particular thing, and this one is often glanced at rather than
            // watched.
            screens.getOrNull(index)?.refresh(LifelogSummary.current)
            maybeMove(now)

            ticker.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Unplugging should not leave the panel burning. Charging state is watched
     * rather than read once, because the whole point of this screen is that it
     * is left alone for hours.
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = applyKeepAwake()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = UploadSettings(this)

        frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(frame)

        // The character first, so every screen is drawn over it. Loaded here
        // and left alone: it takes seconds to have a model on screen, and
        // standby is a display that is looked at rather than waited for.
        if (settings.avatar) {
            stage = StagePage.build(this) { state -> onPageState(state) }
                .also { page ->
                    frame.addView(page)
                    page.loadUrl(StagePage.URL)
                }
        }

        content = FrameLayout(this)
        frame.addView(content, contentLayout())

        screens = settings.signageIds.mapNotNull { Signage.byId(it) }
            .ifEmpty { listOf(Signage.ALL.first()) }
        show(0)

        LifelogSummary.refresh(settings)
        HermesStatus.refresh(this)
        Headlines.refresh(settings)
        lastFetch = System.currentTimeMillis()

        // The side button is the way out, and it does not reach a focused
        // Activity — the launcher's accessibility service has it first. This
        // is how the press gets here.
        shownAt = System.currentTimeMillis()
        showing = this
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
        applyKeepAwake()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        runCatching { unregisterReceiver(powerReceiver) }
        super.onPause()
    }

    private fun show(next: Int) {
        index = next
        val screen = screens.getOrNull(index) ?: return

        // The character is the frame around every other screen and is in the
        // way of this one. Hidden rather than destroyed: the page takes
        // seconds and nineteen megabytes to have a model on screen again, and
        // the wheel can come back here in a moment.
        //
        // Only touched once the page is ready. Before that, [StagePage.build]
        // has already set it INVISIBLE on purpose — not GONE, because a view
        // that is never laid out gives the renderer a buffer of zero by zero,
        // which looks exactly like a model that failed to load (see the class
        // doc on StagePage). Forcing GONE here for every screen, not just this
        // one, during the whole page load would reintroduce that. Once
        // [onPageState] sees "ready" it takes visibility over from here, so
        // this only ever needs to hide or show an already-ready character.
        val whole = screen.fullBleed
        if (stageReady) stage?.visibility = if (whole) View.GONE else View.VISIBLE

        content.layoutParams = contentLayout(screen)
        // Screens paint themselves black — see SignageStyle.column — which is
        // right when they are the whole panel and wrong when there is a
        // character behind them. The scrim on [content] is what backs the text
        // instead, and it stops before the character does.
        content.background = if (stageReady && !whole) scrim() else null

        content.removeAllViews()
        val view = screen.createView(this)
        if (stageReady && !whole) view.setBackgroundColor(Color.TRANSPARENT)
        screen.onTap { handleTap() }
        content.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        screen.refresh(LifelogSummary.current)
    }

    /**
     * How much of the panel the screens get, and what backs them.
     *
     * A screen that asked for the whole panel gets it, and the character is
     * put away for as long as it is showing; see [show]. Everything else sits
     * in the top half over the character, which is what [CONTENT_FRACTION] is.
     */
    private fun contentLayout(screen: SignageScreen? = null): FrameLayout.LayoutParams {
        if (!stageReady || screen?.fullBleed == true) {
            return FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val height = (resources.displayMetrics.heightPixels * CONTENT_FRACTION).toInt()
        return FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height).apply {
            gravity = Gravity.TOP
        }
    }

    /**
     * Opaque where the text is, gone by the time the character starts.
     *
     * Five evenly spaced stops: solid for the first quarter, fading through
     * the second, clear from halfway down. Text on a 3D scene is unreadable
     * and the headline is the one thing this screen exists for.
     */
    private fun scrim() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            Color.BLACK,
            Color.BLACK,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            Color.TRANSPARENT,
        ),
    )

    /**
     * Called from the page once it has a character on screen, or once it knows
     * it will not have one.
     *
     * `assets/stage/character.vrm` is not committed, so a failure here is the
     * ordinary case on a fresh clone rather than an exception: the page stays
     * invisible and the standby display is exactly what it was before.
     */
    private fun onPageState(state: String) {
        Metrics(this).write("signage_page", mapOf("state" to state.take(200)))
        if (!state.startsWith("ready")) {
            Log.w(TAG, "standby page said: $state")
            // The clips are not committed and a device without them says so
            // here. Asked again in a few minutes it would say the same thing.
            if (state.startsWith("motion-failed")) movesMissing = true
            return
        }

        stageReady = true
        val whole = fullBleedShowing()
        stage?.let { page ->
            page.visibility = if (whole) View.GONE else View.VISIBLE
            StagePage.call(page, "window.stageStandby()", "standby")
        }

        // The screens were laid out for a black panel a moment ago. Not the
        // one asking for the whole panel, which wanted exactly that.
        if (!whole) {
            content.background = scrim()
            content.layoutParams = contentLayout(screens.getOrNull(index))
            content.getChildAt(0)?.setBackgroundColor(Color.TRANSPARENT)
        }
        // Not now. The character has this second only just stood up, and the
        // first thing it does should not be to move.
        scheduleMove(System.currentTimeMillis())
    }

    /**
     * Asks the character to move, if it is time and there is anyone to see it.
     *
     * Only on a cable, because off one [applyKeepAwake] has already let the
     * panel go dark and paused the page: a clip played then is a GL context
     * turning over for nobody. Nothing catches up afterwards either — a
     * display that had been asleep for an hour would owe twenty motions, and
     * it owes none.
     *
     * A clip already running comes back "busy" from the page and the timer
     * simply moves on; the next draw is a few minutes away in either case.
     */
    private fun maybeMove(now: Long) {
        if (movesMissing || !stageReady || !charging) return
        val page = stage ?: return
        if (now < nextMoveAt) return
        scheduleMove(now)
        StagePage.call(page, "window.stagePlay()", "play")
    }

    /** Somewhere in the next few minutes; see [IDLE_MOVE_MIN_MS]. */
    private fun scheduleMove(now: Long) {
        nextMoveAt = now + Random.nextLong(IDLE_MOVE_MIN_MS, IDLE_MOVE_MAX_MS)
    }

    private fun applyKeepAwake() {
        charging = runCatching {
            getSystemService(BatteryManager::class.java)?.isCharging == true
        }.getOrDefault(false)

        if (charging) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // The same condition, for the same reason. Once the panel is allowed
        // to go dark there is nobody to draw for, and a GL context turning
        // over against a 1010 mAh battery is exactly the objection this
        // feature has to answer. Paused rather than destroyed: unplugging and
        // plugging in again must not cost another model load.
        stage?.let { page -> if (charging) page.onResume() else page.onPause() }
    }

    /**
     * The wheel steps between screens and never dismisses.
     *
     * Leaving is the side button's job: once to come back to the device, twice
     * to ask a question. Both arrive through KeyService rather than here — the
     * button is taken by the launcher's accessibility service before window
     * focus is consulted, so a focused Activity never sees it.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> return step(keyCode, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> return step(keyCode, 1)
        }
        return super.onKeyDown(keyCode, event)
    }

    /** One turn of the wheel, unless the wheel turned itself; see [settling]. */
    private fun step(keyCode: Int, by: Int): Boolean {
        if (settling(keyCode)) return true
        lastStep = keyCode
        lastStepAt = System.currentTimeMillis()
        show((index + by + screens.size) % screens.size)
        return true
    }

    /**
     * Whether this step is the wheel coming to rest rather than being turned.
     *
     * Three things have to agree, and the third is the one doing the work.
     * Nobody turns this wheel without moving the device it is mounted on, and
     * the magnet that falls back across a detent boundary moves nothing at
     * all — so the accelerometer is a witness the ghost cannot produce. The
     * timing conditions on their own would swallow an ordinary "back up one
     * after reading for a while", which is a thing people do.
     *
     * It gives up after one. A second step is somebody insisting, and
     * insisting has to work: a wheel that has decided you did not mean it is
     * worse than a wheel that occasionally moves on its own. So the cost of
     * guessing wrong is one turn that does nothing, against a screen that
     * vanishes while it is being read.
     */
    private fun settling(keyCode: Int): Boolean {
        val previous = lastStep
        // Only a reversal. Steps that keep going the same way are somebody
        // travelling, and the ghost has never once continued a direction.
        if (previous == 0 || previous == keyCode) return false

        val gap = System.currentTimeMillis() - lastStepAt
        if (gap < SETTLE_MIN_MS || gap > SETTLE_MAX_MS) return false

        // Without the accelerometer running there is no evidence either way,
        // and [Motion.placement] defaults to "still" — which would make every
        // reversal look like a ghost on a device that had simply not started
        // watching yet.
        if (!Motion.watching) return false
        val placement = Motion.placement
        if (placement.moving) return false

        // Every one of these is a guess, so every one is written down. If the
        // window is wrong it will show up here as steps somebody meant.
        Metrics(this).write(
            "wheel_ghost",
            mapOf(
                "key" to KeyEvent.keyCodeToString(keyCode),
                "gap_ms" to gap,
                "tilt_deg" to placement.tiltDeg,
                "posture" to placement.posture.name,
            ),
        )

        lastStep = 0
        lastStepAt = 0L
        return true
    }

    /**
     * A tap asks the character to do something, and no longer leaves.
     *
     * Leaving was always the side button's job — the doc on [onKeyDown] says so
     * — and a touch that dismissed was a second way out that nothing pointed
     * at. Now it is the only thing on this screen a person can actually do.
     *
     * With no character there is nothing to ask, and a display that swallowed
     * touches and did nothing would read as a device that had stopped
     * answering. So it goes back to dismissing.
     */
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            handleTap()
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * What a tap on the standby display does.
     *
     * Pulled out of [onTouchEvent] because a screen with a scroller in it
     * takes the gesture first, and hands it back here through
     * [SignageScreen.onTap] rather than losing it.
     */
    private fun handleTap() {
        val page = stage
        if (stageReady && page != null && !fullBleedShowing()) {
            // The clock starts again from the tap. Otherwise a motion
            // asked for by hand could be followed a second later by one
            // nobody asked for.
            scheduleMove(System.currentTimeMillis())
            StagePage.call(page, "window.stagePlay()", "play")
        } else {
            finish()
        }
    }

    /** Whether the screen on show has asked for the whole panel. */
    private fun fullBleedShowing(): Boolean = screens.getOrNull(index)?.fullBleed == true

    override fun onUserLeaveHint() {
        finish()
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        if (showing === this) showing = null
        // A WebView outliving its Activity holds the whole view tree, and this
        // one has a GL context and a model in it as well.
        stage?.let { page ->
            frame.removeView(page)
            runCatching { page.destroy() }
        }
        stage = null
        super.onDestroy()
    }
}
