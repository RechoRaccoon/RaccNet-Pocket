package com.mediaviewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** Restarts the whole app in a brand-new process.
 *
 *  Used when switching AT Protocol accounts. The ViewModel holds a large
 *  amount of per-account state (feeds, profiles, DM threads, caches, polling
 *  jobs, ...), so rather than trying to reset every piece of it by hand — and
 *  risking one account's data showing up under another — switching persists
 *  the new active session and then does a true cold start, exactly as if the
 *  person had force-closed and reopened the app.
 *
 *  The trick, and the reason this is its own Activity declared with
 *  `android:process=":restart"`: the main process can't reliably relaunch
 *  itself after killing itself. This Activity runs in a separate, tiny
 *  process, so the main process can exit immediately while this one lives
 *  just long enough to start MainActivity fresh (which then spawns a new main
 *  process) and exit too. */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launch = readLaunchIntent()
        if (launch != null) startActivity(launch)
        finish()
        Runtime.getRuntime().exit(0)
    }

    @Suppress("DEPRECATION")
    private fun readLaunchIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_LAUNCH_INTENT, Intent::class.java)
        else intent.getParcelableExtra<Intent>(EXTRA_LAUNCH_INTENT)

    companion object {
        private const val EXTRA_LAUNCH_INTENT = "restart_launch_intent"

        /** Relaunches the app from scratch. Never returns — the calling
         *  process exits. Callers must finish any persistence they need
         *  (e.g. DataStore writes) *before* calling this. */
        fun restartApp(context: Context) {
            val appContext = context.applicationContext
            val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName) ?: return
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val restart = Intent(appContext, RestartActivity::class.java)
            restart.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            restart.putExtra(EXTRA_LAUNCH_INTENT, launch)
            appContext.startActivity(restart)
            Runtime.getRuntime().exit(0)
        }
    }
}
