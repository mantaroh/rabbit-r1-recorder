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
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import java.io.File
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

        const val EXTRA_OPUS = "opus"

        /** Speech at 16 kHz mono stays intelligible well below this. */
        private const val OPUS_BITRATE = 16_000

        /**
         * How much of the conversation before the question goes up with it.
         * This is the whole of "what were we just saying" — nothing else is
         * uploaded, so the window is the memory the agent gets.
         */
        private const val CONTEXT_MS = 120_000L

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
    private lateinit var query: QueryController
    private val vad = Vad()
    private var ring: RingBuffer? = null
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
    /**
     * Off by default. The device now records only so it can answer a question:
     * audio lives in the ring buffer and leaves the device when asked, so
     * nothing is written to disk or uploaded in normal operation. Turn it on
     * to go back to a continuous lifelog.
     */
    @Volatile private var writeAudio = false
    @Volatile private var useOpus = false
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

    /**
     * Link state as seen by the callback, which is the only source that works
     * here — `getActiveNetwork()` returns null on this device even with Wi-Fi
     * up and validated.
     */
    @Volatile private var netState = Uploader.NetworkState(available = false, unmetered = false)

    /**
     * Which networks are currently usable. Keyed by network because more than
     * one can exist at once — losing Wi-Fi while cellular is up is not going
     * offline, and treating it that way stranded a 325-segment queue on a
     * device that could ping the server fine.
     */
    private val usableNetworks = java.util.concurrent.ConcurrentHashMap<Network, Boolean>()

    private fun recomputeNetState() {
        val unmetered = usableNetworks.values.any { it }
        netState = Uploader.NetworkState(
            available = usableNetworks.isNotEmpty(),
            unmetered = unmetered,
        )
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (usable) usableNetworks[network] = unmetered else usableNetworks.remove(network)
            recomputeNetState()

            metrics.write(
                "network",
                mapOf(
                    "transport" to transportName(caps),
                    "unmetered" to unmetered,
                    "validated" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    "usable_count" to usableNetworks.size,
                )
            )
        }

        override fun onLost(network: Network) {
            // Only this network went away; others may still carry traffic.
            usableNetworks.remove(network)
            recomputeNetState()
            metrics.write(
                "network",
                mapOf("transport" to "lost", "usable_count" to usableNetworks.size),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        metrics = Metrics(this)
        uploadSettings = UploadSettings(this)
        query = QueryController(metrics) { from, to -> handleQueryUtterance(from, to) }
        // The accessibility service is the only thing that sees the side
        // button; it hands the gesture straight over.
        KeyService.onDoublePress = { now ->
            val before = query.state
            query.onDoublePress(now)
            if (before == QueryController.State.LIFELOG &&
                query.state == QueryController.State.ARMED
            ) {
                // The press dimmed the screen, so without this the device gives
                // no sign it is listening until the answer lands.
                buzz()
                startActivity(
                    Intent(this, QueryActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }
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
        useOpus = intent?.getBooleanExtra(EXTRA_OPUS, false) ?: false

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
                "codec" to if (useOpus) "opus" else "wav",
                "device" to Build.MODEL,
                "sdk" to Build.VERSION.SDK_INT,
            )
        )

        thread = Thread({ captureLoop() }, "probe-capture").apply { start() }

        // Uploading runs on its own thread: a slow or stalled network must
        // never delay a read from AudioRecord, which is the one thing in this
        // service that cannot be late.
        uploader = Uploader(uploadSettings, metrics) { netState }
        uploadThread = Thread({ uploadLoop() }, "probe-upload").apply { start() }

        return START_STICKY
    }

    private fun uploadLoop() {
        val dir = audioDir()
        while (!stop) {
            // Re-read the system before every pass. The callback is the fast
            // signal, but it is not guaranteed to fire on every transition:
            // a Wi-Fi reconnect once went unreported and left the uploader
            // convinced it was offline while the device could ping the server.
            runCatching { syncNetworkFromSystem() }
            runCatching { uploader?.pump(dir, openSegment) }
                .onFailure { Log.e(TAG, "upload pass failed", it) }
            runCatching { Thread.sleep(UPLOAD_INTERVAL_MS) }
        }
    }

    /** Ground truth, straight from ConnectivityManager. */
    private fun syncNetworkFromSystem() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val live = HashMap<Network, Boolean>()
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (usable) {
                live[network] = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        if (live.keys != usableNetworks.keys) {
            metrics.write(
                "network_resync",
                mapOf("was" to usableNetworks.size, "now" to live.size),
            )
        }
        usableNetworks.clear()
        usableNetworks.putAll(live)
        recomputeNetState()
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
        ring = RingBuffer(sampleRate)

        val buffer = ShortArray(sampleRate / 10) // ~100 ms
        var lastSampleAt = System.currentTimeMillis()
        lastFrameAt = lastSampleAt
        var pending: SegmentWriter? = null
        var pendingSamples = 0

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

            // The ring buffer exists for the query path: a question can begin
            // before the button that asks for it.
            ring?.append(buffer, read, now)
            val ended = vad.accept(buffer, read, now)
            query.tick(now, vad.isSpeaking, ended, vad.utteranceStart)

            // Continuous capture to disk. Amplitude statistics prove the stream
            // is alive; only the audio itself proves it is usable, and only
            // writing it exercises the I/O the real recorder will do.
            if ((writeAudio || sampleRequested) && !diskFull) {
                if (pending == null) {
                    sampleRequested = false
                    pending = openSegmentWriter()
                    pendingSamples = 0
                }
                val writer = pending
                if (writer != null) {
                    runCatching { writer.write(buffer, read) }
                        .onFailure { writeErrors += 1 }
                    pendingSamples += read

                    val limit = sampleRate * if (writeAudio) SEGMENT_SECONDS else 5
                    if (pendingSamples >= limit) {
                        closeSegment(writer)
                        pending = null
                    }
                }
            } else if (pending != null) {
                closeSegment(pending)
                pending = null
            }

            if (now - lastSampleAt >= SAMPLE_INTERVAL_MS) {
                lastSampleAt = now
                emitSample()
            }
        }

        pending?.let { closeSegment(it) }
        releaseRecorder()
    }

    /**
     * Ships the captured question and hands the text to the chat client.
     *
     * Runs off the capture thread: this uploads and waits on a transcription,
     * and an AudioRecord read must never queue behind that.
     *
     * The lifelog copy is untouched — the same speech is still written to its
     * ordinary segment. A question must not punch a hole in the recording.
     */
    private fun handleQueryUtterance(fromMs: Long, toMs: Long) {
        val samples = ring?.slice(fromMs, toMs)
        if (samples == null || samples.isEmpty()) {
            query.finish(false, "utterance no longer in the ring buffer")
            return
        }
        // Grabbed on this thread, before the ring buffer moves on.
        val context = ring?.slice(fromMs - CONTEXT_MS, fromMs)

        Thread({
            // Context first, and only far enough ahead that the agent can find
            // it: the lookup runs server-side after the question is submitted.
            if (context != null && context.isNotEmpty()) {
                runCatching {
                    val ctxFile = File(cacheDir, "context.wav")
                    val writer = WavSegmentWriter(ctxFile, sampleRate)
                    writer.write(context, context.size)
                    if (writer.close() > 0) {
                        val ctxId = "ctx_" + wavStamp.format(Date(fromMs - CONTEXT_MS))
                        uploader?.uploadQuery(ctxFile, ctxId, fromMs - CONTEXT_MS, kind = "lifelog")
                    }
                }.onFailure { Log.w(TAG, "context upload failed", it) }
            }

            val file = File(cacheDir, "query.wav")
            val ok = runCatching {
                val writer = WavSegmentWriter(file, sampleRate)
                writer.write(samples, samples.size)
                writer.close() > 0
            }.getOrDefault(false)

            if (!ok) {
                query.finish(false, "could not stage the question")
                return@Thread
            }

            val id = "qry_" + wavStamp.format(Date(fromMs))
            val transcript = uploader?.uploadQuery(file, id, fromMs)
            if (transcript.isNullOrBlank()) {
                query.finish(false, "no transcript")
                return@Thread
            }

            metrics.write(
                "query_transcript",
                mapOf("segment" to id, "chars" to transcript.length, "text" to transcript.take(200)),
            )
            handOffToChat(transcript)
            query.finish(true, transcript.take(80))
        }, "probe-query").start()
    }

    /**
     * The chat client already renders streaming replies, tool activity and
     * approvals on this panel; re-implementing any of that here would be a
     * worse version of something that works.
     */
    private fun handOffToChat(prompt: String) {
        val intent = Intent()
            .setClassName("com.r1.hermes", "com.r1.hermes.ChatActivity")
            .putExtra("prompt", prompt)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure {
                Log.e(TAG, "chat client unavailable", it)
                metrics.write("query", mapOf("state" to "no_chat_client"))
            }
    }

    /** Confirms the gesture without asking the user to look at the screen. */
    private fun buzz() {
        runCatching {
            val manager = getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator?.vibrate(
                VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

    private fun closeSegment(writer: SegmentWriter) {
        val size = writer.close()
        segments += 1
        // Published only once the container is finalised; before that the file
        // is not a valid segment and must never be uploaded.
        openSegment = null
        if (size <= 0) {
            writeErrors += 1
            metrics.write("segment_bad", mapOf("file" to writer.file.name))
        }
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

    private fun openSegmentWriter(): SegmentWriter? {
        val dir = audioDir()
        if (dir.usableSpace < MIN_FREE_BYTES) {
            diskFull = true
            metrics.write("disk_full", mapOf("usable" to dir.usableSpace))
            return null
        }

        val stem = "seg_${wavStamp.format(Date())}"
        val writer = if (useOpus) {
            OpusSegmentWriter.createOrNull(File(dir, "$stem.opus"), sampleRate, OPUS_BITRATE)
                ?: run {
                    // Better a large segment than none: fall back rather than
                    // stop recording because a codec is missing.
                    useOpus = false
                    metrics.write("opus_unavailable", mapOf("fallback" to "wav"))
                    null
                }
        } else null

        val result = writer
            ?: runCatching { WavSegmentWriter(File(dir, "$stem.wav"), sampleRate) }.getOrNull()

        // Publish it so the uploader skips it: a partial file shipped under a
        // complete-looking id would be indistinguishable from a good segment.
        openSegment = result?.file
        return result
    }

    private val wavStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

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
