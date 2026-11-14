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
 *   keep <on|off>             keep the phone from sleeping (screen wake lock) while the car screen runs
 *   phone <dim|undim>         locked with the power key: wake it (stays locked) at minimum brightness / restore
 * helper -> app, 16-byte header + body:
 *   type 1 (frame): w, h, length → RGBA rows, no padding (w*4 bytes each)
 *   type 2 (text):  length, 0, 0 → UTF-8 line ("OK <displayId> <flags>", "launch: …", "ERR …")
 */
class CarSession(private val sock: Socket, private val input: BufferedReader,
                 private var w: Int, private var h: Int, private var dpi: Int, private val deco: Boolean,
                 /** Mirror of the phone's own screen (display 0) instead of a separate car screen: no
                  *  MediaProjection, so no screen-recording chip/timer and no "Share screen" dialog. */
                 private val mirror: Boolean = false) {

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

    /** Display that touches and keys go to: the phone itself for a mirror. */
    private val inputDisplay get() = if (mirror) 0 else displayId

    private fun createMirror(): Boolean {
        val r = newReader(w, h)
        var made: VirtualDisplay? = null
        var fail: Throwable? = null
        val done = onMain(5000) {
            try {
                // Android 14+: DisplayManager.createVirtualDisplay(name, w, h, displayIdToMirror, surface) (hidden, static)
                val m = android.hardware.display.DisplayManager::class.java.getMethod("createVirtualDisplay",
                    String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, android.view.Surface::class.java)
                made = m.invoke(null, "TeslaDroid mirror", w, h, 0, r.surface) as VirtualDisplay?
            } catch (e: Throwable) { fail = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e }
        }
        val vd = made
        if (!done || vd == null) {
            r.close()
            text("ERR display: mirror: ${fail?.javaClass?.simpleName ?: "timeout"} ${fail?.message ?: ""}")
            return false
        }
        display = vd; reader = r; displayId = vd.display?.displayId ?: -1
        HelperMain.log("mirror $displayId ${w}x$h")
        text("OK $displayId mirror ${w}x$h")
        return true
    }

    /** Car-screen pixels → phone pixels (the mirror is the phone screen fitted into w×h). */
    private fun toPhone(x: Float, y: Float): Pair<Float, Float> {
        val pt = android.graphics.Point()
        @Suppress("DEPRECATION") ShellEnv.displayManager.getDisplay(0).getRealSize(pt)
        val sc = minOf(w / pt.x.toFloat(), h / pt.y.toFloat())
        val ox = (w - pt.x * sc) / 2; val oy = (h - pt.y * sc) / 2
        return Pair(((x - ox) / sc).coerceIn(0f, pt.x - 1f), ((y - oy) / sc).coerceIn(0f, pt.y - 1f))
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
        if (mirror) return createMirror()
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
                val ime = ShellEnv.imeLocal(displayId)
                HelperMain.log("display $displayId ($name), $ime")
                text("OK $displayId $name ${w}x$h@$dpi, $ime")
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
            // keep on|off: the phone must not sleep while the car screen runs — Android stops drawing every
            // display, ours too, when the phone sleeps. A screen wake lock held by the shell user.
            "keep" -> { keepAwake(p[1] == "on"); text("power: keep awake ${p[1]}") }
            // dim: the phone was locked with the power key — wake it (stays locked) at minimum brightness.
            // undim: unlocked by the user — brightness back. (Replaces switching the panel off, which made
            // touch fail and the screen flash on some phones.)
            "phone" -> when (p[1]) {
                "dim" -> { val w = Panel.wake(); dim(true); text("power: wake ${if (w) "ok" else "refused"}, dimmed") }
                "undim" -> { dim(false); text("power: brightness restored") }
                else -> { if (panelOff) { Panel.set(true); panelOff = false }; text("power: panel on") }
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
        val (px, py) = if (mirror) toPhone(x, y) else Pair(x.coerceIn(0f, w - 1f), y.coerceIn(0f, h - 1f))
        val ev = MotionEvent.obtain(downTime, now, action, px, py, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        val ok = try { ShellEnv.inject(ev, inputDisplay) } catch (t: Throwable) { text("ERR inject: ${t.javaClass.simpleName}: ${t.message}"); false }
        ev.recycle()
        if (kind == "u") text("touch: " + (if (ok) "ok" else "refused"))
    }

    private fun key(code: Int) {
        val now = SystemClock.uptimeMillis()
        for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, a, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            ShellEnv.inject(ev, inputDisplay)
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

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var savedBrightness: Pair<String, String>? = null      // mode, level before dimming

    @Suppress("DEPRECATION")
    private fun keepAwake(on: Boolean) {
        try {
            if (on && wakeLock == null) {
                val pm = ShellEnv.context.getSystemService(android.os.PowerManager::class.java)
                wakeLock = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK, "tesladroid:car").also { it.acquire() }
            } else if (!on) { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
        } catch (t: Throwable) { text("ERR keep awake: ${t.javaClass.simpleName} ${t.message}") }
    }

    private fun sh(vararg cmd: String): String = try {
        val pr = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        pr.inputStream.bufferedReader().readText().trim().also { pr.waitFor() }
    } catch (_: Exception) { "" }

    private fun dim(on: Boolean) {
        if (on) {
            if (savedBrightness == null) savedBrightness = Pair(
                sh("settings", "get", "system", "screen_brightness_mode"), sh("settings", "get", "system", "screen_brightness"))
            sh("settings", "put", "system", "screen_brightness_mode", "0")
            sh("settings", "put", "system", "screen_brightness", "1")
        } else savedBrightness?.let { (mode, level) ->
            if (level.toIntOrNull() != null) sh("settings", "put", "system", "screen_brightness", level)
            if (mode.toIntOrNull() != null) sh("settings", "put", "system", "screen_brightness_mode", mode)
            savedBrightness = null
        }
    }

    private fun close() {
        closed = true
        if (!released.compareAndSet(false, true)) return
        if (panelOff) { Panel.set(true); panelOff = false }   // never leave the phone with a dark panel
        dim(false)                                           // nor dimmed
        keepAwake(false)
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
