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
 *  - at most ONE frame is in flight; the next goes out only after the car's "ack"
 *  - the capture keeps only the newest screen; older ones are dropped, never queued
 * so delay stays at about one frame instead of growing (the 15 s seen with other apps).
 *
 * Protocol (WebSocket /ws)
 *   car -> phone  "cfg:<width>:<quality>"   requested frame width (px) and JPEG quality
 *                 "ack:<seq>:<decodeMs>"    frame drawn
 *                 "t:<d|m|u>:<x>:<y>"       touch, x/y normalised 0..1
 *                 "k:<back|home|recents|notifications>"
 *   phone -> car  binary: 12-byte header (seq u32, w u16, h u16, encMs u16, q u16) + JPEG
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
    private var inFlightSeq = -1L
    private var inFlightSince = 0L
    private var sendSeq = 0L
    private val sentAt = HashMap<Long, Long>()   // seq -> capture uptime ms

    // Stats (reset each second)
    private var stFrames = 0
    private var stBytes = 0L
    private var stLatency = 0L
    private var stEnc = 0L
    private var stDecode = 0L
    private var stCaptured = 0
    private var lastStats = 0L

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
                lock.notifyAll()
            }
        } finally {
            img.close()
        }
    }

    // ---------- sender: one frame in flight, always the newest ----------

    private fun sendLoop() {
        val out = ByteArrayOutputStream(256 * 1024)
        while (running) {
            var crop: Bitmap? = null
            var seq = 0L
            var captured = 0L
            synchronized(lock) {
                val now = SystemClock.uptimeMillis()
                if (inFlightSeq >= 0 && now - inFlightSince > 2000) inFlightSeq = -1   // lost ack
                val c = conn
                if (c == null || !c.open || inFlightSeq >= 0 || captureSeq == sentCaptureSeq || frame == null) {
                    lock.wait(50)
                } else {
                    crop = Bitmap.createBitmap(frame!!, 0, 0, frameW, frameH)
                    sentCaptureSeq = captureSeq
                    seq = ++sendSeq
                    captured = now
                    inFlightSeq = seq
                    inFlightSince = now
                    sentAt[seq] = now
                    if (sentAt.size > 64) sentAt.keys.sortedDescending().drop(32).forEach { sentAt.remove(it) }
                }
            }
            val bmp = crop ?: continue
            val t0 = SystemClock.uptimeMillis()
            out.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val enc = (SystemClock.uptimeMillis() - t0).toInt()
            val jpeg = out.toByteArray()
            val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                .putInt(seq.toInt()).putShort(bmp.width.toShort()).putShort(bmp.height.toShort())
                .putShort(enc.coerceAtMost(65535).toShort()).putShort(quality.toShort()).array()
            bmp.recycle()
            val ok = conn?.sendBinary(header, jpeg) ?: false
            synchronized(lock) {
                if (!ok) inFlightSeq = -1
                stBytes += jpeg.size
                stEnc += enc
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
            json = "{\"fps\":%.1f,\"lat\":%d,\"kb\":%d,\"enc\":%d,\"dec\":%d,\"cap\":%.1f,\"w\":%d,\"h\":%d,\"q\":%d,\"touch\":%b}".format(
                java.util.Locale.US, stFrames / secs, stLatency / n, (stBytes / n / 1024), stEnc / n, stDecode / n,
                stCaptured / secs, frameW, frameH, quality, TouchService.instance != null)
            statsLine = "%.1f fps · %d ms · %d KB".format(java.util.Locale.US, stFrames / secs, stLatency / n, stBytes / n / 1024)
            stFrames = 0; stBytes = 0; stLatency = 0; stEnc = 0; stDecode = 0; stCaptured = 0
            lastStats = now
        }
        c.sendText("s:$json")
    }

    // ---------- CarServer.Listener ----------

    override fun onOpen(conn: CarServer.Conn) {
        synchronized(lock) {
            this.conn = conn
            inFlightSeq = -1
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
                val seq = p.getOrNull(1)?.toLongOrNull() ?: return
                val dec = p.getOrNull(2)?.toLongOrNull() ?: 0
                if (seq == inFlightSeq) inFlightSeq = -1
                sentAt.remove(seq)?.let { stLatency += SystemClock.uptimeMillis() - it }
                stDecode += dec
                stFrames++
                lock.notifyAll()
            }
            "cfg" -> {
                p.getOrNull(2)?.toIntOrNull()?.let { quality = it.coerceIn(20, 90) }
                p.getOrNull(1)?.toIntOrNull()?.let { if (it != wantWidth) { wantWidth = it; resize(it) } }
                synchronized(lock) { sentCaptureSeq = 0; lock.notifyAll() }
            }
            "t" -> touch(p)
            "k" -> TouchService.instance?.key(p.getOrNull(1) ?: "")
        }
    }

    private fun touch(p: List<String>) {
        val x = (p.getOrNull(2)?.toFloatOrNull() ?: return).coerceIn(0f, 1f) * screenW
        val y = (p.getOrNull(3)?.toFloatOrNull() ?: return).coerceIn(0f, 1f) * screenH
        synchronized(stroke) {
            when (p.getOrNull(1)) {
                "d" -> { stroke.clear(); stroke.add(x to y); strokeStart = SystemClock.uptimeMillis() }
                "m" -> if (stroke.isNotEmpty() && stroke.size < 200) stroke.add(x to y)
                "u" -> {
                    if (stroke.isEmpty()) return
                    stroke.add(x to y)
                    TouchService.instance?.stroke(ArrayList(stroke), SystemClock.uptimeMillis() - strokeStart)
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
