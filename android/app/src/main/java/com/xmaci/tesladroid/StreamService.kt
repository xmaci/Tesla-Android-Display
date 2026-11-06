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
 *   car -> phone  "cfg:<width>:<quality>:<window>:<partial 1|0|m>:<max fps, 0 = no cap, a = follow car load>:<crop rows, 0 = none>"
 *                 frame width, JPEG quality, frames in flight, sub-rects ok (m = MJPEG view), MJPEG rate
 *                 "load:<ms>"               car page lag (worst timer delay in the last second)
 *                 "prof:<json>"             5 s profile from the car page
 *                 "full"                    car lost its picture (resized canvas): send a complete frame
 *                 "ack:<seq>:<decodeMs>:<drawMs>:<waitMs>:<shown>"   frame handled (shown=0: skipped, newer one queued)
 *                 "t:<d|m|u>:<x>:<y>"       touch, x/y normalised 0..1
 *                 "k:<back|home|recents|notifications>"
 *                 "mode:<phone|car>"         mirror the phone, or a car-shaped screen of its own (helper needed)
 *                 "vp:<w>:<h>"               car browser area in CSS px (shape of the car screen)
 *                 "launch:<pkg/cls>"         open an app on the car screen
 *   phone -> car  binary: 20-byte header (seq u32, frame w,h u16, encMs u16, q u16, rect x,y,w,h u16) + JPEG
 *                 of that rectangle only (partial update) or of the whole frame
 *                 "ti:<1|0|x>" touch injected / failed / no touch service
 *                 "s:{json}" stats once per second
 */
class StreamService : Service(), CarServer.Listener, CarDisplay.Listener {

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
        /** Last 5 s profile from the car: one-line summary + where the JSON was saved. */
        @Volatile var lastProfile = ""
            private set
        /** Car-shaped screen: step-by-step state, "" when not in use. */
        @Volatile var carStatus = ""
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

    /** MJPEG frame cap (0 = none). Old WebKit may restart decoding on every new part and never paint
     *  if parts arrive faster than it can draw; a cap leaves it time to show each frame. */
    @Volatile private var maxFps = 20
    /** Auto pacing (maxFps < 0): the car reports how late its page timers fire ("load"); the MJPEG rate
     *  follows so the browser is never fed more frames than it can decode and paint. MCU1 measured: at
     *  432×960 × 13 fps its page froze for 1.3–1.5 s at a time. */
    @Volatile private var autoFps = 12.0
    @Volatile private var carLag = 0
    @Volatile private var lastLoadAt = 0L
    @Volatile private var lastSilentStep = 0L
    private fun effectiveFps(): Int = if (maxFps < 0) autoFps.toInt() else maxFps
    @Synchronized private fun onCarLoad(lag: Int) {
        carLag = lag; lastLoadAt = SystemClock.uptimeMillis()
        if (maxFps >= 0) return
        // MCU1's page lags ~100-200 ms even at 2 fps (measured), so only real overload counts:
        // 432x960 x 13 fps froze it 1.3-1.5 s, 320x710 x 13 fps stayed near 200 ms and felt smooth.
        autoFps = when {
            lag > 600 -> autoFps * 0.6
            lag > 300 -> autoFps * 0.85
            lag < 200 -> autoFps + 1
            else -> autoFps
        }.coerceIn(5.0, 20.0)
    }
    /** No load report for 2.5 s = the car page is frozen: slow down without waiting for it. */
    @Synchronized private fun checkCarSilent(now: Long) {
        if (maxFps >= 0 || !mjpegMode || lastLoadAt == 0L) return
        if (now - lastLoadAt > 2500 && now - lastSilentStep > 1000) { autoFps = (autoFps * 0.7).coerceAtLeast(5.0); lastSilentStep = now }
    }
    @Volatile private var quality = 50

    // Newest captured frame (guarded by lock)
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = Object() as java.lang.Object
    private var frame: Bitmap? = null          // padded to the buffer's row stride
    private var frameW = 0
    private var frameH = 0
    private var captureSeq = 0L
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
    @Volatile private var lastCfgAt = 0L            // when the car last sent its settings
    /** Rows the car can show without scaling (1:1 views), 0 = whole frame. In 1:1 the browser area is
     *  ~650 rows tall but the portrait frame 960: a third of every frame was decoded and never seen. */
    @Volatile private var cropReq = 0
    /** Fraction of the captured height actually sent (touch y is relative to what the car shows). */
    @Volatile private var sentFrac = 1f
    // Car-shaped screen (helper's virtual display) instead of the phone mirror
    @Volatile private var carMode = false
    @Volatile private var car: CarDisplay? = null
    @Volatile private var vpW = 0
    @Volatile private var vpH = 0
    private var carLaunch = ""

    private fun sendH(fh: Int): Int = if (carMode) fh else if (cropReq in 64 until fh) (cropReq / 2) * 2 else fh
    private val mjOpen = java.util.concurrent.atomic.AtomicInteger(0)   // MJPEG streams being sent now
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

        // Phone rotated (e.g. Waze in landscape): follow it, so a landscape screen fills the car's wide
        // browser area instead of being letterboxed into a portrait frame.
        getSystemService(DisplayManager::class.java).registerDisplayListener(rotationListener, captureHandler)

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

    private val rotationListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != Display.DEFAULT_DISPLAY || carMode) return
            val oldW = screenW; val oldH = screenH
            readScreenSize()
            if ((oldW > oldH) != (screenW > screenH)) {
                Log.i(TAG, "rotated: ${screenW}x$screenH")
                forceFull = true
                resize(wantWidth)
            }
        }
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
        if (carMode) { car?.let { val (w, h, d) = carSize(); it.resize(w, h, d) }; return }
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
        if (carMode) { img.close(); return }
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
                // Forget frames whose ack never came, so the window cannot jam. The car acks every frame it
                // receives (shown, skipped or failed), so this is only a safety net: it was 2 s, and MCU1
                // often needs longer in canvas views — the phone then sent more and the car's queue grew to
                // seconds (profile: queue p95 2.8 s).
                val it = inFlight.entries.iterator()
                while (it.hasNext()) if (now - it.next().value > 10000) it.remove()
                val c = conn
                if (mjpegMode || c == null || !c.open || inFlight.size >= window || captureSeq == sentCaptureSeq || frame == null) {
                    lock.wait(50)
                } else {
                    val ch = sendH(frameH)
                    crop = Bitmap.createBitmap(frame!!, 0, 0, frameW, ch)
                    sentFrac = ch.toFloat() / frameH
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
            var rects: List<IntArray> = if (needFull) listOf(intArrayOf(0, 0, fw, fh))
                else FrameDiff.changedRects(cur, prev!!, fw, fh)
            if (rects.isEmpty()) {
                // Nothing visible changed: send nothing, free the slot.
                synchronized(lock) { inFlight.remove(seq); stIdle++ }
                spare = cur
                full.recycle()
                continue
            }
            // Large total change or a stale picture: send the whole frame (cleans JPEG seams too).
            val total = rects.sumOf { it[2].toLong() * it[3] }
            if (total > fw.toLong() * fh * 6 / 10 || now - lastFullAt > 6000) rects = listOf(intArrayOf(0, 0, fw, fh))
            if (rects.size == 1 && rects[0][2] == fw && rects[0][3] == fh) { lastFullAt = now; forceFull = false }
            spare = shown
            shown = cur; shownW = fw; shownH = fh

            // One message per rectangle (typing: keyboard strip + text line, not the screen between).
            // Quality field bit 15 = "more rectangles of this capture follow".
            var sentArea = 0L
            for ((i, r) in rects.withIndex()) {
                val (rx, ry, rw, rh) = r.toList()
                val mseq = if (i == 0) seq else synchronized(lock) { (++sendSeq).also { inFlight[it] = now } }
                val part = if (rw == fw && rh == fh) full else Bitmap.createBitmap(full, rx, ry, rw, rh)
                val t0 = SystemClock.uptimeMillis()
                out.reset()
                part.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val enc = (SystemClock.uptimeMillis() - t0).toInt()
                val jpeg = out.toByteArray()
                if (part !== full) part.recycle()
                val more = if (i < rects.size - 1) 0x8000 else 0
                // seq u32 · frame w,h u16 · enc ms u16 · quality|more u16 · rect x,y,w,h u16  (20 bytes)
                val header = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
                    .putInt(mseq.toInt()).putShort(fw.toShort()).putShort(fh.toShort())
                    .putShort(enc.coerceAtMost(65535).toShort()).putShort((quality or more).toShort())
                    .putShort(rx.toShort()).putShort(ry.toShort()).putShort(rw.toShort()).putShort(rh.toShort())
                    .array()
                val ok = conn?.sendBinary(header, jpeg) ?: false
                synchronized(lock) {
                    if (!ok) { inFlight.remove(mseq); forceFull = true } else inFlight[mseq] = SystemClock.uptimeMillis()
                    stBytes += jpeg.size
                    stEnc += enc
                }
                sentArea += rw.toLong() * rh
                if (!ok) break
            }
            full.recycle()
            val areaPm = (sentArea * 1000 / (fw.toLong() * fh)).toInt()
            synchronized(lock) {
                stSent++
                stArea += areaPm
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
                "\"area\":%d,\"idle\":%d,\"tok\":%d,\"tfail\":%d,\"pace\":%d,\"lag\":%d,\"mjs\":%d,\"cd\":%s}").format(
                java.util.Locale.US, stFrames / secs, lat, (stBytes / sent / 1024), stEnc / sent, dec, draw, wait,
                (lat - dec - draw - wait).coerceAtLeast(0), stCaptured / secs, frameW, sendH(frameH), quality, window,
                TouchService.instance != null, stArea / sent / 10, stIdle, touchOk, touchFail,
                if (mjpegMode) effectiveFps() else 0, carLag, mjOpen.get(),
                org.json.JSONObject.quote(car?.status?.also { carStatus = it } ?: ""))
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

    // ---------- MJPEG: frames decoded natively by the car browser, no JavaScript per frame ----------

    /**
     * GET /mjpeg -> multipart/x-mixed-replace stream of JPEGs for an <img>.
     * Pacing is TCP backpressure: a small send buffer makes write() block while the car is
     * still busy, and each loop sends only the newest capture, so frames never pile up.
     */
    override fun onHttp(path: String, sock: java.net.Socket, out: java.io.OutputStream): Boolean {
        if (path == "/apps") { sendApps(out); return true }
        if (path != "/mjpeg") return false
        val gen = ++mjpegGen
        val startedAt = SystemClock.uptimeMillis()
        mjOpen.incrementAndGet()
        lastLoadAt = startedAt                          // fresh stream: give the car time to report
        try {
            sock.tcpNoDelay = true
            sock.sendBufferSize = 32 * 1024
            sock.soTimeout = 0
            // The boundary goes AFTER each frame, not before it: browsers treat a part as complete only
            // when they see the next boundary. With the boundary in front, every frame was shown one frame
            // late, and the last change on a still screen waited for the 2 s resend.
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=tdframe\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n--tdframe\r\n").toByteArray())
            out.flush()
            val buf = ByteArrayOutputStream(256 * 1024)
            var lastSent = -1L
            var lastWrite = 0L
            while (running && gen == mjpegGen && !sock.isClosed) {
                // The car switched to a canvas view: end this stream. Old WebKit may keep a hidden
                // multipart <img> loading, and decoding it in the background starved the canvas views
                // (1:1 fell to ~1 fps on MCU1 after MJPEG was added).
                if (!mjpegMode && lastCfgAt > startedAt) break
                var copy: Bitmap? = null
                synchronized(lock) {
                    val idle = SystemClock.uptimeMillis() - lastWrite
                    // New capture, or resend the current one every 2 s (keeps the stream alive).
                    if (frame != null && (captureSeq != lastSent || idle > 2000)) {
                        val ch = sendH(frameH)
                        copy = Bitmap.createBitmap(frame!!, 0, 0, frameW, ch)
                        sentFrac = ch.toFloat() / frameH
                        lastSent = captureSeq
                    } else lock.wait(40)
                }
                val bmp = copy ?: continue
                var changed = 1000
                run {
                    val w = bmp.width; val h = bmp.height
                    val cur = mjSpare?.takeIf { it.size == w * h } ?: IntArray(w * h)
                    bmp.getPixels(cur, 0, w, 0, 0, w, h)
                    val prev = mjPrev
                    val area = if (prev == null || prev.size != cur.size) 1000 else
                        FrameDiff.changedRect(cur, prev, w, h)?.let { r -> (r[2].toLong() * r[3] * 1000 / (w.toLong() * h)).toInt() } ?: 0
                    mjSpare = prev; mjPrev = cur
                    changed = area
                }
                val t0 = SystemClock.uptimeMillis()
                buf.reset()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, buf)
                bmp.recycle()
                val enc = (SystemClock.uptimeMillis() - t0).toInt()
                val tw = SystemClock.uptimeMillis()
                out.write(("Content-Type: image/jpeg\r\nContent-Length: ${buf.size()}\r\n\r\n").toByteArray())
                buf.writeTo(out)
                out.write("\r\n--tdframe\r\n".toByteArray())          // closes this part at once
                out.flush()                                      // blocks while the car is behind
                lastWrite = SystemClock.uptimeMillis()
                synchronized(lock) {
                    stFrames++
                    stBytes += buf.size()
                    stEnc += enc
                    stSent++
                    stArea += changed                             // real change, so "upd" shows if the phone screen changes
                    stLatency += lastWrite - tw                  // time blocked = how far the car lags
                    stDecode += 0
                }
                maybeStats()
                checkCarSilent(SystemClock.uptimeMillis())
                val cap = effectiveFps()
                if (cap > 0) {
                    val left = 1000L / cap - (SystemClock.uptimeMillis() - t0)
                    if (left > 0) Thread.sleep(left)
                }
            }
        } catch (_: Exception) {
        } finally {
            mjOpen.decrementAndGet()
            try { sock.close() } catch (_: Exception) {}
        }
        return true
    }

    /** GET /apps -> [{"n":"Maps","c":"pkg/cls"}, …]: apps that can be opened on the car screen. */
    private fun sendApps(out: java.io.OutputStream) {
        val pm = packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(pm).toString() to "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .sortedBy { it.first.lowercase() }
        val arr = org.json.JSONArray()
        for ((n, c) in list) arr.put(org.json.JSONObject().put("n", n).put("c", c))
        val body = arr.toString().toByteArray(Charsets.UTF_8)
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
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
                lastCfgAt = SystemClock.uptimeMillis()
                forceFull = true
                p.getOrNull(5)?.let { maxFps = if (it == "a") -1 else (it.toIntOrNull() ?: 15).coerceIn(0, 60) }
                cropReq = p.getOrNull(6)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                p.getOrNull(1)?.toIntOrNull()?.let {
                    if (it != wantWidth) { wantWidth = it; resize(it) }
                }
                synchronized(lock) { sentCaptureSeq = 0; lock.notifyAll() }
            }
            "full" -> synchronized(lock) { forceFull = true; sentCaptureSeq = 0; lock.notifyAll() }
            "prof" -> { val json = text.substring(5); Thread({ saveProfile(json) }, "profile-save").start() }
            "load" -> p.getOrNull(1)?.toIntOrNull()?.let { onCarLoad(it) }
            "t" -> touch(conn, p)
            "k" -> { val k = p.getOrNull(1) ?: ""; val c = car; if (carMode && c != null && k != "notifications") c.key(k) else TouchService.instance?.key(k) }
            "mode" -> if (p.getOrNull(1) == "car") startCar() else stopCar()
            "vp" -> {
                val w = p.getOrNull(1)?.toIntOrNull() ?: 0; val h = p.getOrNull(2)?.toIntOrNull() ?: 0
                if (w >= 100 && h >= 100 && (w != vpW || h != vpH)) { vpW = w; vpH = h; if (carMode) resize(wantWidth) }
            }
            "launch" -> { val c = text.substring(7).trim(); carLaunch = c; car?.launch(c) }
        }
    }

    /** Saves a car profile JSON to Downloads (TeslaDroid-prof-<time>.json) and keeps a summary for the app. */
    private fun saveProfile(json: String) {
        val name = "TeslaDroid-prof-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".json"
        val summary = try {
            val o = org.json.JSONObject(json)
            "${o.optString("view")} ${o.optInt("w")}×${o.optInt("h")} · ${o.optDouble("fps")} fps · ${o.optString("verdict").replace('\n', ' ')}"
        } catch (e: Exception) { "unreadable profile (${json.length} chars)" }
        val where = try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { u -> contentResolver.openOutputStream(u)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
            "Downloads/$name"
        } catch (e: Exception) {
            java.io.File(filesDir, name).writeText(json); "app files/$name"
        }
        lastProfile = "$summary\n→ $where"
        Log.i(TAG, "profile saved: $where")
    }

    // ---------- car-shaped screen ----------

    /** Car screen size: the car's frame width, the shape of its browser area (800×480 until it reports). */
    private fun carSize(): Triple<Int, Int, Int> {
        // At least 640 px: MCU1's default 320 would give an unusable ~320×190 screen; 640×370 is still
        // fewer pixels than the 432×960 phone frame MCU1 already handles.
        val w = (wantWidth.coerceIn(640, 1280) / 2) * 2
        val aspect = if (vpW > 0 && vpH > 0) vpH.toFloat() / vpW else 0.6f
        val h = ((w * aspect).toInt().coerceIn(200, 1600) / 2) * 2
        val dpi = (w / 5).coerceIn(110, 240)          // 800 px → 160 dpi: an 800×480 dp tablet-like layout
        return Triple(w, h, dpi)
    }

    @Synchronized private fun startCar() {
        car?.let { if (it.status.startsWith("✗")) stopCar() else return }   // a failed attempt is retried
        carMode = true
        captureHandler.post { display?.surface = null }      // pause the phone mirror while the car screen runs
        val (w, h, d) = carSize()
        car = CarDisplay(this, this).also { it.start(w, h, d, true) }
        synchronized(lock) { forceFull = true; sentCaptureSeq = 0 }
        carStatus = "starting"
    }

    @Synchronized private fun stopCar() {
        val c = car ?: run { carMode = false; return }
        car = null
        c.stop()
        carMode = false
        carStatus = ""
        captureHandler.post { display?.surface = reader?.surface }
        synchronized(lock) { forceFull = true; sentCaptureSeq = 0; lock.notifyAll() }
    }

    override fun onCarFrame(px: ByteArray, w: Int, h: Int) {
        if (!carMode) return
        synchronized(lock) {
            var b = frame
            if (b == null || b.width != w || b.height != h) {
                b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                frame = b
                forceFull = true
            }
            b.copyPixelsFromBuffer(ByteBuffer.wrap(px))
            frameW = w; frameH = h
            captureSeq++
            stCaptured++
            lock.notifyAll()
        }
    }

    override fun onCarText(line: String) {
        // The display is ready: open the last app again (the screen is new and empty without a launcher).
        if (line.startsWith("OK ") && carLaunch.isNotEmpty()) car?.launch(carLaunch)
        if (line.startsWith("launch:") || line.startsWith("ERR")) conn?.sendText("cd:" + line)
    }

    private fun touch(conn: CarServer.Conn, p: List<String>) {
        val c = car
        if (carMode && c != null) {
            val kind = p.getOrNull(1) ?: return
            val x = (p.getOrNull(2)?.toFloatOrNull() ?: return).coerceIn(0f, 1f) * c.w
            val y = (p.getOrNull(3)?.toFloatOrNull() ?: return).coerceIn(0f, 1f) * c.h
            c.touch(kind, x, y)
            if (kind == "u") { touchOk++; conn.sendText("ti:1") }
            return
        }
        val x = (p.getOrNull(2)?.toFloatOrNull() ?: return).coerceIn(0f, 1f) * screenW
        val y = (p.getOrNull(3)?.toFloatOrNull() ?: return).coerceIn(0f, 1f) * sentFrac * screenH
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
        stopCar()
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
        try { getSystemService(DisplayManager::class.java).unregisterDisplayListener(rotationListener) } catch (_: Exception) {}
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
