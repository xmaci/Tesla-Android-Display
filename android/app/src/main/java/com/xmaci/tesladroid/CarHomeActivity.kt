package com.xmaci.tesladroid

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Home screen of the car screen (opened there by the helper). Replaces the system launcher, which
 * Android covers with its lock screen while the phone is locked; this ordinary activity stays visible.
 * A tap asks StreamService to open the app on the car screen through the helper.
 */
class CarHomeActivity : Activity() {

    companion object {
        /** Set by the dock's ■ (recent apps) / ● (home) before the car screen opens this activity. */
        @Volatile var recentMode = false
    }

    private data class App(val label: String, val component: String, val icon: Drawable?)

    private var apps: List<App> = emptyList()
    private var all: List<App> = emptyList()
    private lateinit var title: TextView
    private lateinit var closeAll: TextView
    private lateinit var grid: GridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        grid = GridView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(160)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(18)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ -> apps.getOrNull(pos)?.let { StreamService.launchOnCar(it.component) } }
            // Long press: pin to / unpin from the car panel
            setOnItemLongClickListener { _, _, pos, _ ->
                // Recent apps: long press closes the app (like swiping it away).
                if (recentMode) {
                    apps.getOrNull(pos)?.let {
                        StreamService.closeOnCar(it.component)
                        android.widget.Toast.makeText(this@CarHomeActivity, "✕ ${it.label} — закрито · closed", android.widget.Toast.LENGTH_SHORT).show()
                        grid.postDelayed({ show() }, 300)
                    }
                    return@setOnItemLongClickListener true
                }
                apps.getOrNull(pos)?.let {
                    val on = CarApps.toggleFavourite(this@CarHomeActivity, it.component)
                    android.widget.Toast.makeText(this@CarHomeActivity,
                        if (on) "★ ${it.label} — на панелі · pinned" else "${it.label} — знято з панелі · unpinned",
                        android.widget.Toast.LENGTH_SHORT).show()
                    StreamService.favouritesChanged()
                    (adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
                true
            }
        }
        title = TextView(this).apply {
            setTextColor(Color.parseColor("#9aa0a6")); textSize = 22f
            setPadding(dp(24), dp(16), dp(24), 0); setBackgroundColor(Color.parseColor("#111111"))
            text = "Останні · Recent"; visibility = View.GONE
        }
        // "Close all" (not Waze: navigation must not stop by accident).
        closeAll = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setBackgroundColor(Color.parseColor("#3a3a3a"))
            text = "✕ Закрити всі (крім Waze) · Close all"; visibility = View.GONE
            setOnClickListener {
                CarApps.recents(this@CarHomeActivity).filter { !it.startsWith("com.waze/") }.forEach { StreamService.closeOnCar(it) }
                postDelayed({ show() }, 500)
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111111"))
            addView(title)
            addView(closeAll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(8), 0, dp(4)) })
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })

        thread {
            val pm = packageManager
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { App(it.loadLabel(pm).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}",
                    try { it.loadIcon(pm) } catch (_: Throwable) { null }) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { all = list; grid.adapter = Adapter(::dp); show() }
        }
    }

    private inner class Adapter(val dp: (Int) -> Int) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int) = apps[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val box = (convert as? LinearLayout) ?: LinearLayout(this@CarHomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(6), dp(4), dp(6))
                addView(ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)) })
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
                    maxLines = 2; setPadding(0, dp(8), 0, 0)
                })
            }
            val a = apps[p]
            (box.getChildAt(0) as ImageView).setImageDrawable(a.icon)
            (box.getChildAt(1) as TextView).text =
                if (CarApps.isFavourite(this@CarHomeActivity, a.component)) "★ ${a.label}" else a.label
            return box
        }
    }

    override fun onResume() { super.onResume(); show() }

    /** All apps (●) or the apps last opened on the car (■). */
    private fun show() {
        if (!::grid.isInitialized) return
        apps = if (recentMode) {
            val by = all.associateBy { it.component }
            CarApps.recents(this).mapNotNull { by[it] }
        } else all
        title.visibility = if (recentMode) View.VISIBLE else View.GONE
        closeAll.visibility = if (recentMode && apps.isNotEmpty()) View.VISIBLE else View.GONE
        if (recentMode) title.text = "Останні · Recent — довге натискання закриває · long press closes"
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    /** Back on the car home does nothing: without it the car screen would be empty (no system home there). */
    @Deprecated("plain Activity API")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {}
}
