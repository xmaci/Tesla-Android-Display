package com.xmaci.tesladroid

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Black box": the last [KEEP_MS] of events (connections, stats every second, helper, errors, freezes
 * the car page reports). A ring: older lines are dropped as new ones come. Saved to one file (overwritten)
 * every 30 s and on important events, so it survives an app crash. "Send report" saves it to Downloads
 * and opens the share sheet.
 */
object DiagLog {
    private const val KEEP_MS = 30 * 60 * 1000L
    private const val MAX_LINES = 6000
    private const val FILE = "diag.log"

    private class Line(val at: Long, val text: String)

    private val lines = ArrayDeque<Line>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var appCtx: Context? = null
    private var lastSave = 0L
    @Volatile private var dirty = false

    /** Loads what the previous run left (only the last 30 minutes). */
    @Synchronized fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        val f = File(ctx.filesDir, FILE)
        val cutoff = System.currentTimeMillis() - KEEP_MS
        if (f.exists()) try {
            f.readLines().forEach { l ->
                val at = l.substringBefore('\t').toLongOrNull() ?: return@forEach
                if (at >= cutoff) lines.addLast(Line(at, l.substringAfter('\t')))
            }
        } catch (_: Exception) {}
        val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
        add("app", "start v$ver · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
    }

    /** One event. [important] saves the file at once (crashes, freezes, helper problems). */
    fun add(tag: String, msg: String, important: Boolean = false) {
        val now = System.currentTimeMillis()
        val text = "${fmt.format(Date(now))} ${tag.padEnd(5)} ${msg.replace('\n', ' ').take(400)}"
        synchronized(this) {
            lines.addLast(Line(now, text))
            while (lines.size > MAX_LINES || (lines.isNotEmpty() && now - lines.first().at > KEEP_MS)) lines.removeFirst()
            dirty = true
        }
        val up = SystemClock.uptimeMillis()
        if (important || up - lastSave > 30_000) save()
    }

    @Synchronized fun save() {
        val ctx = appCtx ?: return
        if (!dirty) return
        lastSave = SystemClock.uptimeMillis()
        dirty = false
        try {
            // Written to a temp file and renamed: never a half-written log.
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.bufferedWriter().use { w -> for (l in lines) { w.write("${l.at}\t${l.text}\n") } }
            tmp.renameTo(File(ctx.filesDir, FILE))
        } catch (_: Exception) {}
    }

    @Synchronized private fun text(): String = lines.joinToString("\n") { it.text }

    /**
     * Saves the report to Downloads (TeslaDroid-report-<time>.txt) and returns a share Intent for it.
     * [extra] = state worth having at the top (versions, settings).
     */
    fun report(ctx: Context, extra: String): Intent? {
        add("app", "report requested", important = true)
        val name = "TeslaDroid-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val body = "TeslaDroid report · ${Date()}\n$extra\n\n— last 30 min —\n${text()}\n"
        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Надіслати звіт · Send report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { null }
    }
}
