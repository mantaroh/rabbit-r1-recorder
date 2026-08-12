package com.r1.hermes

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Push-to-talk recorder.
 *
 * MPEG_4 + AAC produces `audio/mp4`, which is on the transcribe endpoint's
 * accepted MIME list, so the recording uploads without transcoding.
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    val isRecording: Boolean get() = recorder != null

    /** Returns null on success, or a message describing why recording failed. */
    fun start(): String? {
        if (recorder != null) return null

        val file = File(context.cacheDir, "ptt.m4a")
        val rec = MediaRecorder(context).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(32_000)
            setOutputFile(file.absolutePath)
        }

        return try {
            rec.prepare()
            rec.start()
            recorder = rec
            target = file
            null
        } catch (t: Throwable) {
            runCatching { rec.release() }
            recorder = null
            target = null
            t.message ?: t.javaClass.simpleName
        }
    }

    /**
     * Stops and returns the recording, or null when nothing usable was
     * captured — a tap that never became a hold produces a file MediaRecorder
     * refuses to finalise, and that must not surface as an error.
     */
    fun stop(): File? {
        val rec = recorder ?: return null
        val file = target
        recorder = null
        target = null

        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }

        return if (stopped && file != null && file.length() > 0) file else null
    }

    fun cancel() {
        val rec = recorder ?: return
        recorder = null
        target = null
        runCatching { rec.stop() }
        runCatching { rec.release() }
    }
}
