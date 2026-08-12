package com.r1.audioprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Always-on capture probe.
 *
 * Measures the things that decide whether a 24/7 lifelog is viable on this
 * device, and that no amount of reading documentation can settle:
 *
 *  - does `AudioRecord` keep delivering once the screen is off,
 *  - does it keep delivering *audio* rather than a stream of zeros — the
 *    "we thought it was recording" failure is silent by definition,
 *  - what the read cadence looks like (gaps mean dropped speech),
 *  - whether the network really reports unmetered on Wi-Fi and metered on the
 *    SIM, which is what a Wi-Fi-only upload policy would hinge on,
 *  - what it costs in battery and heat.
 */
class RecorderService : Service() {

    companion object {
        private const val TAG = "R1AudioProbe"
        private const val CHANNEL_ID = "probe"
        private const val NOTIFICATION_ID = 1

        const val EXTRA_WAKELOCK = "wakelock"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_WRITE_AUDIO = "write_audio"

        const val ACTION_SAMPLE = "com.r1.audioprobe.SAMPLE"

        /** Rotation period for continuous capture, matching the design's max segment. */
        private const val SEGMENT_SECONDS = 60

        /** Stop writing rather than fill the device and break everything else. */
        private const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

        private const val UPLOAD_INTERVAL_MS = 15_000L

        /** Preferred capture format; fallbacks are logged if it is unavailable. */
        private const val TARGET_RATE = 16_000

        private const val SAMPLE_INTERVAL_MS = 30_000L

        /** A read gap beyond this means the pipeline stalled, not just jittered. */
        private const val STALL_MS = 10_000L

        @Volatile var running = false; private set
        @Volatile var snapshot: String = "idle"; private set
    }

    private lateinit var metrics: Metrics
    private lateinit var uploadSettings: UploadSettings
    private var uploader: Uploader? = null
    private var uploadThread: Thread? = null
    /** The segment currently being written; never a candidate for upload. */
    @Volatile private var openSegment: File? = null

    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var stop = false
    @Volatile private var lastFrameAt = 0L
    @Volatile private var frames = 0L
    @Volatile private var bytes = 0L
    @Volatile private var reinits = 0
    @Volatile private var maxGapMs = 0L
    @Volatile private var stalls = 0
    @Volatile private var sampleRequested = false
    @Volatile private var writeAudio = true
    @Volatile private var segments = 0
    @Volatile private var writeErrors = 0
    @Volatile private var diskFull = false

    private var startedAt = 0L
    private var sampleRate = TARGET_RATE
    private var audioSource = MediaRecorder.AudioSource.MIC

    // Peak/RMS over the current reporting window, reset on each sample.
    @Volatile private var windowPeak = 0.0
    @Volatile private var windowSumSquares = 0.0
    @Volatile private var windowSamples = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            metrics.write("screen", mapOf("state" to if (intent?.action == Intent.ACTION_SCREEN_ON) "on" else "off"))
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            metrics.write(
                "network",
                mapOf(
                    "transport" to transportName(caps),
                    "unmetered" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    "validated" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            )
        }

        override fun onLost(network: Network) {
            metrics.write("network", mapOf("transport" to "none"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        metrics = Metrics(this)
        uploadSettings = UploadSettings(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SAMPLE) {
            sampleRequested = true
            return START_STICKY
        }
        if (running) return START_STICKY

        val holdWakeLock = intent?.getBooleanExtra(EXTRA_WAKELOCK, true) ?: true
        audioSource = intent?.getIntExtra(EXTRA_SOURCE, MediaRecorder.AudioSource.MIC)
            ?: MediaRecorder.AudioSource.MIC
        writeAudio = intent?.getBooleanExtra(EXTRA_WRITE_AUDIO, true) ?: true

        startForeground(NOTIFICATION_ID, buildNotification("starting"))

        if (holdWakeLock) {
            // Tested both ways: if capture survives without this, the real app
            // should not hold one either.
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audioprobe:capture")
                .apply { acquire() }
        }

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        }

        startedAt = System.currentTimeMillis()
        stop = false
        running = true

        metrics.write(
            "start",
            mapOf(
                "wakelock" to holdWakeLock,
                "source" to sourceName(audioSource),
                "write_audio" to writeAudio,
                "device" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT,
            )
        )

        thread = Thread({ captureLoop() }, "probe-capture").apply { start() }

        // Uploading runs on its own thread: a slow or stalled network must
        // never delay a read from AudioRecord, which is the one thing in this
        // service that cannot be late.
        uploader = Uploader(
            uploadSettings,
            metrics,
            getSystemService(ConnectivityManager::class.java),
        )
        uploadThread = Thread({ uploadLoop() }, "probe-upload").apply { start() }

        return START_STICKY
    }

    private fun uploadLoop() {
        val dir = audioDir()
        while (!stop) {
            runCatching { uploader?.pump(dir, openSegment) }
                .onFailure { Log.e(TAG, "upload pass failed", it) }
            runCatching { Thread.sleep(UPLOAD_INTERVAL_MS) }
        }
    }

    override fun onDestroy() {
        stop = true
        running = false
        runCatching { thread?.join(2_000) }
        runCatching { uploadThread?.join(2_000) }
        releaseRecorder()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                .unregisterNetworkCallback(networkCallback)
        }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        metrics.write("stop", mapOf("frames" to frames, "reinits" to reinits))
        snapshot = "stopped"
        super.onDestroy()
    }

    // ------------------------------------------------------------ capture ---

    private fun openRecorder(): Boolean {
        for (rate in intArrayOf(TARGET_RATE, 44_100, 48_000)) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                metrics.write("rate_rejected", mapOf("rate" to rate, "minBuffer" to minBuffer))
                continue
            }

            val record = runCatching {
                @Suppress("MissingPermission")
                AudioRecord(
                    audioSource,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuffer * 4
                )
            }.getOrNull()

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record?.release() }
                metrics.write("rate_failed", mapOf("rate" to rate))
                continue
            }

            recorder = record
            sampleRate = rate
            metrics.write(
                "recorder_open",
                mapOf("rate" to rate, "minBuffer" to minBuffer, "source" to sourceName(audioSource))
            )
            return true
        }
        return false
    }

    private fun releaseRecorder() {
        val record = recorder ?: return
        recorder = null
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    private fun captureLoop() {
        if (!openRecorder()) {
            metrics.write("fatal", mapOf("reason" to "no usable AudioRecord configuration"))
            snapshot = "no mic config"
            return
        }

        val record = recorder ?: return
        runCatching { record.startRecording() }

        val buffer = ShortArray(sampleRate / 10) // ~100 ms
        var lastSampleAt = System.currentTimeMillis()
        lastFrameAt = lastSampleAt
        var pending: RandomAccessFile? = null
        var pendingBytes = 0

        while (!stop) {
            val read = runCatching { recorder?.read(buffer, 0, buffer.size) ?: -1 }
                .getOrElse { -1 }

            val now = System.currentTimeMillis()

            if (read <= 0) {
                metrics.write("read_error", mapOf("code" to read))
                reinitialise()
                continue
            }

            val gap = now - lastFrameAt
            if (gap > maxGapMs) maxGapMs = gap
            if (gap > STALL_MS) {
                stalls += 1
                metrics.write("stall", mapOf("gap_ms" to gap))
                reinitialise()
            }
            lastFrameAt = now
            frames += 1
            bytes += read.toLong() * 2

            accumulate(buffer, read)

            // Continuous capture to disk. Amplitude statistics prove the stream
            // is alive; only the audio itself proves it is usable, and only
            // writing it exercises the I/O the real recorder will do.
            if ((writeAudio || sampleRequested) && !diskFull) {
                if (pending == null) {
                    sampleRequested = false
                    pending = openWav()
                    pendingBytes = 0
                }
                if (pending != null) {
                    val written = writePcm(pending, buffer, read)
                    if (written == 0) writeErrors += 1
                    pendingBytes += written

                    val limit = sampleRate * 2 * if (writeAudio) SEGMENT_SECONDS else 5
                    if (pendingBytes >= limit) {
                        finishWav(pending, pendingBytes)
                        pending = null
                        segments += 1
                    }
                }
            } else if (pending != null) {
                finishWav(pending, pendingBytes)
                pending = null
                segments += 1
            }

            if (now - lastSampleAt >= SAMPLE_INTERVAL_MS) {
                lastSampleAt = now
                emitSample()
            }
        }

        pending?.let { finishWav(it, pendingBytes) }
        releaseRecorder()
    }

    private fun reinitialise() {
        reinits += 1
        metrics.write("reinit", mapOf("count" to reinits))
        releaseRecorder()
        runCatching { Thread.sleep(500) }
        if (openRecorder()) {
            runCatching { recorder?.startRecording() }
            lastFrameAt = System.currentTimeMillis()
        } else {
            metrics.write("fatal", mapOf("reason" to "reinit failed"))
            stop = true
        }
    }

    private fun accumulate(buffer: ShortArray, read: Int) {
        var peak = windowPeak
        var sum = windowSumSquares
        for (i in 0 until read) {
            val v = buffer[i].toDouble() / Short.MAX_VALUE
            val a = abs(v)
            if (a > peak) peak = a
            sum += v * v
        }
        windowPeak = peak
        windowSumSquares = sum
        windowSamples += read
    }

    private fun emitSample() {
        val rms = if (windowSamples > 0) sqrt(windowSumSquares / windowSamples) else 0.0
        val peak = windowPeak
        windowPeak = 0.0
        windowSumSquares = 0.0
        windowSamples = 0

        val battery = getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging ?: false
        // Percent moves too coarsely to read a slope from a few hours of data.
        // Instantaneous current and remaining charge give a usable estimate
        // within minutes instead of overnight.
        val currentUa = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val chargeUah = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: -1
        val thermal = runCatching {
            (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        }.getOrDefault(-1)

        val uptime = (System.currentTimeMillis() - startedAt) / 1000
        // Peak of exactly zero across 30s is the signature of a dead stream
        // that still returns success — the failure this probe exists to catch.
        val silent = peak == 0.0
        // Read before the reset below, or every report shows a gap of zero.
        val gap = maxGapMs
        maxGapMs = 0

        metrics.write(
            "sample",
            mapOf(
                "uptime_s" to uptime,
                "rate" to sampleRate,
                "frames" to frames,
                "bytes" to bytes,
                "max_gap_ms" to gap,
                "stalls" to stalls,
                "reinits" to reinits,
                "peak" to String.format(Locale.US, "%.4f", peak).toDouble(),
                "rms" to String.format(Locale.US, "%.4f", rms).toDouble(),
                "all_zero" to silent,
                "battery" to level,
                "charging" to charging,
                "current_ua" to currentUa,
                "charge_uah" to chargeUah,
                "thermal" to thermal,
                "segments" to segments,
                "audio_mb" to audioDir().listFiles()
                    .orEmpty().sumOf { it.length() } / (1024 * 1024),
                "free_gb" to filesDir.usableSpace / (1024 * 1024 * 1024),
                "write_errors" to writeErrors,
                "queued" to (uploader?.queueDepth(audioDir(), openSegment) ?: -1),
                "uploaded" to (uploader?.uploaded ?: 0),
                "upload_failures" to (uploader?.failures ?: 0),
                "upload_blocked" to uploader?.blockedReason(),
                "upload_error" to uploader?.lastError,
            )
        )

        val mA = currentUa / 1000
        val up = uploader
        snapshot = "up ${uptime}s · peak ${"%.3f".format(peak)}" +
            (if (silent) " · SILENT" else "") +
            " · seg $segments · gap ${gap}ms · reinit $reinits" +
            " · batt $level%" + (if (charging) " chg" else " ${mA}mA") +
            (if (up != null) {
                " · up ${up.uploaded}/q${up.queueDepth(audioDir(), openSegment)}" +
                    (up.blockedReason()?.let { " ($it)" } ?: "")
            } else "") +
            (if (diskFull) " · DISK FULL" else "")

        runCatching {
            (getSystemService(NotificationManager::class.java))
                .notify(NOTIFICATION_ID, buildNotification(snapshot))
        }
    }

    // --------------------------------------------------------------- wav ----

    private fun audioDir(): File = File(filesDir, "audio").apply { mkdirs() }

    private fun openWav(): RandomAccessFile? = runCatching {
        val dir = audioDir()
        if (dir.usableSpace < MIN_FREE_BYTES) {
            diskFull = true
            metrics.write("disk_full", mapOf("usable" to dir.usableSpace))
            return null
        }
        val file = File(dir, "seg_${wavStamp.format(Date())}.wav")
        val raf = RandomAccessFile(file, "rw")
        raf.write(ByteArray(44)) // header rewritten on close
        // Publish it so the uploader skips it: a partial WAV shipped under a
        // complete-looking id would be indistinguishable from a good segment.
        openSegment = file
        raf
    }.getOrNull()

    private val wavStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    private fun writePcm(raf: RandomAccessFile?, buffer: ShortArray, read: Int): Int {
        if (raf == null) return 0
        val bb = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until read) bb.putShort(buffer[i])
        return runCatching { raf.write(bb.array()); read * 2 }.getOrDefault(0)
    }

    private fun finishWav(raf: RandomAccessFile, dataBytes: Int) {
        runCatching {
            raf.seek(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)             // PCM
            header.putShort(1)             // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)  // byte rate
            header.putShort(2)             // block align
            header.putShort(16)            // bits
            header.put("data".toByteArray())
            header.putInt(dataBytes)
            raf.write(header.array())
            raf.close()
        }
        // Header written and closed — the file is now a valid segment.
        openSegment = null
    }

    // ------------------------------------------------------ notification ----

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio probe",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("R1 Audio Probe")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()

    private fun transportName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> source.toString()
    }
}
