package com.xmaci.tesladroid

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * M1a: mirrors the phone screen to the car as JPEG frames.
 *
 * Flow control is the whole point (MCU1 decodes an 800x480 JPEG in ~76 ms):
 *  - at most WINDOW frames (default 2) are in flight; the next goes out only after an "ack"
 *  - the capture keeps only the newest screen; older ones are dropped, never queued
 * so delay stays at about one frame instead of growing (the 15 s seen with other apps).
 *
 * Protocol (WebSocket /ws)
 *   car -> phone  "cfg:<width>:<quality>:<window>:<partial 1|0|m>:<auto 1|0>"   frame width, JPEG quality,
 *                 frames in flight, sub-rects ok (m = MJPEG view), adaptive resolution on/off
 *                 "full"                    car lost its picture (resized canvas): send a complete frame
 *                 "ack:<seq>:<decodeMs>:<drawMs>:<waitMs>:<shown>"   frame handled (shown=0: skipped, newer one queued)
 *                 "t:<d|m|u>:<x>:<y>"       touch, x/y normalised 0..1
 *                 "k:<back|home|recents|notifications>"
 *   phone -> car  binary: 20-byte header (seq u32, frame w,h u16, encMs u16, q u16, rect x,y,w,h u16) + JPEG
 *                 of that rectangle only (partial update) or of the whole frame
 *                 "ti:<1|0|x>" touch injected / failed / no touch service
 *                 "s:{json}" stats once per second
 */
class StreamService : Service(), CarServer.Listener {

    companion object {
        private const val TAG = "TeslaDroid"
        private const val CHANNEL = "stream"
        private const val NOTIF_ID = 1
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"

        @Volatile var running = false
            private set
        @Volatile var status = "stopped"
            private set
        @Volatile var serverPort = 0
            private set
        @Volatile var carConnected = false
            private set
        @Volatile var statsLine = ""
            private set
        @Volatile var instanceServer: CarServer? = null
            private set

        fun start(ctx: Context, code: Int, data: Intent) {
            ctx.startForegroundService(Intent(ctx, StreamService::class.java)
                .putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, StreamService::class.java).setAction(ACTION_STOP))
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val captureThread = HandlerThread("capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private var server: CarServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var screenW = 1080
    private var screenH = 2400
    private var dpi = 420

    // Requested by the car
    @Volatile private var wantWidth = 432

    // Adaptive resolution: while most of the screen keeps changing (maps, scrolling), capture at
    // MOTION_SCALE of the chosen width — MCU1 decode cost follows pixel count — and return to full
    // sharpness once the screen calms down.
    @Volatile private var autoRes = true
    @Volatile private var motion = false
    private var motionStreak = 0
    private var calmSince = 0L
    private var lastResizeAt = 0L
    private val motionScale = 0.6
    @Volatile private var quality = 50

    // Newest captured frame (guarded by lock)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object() as java.lang.Object
    private var frame: Bitmap? = null          // padded to the buffer's row stride
    private var frameW = 0
    private var frameH = 0
    private var captureSeq = 0L
    @Volatile private var lastCaptureAt = 0L
    private var sentCaptureSeq = 0L

    // In-flight tracking
    @Volatile private var conn: CarServer.Conn? = null
    private val inFlight = LinkedHashMap<Long, Long>()   // seq -> send uptime ms, oldest first
    @Volatile private var window = 2
    private var sendSeq = 0L

    // Partial updates: what the car currently shows (pixels of everything sent so far)
    @Volatile private var partialOk = true          // car can draw sub-rectangles (not in 'img' mode)
    @Volatile private var mjpegMode = false         // car shows the /mjpeg stream: WebSocket sends no frames
    @Volatile private var mjpegGen = 0              // newest /mjpeg client wins
    @Volatile private var forceFull = true          // next frame must be complete (connect, resize, car request)
    private var shown: IntArray? = null             // car-side picture, frameW*frameH
    private var spare: IntArray? = null             // reused buffer for the next capture
    private var shownW = 0
    private var shownH = 0
    private var lastFullAt = 0L

    // Stats (reset each second)
    private var stFrames = 0
    private var stBytes = 0L
    private var stLatency = 0L
    private var stEnc = 0L
    private var stDecode = 0L
    private var stDraw = 0L
    private var stWait = 0L
    private var stCaptured = 0
    private var stArea = 0L          // sum of sent area in per-mille of the full frame
    private var stSent = 0
    private var stIdle = 0           // captures with no visible change (nothing sent)
    private var lastStats = 0L
    @Volatile private var touchOk = 0
    @Volatile private var touchFail = 0

    // Touch stroke being recorded
    private val stroke = ArrayList<Pair<Float, Float>>()
    private var strokeStart = 0L

    private var sender: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(); stopSelf(); return START_NOT_STICKY
        }
        // Android 14+: the mediaProjection foreground service must be running first.
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null || running) {
            if (!running) { status = "no screen-capture permission"; stopSelf() }
            return START_NOT_STICKY
        }
        try {
            begin(code, data)
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
            status = "error: ${t.message}"
            shutdown(); stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent) {
        readScreenSize()
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data) ?: throw IllegalStateException("projection denied")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                status = "screen capture stopped"
                shutdown(); stopSelf()
            }
        }, captureHandler)

        val (w, h) = frameSize(wantWidth)
        val r = newReader(w, h)
        reader = r
        display = mp.createVirtualDisplay("TeslaDroid", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, captureHandler)

        server = CarServer({ name -> try { assets.open(name).use { it.readBytes() } } catch (_: java.io.IOException) { null } },
            this, { Log.i(TAG, it) }).also {
            instanceServer = it
            if (!it.start()) throw IllegalStateException("server: ${it.lastError}")
            serverPort = it.port
        }
        @Suppress("DEPRECATION")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "tesladroid:stream")
            .also { it.acquire(6 * 60 * 60 * 1000L) }

        running = true
        status = "streaming"
        sender = Thread({ sendLoop() }, "sender").apply { start() }
    }

    @Suppress("DEPRECATION")
    private fun readScreenSize() {
        val d = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        d.getRealSize(p)
        screenW = p.x; screenH = p.y
        dpi = resources.displayMetrics.densityDpi
    }

    private fun frameSize(width: Int): Pair<Int, Int> {
        val w = (width.coerceIn(240, screenW) / 2) * 2
        val h = ((w.toLong() * screenH / screenW).toInt() / 2) * 2
        return Pair(w, h)
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ onImage(it) }, captureHandler)
        return r
    }

    /** Car asked for a different width: resize the virtual display onto a new reader. */
    private fun resize(width: Int) {
        captureHandler.post {
            val (w, h) = frameSize(width)
            if (w == frameW && h == frameH) return@post
            val old = reader
            val r = newReader(w, h)
            reader = r
            display?.resize(w, h, dpi)
            display?.surface = r.surface
            old?.close()
            Log.i(TAG, "frame size ${w}x$h")
        }
    }

    private fun onImage(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return
        try {
            val plane = img.planes[0]
            val padW = plane.rowStride / plane.pixelStride
            synchronized(lock) {
                var b = frame
                if (b == null || b.width != padW || b.height != img.height) {
                    b = Bitmap.createBitmap(padW, img.height, Bitmap.Config.ARGB_8888)
                    frame = b
                }
                plane.buffer.rewind()
                b.copyPixelsFromBuffer(plane.buffer)
                frameW = img.width
                frameH = img.height
                captureSeq++
                stCaptured++
                lastCaptureAt = SystemClock.uptimeMillis()
                lock.notifyAll()
            }
        } finally {
            img.close()
        }
    }

    // ---------- sender: up to `window` frames in flight, always the newest screen ----------

    private fun sendLoop() {
        val out = ByteArrayOutputStream(256 * 1024)
        while (running) {
            var crop: Bitmap? = null
            var seq = 0L
            var captured = 0L
            synchronized(lock) {
                val now = SystemClock.uptimeMillis()
                // Forget frames whose ack never came (2 s), so the window cannot jam.
                val it = inFlight.entries.iterator()
                while (it.hasNext()) if (now - it.next().value > 2000) it.remove()
                val c = conn
                if (mjpegMode || c == null || !c.open || inFlight.size >= window || captureSeq == sentCaptureSeq || frame == null) {
                    checkCalm(now)
                    lock.wait(50)
                } else {
                    crop = Bitmap.createBitmap(frame!!, 0, 0, frameW, frameH)
                    sentCaptureSeq = captureSeq
                    seq = ++sendSeq
                    captured = now
                    inFlight[seq] = now
                }
            }
            val full = crop ?: continue
            val fw = full.width; val fh = full.height
            val cur = spare?.takeIf { it.size == fw * fh } ?: IntArray(fw * fh)
            spare = null
            full.getPixels(cur, 0, fw, 0, 0, fw, fh)

            // What changed since the picture the car already has?
            val now = SystemClock.uptimeMillis()
            val prev = shown
            val needFull = forceFull || !partialOk || prev == null || shownW != fw || shownH != fh
            val rect: IntArray? = if (needFull) intArrayOf(0, 0, fw, fh)
                else FrameDiff.changedRect(cur, prev!!, fw, fh)
            if (rect == null) {
                // Nothing visible changed: send nothing, free the slot.
                synchronized(lock) { inFlight.remove(seq); stIdle++ }
                spare = cur
                full.recycle()
                continue
            }
            var (rx, ry, rw, rh) = rect.toList()
            // Large changes or a stale picture: send the whole frame (cleans JPEG seams too).
            if (rw * rh > fw * fh * 6 / 10 || now - lastFullAt > 6000) { rx = 0; ry = 0; rw = fw; rh = fh }
            if (rw == fw && rh == fh) { lastFullAt = now; forceFull = false }
            spare = shown
            shown = cur; shownW = fw; shownH = fh

            val part = if (rw == fw && rh == fh) full else Bitmap.createBitmap(full, rx, ry, rw, rh)
            val t0 = SystemClock.uptimeMillis()
            out.reset()
            part.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val enc = (SystemClock.uptimeMillis() - t0).toInt()
            val jpeg = out.toByteArray()
            // seq u32 · frame w,h u16 · enc ms u16 · quality u16 · rect x,y,w,h u16  (20 bytes)
            val header = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
                .putInt(seq.toInt()).putShort(fw.toShort()).putShort(fh.toShort())
                .putShort(enc.coerceAtMost(65535).toShort()).putShort(quality.toShort())
                .putShort(rx.toShort()).putShort(ry.toShort()).putShort(rw.toShort()).putShort(rh.toShort())
                .array()
            if (part !== full) part.recycle()
            full.recycle()
            val ok = conn?.sendBinary(header, jpeg) ?: false
            synchronized(lock) {
                if (!ok) { inFlight.remove(seq); forceFull = true } else inFlight[seq] = SystemClock.uptimeMillis()
                stBytes += jpeg.size
                stEnc += enc
                stSent++
                stArea += (rw.toLong() * rh * 1000) / (fw.toLong() * fh)
                noteChange(((rw.toLong() * rh * 1000) / (fw.toLong() * fh)).toInt(), SystemClock.uptimeMillis())
            }
            maybeStats()
        }
    }

    private fun maybeStats() {
        val now = SystemClock.uptimeMillis()
        if (now - lastStats < 1000) return
        val c = conn ?: return
        val json: String
        synchronized(lock) {
            val secs = if (lastStats == 0L) 1.0 else (now - lastStats) / 1000.0
            val n = stFrames.coerceAtLeast(1)
            // lat  = phone sent -> car drew (incl. waiting in the car's queue)
            // net  = lat minus the car's own wait/decode/draw time = network + WebSocket delivery
            val lat = stLatency / n; val dec = stDecode / n; val draw = stDraw / n; val wait = stWait / n
            val sent = stSent.coerceAtLeast(1)
            json = ("{\"fps\":%.1f,\"lat\":%d,\"kb\":%d,\"enc\":%d,\"dec\":%d,\"draw\":%d,\"wait\":%d," +
                "\"net\":%d,\"cap\":%.1f,\"w\":%d,\"h\":%d,\"q\":%d,\"win\":%d,\"touch\":%b," +
                "\"area\":%d,\"idle\":%d,\"tok\":%d,\"tfail\":%d,\"motion\":%b}").format(
                java.util.Locale.US, stFrames / secs, lat, (stBytes / sent / 1024), stEnc / sent, dec, draw, wait,
                (lat - dec - draw - wait).coerceAtLeast(0), stCaptured / secs, frameW, frameH, quality, window,
                TouchService.instance != null, stArea / sent / 10, stIdle, touchOk, touchFail, motion)
            statsLine = "%.1f fps · %d ms · %d KB".format(java.util.Locale.US, stFrames / secs, stLatency / n, stBytes / n / 1024)
            stFrames = 0; stBytes = 0; stLatency = 0; stEnc = 0; stDecode = 0; stDraw = 0; stWait = 0; stCaptured = 0
            stArea = 0; stSent = 0; stIdle = 0
            lastStats = now
        }
        c.sendText("s:$json")
    }

    // ---------- adaptive resolution ----------

    private var mjPrev: IntArray? = null
    private var mjSpare: IntArray? = null

    /** Called with the changed area (per-mille of the frame) of every frame sent. */
    @Synchronized private fun noteChange(areaPermille: Int, now: Long) {
        if (!autoRes || now - lastResizeAt < 800) return      // the frame after a resize is always full
        if (!motion) {
            motionStreak = if (areaPermille > 500) motionStreak + 1 else 0
            if (motionStreak >= 3) setMotion(true, now)
        } else {
            if (areaPermille < 200) { if (calmSince == 0L) calmSince = now } else calmSince = 0L
            checkCalm(now)
        }
    }

    /** Back to full sharpness after 1.5 s of small or no changes. */
    @Synchronized private fun checkCalm(now: Long) {
        if (!motion) return
        if (calmSince == 0L) { if (now - lastResizeAt > 1500 && now - lastChangeAt() > 1500) setMotion(false, now); return }
        if (now - calmSince > 1500) setMotion(false, now)
    }

    private fun lastChangeAt(): Long = lastCaptureAt

    private fun setMotion(on: Boolean, now: Long) {
        motion = on; motionStreak = 0; calmSince = 0L; lastResizeAt = now
        resize(if (on) (wantWidth * motionScale).toInt() else wantWidth)
    }

    // ---------- MJPEG: frames decoded natively by the car browser, no JavaScript per frame ----------

    /**
     * GET /mjpeg -> multipart/x-mixed-replace stream of JPEGs for an <img>.
     * Pacing is TCP backpressure: a small send buffer makes write() block while the car is
     * still busy, and each loop sends only the newest capture, so frames never pile up.
     */
    override fun onHttp(path: String, sock: java.net.Socket, out: java.io.OutputStream): Boolean {
        if (path != "/mjpeg") return false
        val gen = ++mjpegGen
        try {
            sock.tcpNoDelay = true
            sock.sendBufferSize = 32 * 1024
            sock.soTimeout = 0
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=tdframe\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            val buf = ByteArrayOutputStream(256 * 1024)
            var lastSent = -1L
            var lastWrite = 0L
            while (running && gen == mjpegGen && !sock.isClosed) {
                var copy: Bitmap? = null
                synchronized(lock) {
                    val idle = SystemClock.uptimeMillis() - lastWrite
                    // New capture, or resend the current one every 2 s (keeps the stream alive).
                    if (frame != null && (captureSeq != lastSent || idle > 2000)) {
                        copy = Bitmap.createBitmap(frame!!, 0, 0, frameW, frameH)
                        lastSent = captureSeq
                    } else { checkCalm(SystemClock.uptimeMillis()); lock.wait(40) }
                }
                val bmp = copy ?: continue
                run {
                    val w = bmp.width; val h = bmp.height
                    val cur = mjSpare?.takeIf { it.size == w * h } ?: IntArray(w * h)
                    bmp.getPixels(cur, 0, w, 0, 0, w, h)
                    val prev = mjPrev
                    val area = if (prev == null || prev.size != cur.size) 1000 else
                        FrameDiff.changedRect(cur, prev, w, h)?.let { r -> (r[2].toLong() * r[3] * 1000 / (w.toLong() * h)).toInt() } ?: 0
                    mjSpare = prev; mjPrev = cur
                    noteChange(area, SystemClock.uptimeMillis())
                }
                val t0 = SystemClock.uptimeMillis()
                buf.reset()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, buf)
                bmp.recycle()
                val enc = (SystemClock.uptimeMillis() - t0).toInt()
                val tw = SystemClock.uptimeMillis()
                out.write(("--tdframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${buf.size()}\r\n\r\n").toByteArray())
                buf.writeTo(out)
                out.write("\r\n".toByteArray())
                out.flush()                                      // blocks while the car is behind
                lastWrite = SystemClock.uptimeMillis()
                synchronized(lock) {
                    stFrames++
                    stBytes += buf.size()
                    stEnc += enc
                    stSent++
                    stArea += 1000
                    stLatency += lastWrite - tw                  // time blocked = how far the car lags
                    stDecode += 0
                }
                maybeStats()
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
        return true
    }

    // ---------- CarServer.Listener ----------

    override fun onOpen(conn: CarServer.Conn) {
        synchronized(lock) {
            this.conn = conn
            inFlight.clear()
            forceFull = true
            sentCaptureSeq = 0          // send the current screen right away
            lock.notifyAll()
        }
        carConnected = true
    }

    override fun onClose(conn: CarServer.Conn) {
        synchronized(lock) { if (this.conn === conn) { this.conn = null; carConnected = false } }
    }

    override fun onText(conn: CarServer.Conn, text: String) {
        val p = text.split(":")
        when (p[0]) {
            "ack" -> synchronized(lock) {
                // ack:<seq>:<decodeMs>:<drawMs>:<waitMs>:<shown 1|0>
                val seq = p.getOrNull(1)?.toLongOrNull() ?: return
                val sent = inFlight.remove(seq)
                val shown = p.getOrNull(5) != "0"
                if (sent != null && shown) {
                    stLatency += SystemClock.uptimeMillis() - sent
                    stDecode += p.getOrNull(2)?.toLongOrNull() ?: 0
                    stDraw += p.getOrNull(3)?.toLongOrNull() ?: 0
                    stWait += p.getOrNull(4)?.toLongOrNull() ?: 0
                    stFrames++
                }
                lock.notifyAll()
            }
            "cfg" -> {
                p.getOrNull(2)?.toIntOrNull()?.let { quality = it.coerceIn(20, 90) }
                p.getOrNull(3)?.toIntOrNull()?.let { window = it.coerceIn(1, 4) }
                p.getOrNull(4)?.let { partialOk = it == "1"; mjpegMode = it == "m" }
                forceFull = true
                p.getOrNull(5)?.let { val a = it != "0"; if (a != autoRes) { autoRes = a; if (!a && motion) setMotion(false, SystemClock.uptimeMillis()) } }
                p.getOrNull(1)?.toIntOrNull()?.let {
                    if (it != wantWidth) { wantWidth = it; motion = false; motionStreak = 0; lastResizeAt = SystemClock.uptimeMillis(); resize(it) }
                }
                synchronized(lock) { sentCaptureSeq = 0; lock.notifyAll() }
            }
            "full" -> synchronized(lock) { forceFull = true; sentCaptureSeq = 0; lock.notifyAll() }
            "t" -> touch(conn, p)
            "k" -> TouchService.instance?.key(p.getOrNull(1) ?: "")
        }
    }

    private fun touch(conn: CarServer.Conn, p: List<String>) {
        val x = (p.getOrNull(2)?.toFloatOrNull() ?: return).coerceIn(0f, 1f) * screenW
        val y = (p.getOrNull(3)?.toFloatOrNull() ?: return).coerceIn(0f, 1f) * screenH
        synchronized(stroke) {
            when (p.getOrNull(1)) {
                "d" -> { stroke.clear(); stroke.add(x to y); strokeStart = SystemClock.uptimeMillis() }
                "m" -> if (stroke.isNotEmpty() && stroke.size < 200) stroke.add(x to y)
                "u" -> {
                    if (stroke.isEmpty()) return
                    stroke.add(x to y)
                    val svc = TouchService.instance
                    val ok = svc?.stroke(ArrayList(stroke), SystemClock.uptimeMillis() - strokeStart) ?: false
                    if (ok) touchOk++ else touchFail++
                    // ti:<1 injected | 0 failed | x no touch service>
                    conn.sendText("ti:" + (if (svc == null) "x" else if (ok) "1" else "0"))
                    stroke.clear()
                }
            }
        }
    }

    // ---------- teardown ----------

    private fun shutdown() {
        running = false
        status = if (status == "streaming") "stopped" else status
        sender?.interrupt(); sender = null
        server?.stop(); server = null; instanceServer = null
        carConnected = false
        try { display?.release() } catch (_: Throwable) {}
        display = null
        try { reader?.close() } catch (_: Throwable) {}
        reader = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        synchronized(lock) { frame?.recycle(); frame = null; conn = null }
    }

    override fun onDestroy() {
        shutdown()
        captureThread.quitSafely()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("TeslaDroid")
            .setContentText("Трансляція на Tesla · Streaming to Tesla")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Стоп · Stop", stop).build())
            .setOngoing(true)
            .build()
    }
}
