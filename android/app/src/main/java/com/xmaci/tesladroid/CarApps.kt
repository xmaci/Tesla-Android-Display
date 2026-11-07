package com.xmaci.tesladroid

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Favourites and recently opened apps for the car panel, plus app icons as PNG.
 * Stored on the phone, so the car page and the car-screen home (long press = pin) share them.
 */
object CarApps {
    private const val PREFS = "car_apps"
    private const val MAX_RECENT = 8

    /** Default favourites, in this order, if installed. Scan My Tesla is found by its name. */
    private val DEFAULT_PKGS = listOf(
        "com.waze", "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "com.netflix.mediaclient", "com.spotify.music")
    private val SMT_LABEL = Regex("scan\\s*my\\s*tesla", RegexOption.IGNORE_CASE)

    data class App(val label: String, val component: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** All apps that can be opened, by component "pkg/cls". */
    fun launchable(ctx: Context): Map<String, App> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .associate {
                val c = "${it.activityInfo.packageName}/${it.activityInfo.name}"
                c to App(it.loadLabel(pm).toString(), c)
            }
    }

    private fun load(ctx: Context, key: String): MutableList<String>? =
        prefs(ctx).getString(key, null)?.let { s -> s.split('\n').filter { it.isNotBlank() }.toMutableList() }

    private fun save(ctx: Context, key: String, list: List<String>) =
        prefs(ctx).edit().putString(key, list.joinToString("\n")).apply()

    fun favourites(ctx: Context): List<String> {
        load(ctx, "fav")?.let { return it }
        val apps = launchable(ctx).values
        val list = DEFAULT_PKGS.mapNotNull { p -> apps.firstOrNull { it.component.startsWith("$p/") }?.component }.toMutableList()
        apps.firstOrNull { SMT_LABEL.containsMatchIn(it.label) }?.let { list.add(it.component) }
        save(ctx, "fav", list)
        return list
    }

    fun isFavourite(ctx: Context, component: String) = component in favourites(ctx)

    /** Pins or unpins; returns true if it is now a favourite. */
    fun toggleFavourite(ctx: Context, component: String): Boolean {
        val list = favourites(ctx).toMutableList()
        val now = if (list.remove(component)) false else { list.add(component); true }
        save(ctx, "fav", list)
        return now
    }

    fun addRecent(ctx: Context, component: String) {
        if (component == StreamService.CAR_HOME) return
        val list = load(ctx, "rec") ?: mutableListOf()
        list.remove(component)
        list.add(0, component)
        save(ctx, "rec", list.take(MAX_RECENT))
    }

    /** GET /panel: favourites, recents (not repeating favourites), phone battery and network. */
    fun panelJson(ctx: Context): String {
        val apps = launchable(ctx)
        fun arr(list: List<String>) = JSONArray().also { a ->
            for (c in list) apps[c]?.let { a.put(JSONObject().put("n", it.label).put("c", c)) }
        }
        val fav = favourites(ctx)
        val rec = (load(ctx, "rec") ?: emptyList()).filter { it !in fav }
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { b ->
            val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && s > 0) l * 100 / s else -1
        } ?: -1
        val charging = bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false
        val net = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> ""
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "моб"
                else -> ""
            }
        } catch (_: Throwable) { "" }
        return JSONObject().put("fav", arr(fav)).put("rec", arr(rec))
            .put("bat", level).put("chg", charging).put("net", net).toString()
    }

    private val iconCache = HashMap<String, ByteArray>()

    /** App icon as a PNG, [size] px square; null if the app is unknown. */
    @Synchronized fun iconPng(ctx: Context, component: String, size: Int): ByteArray? {
        val key = "$component@$size"
        iconCache[key]?.let { return it }
        val pkg = component.substringBefore('/')
        val cls = component.substringAfter('/')
        val d = try {
            ctx.packageManager.getActivityIcon(android.content.ComponentName(pkg, cls))
        } catch (_: Throwable) { return null }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bmp.recycle()
        if (iconCache.size > 200) iconCache.clear()
        iconCache[key] = png
        return png
    }
}
