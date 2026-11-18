package com.xmaci.tesladroid

import android.content.Context
import android.content.Intent

/**
 * Hotspot on/off, from the app or from the streaming service (Stop in the notification).
 * First the helper (works where the shell user may tether); if Android refuses (Android 17 Pixel:
 * error 14), the touch service opens the hotspot page and presses the switch.
 */
object HotspotCtl {
    private const val PREFS = "tesladroid"
    private const val BY_US = "hotspot_by_us"

    /** True (and forgotten) if TeslaDroid switched the hotspot on, so it is ours to switch off. */
    fun takeOurs(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(BY_US, false)) return false
        p.edit().putBoolean(BY_US, false).apply()
        return true
    }

    /** Hotspot is on if an access-point style interface has an IPv4 address (names differ by phone). */
    fun isOn(): Boolean = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            val n = ni.name.lowercase()
            ni.isUp && (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") ||
                Regex("wlan[1-9]").matches(n)) &&
                ni.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
        }
    } catch (_: Exception) { false }

    private fun waitFor(on: Boolean, ms: Long): Boolean {
        val end = android.os.SystemClock.uptimeMillis() + ms
        while (android.os.SystemClock.uptimeMillis() < end) { if (isOn() == on) return true; Thread.sleep(200) }
        return false
    }

    fun switch(ctx: Context, on: Boolean, helperRunning: Boolean, backTo: Class<*>? = MainActivity::class.java,
               onFail: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        Thread {
            val viaHelper = if (helperRunning) Helper.hotspot(app, on) else null
            // Trust what the phone shows, not the answer: wait until the hotspot interface is up / down.
            var ok = viaHelper?.startsWith(if (on) "hotspot on" else "hotspot off") == true && waitFor(on, 6000)
            var how = "helper: $viaHelper" + if (viaHelper?.startsWith("hotspot o") == true && !ok) " (but the hotspot did not change)" else ""
            var touch = TouchService.instance
            if (!ok && touch == null && helperRunning) {
                // Android may switch the touch service off after an app update: the helper turns it back on.
                Helper.enableTouch(app)
                repeat(20) { if (TouchService.instance == null) Thread.sleep(150) }
                touch = TouchService.instance
                if (touch == null) how += " · touch service off"
            }
            if (!ok && touch != null) {
                // Started from the accessibility service: allowed even when TeslaDroid is in the background.
                val open = { action: String -> try { touch.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false } }
                if (open("com.android.settings.WIFI_TETHER_SETTINGS") || open("android.settings.TETHER_SETTINGS")) {
                    val r = touch.setHotspot(on)
                    ok = (r.startsWith("switched") || r.startsWith("already")) && waitFor(on, 6000)
                    how = "switch: $r"
                    if (backTo != null) try {
                        touch.startActivity(Intent(touch, backTo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    } catch (_: Exception) {}
                }
            }
            DiagLog.add("hot", "hotspot ${if (on) "on" else "off"}: $how", important = !ok)
            if (on && ok && !how.contains("already"))
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(BY_US, true).apply()
            if (!ok) onFail?.invoke(how)
        }.start()
    }
}
