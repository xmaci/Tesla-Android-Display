package com.xmaci.tesladroid.helper

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.view.InputEvent
import java.lang.reflect.Method

/**
 * What the helper needs from Android's hidden APIs, all by reflection (the helper runs in
 * app_process as the shell user, where hidden APIs are not blocked). Same technique as scrcpy
 * (Apache-2.0, github.com/Genymobile/scrcpy): a system Context that claims to be com.android.shell,
 * a DisplayManager built on it, and InputManager(Global).injectInputEvent.
 */
object ShellEnv {
    private const val SHELL_PKG = "com.android.shell"
    private const val SHELL_UID = 2000

    /** Context whose package is com.android.shell, so system services accept calls from uid 2000. */
    val context: Context by lazy {
        val at = Class.forName("android.app.ActivityThread")
        val ctor = at.getDeclaredConstructor().apply { isAccessible = true }
        val thread = ctor.newInstance()
        at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        try { at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true) } catch (_: Throwable) {}
        // Android 12+: getSystemContext() needs a ConfigurationController (as in scrcpy's Workarounds).
        try {
            val cc = Class.forName("android.app.ConfigurationController")
            val ctl = cc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
                .apply { isAccessible = true }.newInstance(thread)
            at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, ctl)
        } catch (_: Throwable) {}
        val sys = at.getDeclaredMethod("getSystemContext").apply { isAccessible = true }.invoke(thread) as Context
        ShellContext(sys)
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PKG
        override fun getOpPackageName(): String = SHELL_PKG
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PKG).build()
        override fun getApplicationContext(): Context = this
    }

    val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
    }

    // ---- input ----

    private val inputManager: Any by lazy {
        try {      // Android 14+
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } catch (_: Throwable) {
            android.hardware.input.InputManager::class.java.getDeclaredMethod("getInstance").invoke(null)!!
        }
    }
    private val injectMethod: Method by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayId: Method by lazy {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
    }

    /**
     * Keyboard on [displayId] instead of the phone (scrcpy --display-ime-policy=local).
     * Android 12+: setDisplayImePolicy(id, 0 = LOCAL); Android 10–11: setShouldShowIme(id, true).
     */
    fun imeLocal(displayId: Int): String = try {
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as android.os.IBinder
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)!!
        if (android.os.Build.VERSION.SDK_INT >= 31)
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, 0)
        else
            wm.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(wm, displayId, true)
        "keyboard here"
    } catch (e: Throwable) {
        val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        "keyboard: ${c.javaClass.simpleName} ${c.message}"
    }

    /** Sends [ev] to [displayId]. Mode 0 = async (don't wait for the app to handle it). */
    fun inject(ev: InputEvent, displayId: Int): Boolean {
        setDisplayId.invoke(ev, displayId)
        return injectMethod.invoke(inputManager, ev, 0) as Boolean
    }
}

/**
 * The phone's own panel on/off without putting the phone to sleep (scrcpy's --turn-screen-off):
 * the phone stays awake and locked, so the car screen keeps drawing, but its panel is dark.
 * Android 14+ keeps the display tokens in services.jar (DisplayControl).
 */
object Panel {
    // Runs only inside the helper (app_process as the shell user), where hidden APIs are not blocked.
    @android.annotation.SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi", "PrivateApi")
    private fun tokens(): List<android.os.IBinder> {
        val sc = Class.forName("android.view.SurfaceControl")
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val f = Class.forName("com.android.internal.os.ClassLoaderFactory").getDeclaredMethod("createClassLoader",
                String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
            val cl = f.invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
            val dc = cl.loadClass("com.android.server.display.DisplayControl")
            Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(Runtime.getRuntime(), dc, "android_servers")
            val ids = dc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val get = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            return ids.toList().mapNotNull { get.invoke(null, it) as android.os.IBinder? }
        }
        return try {
            val ids = sc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val get = sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            ids.toList().mapNotNull { get.invoke(null, it) as android.os.IBinder? }
        } catch (_: NoSuchMethodException) {
            listOfNotNull(sc.getMethod("getInternalDisplayToken").invoke(null) as android.os.IBinder?)
        }
    }

    private var cached: List<android.os.IBinder>? = null

    /** Returns a short result line for diagnostics. */
    fun set(on: Boolean): String = try {
        val t = cached ?: tokens().also { cached = it }
        val m = Class.forName("android.view.SurfaceControl")
            .getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
        for (b in t) m.invoke(null, b, if (on) 2 else 0)     // 2 = POWER_MODE_NORMAL, 0 = OFF
        "panel ${if (on) "on" else "off"} (${t.size})"
    } catch (e: Throwable) {
        val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        "panel failed: ${c.javaClass.simpleName} ${c.message}"
    }

    /** Wakes the phone (stays locked: the lock screen comes back, nothing is unlocked). */
    fun wake(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        var ok = true
        for (a in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            val ev = android.view.KeyEvent(now, now, a, android.view.KeyEvent.KEYCODE_WAKEUP, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_KEYBOARD)
            ok = ShellEnv.inject(ev, 0) && ok
        }
        return ok
    }
}
