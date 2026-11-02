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
import android.system.OsConstants

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
        /** Second tunnel's address; it becomes the active VPN instead of the car address. */
        private const val HELPER_IP = "192.168.229.230"

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

    private var tun: ParcelFileDescriptor? = null      // car address, e.g. 7.7.7.8
    private var tun2: ParcelFileDescriptor? = null     // helper tunnel that becomes the active VPN

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * Two tunnels, as NavMirror does (MIT, github.com/ShineCharon/NavMirror):
     *
     * Android drops packets that arrive on another interface (the hotspot) but are addressed
     * to the *active* VPN's own address. Establishing a second interface makes that one the
     * active VPN; the first interface stays up with the car address as an ordinary local
     * address, so the car's packets are delivered to our 0.0.0.0 server.
     * Nothing is read from the tunnels; the phone's own traffic is untouched (allowBypass,
     * /32 routes only).
     */
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
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6)
                .establish()
            tun2 = Builder()
                .setSession("TeslaDroid · helper")
                .addAddress(HELPER_IP, 32)
                .addRoute(HELPER_IP, 32)
                .setMtu(1500)
                .allowBypass()
                .allowFamily(OsConstants.AF_INET)
                .establish()
            if (tun == null && tun2 == null) {
                lastError = "VPN permission not granted"
                stopSelf()
                return START_NOT_STICKY
            }
            address = ip
            running = true
            lastError = ""
            Log.i(TAG, "car link up on $ip/32 (+ helper $HELPER_IP)")
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            Log.e(TAG, "VPN establish failed", t)
            running = false
            stopSelf()
        }
        return START_STICKY
    }

    fun shutdown() {
        running = false
        try { tun?.close() } catch (_: Throwable) {}
        try { tun2?.close() } catch (_: Throwable) {}
        tun = null
        tun2 = null
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
