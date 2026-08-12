package com.r1.core

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Rabbit R1 camera motor controller for CarrotOS.
 *
 * CarrotOS exposes a root shell on localhost:1337 ("carroot").
 * The physical camera is controlled by:
 *
 *   /sys/devices/platform/step_motor_ms35774/orientation
 *
 * Observed values in the CarrotOS R1Launcher implementation:
 *   0   -> selfie/front
 *   90  -> parked/home
 *   180 -> outward/back
 *
 * Large single jumps can cause missed physical steps, so moves are split
 * into <= 45 degree chunks with a short settle delay.
 */
object R1Motor {
    const val MOTOR_FACE = 0
    const val MOTOR_HOME = 90
    const val MOTOR_BACK = 180

    private const val TAG = "R1CameraWrapper"
    private const val SYSFS_ORIENTATION =
        "/sys/devices/platform/step_motor_ms35774/orientation"

    private const val CHUNK_MAX_DEG = 45
    private const val CHUNK_SETTLE_MS = 100L
    private const val READ_TIMEOUT_MS = 800

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "r1-camera-motor").apply {
            isDaemon = true
        }
    }

    /**
     * Best known logical position. Seeded with the CarrotOS default parked
     * state and corrected by [syncFromDevice] when carroot answers a read.
     */
    @Volatile
    var currentAngle = MOTOR_HOME
        private set

    /**
     * Ask carroot for the real sysfs orientation so the first move starts from
     * the true position instead of the assumed 90 degrees.
     *
     * [onDone] receives the angle that was read, or null when carroot did not
     * answer with a parsable value (in which case [currentAngle] is unchanged).
     */
    fun syncFromDevice(onDone: (Int?) -> Unit = {}) {
        executor.execute {
            val read = readOrientation()
            if (read != null) {
                currentAngle = read
                Log.i(TAG, "orientation synced from device: $read")
            } else {
                Log.w(TAG, "orientation read failed, assuming $currentAngle")
            }
            onDone(read)
        }
    }

    fun moveTo(value: Int, onDone: (Boolean) -> Unit = {}) {
        executor.execute {
            val target = value.coerceIn(MOTOR_FACE, MOTOR_BACK)
            val start = currentAngle
            val total = target - start

            if (total == 0) {
                onDone(true)
                return@execute
            }

            val steps =
                ((abs(total) + CHUNK_MAX_DEG - 1) / CHUNK_MAX_DEG).coerceAtLeast(1)

            val sequence = IntArray(steps) { i ->
                val fraction = (i + 1).toFloat() / steps.toFloat()
                (start + (total * fraction).toInt())
                    .coerceIn(MOTOR_FACE, MOTOR_BACK)
            }
            sequence[sequence.lastIndex] = target

            for ((index, stepTarget) in sequence.withIndex()) {
                if (!writeOrientation(stepTarget)) {
                    Log.e(TAG, "Motor move failed at $stepTarget")
                    onDone(false)
                    return@execute
                }

                currentAngle = stepTarget

                if (index < sequence.lastIndex) {
                    runCatching { Thread.sleep(CHUNK_SETTLE_MS) }
                }
            }

            onDone(true)
        }
    }

    /** Relative move, clamped to the physical 0..180 range. */
    fun moveBy(deltaDeg: Int, onDone: (Boolean) -> Unit = {}) {
        moveTo(currentAngle + deltaDeg, onDone)
    }

    private fun writeOrientation(target: Int): Boolean {
        repeat(2) { attempt ->
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress("127.0.0.1", 1337),
                        1500
                    )

                    val command =
                        "echo $target > $SYSFS_ORIENTATION\n"

                    socket.getOutputStream().apply {
                        write(command.toByteArray())
                        flush()
                    }

                    // Give carroot time to pass the sysfs write to the kernel driver.
                    Thread.sleep(60)

                    Log.i(
                        TAG,
                        "orientation=$target written via carroot (attempt=${attempt + 1})"
                    )
                    return true
                }
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "orientation=$target failed (attempt=${attempt + 1}): ${t.message}"
                )
                runCatching { Thread.sleep(150) }
            }
        }

        return false
    }

    /**
     * carroot echoes command output back over the same socket. Read whatever
     * arrives within [READ_TIMEOUT_MS] and pull the first integer out of it;
     * an unreadable or silent socket simply yields null.
     */
    private fun readOrientation(): Int? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 1337), 1500)
                socket.soTimeout = READ_TIMEOUT_MS

                socket.getOutputStream().apply {
                    write("cat $SYSFS_ORIENTATION\n".toByteArray())
                    flush()
                }

                val buffer = ByteArray(256)
                val text = StringBuilder()
                try {
                    while (text.length < 128) {
                        val read = socket.getInputStream().read(buffer)
                        if (read <= 0) break
                        text.append(String(buffer, 0, read))
                        if (text.contains('\n')) break
                    }
                } catch (_: Throwable) {
                    // Socket timeout just means carroot stayed quiet; use what we have.
                }

                Regex("-?\\d+").find(text.toString())
                    ?.value
                    ?.toIntOrNull()
                    ?.takeIf { it in MOTOR_FACE..MOTOR_BACK }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "orientation read failed: ${t.message}")
            null
        }
    }
}
