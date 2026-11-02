package com.xmaci.tesladroid.helper

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
 * M1 step 2a: only proves it is alive and privileged.
 * Step 2b adds the car-shaped virtual display; 2c adds app launch and input on it.
 *
 * Control socket: 127.0.0.1:7790, one text line per request. Every request starts with the
 * token the app passed at launch, so other apps on the phone cannot use the helper.
 *   "<token> hello"  -> "TDHELPER v=<version> uid=<uid> pid=<pid>"
 *   "<token> quit"   -> helper exits
 */
object HelperMain {
    const val PORT = 7790
    const val VERSION = 1

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.firstOrNull()
        if (token.isNullOrBlank()) { System.err.println("token required"); exitProcess(2) }
        val server = try {
            ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            System.err.println("already running or port busy: ${e.message}")
            exitProcess(1)
        }
        println("TeslaDroid helper v$VERSION uid=${Os.getuid()} pid=${Os.getpid()}")
        while (true) {
            val s = try { server.accept() } catch (_: Exception) { break }
            thread(isDaemon = true) { handle(s, token) }
        }
    }

    private fun handle(s: Socket, token: String) {
        s.use { sock ->
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
                else -> out.write("ERR unknown\n".toByteArray())
            }
            out.flush()
        }
    }
}
