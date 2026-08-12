package com.r1.hermes

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.r1.core.CameraController
import com.r1.core.R1Motor
import java.io.File

/**
 * Take a photo to attach to the next message.
 *
 * The R1's sensor rides a motorised arm, so capturing means aiming it first —
 * outward on entry, parked again on exit, exactly as the camera app does. The
 * still is deliberately small: it is about to be base64'd through a tunnel and
 * read by a model, neither of which benefits from 8 megapixels.
 */
class CaptureActivity : Activity() {

    companion object {
        const val EXTRA_PHOTO_PATH = "photo_path"

        private const val REQUEST_CAMERA_PERMISSION = 3201

        /**
         * Long-edge cap for the still. The R1 offers 1280x960 below this, which
         * lands around 200–400 KB — legible to the model, cheap to upload.
         */
        private const val CAPTURE_MAX_EDGE = 1280

        /** Verified on the device: at 180 degrees the sensor is already upright. */
        private const val ROTATION_AT_REAR = 0
    }

    private lateinit var textureView: TextureView
    private lateinit var statusView: TextView
    private lateinit var shutter: View
    private lateinit var camera: CameraController

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        camera = CameraController(this, textureView, CAPTURE_MAX_EDGE) { message: String ->
            runOnUiThread { statusView.text = message }
        }
        camera.rotationDeg = ROTATION_AT_REAR

        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA_PERMISSION)
        }

        statusView.text = "aiming…"
        R1Motor.syncFromDevice {
            R1Motor.moveTo(R1Motor.MOTOR_BACK) { ok ->
                runOnUiThread { statusView.text = if (ok) "" else "motor failed" }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            camera.start()
        }
    }

    override fun onPause() {
        camera.stop()
        super.onPause()
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(MATCH, MATCH))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x8C000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
        )

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x8C000000.toInt())
            setPadding(dp(6), dp(5), dp(6), dp(6))
        }

        shutter = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(3), Color.argb(255, 90, 90, 90))
            }
            contentDescription = "Shutter"
            setOnClickListener { take() }
        }

        bar.addView(flatButton("Cancel") { cancel() }, LinearLayout.LayoutParams(0, dp(32), 1f))
        bar.addView(
            shutter,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
        )
        // Keeps the shutter centred without a second action competing for it.
        bar.addView(View(this), LinearLayout.LayoutParams(0, dp(32), 1f))

        root.addView(
            bar,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM)
        )

        return root
    }

    private val MATCH get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP get() = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun flatButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        isAllCaps = false
        minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
        stateListAnimator = null
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.rgb(58, 58, 58))
        }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun take() {
        if (busy) return
        busy = true
        shutter.alpha = 0.4f
        statusView.text = "capturing…"

        camera.captureBytes { bytes ->
            if (bytes == null) {
                busy = false
                shutter.alpha = 1f
                statusView.text = "capture failed"
                return@captureBytes
            }

            // Hand the file over rather than the bytes: an Intent extra is a
            // Binder transaction with a ~1 MB budget shared process-wide.
            val file = File(cacheDir, "capture.jpg")
            val written = runCatching { file.writeBytes(bytes) }.isSuccess
            if (!written) {
                busy = false
                shutter.alpha = 1f
                statusView.text = "could not stage the photo"
                return@captureBytes
            }

            setResult(RESULT_OK, Intent().putExtra(EXTRA_PHOTO_PATH, file.absolutePath))
            park()
        }
    }

    private fun cancel() {
        if (busy) return
        busy = true
        setResult(RESULT_CANCELED)
        park()
    }

    /** Leave the arm where CarrotOS expects to find it. */
    private fun park() {
        statusView.text = "parking…"
        camera.stop()
        R1Motor.moveTo(R1Motor.MOTOR_HOME) {
            runOnUiThread { finish() }
        }
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
        } else {
            statusView.text = "camera permission denied"
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = when (keyCode) {
        // Side button is the shutter here; the wheel has nothing to scroll.
        KeyEvent.KEYCODE_BUTTON_1 -> {
            if (event?.repeatCount == 0) take()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }
}
