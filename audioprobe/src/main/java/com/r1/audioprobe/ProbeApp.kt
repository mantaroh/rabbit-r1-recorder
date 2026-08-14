package com.r1.audioprobe

import android.app.Application
import android.content.Intent
import com.r1.core.Idle

/**
 * Exists to say what "idle" means for this app.
 *
 * The timer itself lives in :core so the chat screen — whose source the
 * standalone client also compiles — can arm it without depending on anything
 * here. This is the other half: in the merged app, going idle means putting up
 * the standby display. In the standalone chat nothing sets this and nothing
 * happens, which is correct for a client that is only ever open because
 * somebody is using it.
 */
class ProbeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("R1AudioProbe", "ProbeApp: idle handler installed")
        Idle.onIdle = { activity ->
            activity.startActivity(Intent(activity, SignageActivity::class.java))
        }
    }
}
