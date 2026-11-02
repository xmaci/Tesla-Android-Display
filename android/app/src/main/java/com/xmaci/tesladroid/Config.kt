package com.xmaci.tesladroid

import android.content.Context

/**
 * Settings shared by the activity and services.
 *
 * The Tesla browser refuses private addresses (10.x, 172.16-31.x, 192.168.x),
 * so the phone gives itself a non-private address on a VPN tunnel (CarVpnService)
 * and the car opens it through a public wildcard-DNS name (sslip.io), e.g.
 * http://7.7.7.8.sslip.io:8080/ -> 7.7.7.8. The car's packets reach the phone over
 * the hotspot and the kernel delivers them to the server bound to 0.0.0.0.
 */
object Config {
    private const val PREFS = "tesladroid"

    const val DEFAULT_IP = "7.7.7.8"
    const val DEFAULT_PORT = 8080

    fun ip(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ip", DEFAULT_IP) ?: DEFAULT_IP

    fun setIp(ctx: Context, ip: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ip", ip.trim()).apply()
    }

    fun carUrl(ip: String, port: Int): String =
        if (port == 80) "http://$ip.sslip.io/" else "http://$ip.sslip.io:$port/"

    fun isValidIpv4(s: String): Boolean {
        val p = s.trim().split(".")
        return p.size == 4 && p.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
    }

    fun isPrivate(s: String): Boolean {
        val p = s.trim().split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return true
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || p[0] == 127
    }
}
