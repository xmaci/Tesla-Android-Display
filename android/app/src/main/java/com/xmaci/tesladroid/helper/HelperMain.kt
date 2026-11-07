package com.xmaci.tesladroid.helper

import android.os.Looper
import android.system.Os
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Privileged helper. Started once over Wireless debugging as:
 *   CLASSPATH=<apk> setsid app_process /system/bin com.xmaci.tesladroid.helper.HelperMain <token>
 * so it runs as the `shell` user (uid 2000) and keeps running after debugging is turned off,
 * until the phone reboots.
 *
 * v1: alive and privileged. v2: car-shaped virtual display with input and app launch (CarSession).
 *
 * Control socket: 127.0.0.1:7790, one text line per request. Every request starts with the
 * token the app passed at launch, so other apps on the phone cannot use the helper.
 *   "<token> hello"  -> "TDHELPER v=<version> uid=<uid> pid=<pid>"
 *   "<token> quit"   -> helper exits
 *   "<token> display <w> <h> <dpi> <home 1|0>" -> keeps the socket open as a CarSession
 *   "<token> upgrade <apk path>" -> starts the helper from a newer app version and exits (v3+), so
 *                                   app updates don't need Wireless debugging / Wi‑Fi again
 */
object HelperMain {
    const val PORT = 7790
    const val VERSION = 6

    private var server: ServerSocket? = null

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.firstOrNull()
        if (token.isNullOrBlank()) { System.err.println("token required"); exitProcess(2) }
        server = try {
            ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            System.err.println("already running or port busy: ${e.message}")
            exitProcess(1)
        }
        log("TeslaDroid helper v$VERSION uid=${Os.getuid()} pid=${Os.getpid()} listening")
        // System services (DisplayManager, ImageReader callbacks) expect a main looper. The system
        // context itself is made later, on the first car-screen request, so "hello" always works.
        try { Looper.prepareMainLooper() } catch (t: Throwable) { log("looper: $t") }
        thread(name = "accept") {
            while (true) {
                val s = try { server!!.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(s, token) }
            }
            exitProcess(0)
        }
        Looper.loop()
    }

    fun log(msg: String) { System.err.println(msg); System.err.flush() }

    private fun handle(s: Socket, token: String) {
        var keep = false
        try { s.use { sock ->
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val out = sock.getOutputStream()
            val line = reader.readLine() ?: return
            val parts = line.trim().split(" ")
            if (parts.size < 2 || parts[0] != token) {
                out.write("ERR auth\n".toByteArray()); return
            }
            when (parts[1]) {
                "hello" -> out.write("TDHELPER v=$VERSION uid=${Os.getuid()} pid=${Os.getpid()}\n".toByteArray())
                "quit" -> { out.write("BYE\n".toByteArray()); out.flush(); exitProcess(0) }
                "upgrade" -> {
                    val apk = parts.getOrNull(2) ?: ""
                    if (!apk.matches(Regex("/data/app/[A-Za-z0-9_.~=+/-]+/base\\.apk"))) { out.write("ERR apk\n".toByteArray()); return }
                    out.write("UPGRADING\n".toByteArray()); out.flush()
                    try { server?.close() } catch (_: Exception) {}       // free the port for the new helper
                    ProcessBuilder("setsid", "sh", "-c", "sleep 1; CLASSPATH=$apk app_process /system/bin " +
                        "${HelperMain::class.java.name} $token >/data/local/tmp/tesladroid-helper.log 2>&1 </dev/null")
                        .redirectErrorStream(true).start()
                    Thread.sleep(200)
                    exitProcess(0)
                }
                "display" -> {
                    // display <w> <h> <dpi> <home 1|0>; the session owns the socket until the app closes it
                    sock.soTimeout = 0
                    sock.tcpNoDelay = true
                    keep = true
                    CarSession(sock, reader, parts[2].toInt(), parts[3].toInt(), parts[4].toInt(),
                        parts.getOrNull(5) == "1").run()
                    return
                }
                else -> out.write("ERR unknown\n".toByteArray())
            }
            out.flush()
        } } catch (e: Exception) {
            if (keep) log("session: $e")
        }
    }
}
