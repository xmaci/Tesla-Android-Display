package com.xmaci.tesladroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream

/**
 * Puts a non-private address (default 7.7.7.8/32) on a TUN interface.
 *
 * Only that one /32 is routed into the tunnel and other traffic bypasses it, so the
 * phone's own internet is untouched. The car reaches the address over the hotspot
 * (the phone is its gateway) and Linux delivers those packets locally to sockets
 * bound to 0.0.0.0 — no packet forwarding and no root needed.
 */
class CarVpnService : VpnService() {

    companion object {
        private const val TAG = "TeslaDroid"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 2

        @Volatile var running = false
            private set
        @Volatile var address: String = ""
            private set
        @Volatile var lastError: String = ""
            private set
        @Volatile private var instance: CarVpnService? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CarVpnService::class.java))
        }

        /** VpnService is bound by the system; closing the fd is what tears the tunnel down. */
        fun stop(ctx: Context) {
            instance?.shutdown()
            ctx.stopService(Intent(ctx, CarVpnService::class.java))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var drainThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val ip = Config.ip(this)
        try {
            shutdown()
            tun = Builder()
                .setSession("TeslaDroid · car link")
                .addAddress(ip, 32)
                .addRoute(ip, 32)
                .setMtu(1500)
                .allowBypass()
                .setBlocking(true)
                .establish()
            if (tun == null) {
                lastError = "VPN permission not granted"
                stopSelf()
                return START_NOT_STICKY
            }
            address = ip
            running = true
            lastError = ""
            startDrain()
            Log.i(TAG, "car link up on $ip/32")
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            Log.e(TAG, "VPN establish failed", t)
            running = false
            stopSelf()
        }
        return START_STICKY
    }

    /** Packets the phone itself sends to the /32 land in the TUN; read and drop them. */
    private fun startDrain() {
        val fd = tun ?: return
        drainThread = Thread({
            val buf = ByteArray(32 * 1024)
            try {
                FileInputStream(fd.fileDescriptor).use { input ->
                    while (!Thread.currentThread().isInterrupted) {
                        if (input.read(buf) < 0) break
                    }
                }
            } catch (_: Throwable) {
            }
        }, "vpn-drain").apply { isDaemon = true; start() }
    }

    fun shutdown() {
        running = false
        drainThread?.interrupt()
        drainThread = null
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
    }

    override fun onRevoke() {
        shutdown()
        lastError = "VPN revoked"
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        instance = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car link", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("TeslaDroid")
            .setContentText("Адреса для Tesla активна · Car address active")
            .setOngoing(true)
            .build()
    }
}
