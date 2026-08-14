package com.r1.audioprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Base64
import com.r1.core.R1Motor
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

        /** Are the two built-in microphones actually separate? See MicProbe. */
        const val ACTION_MIC_PROBE = "com.r1.audioprobe.MIC_PROBE"

        /** The evening prompt came back with an answer; see StopPromptActivity. */
        const val ACTION_PROMPT_ANSWER = "com.r1.audioprobe.PROMPT_ANSWER"
        const val EXTRA_STOP_RECORDING = "stop_recording"

        /** How often the wall clock is consulted. */
        private const val SCHEDULE_INTERVAL_MS = 30_000L

        /** Long enough to speak from in front, then from behind. */
        private const val MIC_PROBE_SECONDS = 40

        /** Rotation period for continuous capture, matching the design's max segment. */
        private const val SEGMENT_SECONDS = 60

        /** Stop writing rather than fill the device and break everything else. */
        private const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

        private const val UPLOAD_INTERVAL_MS = 15_000L

        const val EXTRA_OPUS = "opus"

        /**
         * 32 kbps is transparent enough for speech at 48 kHz and keeps a year
         * of continuous recording near 126 GB. The bit rate is the knob that
         * decides what the archive costs; the sample rate below decides what
         * is in it at all.
         */
        private const val OPUS_BITRATE = 32_000

        /**
         * How much of the conversation before the question goes up with it.
         * This is the whole of "what were we just saying" — nothing else is
         * uploaded, so the window is the memory the agent gets.
         */
        const val EXTRA_PHOTOS = "photos"

        private const val CONTEXT_MS = 120_000L

        /**
         * Preferred capture format; fallbacks are logged if it is unavailable.
         *
         * 48 kHz, not the 16 kHz this started with. Sample rate is the one
         * decision that cannot be revisited: a recording band-limited to 8 kHz
         * stays band-limited forever, whatever transcribes it later. The
         * device's audio policy advertises 8000/16000/32000/44100/48000 on the
         * built-in mic, and Opus encodes natively at 48 kHz, so the whole path
         * runs without a resampler.
         */
        private const val TARGET_RATE = 48_000

        /**
         * Stereo, though the two capsules turned out not to encode direction.
         *
         * Measured with someone speaking from in front and then from behind:
         * 0.2 dB apart broadband, 0.3 dB in the high band where the body
         * should shadow. Whatever separates the channels — they are never
         * bit-identical — it is not where the speaker is standing.
         *
         * Kept anyway, on the same grounds as the sample rate: a channel not
         * captured cannot be recovered later, and the alternative is deciding
         * on the archive's behalf that a difference we cannot currently read
         * is a difference nobody will ever read. Doubles the bytes.
         */
        private const val TARGET_CHANNELS = 2

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
    @Volatile private var photosEnabled = true
    @Volatile private var micProbeRequested = false

    @Volatile private var schedule = Scheduler.State()
    private var lastScheduleCheck = 0L

    /**
     * Anything that makes a noise or opens a window. Beeps have gaps in them
     * and Activity launches block; neither belongs on the thread that reads
     * AudioRecord, which is the one thing here that cannot be late.
     */
    private val alerts = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "probe-alerts").apply { isDaemon = true }
    }
    private var timelapse: Timelapse? = null
    @Volatile private var segments = 0
    @Volatile private var writeErrors = 0
    @Volatile private var diskFull = false

    private var startedAt = 0L
    private var sampleRate = TARGET_RATE

    /** What the device actually gave us, which may be fewer than asked for. */
    private var channels = 1

    /** Per-second loudness of the segment currently being written. */
    private val envelope = ArrayList<Int>(SEGMENT_SECONDS + 2)
    private var secondSumSquares = 0.0
    private var secondSamples = 0
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
        if (intent?.action == ACTION_MIC_PROBE) {
            micProbeRequested = true
            return START_STICKY
        }
        if (intent?.action == ACTION_PROMPT_ANSWER) {
            val stop = intent.getBooleanExtra(EXTRA_STOP_RECORDING, false)
            schedule = schedule.copy(answeredAt = System.currentTimeMillis())
            uploadSettings.scheduleState = schedule
            if (stop) {
                writeAudio = false
                uploadSettings.recording = false
            }
            metrics.write("schedule", mapOf("what" to "answered", "stopped" to stop))
            return START_STICKY
        }
        if (running) return START_STICKY

        val holdWakeLock = intent?.getBooleanExtra(EXTRA_WAKELOCK, true) ?: true
        audioSource = intent?.getIntExtra(EXTRA_SOURCE, MediaRecorder.AudioSource.MIC)
            ?: MediaRecorder.AudioSource.MIC
        // The stored state wins over the launch extra. Autostart passes "true"
        // unconditionally, and a reinstall or a reboot would otherwise undo an
        // explicit "stop for today" minutes after it was given, with nothing
        // on screen to say so.
        schedule = uploadSettings.scheduleState
        writeAudio = if (Scheduler.firedToday(schedule.answeredAt, System.currentTimeMillis())) {
            uploadSettings.recording
        } else {
            intent?.getBooleanExtra(EXTRA_WRITE_AUDIO, true) ?: true
        }
        useOpus = intent?.getBooleanExtra(EXTRA_OPUS, false) ?: false
        photosEnabled = intent?.getBooleanExtra(EXTRA_PHOTOS, true) ?: true
        if (photosEnabled) {
            timelapse = Timelapse(this, metrics, uploadSettings, photoDir())
            // The arm's real position is unknown until carroot is asked, and
            // the first cycle needs somewhere to put it back to.
            R1Motor.syncFromDevice()
        }

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
        uploader = Uploader(uploadSettings, metrics, { sampleRate }, { channels }) { netState }
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
            // Photos go after the audio, deliberately: they are the smaller
            // loss if the link dies mid-pass.
            runCatching { uploader?.pumpPhotos(photoDir()) }
                .onFailure { Log.e(TAG, "photo upload pass failed", it) }
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
        // Stereo first, mono as the fallback. The two capsules carry almost no
        // difference that tracks where a speaker is standing — measured, twice
        // — but they are not the same signal, and a channel not captured is
        // one no later work can recover. Same reasoning as the sample rate.
        val layouts = intArrayOf(TARGET_CHANNELS, 1)
        for (wantChannels in layouts) {
            val mask =
                if (wantChannels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
            for (rate in intArrayOf(TARGET_RATE, 44_100, 16_000)) {
                val minBuffer = AudioRecord.getMinBufferSize(
                    rate,
                    mask,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuffer <= 0) {
                    metrics.write(
                        "rate_rejected",
                        mapOf("rate" to rate, "channels" to wantChannels, "minBuffer" to minBuffer),
                    )
                    continue
                }

                val record = runCatching {
                    @Suppress("MissingPermission")
                    AudioRecord(
                        audioSource,
                        rate,
                        mask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuffer * 4
                    )
                }.getOrNull()

                if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { record?.release() }
                    metrics.write("rate_failed", mapOf("rate" to rate, "channels" to wantChannels))
                    continue
                }

                recorder = record
                sampleRate = rate
                channels = record.channelCount
                metrics.write(
                    "recorder_open",
                    mapOf(
                        "rate" to rate,
                        "channels" to channels,
                        "minBuffer" to minBuffer,
                        "source" to sourceName(audioSource),
                    )
                )
                return true
            }
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
        ring = RingBuffer(sampleRate, channels)

        val buffer = ShortArray(sampleRate * channels / 10) // ~100 ms
        var lastSampleAt = System.currentTimeMillis()
        lastFrameAt = lastSampleAt
        var pending: SegmentWriter? = null
        var pendingSamples = 0

        while (!stop) {
            // A one-off experiment that needs the microphone to itself: two
            // AudioRecords on one device is a reliable way to be handed
            // silence, so the archive pauses for the duration.
            if (micProbeRequested) {
                micProbeRequested = false
                pending?.let { closeSegment(it) }
                pending = null
                releaseRecorder()
                MicProbe.run(sampleRate, audioSource, MIC_PROBE_SECONDS, metrics) { count ->
                    // Audible, because the vibration on this device is too
                    // faint to notice while talking. Nothing the probe hears
                    // is archived, so a beep costs nothing — and it lands in
                    // the measurement as a marker for where each phase began.
                    repeat(count) {
                        beep()
                        buzz()
                        runCatching { Thread.sleep(280) }
                    }
                }
                reinitialise()
                continue
            }

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

            // Returns immediately; the cycle itself runs on its own thread.
            // Skipped entirely while a question is in flight — the camera and
            // the arm have no business moving mid-question.
            if (photosEnabled && query.state == QueryController.State.LIFELOG) {
                if (vad.isSpeaking) timelapse?.noteSpeech()
                timelapse?.tick(now, vad.isSpeaking)
            }

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

                    val limit = sampleRate * channels * if (writeAudio) SEGMENT_SECONDS else 5
                    if (pendingSamples >= limit) {
                        closeSegment(writer)
                        pending = null
                    }
                }
            } else if (pending != null) {
                closeSegment(pending)
                pending = null
            }

            if (now - lastScheduleCheck >= SCHEDULE_INTERVAL_MS) {
                lastScheduleCheck = now
                runCatching { applySchedule(now) }
                    .onFailure { Log.w(TAG, "schedule check failed", it) }
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
                    val writer = WavSegmentWriter(ctxFile, sampleRate, channels)
                    writer.write(context, context.size)
                    if (writer.close() > 0) {
                        val ctxId = "ctx_" + wavStamp.format(Date(fromMs - CONTEXT_MS))
                        uploader?.uploadQuery(ctxFile, ctxId, fromMs - CONTEXT_MS, kind = "lifelog")
                    }
                }.onFailure { Log.w(TAG, "context upload failed", it) }
            }

            val file = File(cacheDir, "query.wav")
            val ok = runCatching {
                val writer = WavSegmentWriter(file, sampleRate, channels)
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

    /**
     * The day's shape, checked twice a minute against the wall clock.
     *
     * The evening prompt is a question, never a decision: it beeps, it shows a
     * screen, and if nobody answers it comes back in ten minutes and the
     * recording carries on regardless. Losing a night because the room was
     * empty is the one outcome worth ruling out.
     */
    private fun applySchedule(nowMs: Long) {
        when (Scheduler.decide(nowMs, writeAudio, schedule)) {
            Scheduler.Action.NONE -> Unit

            Scheduler.Action.START -> {
                writeAudio = true
                uploadSettings.recording = true
                schedule = schedule.copy(startedAt = nowMs)
                uploadSettings.scheduleState = schedule
                metrics.write("schedule", mapOf("what" to "auto_start"))
            }

            Scheduler.Action.ASK_TO_STOP -> {
                val first = !Scheduler.firedToday(schedule.promptedAt, nowMs)
                schedule = schedule.copy(promptedAt = nowMs)
                uploadSettings.scheduleState = schedule
                metrics.write("schedule", mapOf("what" to "prompt", "first" to first))

                // Off the capture thread. Three tones with gaps, a vibration
                // and an Activity launch is the better part of two seconds,
                // and this runs on the thread that reads AudioRecord: the
                // first version pushed max_gap_ms from 105 to 1731.
                alerts.execute {
                    // Sound first: the screen may be face down, or the person
                    // may be on another floor. Three tones so it does not read
                    // as an upload chirp.
                    repeat(3) {
                        beep()
                        buzz()
                        runCatching { Thread.sleep(260) }
                    }
                    runCatching {
                        startActivity(
                            Intent(this, StopPromptActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                    }.onFailure { Log.w(TAG, "stop prompt could not be shown", it) }
                }
            }
        }
    }

    /** A short tone, loud enough to hear across a room while speaking. */
    private fun beep() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tone.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 180)
            Thread.sleep(220)
            tone.release()
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

        // The sidecar travels with the segment because uploading happens on
        // another thread, minutes to days later — by which time this one has
        // long since moved on. Written before the segment is published so the
        // uploader never sees audio without it.
        //
        // It carries the format as well as the envelope, and that is not
        // decoration: the uploader used to report whatever the recorder was
        // configured for *at upload time*, so a mono file still queued when
        // the recorder restarted in stereo was archived labelled stereo. A
        // recording described wrongly is decoded wrongly forever.
        runCatching {
            val envelope = takeEnvelope().orEmpty()
            File(writer.file.parentFile, writer.file.nameWithoutExtension + ".rms")
                .writeText("rate=$sampleRate channels=$channels\n$envelope")
        }.onFailure { metrics.write("sidecar_write_failed", mapOf("file" to writer.file.name)) }

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
        var bufferSum = 0.0
        for (i in 0 until read) {
            val v = buffer[i].toDouble() / Short.MAX_VALUE
            val a = abs(v)
            if (a > peak) peak = a
            bufferSum += v * v
        }
        windowPeak = peak
        windowSumSquares += bufferSum
        windowSamples += read

        // Second-by-second loudness for the segment being written. The server
        // decides from this whether the minute is worth transcribing; it cannot
        // measure that itself without decoding Opus, and this thread already
        // has the PCM in hand.
        secondSumSquares += bufferSum
        secondSamples += read
        // A second of *audio*, not of samples: stereo delivers twice as many.
        if (secondSamples >= sampleRate * channels) {
            val level = rmsByte(secondSumSquares, secondSamples)
            envelope.add(level)
            // The same measurement decides whether the scene is worth
            // photographing; there is no reason to compute it twice.
            timelapse?.noteSecond(level)
            secondSumSquares = 0.0
            secondSamples = 0
        }
    }

    /**
     * One byte of loudness: raw RMS shifted down four bits, saturating.
     *
     * Measured rooms land at 11 (quiet), 22 (background activity) and 152–221
     * (speech), so a byte holds the whole useful range with the interesting
     * decisions nowhere near either end.
     */
    private fun rmsByte(sumSquares: Double, samples: Int): Int {
        if (samples <= 0) return 0
        val raw = sqrt(sumSquares / samples) * Short.MAX_VALUE
        return (raw / 16).toInt().coerceIn(0, 255)
    }

    /** Base64 of the per-second bytes, or null when nothing was measured. */
    private fun takeEnvelope(): String? {
        if (secondSamples > 0) {
            envelope.add(rmsByte(secondSumSquares, secondSamples))
        }
        secondSumSquares = 0.0
        secondSamples = 0
        if (envelope.isEmpty()) return null
        val bytes = ByteArray(envelope.size) { envelope[it].toByte() }
        envelope.clear()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
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

    /**
     * Separate from the audio: the uploader's queue depth and the `audio_mb`
     * metric both sum whatever is in a directory, and photos sitting alongside
     * segments would quietly corrupt both readings.
     */
    private fun photoDir(): File = File(filesDir, "photos").apply { mkdirs() }

    private fun openSegmentWriter(): SegmentWriter? {
        val dir = audioDir()
        if (dir.usableSpace < MIN_FREE_BYTES) {
            diskFull = true
            metrics.write("disk_full", mapOf("usable" to dir.usableSpace))
            return null
        }

        val stem = "seg_${wavStamp.format(Date())}"
        val writer = if (useOpus) {
            // Bit rate scales with the channel count, so stereo keeps the same
            // per-channel quality rather than halving it to stay the same size.
            OpusSegmentWriter.createOrNull(
                File(dir, "$stem.opus"), sampleRate, OPUS_BITRATE * channels, channels,
            )
                ?: run {
                    // Better a large segment than none: fall back rather than
                    // stop recording because a codec is missing.
                    useOpus = false
                    metrics.write("opus_unavailable", mapOf("fallback" to "wav"))
                    null
                }
        } else null

        val result = writer
            ?: runCatching {
                WavSegmentWriter(File(dir, "$stem.wav"), sampleRate, channels)
            }.getOrNull()

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
