package com.xmaci.tesladroid.helper

import android.os.Bundle

/**
 * Brings an app's existing task to the car display (shell user, hidden APIs by reflection):
 * IActivityTaskManager.startActivityFromRecents(taskId, options) with the launch display and
 * full-screen windowing — what the recents screen does, so a locked phone does not get in the way.
 */
object Tasks {
    /** Why the last resume fell back to `am start` (shown in the black box). */
    @Volatile var lastError: String? = null

    private val atm: Any? by lazy {
        try { Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null) } catch (_: Throwable) { null }
    }

    private fun tasks(): List<Any> {
        val a = atm ?: return emptyList()
        val m = a.javaClass.methods.filter { it.name == "getTasks" }.minByOrNull { it.parameterTypes.size } ?: return emptyList()
        var firstInt = true
        val args = m.parameterTypes.map { t ->
            when (t) {
                Int::class.javaPrimitiveType -> if (firstInt) { firstInt = false; 100 } else -1
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        return (m.invoke(a, *args) as? List<Any>) ?: emptyList()
    }

    private fun windowingMode(ti: Any): Int = try {
        val cfg = ti.javaClass.getField("configuration").get(ti)!!
        val wc = cfg.javaClass.getField("windowConfiguration").get(cfg)!!
        wc.javaClass.getMethod("getWindowingMode").invoke(wc) as Int
    } catch (_: Throwable) { 0 }

    /** Package of the app on top of [displayId] (tasks come most recent first), or null. */
    fun top(displayId: Int): String? = try {
        tasks().firstOrNull { ti ->
            val c = ti.javaClass
            c.getField("displayId").getInt(ti) == displayId &&
                (try { c.getField("isVisible").getBoolean(ti) } catch (_: Throwable) { true }) &&
                windowingMode(ti) != 2          // not a picture-in-picture window (it floats over the real top)
        }?.let { ti -> (ti.javaClass.getField("topActivity").get(ti) as? android.content.ComponentName)?.packageName }
    } catch (_: Throwable) { null }

    /**
     * The orientation [pkg]'s top activity asks for right now (YouTube asks for landscape in full screen),
     * from `dumpsys activity activities <pkg>`: "requestedOrientation=SENSOR_LANDSCAPE" etc. "?" if unknown.
     */
    fun orientation(pkg: String): String = try {
        val pr = ProcessBuilder("dumpsys", "activity", "activities", pkg).redirectErrorStream(true).start()
        val line = pr.inputStream.bufferedReader().useLines { seq -> seq.firstOrNull { it.contains("requestedOrientation=") } }
        pr.destroy()
        line?.substringAfter("requestedOrientation=")?.trim()?.substringBefore(' ') ?: "?"
    } catch (_: Throwable) { "?" }

    /** taskId of [pkg]'s task (base or top activity), or null. */
    private fun findTask(pkg: String): Int? {
        val a = atm ?: return null
        val m = a.javaClass.methods.filter { it.name == "getTasks" }.minByOrNull { it.parameterTypes.size } ?: return null
        var firstInt = true
        val args = m.parameterTypes.map { t ->
            when (t) {
                Int::class.javaPrimitiveType -> if (firstInt) { firstInt = false; 100 } else -1   // max, then INVALID_DISPLAY = all
                Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        @Suppress("UNCHECKED_CAST")
        val list = m.invoke(a, *args) as? List<Any> ?: return null
        for (ti in list) {
            val c = ti.javaClass
            fun comp(f: String) = try { (c.getField(f).get(ti) as? android.content.ComponentName)?.packageName } catch (_: Throwable) { null }
            if (comp("baseActivity") == pkg || comp("topActivity") == pkg) return c.getField("taskId").getInt(ti)
        }
        return null
    }

    /** Short result for the black box, or null when there is no task (then `am start` opens the app). */
    fun resume(pkg: String, displayId: Int): String? = try {
        val id = findTask(pkg)
        if (id == null) null else {
            val o = android.app.ActivityOptions.makeBasic()
            o.javaClass.getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType).invoke(o, displayId)
            try { o.javaClass.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(o, 1) } catch (_: Throwable) {}
            val b = o.toBundle()
            val a = atm!!
            val r = a.javaClass.getMethod("startActivityFromRecents", Int::class.javaPrimitiveType, Bundle::class.java).invoke(a, id, b)
            "resumed task $id ($pkg) → $r"
        }
    } catch (e: Throwable) {
        val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        // Not allowed or failed: fall back to `am start` (the caller logs this line too).
        lastError = "resume $pkg failed: ${c.javaClass.simpleName} ${c.message}".take(200)
        null
    }
}
