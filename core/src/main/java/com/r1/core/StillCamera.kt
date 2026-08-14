package com.r1.core

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Takes one JPEG, with no preview and no Activity.
 *
 * [CameraController] is the interactive path: it needs a TextureView to draw
 * into and an Activity to own it. A background service has neither, and does
 * not want them — Camera2 is perfectly willing to configure a session whose
 * only output is an ImageReader, which is all a timelapse frame needs.
 *
 * Everything is opened and torn down per shot. Holding the camera open between
 * frames five minutes apart would keep the sensor powered for nothing and, more
 * to the point, would stop any other app on the device from using it.
 */
class StillCamera(private val context: Context) {

    companion object {
        private const val TAG = "R1AudioProbe"

        /** How long to wait for the whole open → configure → capture sequence. */
        private const val TIMEOUT_MS = 8_000L

        /**
         * How long to let frames flow before taking the shot.
         *
         * Auto-exposure converges by observing successive frames; with none
         * flowing it never leaves its default, which on this sensor means a
         * black image. Measured against the alternative of polling
         * CONTROL_AE_STATE, a fixed wait is both simpler and adequate — this
         * runs every five minutes, so a second of settling costs nothing.
         */
        private const val WARMUP_MS = 1_200L
    }

    /**
     * Returns JPEG bytes, or null if anything failed.
     *
     * Blocking: callers are background threads that have nothing better to do,
     * and the alternative is a callback chain across four Camera2 listeners.
     */
    /**
     * [rotationDeg] is the calibrated correction for the arm position this
     * shot is taken at, added on top of the sensor's own orientation. The
     * first version of this set no orientation at all and every frame reached
     * R2 rotated a quarter turn — the encoder emits raw sensor orientation
     * unless told otherwise, and on this device that is not upright.
     *
     * The sensor rides on the arm, so the two positions are 180 degrees apart
     * and each needs its own value. They are calibrated rather than derived:
     * the panel and sensor mounting here do not follow the usual convention,
     * which is why the camera app persists them per side too.
     */
    fun capture(maxEdge: Int, rotationDeg: Int = 0): ByteArray? {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = selectCameraId(manager) ?: run {
            Log.w(TAG, "no camera available")
            return null
        }

        val thread = HandlerThread("r1-still").apply { start() }
        val handler = Handler(thread.looper)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var texture: SurfaceTexture? = null
        var bytes: ByteArray? = null

        try {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val sensorOrientation =
                characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val jpegOrientation = ((sensorOrientation + rotationDeg) % 360 + 360) % 360
            Log.i(TAG, "still: sensor=$sensorOrientation cal=$rotationDeg jpeg=$jpegOrientation")

            val size = chooseSize(characteristics, maxEdge)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)

            val imageReady = CountDownLatch(1)
            reader.setOnImageAvailableListener({ r ->
                runCatching {
                    r.acquireLatestImage()?.use { image ->
                        val buffer = image.planes[0].buffer
                        bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    }
                }.onFailure { Log.w(TAG, "image read failed", it) }
                imageReady.countDown()
            }, handler)

            device = openDevice(manager, cameraId, handler) ?: return null

            // A throwaway preview target. Firing a still the instant the
            // session configures gives auto-exposure nothing to work from and
            // the sensor returns a black frame — the first version of this did
            // exactly that, producing valid 640x480 JPEGs of nothing at all.
            // AE only converges while frames are actually flowing, so a
            // repeating request has to run first, and it needs somewhere to
            // put those frames that is not the JPEG reader.
            texture = SurfaceTexture(0).apply {
                setDefaultBufferSize(size.width, size.height)
            }
            val previewSurface = Surface(texture)

            session = configureSession(device, listOf(previewSurface, reader.surface), handler)
                ?: return null

            val af = afMode(characteristics)
            session.setRepeatingRequest(
                device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, af)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                }.build(),
                null,
                handler,
            )
            Thread.sleep(WARMUP_MS)

            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, af)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_QUALITY, 60.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
            }.build()

            session.stopRepeating()
            session.capture(request, null, handler)
            if (!imageReady.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "capture timed out")
            }
            return bytes
        } catch (t: Throwable) {
            Log.w(TAG, "still capture failed", t)
            return null
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            runCatching { texture?.release() }
            thread.quitSafely()
        }
    }

    private fun openDevice(
        manager: CameraManager,
        cameraId: String,
        handler: Handler,
    ): CameraDevice? {
        var opened: CameraDevice? = null
        val latch = CountDownLatch(1)
        try {
            @Suppress("MissingPermission")
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        opened = camera
                        latch.countDown()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        latch.countDown()
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        Log.w(TAG, "camera open error $error")
                        camera.close()
                        latch.countDown()
                    }
                },
                handler,
            )
        } catch (t: Throwable) {
            // Most often CameraAccessException because something else holds the
            // camera, or SecurityException when the service has no while-in-use
            // grant. Both mean "not this time", not "broken".
            Log.w(TAG, "openCamera refused", t)
            return null
        }
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return opened
    }

    private fun configureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession? {
        var configured: CameraCaptureSession? = null
        val latch = CountDownLatch(1)

        @Suppress("DEPRECATION")
        device.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    configured = cameraCaptureSession
                    latch.countDown()
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    Log.w(TAG, "session configure failed")
                    latch.countDown()
                }
            },
            handler,
        )

        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return configured
    }

    private fun selectCameraId(manager: CameraManager): String? {
        val ids = runCatching { manager.cameraIdList }.getOrNull() ?: return null
        if (ids.isEmpty()) return null

        fun facing(id: String): Int? = runCatching {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        }.getOrNull()

        // Same order as CameraController: this device reports its single
        // motorised lens as back-facing.
        return ids.firstOrNull { facing(it) == CameraMetadata.LENS_FACING_BACK }
            ?: ids.firstOrNull { facing(it) == CameraMetadata.LENS_FACING_EXTERNAL }
            ?: ids.first()
    }

    /** The R1 lens is fixed-focus and advertises AF_MODE_OFF only. */
    private fun afMode(characteristics: CameraCharacteristics): Int {
        val available = characteristics
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?.toList()
            .orEmpty()
        return listOf(
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraMetadata.CONTROL_AF_MODE_AUTO,
            CameraMetadata.CONTROL_AF_MODE_OFF,
        ).firstOrNull { available.contains(it) } ?: CameraMetadata.CONTROL_AF_MODE_OFF
    }

    private fun chooseSize(characteristics: CameraCharacteristics, maxEdge: Int): Size {
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.toList()
            .orEmpty()
        if (sizes.isEmpty()) return Size(640, 480)

        return sizes.filter { max(it.width, it.height) <= maxEdge }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: Size(640, 480)
    }
}
