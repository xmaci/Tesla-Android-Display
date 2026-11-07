package com.xmaci.tesladroid.helper

import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.DataOutputStream
import java.net.Socket

/**
 * One car-shaped virtual display, alive as long as the app keeps this socket open.
 *
 * The display belongs to the shell user, so it may be trusted and always unlocked: apps on it keep
 * running while the phone itself is locked (flags as in scrcpy's --new-display).
 *
 * app -> helper, text lines:
 *   t <d|m|u> <x> <y>        touch on the display, pixels
 *   k <back|home|recents>     key on the display
 *   launch <pkg/cls>          start an activity on the display
 *   size <w> <h> <dpi>        resize the display
 *   phone <off|on>            phone went to sleep: wake it (stays locked) and turn its panel off / back on
 * helper -> app, 16-byte header + body:
 *   type 1 (frame): w, h, length → RGBA rows, no padding (w*4 bytes each)
 *   type 2 (text):  length, 0, 0 → UTF-8 line ("OK <displayId> <flags>", "launch: …", "ERR …")
 */
class CarSession(private val sock: Socket, private val input: BufferedReader,
                 private var w: Int, private var h: Int, private var dpi: Int, private val deco: Boolean) {

    private companion object {
        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are hidden)
        const val PUBLIC = 1
        const val PRESENTATION = 1 shl 1
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val ROTATES_WITH_CONTENT = 1 shl 7
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        const val DEVICE_DISPLAY_GROUP = 1 shl 15
        const val MIN_FRAME_MS = 33L      // ≤ 30 frames/s to the app; the car takes far fewer
    }

    private val out = DataOutputStream(sock.getOutputStream().buffered(256 * 1024))
    private val thread = HandlerThread("car-display").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var displayId = -1
    private var pixels = ByteArray(0)
    private var lastFrameAt = 0L
    private var pullPosted = false
    private var downTime = 0L
    @Volatile private var panelOff = false
    @Volatile private var closed = false
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    fun run() {
        try {
            if (!create()) return
            while (!closed) {
                val line = input.readLine() ?: break
                val p = line.trim().split(" ")
                try { command(p) } catch (t: Throwable) { text("ERR ${p[0]}: ${t.javaClass.simpleName}: ${t.message}") }
            }
        } catch (_: Exception) {
        } finally { close() }
    }

    private fun create(): Boolean {
        HelperMain.log("display ${w}x$h@$dpi home=$deco: system context…")
        // Built on another thread with a time limit: if Android's internals hang here, the helper
        // reports it instead of hanging itself.
        // Android's display code needs a thread with a Looper: run it on the helper's main looper.
        var err: Throwable? = null
        val done = onMain(5000) { try { ShellEnv.displayManager } catch (e: Throwable) { err = e } }
        if (!done || err != null) {
            val c = err?.let { (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it }
            val why = if (c == null) "timeout (5 s)" else "${c.javaClass.simpleName} ${c.message}"
            HelperMain.log("system context failed: $why")
            text("ERR display: system context: $why")
            return false
        }
        HelperMain.log("display: creating")
        // No PRESENTATION flag: Android shows its lock screen (clock) on every presentation display while
        // the phone is locked, which covered the launcher on the car screen.
        val base = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
        val unlocked = TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        val focus = OWN_FOCUS or DEVICE_DISPLAY_GROUP
        val d = if (deco) SHOULD_SHOW_SYSTEM_DECORATIONS else 0
        // Strongest first; older or stricter phones refuse some flags.
        val tries = listOf(
            "unlocked+focus" + (if (deco) "+home" else "") to (base or unlocked or focus or d),
            "unlocked" + (if (deco) "+home" else "") to (base or unlocked or d),
            "unlocked" to (base or unlocked),
            "basic" to base)
        val r = newReader(w, h)
        val errors = StringBuilder()
        for ((name, flags) in tries) {
            try {
                var made: VirtualDisplay? = null
                var fail: Throwable? = null
                if (!onMain(5000) {
                        try { made = ShellEnv.displayManager.createVirtualDisplay("TeslaDroid car", w, h, dpi, r.surface, flags) }
                        catch (e: Throwable) { fail = e }
                    }) throw IllegalStateException("timeout")
                fail?.let { throw it }
                val vd = made ?: throw IllegalStateException("null display")
                display = vd; reader = r; displayId = vd.display.displayId
                text("OK $displayId $name ${w}x$h@$dpi")
                return true
            } catch (t: Throwable) {
                errors.append(name).append(": ").append(t.javaClass.simpleName).append(' ').append(t.message).append("; ")
            }
        }
        r.close()
        text("ERR display: $errors")
        return false
    }

    /** Runs [block] on the main looper thread and waits up to [ms]; false on timeout. */
    private fun onMain(ms: Long, block: () -> Unit): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        Handler(android.os.Looper.getMainLooper()).post { try { block() } finally { latch.countDown() } }
        return latch.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).also {
            it.setOnImageAvailableListener({ schedulePull() }, handler)
        }

    /** At most one frame per MIN_FRAME_MS; the newest image wins, older ones are dropped. */
    private fun schedulePull() {
        if (pullPosted) return
        pullPosted = true
        val wait = (lastFrameAt + MIN_FRAME_MS - SystemClock.uptimeMillis()).coerceAtLeast(0)
        handler.postDelayed({ pullPosted = false; pull() }, wait)
    }

    private fun pull() {
        val r = reader ?: return
        val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        val iw: Int; val ih: Int
        try {
            iw = img.width; ih = img.height
            val plane = img.planes[0]
            val row = iw * 4
            if (pixels.size != row * ih) pixels = ByteArray(row * ih)
            val buf = plane.buffer
            if (plane.rowStride == row) { buf.rewind(); buf.get(pixels, 0, row * ih) }
            else for (y in 0 until ih) { buf.position(y * plane.rowStride); buf.get(pixels, y * row, row) }
        } finally { img.close() }
        lastFrameAt = SystemClock.uptimeMillis()
        try {
            synchronized(out) {
                out.writeInt(1); out.writeInt(iw); out.writeInt(ih); out.writeInt(pixels.size)
                out.write(pixels); out.flush()
            }
        } catch (_: Exception) { close() }
    }

    private fun text(s: String) {
        val b = s.toByteArray()
        try {
            synchronized(out) { out.writeInt(2); out.writeInt(b.size); out.writeInt(0); out.writeInt(0); out.write(b); out.flush() }
        } catch (_: Exception) { closed = true }
    }

    private fun command(p: List<String>) {
        when (p[0]) {
            "t" -> touch(p[1], p[2].toFloat(), p[3].toFloat())
            "k" -> key(when (p[1]) {
                "back" -> KeyEvent.KEYCODE_BACK
                "home" -> KeyEvent.KEYCODE_HOME
                "recents" -> KeyEvent.KEYCODE_APP_SWITCH
                else -> return
            })
            "launch" -> launch(p[1])
            "size" -> resize(p[1].toInt(), p[2].toInt(), p[3].toInt())
            "phone" -> {
                val w = Panel.wake()
                if (p[1] == "off") Thread.sleep(700)          // let the panel come on first, then darken it
                val r = Panel.set(p[1] != "off")
                panelOff = p[1] == "off" && r.startsWith("panel off")
                text("power: wake ${if (w) "ok" else "refused"}, $r")
            }
        }
    }

    private fun touch(kind: String, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val action = when (kind) {
            "d" -> { downTime = now; MotionEvent.ACTION_DOWN }
            "m" -> MotionEvent.ACTION_MOVE
            "u" -> MotionEvent.ACTION_UP
            else -> return
        }
        val ev = MotionEvent.obtain(downTime, now, action, x.coerceIn(0f, w - 1f), y.coerceIn(0f, h - 1f), 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        val ok = try { ShellEnv.inject(ev, displayId) } catch (t: Throwable) { text("ERR inject: ${t.javaClass.simpleName}: ${t.message}"); false }
        ev.recycle()
        if (kind == "u") text("touch: " + (if (ok) "ok" else "refused"))
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, a, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            ShellEnv.inject(ev, displayId)
        }
    }

    /** `am start` as the shell user may put any app on any display. */
    private fun launch(component: String) {
        if (!component.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+"))) { text("launch: bad name"); return }
        val pr = ProcessBuilder("/system/bin/am", "start", "--display", displayId.toString(),
            "-f", "0x10000000", "-n", component).redirectErrorStream(true).start()
        val res = pr.inputStream.bufferedReader().readText().trim().lines().lastOrNull { it.isNotBlank() } ?: ""
        pr.waitFor()
        text("launch: ${res.take(200)}")
    }

    private fun resize(nw: Int, nh: Int, nd: Int) {
        if (nw < 160 || nh < 160 || nw > 2560 || nh > 2560) return
        handler.post {
            if (nw == w && nh == h && nd == dpi) return@post
            val old = reader
            val r = newReader(nw, nh)
            reader = r
            display?.resize(nw, nh, nd)
            display?.surface = r.surface
            old?.close()
            w = nw; h = nh; dpi = nd
            text("size ${nw}x$nh@$nd")
        }
    }

    private fun close() {
        closed = true
        if (!released.compareAndSet(false, true)) return
        if (panelOff) { Panel.set(true); panelOff = false }   // never leave the phone with a dark panel
        handler.post {
            try { display?.release() } catch (_: Throwable) {}
            display = null
            try { reader?.close() } catch (_: Throwable) {}
            reader = null
            thread.quitSafely()
        }
        try { sock.close() } catch (_: Exception) {}
    }
}
