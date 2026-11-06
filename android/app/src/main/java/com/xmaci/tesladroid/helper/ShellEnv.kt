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

    /** Sends [ev] to [displayId]. Mode 0 = async (don't wait for the app to handle it). */
    fun inject(ev: InputEvent, displayId: Int): Boolean {
        setDisplayId.invoke(ev, displayId)
        return injectMethod.invoke(inputManager, ev, 0) as Boolean
    }
}
