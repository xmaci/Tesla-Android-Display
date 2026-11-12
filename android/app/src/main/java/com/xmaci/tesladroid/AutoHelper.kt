package com.xmaci.tesladroid

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.xmaci.tesladroid.helper.HelperMain
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Starts the helper by itself, like ATTD: whenever the phone is on Wi‑Fi (unmetered network) and the
 * helper is not running — after a reboot — it turns Wireless debugging on, connects with the saved
 * pairing, starts the helper and puts Wireless debugging back as it was. Needs WRITE_SECURE_SETTINGS,
 * which the helper grants the first time it is started by hand.
 *
 * Android only allows it on a Wi‑Fi network where "Always allow on this network" was ticked once.
 */
class AutoHelperJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "auto-helper") {
            try { tryStart(this) } catch (t: Throwable) { Log.w(TAG, "auto helper", t) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val TAG = "TeslaDroid"
        private const val JOB_PERIODIC = 4201
        private const val JOB_NOW = 4202

        /** Periodic check (every ~15 min on Wi‑Fi) plus one run as soon as Wi‑Fi is there. Survives reboots. */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val cn = ComponentName(ctx, AutoHelperJob::class.java)
            try {
                js.schedule(JobInfo.Builder(JOB_PERIODIC, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
                js.schedule(JobInfo.Builder(JOB_NOW, cn)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                    .setPersisted(true).build())
            } catch (t: Throwable) { Log.w(TAG, "schedule auto helper", t) }
        }

        fun canAutoStart(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        @Synchronized fun tryStart(ctx: Context): String {
            val r = attempt(ctx)
            val line = SimpleDateFormat("dd.MM HH:mm", Locale.US).format(Date()) + " " + r
            ctx.getSharedPreferences("tesladroid", Context.MODE_PRIVATE).edit().putString("auto_helper", line).apply()
            Log.i(TAG, "auto helper: $line")
            DiagLog.init(ctx); DiagLog.add("help", "auto start: $r", important = true)
            return r
        }

        fun lastResult(ctx: Context): String =
            ctx.getSharedPreferences("tesladroid", Context.MODE_PRIVATE).getString("auto_helper", "") ?: ""

        private fun attempt(ctx: Context): String {
            if (Helper.runningVersion(ctx) >= HelperMain.VERSION) return "вже працює · already running"
            if (!Config.paired(ctx)) return "не сполучено · not paired"
            if (!canAutoStart(ctx)) return "немає дозволу — запустіть помічника вручну раз · no permission yet"
            val cr = ctx.contentResolver
            val was = Settings.Global.getInt(cr, "adb_wifi_enabled", 0)
            try {
                if (was == 0) { Settings.Global.putInt(cr, "adb_wifi_enabled", 1); Thread.sleep(3500) }
                return Helper.start(ctx).lineSequence().firstOrNull() ?: ""
            } finally {
                if (was == 0) {
                    Thread.sleep(1500)
                    try { Settings.Global.putInt(cr, "adb_wifi_enabled", 0) } catch (_: Throwable) {}
                }
            }
        }
    }
}

/** After a reboot: run the auto start as soon as the phone is on Wi‑Fi. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            AutoHelperJob.schedule(ctx)
    }
}
