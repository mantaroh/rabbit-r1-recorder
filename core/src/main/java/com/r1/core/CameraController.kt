package com.r1.core

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal Camera2 preview + still capture for the Rabbit R1.
 *
 * The R1 has a single sensor on a motorised arm, so "front" and "back" are the
 * same camera at different [R1Motor] angles. Turning the arm past the parked
 * position physically inverts the sensor, which is what [flipped] compensates
 * for — both in the on-screen preview and in the saved JPEG orientation.
 */
class CameraController(
    private val activity: Activity,
    private val textureView: TextureView,
    /**
     * Caps the still-capture resolution at this long edge. The full 8 MP frame
     * is right for the gallery but wasteful when the photo is about to be
     * base64'd over a tunnel, so uploaders pass something smaller.
     */
    private val captureMaxEdge: Int = Int.MAX_VALUE,
    private val onStatus: (String) -> Unit,
) {
    companion object {
        private const val TAG = "R1CameraWrapper"

        /** The R1 panel is 480x640; a bigger preview stream only costs battery. */
        private const val PREVIEW_MAX_PIXELS = 1280L * 960L

        /** Aspect ratios within this much of each other count as the same shape. */
        private const val ASPECT_TOLERANCE = 0.02
    }

    private val cameraManager =
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val executor = Executor { runnable -> handler?.post(runnable) ?: runnable.run() }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var cameraId: String? = null
    private var sensorOrientation = 0
    private var previewSize = Size(640, 480)

    /** The R1 lens is fixed-focus, so this usually resolves to AF_MODE_OFF. */
    private var afMode = CameraMetadata.CONTROL_AF_MODE_OFF

    private var pendingCapture: ((String?) -> Unit)? = null
    private var pendingBytes: ((ByteArray?) -> Unit)? = null

    /**
     * Clockwise degrees applied to the sensor buffer so the scene looks upright.
     *
     * The sensor rides on the arm, so the needed value differs by 180 between
     * the two lens directions. It is calibrated on the device rather than
     * derived, because the panel/sensor mounting on the R1 does not follow the
     * usual `SENSOR_ORIENTATION` convention.
     */
    var rotationDeg = 0
        set(value) {
            field = ((value % 360) + 360) % 360
            activity.runOnUiThread { configureTransform() }
        }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = openCamera()

        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) =
            configureTransform()

        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(s: SurfaceTexture) = Unit
    }

    fun start() {
        if (thread == null) {
            thread = HandlerThread("r1-camera").also {
                it.start()
                handler = Handler(it.looper)
            }
        }

        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceListener
        }
    }

    fun stop() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        runCatching { previewSurface?.release() }
        previewSurface = null

        thread?.quitSafely()
        thread = null
        handler = null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (device != null) return

        val id = cameraId ?: selectCameraId()
        if (id == null) {
            onStatus("No camera found")
            return
        }
        cameraId = id

        try {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            ) ?: run {
                onStatus("No stream configuration")
                return
            }

            afMode = chooseAfMode(characteristics)

            // Pick the still size first: it defines the full sensor field of view,
            // and the preview has to match its shape or the two disagree.
            val captureSize = chooseCaptureSize(map.getOutputSizes(ImageFormat.JPEG))
            previewSize = choosePreviewSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                aspectOf(captureSize)
            )

            Log.i(
                TAG,
                "camera=$id sensorOrientation=$sensorOrientation afMode=$afMode " +
                    "preview=${previewSize.width}x${previewSize.height} " +
                    "capture=${captureSize.width}x${captureSize.height}"
            )

            reader = ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                ImageFormat.JPEG,
                2
            ).apply {
                setOnImageAvailableListener({ r -> onImageAvailable(r) }, handler)
            }

            cameraManager.openCamera(id, deviceCallback, handler)
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera failed", t)
            onStatus("Cannot open camera: ${t.message}")
        }
    }

    /**
     * The R1 exposes its single motorised sensor as a normal camera. Prefer a
     * back-facing id, then an external one, and fall back to whatever exists.
     */
    private fun selectCameraId(): String? {
        val ids = runCatching { cameraManager.cameraIdList }.getOrNull() ?: return null
        if (ids.isEmpty()) return null

        fun facing(id: String): Int? = runCatching {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
        }.getOrNull()

        return ids.firstOrNull { facing(it) == CameraMetadata.LENS_FACING_BACK }
            ?: ids.firstOrNull { facing(it) == CameraMetadata.LENS_FACING_EXTERNAL }
            ?: ids.first()
    }

    private fun chooseAfMode(characteristics: CameraCharacteristics): Int {
        val available = characteristics
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toList()
            .orEmpty()

        // The R1 lens is fixed-focus and advertises AF_MODE_OFF only. Asking for
        // a mode it does not list makes the request illegal.
        return listOf(
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_OFF,
        ).firstOrNull { available.contains(it) } ?: CameraMetadata.CONTROL_AF_MODE_OFF
    }

    private fun aspectOf(size: Size): Double =
        size.width.toDouble() / size.height.toDouble()

    private fun chooseCaptureSize(sizes: Array<Size>?): Size {
        val all = sizes?.toList().orEmpty()
        if (all.isEmpty()) return Size(640, 480)

        val capped = all.filter { max(it.width, it.height) <= captureMaxEdge }
        // Largest within the cap; if the cap excludes everything, the smallest
        // available is still closer to the intent than the full sensor frame.
        return capped.maxByOrNull { it.width.toLong() * it.height }
            ?: all.minByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)
    }

    /**
     * Anything but the still image's own aspect ratio makes the sensor crop, so
     * the preview would show a narrower scene than the photo it produces. Keep
     * the shape and take the smallest stream that still covers the panel.
     */
    private fun choosePreviewSize(sizes: Array<Size>?, targetAspect: Double): Size {
        val all = sizes?.toList().orEmpty()
        if (all.isEmpty()) return Size(640, 480)

        // Buffers come out in sensor (landscape) orientation, so compare against
        // the long and short edges of the view rather than width and height.
        // Before layout the view reports 0, so fall back to the panel size.
        val metrics = activity.resources.displayMetrics
        val viewLong = max(textureView.width, textureView.height)
            .takeIf { it > 0 } ?: max(metrics.widthPixels, metrics.heightPixels)
        val viewShort = min(textureView.width, textureView.height)
            .takeIf { it > 0 } ?: min(metrics.widthPixels, metrics.heightPixels)

        val sameShape = all.filter {
            abs(aspectOf(it) - targetAspect) <= ASPECT_TOLERANCE
        }
        val pool = sameShape.ifEmpty { all }

        return pool
            .filter { it.width >= viewLong && it.height >= viewShort }
            .filter { it.width.toLong() * it.height <= PREVIEW_MAX_PIXELS }
            .minByOrNull { it.width.toLong() * it.height }
            ?: pool.filter { it.width.toLong() * it.height <= PREVIEW_MAX_PIXELS }
                .maxByOrNull { it.width.toLong() * it.height }
            ?: pool.minByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            onStatus("Camera error ($error)")
        }
    }

    private fun createSession(camera: CameraDevice) {
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(texture)
        previewSurface = surface

        val imageSurface = reader?.surface ?: return
        activity.runOnUiThread { configureTransform() }

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface), OutputConfiguration(imageSurface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(configured: CameraCaptureSession) {
                    session = configured
                    startPreview(camera, configured, surface)
                }

                override fun onConfigureFailed(configured: CameraCaptureSession) {
                    onStatus("Session configuration failed")
                }
            }
        )

        runCatching { camera.createCaptureSession(config) }
            .onFailure { onStatus("Cannot create session: ${it.message}") }
    }

    private fun startPreview(
        camera: CameraDevice,
        session: CameraCaptureSession,
        surface: Surface,
    ) {
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_AF_MODE, afMode)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        runCatching { session.setRepeatingRequest(builder.build(), null, handler) }
            .onFailure { onStatus("Preview start failed: ${it.message}") }
    }

    /**
     * The JPEG comes out of the encoder in raw sensor orientation, so it needs
     * the usual `SENSOR_ORIENTATION` term on top of the calibrated correction.
     */
    private fun jpegOrientation(): Int =
        ((sensorOrientation + rotationDeg) % 360 + 360) % 360

    /**
     * A TextureView stretches its buffer over the whole view. Undo that stretch,
     * rotate the buffer upright, then scale it to fit inside the view. Fitting
     * rather than cropping keeps the preview showing exactly what the still
     * capture will contain; on the R1 the two shapes match, so nothing is
     * letterboxed in practice.
     */
    private fun configureTransform() {
        val viewW = textureView.width.toFloat()
        val viewH = textureView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val rotation = rotationDeg
        val bufW = previewSize.width.toFloat()
        val bufH = previewSize.height.toFloat()
        val cx = viewW / 2f
        val cy = viewH / 2f

        val matrix = Matrix()
        matrix.setScale(bufW / viewW, bufH / viewH, cx, cy)
        matrix.postRotate(rotation.toFloat(), cx, cy)

        val rotatedW = if (rotation % 180 == 0) bufW else bufH
        val rotatedH = if (rotation % 180 == 0) bufH else bufW
        val scale = min(viewW / rotatedW, viewH / rotatedH)
        matrix.postScale(scale, scale, cx, cy)

        textureView.setTransform(matrix)
    }

    /** Takes one JPEG and saves it to the gallery. [onSaved] gets the display name. */
    fun capture(onSaved: (String?) -> Unit) {
        if (pendingCapture != null || pendingBytes != null) return
        pendingCapture = onSaved
        if (!fire()) {
            pendingCapture = null
            onSaved(null)
        }
    }

    /** Takes one JPEG and hands back the encoded bytes without touching storage. */
    fun captureBytes(onBytes: (ByteArray?) -> Unit) {
        if (pendingCapture != null || pendingBytes != null) return
        pendingBytes = onBytes
        if (!fire()) {
            pendingBytes = null
            onBytes(null)
        }
    }

    private fun fire(): Boolean {
        val camera = device
        val currentSession = session
        val imageSurface = reader?.surface

        if (camera == null || currentSession == null || imageSurface == null) return false

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(imageSurface)
            set(CaptureRequest.CONTROL_AF_MODE, afMode)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
        }

        return runCatching { currentSession.capture(builder.build(), null, handler) }
            .onFailure { Log.e(TAG, "capture failed", it) }
            .isSuccess
    }

    private fun onImageAvailable(imageReader: ImageReader) {
        val saveCallback = pendingCapture
        val bytesCallback = pendingBytes
        pendingCapture = null
        pendingBytes = null

        val bytes = try {
            imageReader.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "reading image failed", t)
            null
        }

        if (bytesCallback != null) {
            activity.runOnUiThread { bytesCallback(bytes) }
            return
        }

        val name = bytes?.let {
            runCatching { saveToGallery(it) }.getOrNull()
        }
        activity.runOnUiThread { saveCallback?.invoke(name) }
    }

    private fun saveToGallery(bytes: ByteArray): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "R1_$stamp.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/R1Camera")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = activity.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "saved $name (jpegOrientation=${jpegOrientation()})")
            name
        } catch (t: Throwable) {
            Log.e(TAG, "write failed", t)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
