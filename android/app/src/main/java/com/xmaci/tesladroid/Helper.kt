package com.xmaci.tesladroid

import android.content.Context
import android.util.Log
import com.xmaci.tesladroid.helper.HelperMain
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** App side of the privileged helper: start it over Wireless debugging and talk to it. */
object Helper {
    private const val TAG = "TeslaDroid"

    /** Last known state, refreshed by [ping]. */
    @Volatile var info: String? = null
        private set

    /** Random secret shared with the helper so other apps cannot drive it. */
    fun token(ctx: Context): String {
        val f = File(ctx.filesDir, "helper.token")
        if (f.exists()) return f.readText().trim()
        val t = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        f.writeText(t)
        return t
    }

    /** Sends one command line; returns the reply or null if the helper is not running. */
    fun request(ctx: Context, cmd: String, timeoutMs: Int = 800): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", HelperMain.PORT), timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().write("${token(ctx)} $cmd\n".toByteArray())
            s.getInputStream().bufferedReader().readLine()
        }
    } catch (_: Exception) { null }

    /** Version of the running helper, 0 if none. v2+ can make the car screen; v3 also keeps it alive when locked. */
    fun runningVersion(ctx: Context): Int =
        Regex("v=(\\d+)").find(ping(ctx) ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0

    fun ping(ctx: Context): String? = request(ctx, "hello").takeIf { it?.startsWith("TDHELPER") == true }.also { info = it }

    /**
     * Connects to this phone's adbd (Wireless debugging must be on and the app paired) and
     * starts the helper detached, so it survives turning debugging off. Blocking; call off the UI thread.
     */
    fun start(ctx: Context): String {
        ping(ctx)?.let {
            // An older helper (from a previous app version) lacks newer commands: replace it.
            val v = Regex("v=(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (v >= HelperMain.VERSION) return "already running: $it"
            if (v >= 3) upgrade(ctx)?.let { r -> return r }
            request(ctx, "quit"); Thread.sleep(400)
        }
        val adb = AdbManager.get(ctx)
        try {
            if (!adb.isConnected && !adb.connectTls(ctx, 10_000)) {
                return "no Wireless debugging found (turn it on, phone must be on Wi‑Fi)"
            }
            Config.setPaired(ctx, true)
        } catch (_: AdbPairingRequiredException) {
            Config.setPaired(ctx, false)
            return "not paired yet — use Pair first"
        } catch (e: Exception) {
            return "adb connect failed: ${e.message}"
        }
        // Sideloaded apps can't switch on Accessibility on newer Android ("restricted setting",
        // and the "Allow restricted settings" menu may be hidden). With shell rights we can:
        // allow it and turn on TeslaDroid's touch service directly.
        val pkg = ctx.packageName
        val svc = "$pkg/${TouchService::class.java.name}"
        runShell(adb, "appops set $pkg ACCESS_RESTRICTED_SETTINGS allow; " +
            "S=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$S\" in *$svc*) ;; null|\"\") settings put secure enabled_accessibility_services $svc ;; " +
            "*) settings put secure enabled_accessibility_services \"\$S:$svc\" ;; esac; " +
            "settings put secure accessibility_enabled 1", 1500)

        val apk = ctx.applicationInfo.sourceDir
        val launch = runShell(adb, "CLASSPATH=$apk setsid app_process /system/bin ${HelperMain::class.java.name} ${token(ctx)}" +
            " >/data/local/tmp/tesladroid-helper.log 2>&1 </dev/null & echo launched", 1500)
        repeat(20) {
            ping(ctx)?.let { return "running: $it" }
            Thread.sleep(250)
        }
        // Not answering: show what happened (the log is only readable by the shell user).
        val diag = runShell(adb, "id; echo ---; cat /data/local/tmp/tesladroid-helper.log 2>&1 | tail -c 600; " +
            "echo ---; ps -A -o PID,ARGS 2>/dev/null | grep -i -e tesladroid -e app_process | grep -v grep", 2500)
        return "helper did not answer.\nlaunch: ${launch.trim().ifEmpty { "(no output)" }}\n${diag.trim().take(900)}"
    }

    /**
     * Helper v3+ restarts itself from the installed (newer) APK: no Wireless debugging or Wi‑Fi needed,
     * so it works in the car. Returns a status line if the new helper answers, null otherwise.
     */
    fun upgrade(ctx: Context): String? {
        if (request(ctx, "upgrade ${ctx.applicationInfo.sourceDir}")?.startsWith("UPGRADING") != true) return null
        repeat(24) {
            Thread.sleep(250)
            ping(ctx)?.let { return "updated: $it" }
        }
        return null
    }

    fun stop(ctx: Context) { request(ctx, "quit"); info = null }

    /** Runs a shell command over ADB and drains its output for up to [waitMs]. */
    private fun runShell(adb: AdbManager, command: String, waitMs: Long): String {
        val out = StringBuilder()
        try {
            adb.openStream("shell:$command").use { stream ->
                val input = stream.openInputStream()
                val buf = ByteArray(1024)
                val until = System.currentTimeMillis() + waitMs
                while (System.currentTimeMillis() < until && !stream.isClosed) {
                    if (input.available() > 0) {
                        val n = input.read(buf)
                        if (n > 0) out.append(String(buf, 0, n))
                    } else Thread.sleep(50)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "shell: $command", e)
            out.append("[adb error: ${e.javaClass.simpleName}: ${e.message}]")
        }
        return out.toString()
    }
}
