package com.r1.camerawrapper

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TextView.BufferType
import android.widget.Toast
import com.r1.core.CameraController
import com.r1.core.R1Motor

/**
 * Camera app for the Rabbit R1 running CarrotOS.
 *
 * The preview and shutter live in this app; the lens direction is driven
 * directly through [R1Motor] rather than by handing off to another camera app.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_CAMERA_PERMISSION = 2001

        /** Scroll-wheel step for fine aiming. */
        private const val NUDGE_DEG = 10

        private const val PREFS = "r1camera"

        /**
         * Preview rotation is stored per lens direction: the sensor rides on the
         * arm, so the two sides sit 180 degrees apart. Defaults are a starting
         * guess — tapping the readout calibrates and persists the real values.
         */
        private const val KEY_ROTATION_FACE = "rotation_face"
        private const val KEY_ROTATION_BACK = "rotation_back"
        private const val DEFAULT_ROTATION_FACE = 180
        private const val DEFAULT_ROTATION_BACK = 0
    }

    private lateinit var textureView: TextureView
    private lateinit var statusView: TextView
    private lateinit var shutterButton: View
    private lateinit var rotationButton: Button
    private lateinit var camera: CameraController

    private var motorBusy = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        camera = CameraController(this, textureView) { message: String ->
            runOnUiThread { statusView.text = message }
        }

        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        }

        // Correct the assumed parked angle before the first move, then aim outward.
        R1Motor.syncFromDevice {
            runOnUiThread {
                moveMotorTo(R1Motor.MOTOR_BACK)
            }
        }
    }

    // ---------------------------------------------------------------- UI ----

    /**
     * The 480x640 panel cannot show a 4:3 preview without letterboxing, so the
     * layout stacks instead of overlaying: the bars that would be wasted black
     * space carry the readout and the controls, and the preview keeps its full
     * width.
     */
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            text = "Starting…"
            // Tapping the readout also calibrates, matching the rotation button.
            setOnClickListener { cycleRotation() }
        }
        root.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        textureView = TextureView(this)
        root.addView(
            textureView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        root.addView(buildShutterRow(), matchWidth())
        root.addView(buildUtilityRow(), matchWidth())

        return root
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun buildShutterRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(2))
        }

        shutterButton = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(3), Color.argb(255, 90, 90, 90))
            }
            contentDescription = "Shutter"
            setOnClickListener { takePicture() }
        }

        row.addView(
            flatButton("0°\nSelfie") { moveMotorTo(R1Motor.MOTOR_FACE) },
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )
        row.addView(
            shutterButton,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }
        )
        row.addView(
            flatButton("180°\nRear") { moveMotorTo(R1Motor.MOTOR_BACK) },
            LinearLayout.LayoutParams(0, dp(38), 1f)
        )

        return row
    }

    private fun buildUtilityRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), dp(4))
        }

        rotationButton = flatButton("Rotate") { cycleRotation() }
        row.addView(rotationButton, LinearLayout.LayoutParams(0, dp(26), 1f))

        row.addView(
            flatButton("Exit") { parkAndExit() },
            LinearLayout.LayoutParams(0, dp(26), 1f).apply { marginStart = dp(6) }
        )

        return row
    }

    /**
     * The stock button background eats most of a 240dp-wide panel in padding,
     * so the controls use a flat rounded rectangle sized to the row instead.
     */
    private fun flatButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            isAllCaps = false
            setLineSpacing(0f, 0.9f)
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            setPadding(dp(2), 0, dp(2), 0)
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.argb(255, 58, 58, 58))
            }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun updateStatus(extra: String? = null) {
        val side = if (R1Motor.currentAngle < R1Motor.MOTOR_HOME) "Selfie" else "Rear"
        val text = "${R1Motor.currentAngle}° $side" + (extra?.let { " · $it" } ?: "")
        statusView.setText(text, BufferType.NORMAL)
        rotationButton.text = "Rotate ${camera.rotationDeg}°"
    }

    // ------------------------------------------------------------- motor ----

    /** Which stored rotation applies at the current arm angle. */
    private fun rotationKey(): String =
        if (R1Motor.currentAngle < R1Motor.MOTOR_HOME) KEY_ROTATION_FACE else KEY_ROTATION_BACK

    private fun storedRotation(): Int {
        val key = rotationKey()
        val fallback =
            if (key == KEY_ROTATION_FACE) DEFAULT_ROTATION_FACE else DEFAULT_ROTATION_BACK
        return prefs.getInt(key, fallback)
    }

    private fun applyStoredRotation() {
        camera.rotationDeg = storedRotation()
    }

    private fun moveMotorTo(angle: Int) {
        if (motorBusy) return
        motorBusy = true
        statusView.text = "Turning…"

        R1Motor.moveTo(angle) { ok ->
            runOnUiThread {
                motorBusy = false
                if (!ok) {
                    updateStatus("motor failed")
                    Toast.makeText(this, "Motor control failed", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                applyStoredRotation()
                updateStatus()
            }
        }
    }

    /**
     * Steps the preview rotation for the current lens direction and remembers
     * it, so the correct value only has to be found once per side.
     */
    private fun cycleRotation() {
        val next = (storedRotation() + 90) % 360
        prefs.edit().putInt(rotationKey(), next).apply()
        camera.rotationDeg = next
        updateStatus()
    }

    // ----------------------------------------------------------- shutter ----

    private fun takePicture() {
        if (!hasCameraPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
            return
        }

        shutterButton.isEnabled = false
        shutterButton.alpha = 0.4f
        statusView.text = "Capturing…"

        camera.capture { name ->
            shutterButton.isEnabled = true
            shutterButton.alpha = 1f
            if (name == null) {
                updateStatus("save failed")
                Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show()
            } else {
                updateStatus("saved $name")
            }
        }
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------- lifecycle ---

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            applyStoredRotation()
            camera.start()
        }
        updateStatus()
    }

    override fun onPause() {
        camera.stop()
        super.onPause()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSION) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            camera.start()
            updateStatus()
        } else {
            statusView.text = "Camera permission denied"
        }
    }

    /**
     * The R1 scroll wheel reports as D-pad up/down and the side button as camera
     * keys, so both hardware controls are wired to something useful.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP -> {
                nudgeMotor(-NUDGE_DEG); true
            }

            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                nudgeMotor(NUDGE_DEG); true
            }

            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                takePicture(); true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun nudgeMotor(deltaDeg: Int) {
        moveMotorTo((R1Motor.currentAngle + deltaDeg).coerceIn(R1Motor.MOTOR_FACE, R1Motor.MOTOR_BACK))
    }

    @Deprecated("Deprecated in Android API, kept so the lens is parked before exit.")
    override fun onBackPressed() = parkAndExit()

    private fun parkAndExit() {
        if (motorBusy) return
        motorBusy = true
        statusView.text = "Parking…"

        // Park the lens where CarrotOS expects to find it before leaving.
        R1Motor.moveTo(R1Motor.MOTOR_HOME) {
            runOnUiThread { finish() }
        }
    }
}
