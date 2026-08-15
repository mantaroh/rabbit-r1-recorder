package com.r1.audioprobe

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder

/**
 * Starting and stopping the capture service, from wherever asks.
 *
 * Two screens need this now — the home menu and the settings panel — and the
 * options it passes are read from storage rather than from whichever screen
 * happens to be open. That is the point: they used to live as fields on one
 * Activity, so every launch quietly reset them.
 */
object Recorder {

    private const val REQUEST_PERMISSIONS = 4001

    /**
     * Must be called from a visible Activity: Android 14 refuses to start a
     * microphone foreground service from the background, which is also why
     * the boot receiver goes through an Activity to get here.
     */
    fun start(activity: Activity, writeAudio: Boolean? = null) {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ensurePermissions(activity)
            return
        }

        val settings = UploadSettings(activity)
        val intent = Intent(activity, RecorderService::class.java)
            .putExtra(RecorderService.EXTRA_WAKELOCK, settings.wakeLock)
            .putExtra(
                RecorderService.EXTRA_SOURCE,
                if (settings.voiceRecognition) MediaRecorder.AudioSource.VOICE_RECOGNITION
                else MediaRecorder.AudioSource.MIC,
            )
            // The service still has the last word: an evening "stop for today"
            // that has not yet expired overrides whatever is passed here.
            .putExtra(RecorderService.EXTRA_WRITE_AUDIO, writeAudio ?: settings.recording)
            .putExtra(RecorderService.EXTRA_OPUS, settings.opus)

        activity.startForegroundService(intent)
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, RecorderService::class.java))
    }

    fun ensurePermissions(activity: Activity) {
        val wanted = mutableListOf<String>()
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) wanted.add(Manifest.permission.RECORD_AUDIO)
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (wanted.isNotEmpty()) activity.requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }
}
