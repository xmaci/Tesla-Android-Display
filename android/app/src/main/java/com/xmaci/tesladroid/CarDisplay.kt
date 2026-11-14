package com.xmaci.tesladroid

import android.content.Context
import android.util.Log
import com.xmaci.tesladroid.helper.HelperMain
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * App side of a car-shaped screen: asks the helper (shell user) for a virtual display the size of the
 * car's browser area, receives its frames as raw RGBA and sends touches, keys and app launches back.
 * Protocol: see helper/CarSession.kt. Closing the socket removes the display.
 */
class CarDisplay(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        /** Raw RGBA, [w]*[h]*4 bytes, valid only during the call. */
        fun onCarFrame(px: ByteArray, w: Int, h: Int)
        fun onCarText(line: String)
        /** The car screen failed or the helper went away (not called after stop()). */
        fun onCarEnded(display: CarDisplay)
    }

    @Volatile var displayId = -1
        private set
    /** Step-by-step state for diagnostics: shown in the app and on the car. */
    @Volatile var status = "starting"
        private set
    @Volatile var frames = 0
        private set
    @Volatile var w = 0
        private set
    @Volatile var h = 0
        private set

    @Volatile private var sock: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var stopped = false
    /** Older helper: the screen works, but not with the phone locked. */
    @Volatile private var oldHelper = false

    /** Mirror of the phone screen made by the helper (no MediaProjection) instead of a car screen. */
    @Volatile var mirror = false
        private set

    fun start(width: Int, height: Int, dpi: Int, home: Boolean, mirror: Boolean = false) {
        this.mirror = mirror
        w = width; h = height
        thread(name = "car-display", isDaemon = true) { run(width, height, dpi, home) }
    }

    private fun run(width: Int, height: Int, dpi: Int, home: Boolean) {
        try { session(width, height, dpi, home) } finally { if (!stopped) listener.onCarEnded(this) }
    }

    private fun session(width: Int, height: Int, dpi: Int, home: Boolean) {
        status = "1/4 helper…"
        var v = Helper.runningVersion(ctx)
        if (v in 3 until HelperMain.VERSION) { status = "1/4 оновлюю помічника · updating helper…"; Helper.upgrade(ctx); v = Helper.runningVersion(ctx) }
        if (v == 0) {
            status = "✗ 1/4 помічник не запущено — запустіть вдома через Wi‑Fi (Додатково → Запустити помічника) · helper not running — start it at home on Wi‑Fi (Advanced → Start helper)"
            return
        }
        if (v < 2) {
            status = "✗ 1/4 помічник v$v застарий — перезапустіть вдома через Wi‑Fi · helper v$v too old — restart it at home on Wi‑Fi"
            return
        }
        oldHelper = v < HelperMain.VERSION
        try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", HelperMain.PORT), 2000)
            s.tcpNoDelay = true
            s.receiveBufferSize = 4 * 1024 * 1024
            sock = s
            out = s.getOutputStream()
            status = "2/4 display…"
            send(if (mirror) "${Helper.token(ctx)} mirror $width $height"
                 else "${Helper.token(ctx)} display $width $height $dpi ${if (home) 1 else 0}")
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 20))
            var px = ByteArray(0)
            while (!stopped) {
                val type = input.readInt()
                val a = input.readInt(); val b = input.readInt(); val c = input.readInt()
                when (type) {
                    1 -> {
                        if (c <= 0 || c > 64 shl 20) throw IllegalStateException("bad frame $a×$b $c")
                        if (px.size != c) px = ByteArray(c)
                        input.readFully(px)
                        w = a; h = b; frames++
                        if (frames == 1) status = "✓ 4/4 кадри йдуть · frames flowing (display $displayId, ${a}×$b)" +
                            (if (oldHelper) " · ⚠ помічник старий: з заблокованим телефоном не працюватиме, перезапустіть вдома · old helper: no locked phone, restart it at home" else "")
                        listener.onCarFrame(px, a, b)
                    }
                    2 -> {
                        val t = ByteArray(a).also { input.readFully(it) }.toString(Charsets.UTF_8)
                        if (t.startsWith("OK ")) {
                            displayId = t.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
                            status = "3/4 display $displayId (${t.substringAfter(' ').substringAfter(' ')}) — чекаю кадр · waiting for a frame"
                        } else if (t.startsWith("ERR display")) {
                            status = "✗ 2/4 $t"
                        }
                        Log.i(TAG, "car display: $t")
                        listener.onCarText(t)
                    }
                    else -> throw IllegalStateException("helper said: " + intToText(type))
                }
            }
        } catch (e: Exception) {
            if (!stopped) {
                if (!status.startsWith("✗")) status = "✗ ${status.take(4)} ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "car display", e)
            }
        } finally { close() }
    }

    private fun intToText(v: Int) = String(byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()))

    /** One writer thread keeps touch events in order and never blocks the caller (WebSocket thread). */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun send(line: String) {
        val o = out ?: return
        try {
            writer.execute { try { o.write((line + "\n").toByteArray()); o.flush() } catch (_: Exception) {} }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    fun touch(kind: String, x: Float, y: Float) = send("t $kind ${"%.1f".format(java.util.Locale.US, x)} ${"%.1f".format(java.util.Locale.US, y)}")
    fun key(name: String) = send("k $name")
    fun launch(component: String) = send("launch $component")
    fun resize(width: Int, height: Int, dpi: Int) = send("size $width $height $dpi")
    /** off: the phone went to sleep — wake it (stays locked) and darken its panel; on: panel back on. */
    fun phone(on: Boolean) = send("phone ${if (on) "undim" else "dim"}")
    fun keepAwake(on: Boolean) = send("keep ${if (on) "on" else "off"}")

    /** Lets queued lines (e.g. "phone on") reach the helper before the socket closes. */
    fun flushAndStop() {
        try { writer.submit { }.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        stop()
    }

    fun stop() { stopped = true; close(); writer.shutdown() }

    private fun close() {
        try { sock?.close() } catch (_: Exception) {}
        sock = null; out = null
    }

    private companion object { const val TAG = "TeslaDroid" }
}
