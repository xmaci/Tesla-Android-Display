package com.xmaci.tesladroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import io.github.muntashirakon.adb.android.AdbMdns
import kotlin.concurrent.thread

/**
 * One-time pairing with this phone's Wireless debugging, done from the notification shade:
 *
 *  1. User opens Developer options → Wireless debugging → "Pair device with pairing code".
 *     Settings shows a 6-digit code and starts a pairing service on a random port.
 *  2. This service finds that port by mDNS (_adb-tls-pairing._tcp) — no typing of ports.
 *  3. User pulls down the shade and types the code into this notification's reply field,
 *     so the Settings dialog (and its code) stays open.
 *  4. We pair, connect, and start the helper.
 */
class PairingService : Service() {

    companion object {
        private const val TAG = "TeslaDroid"
        private const val CHANNEL = "pairing"
        private const val NOTIF_ID = 3
        private const val ACTION_CODE = "code"
        private const val ACTION_STOP = "stop"
        private const val KEY_CODE = "pairing_code"

        @Volatile var state = ""
            private set

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, PairingService::class.java))
    }

    private var mdns: AdbMdns? = null
    @Volatile private var host: String? = null
    @Volatile private var port = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { finish("cancelled"); return START_NOT_STICKY }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
                if (!code.isNullOrEmpty()) pair(code)
                return START_NOT_STICKY
            }
        }
        state = "waiting for pairing code dialog"
        startForeground(NOTIF_ID, notify("Відкрийте «Сполучити пристрій за кодом» · Open \"Pair device with pairing code\"", false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (mdns == null) {
            mdns = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { addr, p ->
                if (addr != null && p > 0) {
                    host = addr.hostAddress; port = p
                    state = "pairing port $p found — enter the code"
                    update("Введіть 6 цифр коду тут · Enter the 6-digit code here", true)
                }
            }.also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun pair(code: String) {
        val h = host; val p = port
        if (h == null || p <= 0) { update("Порт ще не знайдено · Pairing port not found yet", true); return }
        update("Сполучення… · Pairing…", false)
        thread {
            try {
                val ok = AdbManager.get(this).pair(h, p, code)
                if (!ok) { update("Невірний код? Спробуйте ще · Wrong code? Try again", true); return@thread }
                state = "paired — starting helper"
                update("Сполучено ✓ Запуск помічника… · Paired ✓ Starting helper…", false)
                val result = Helper.start(this)
                finish(result)
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                update("Помилка · Error: ${e.message}", true)
            }
        }
    }

    private fun finish(result: String) {
        state = result
        mdns?.stop(); mdns = null
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID + 1,
            Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("TeslaDroid").setContentText(result).setAutoCancel(true).build())
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(text: String, askCode: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notify(text, askCode))
    }

    private fun notify(text: String, askCode: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("TeslaDroid · Wireless debugging")
            .setContentText(text)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Скасувати · Cancel",
                PendingIntent.getService(this, 2, Intent(this, PairingService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE)).build())
        if (askCode) {
            val input = RemoteInput.Builder(KEY_CODE).setLabel("6 цифр · 6 digits").build()
            val pi = PendingIntent.getService(this, 1, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(Notification.Action.Builder(null, "Ввести код · Enter code", pi).addRemoteInput(input).build())
        }
        return b.build()
    }

    override fun onDestroy() {
        mdns?.stop()
        super.onDestroy()
    }
}
