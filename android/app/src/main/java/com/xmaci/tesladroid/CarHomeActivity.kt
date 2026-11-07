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

    private data class App(val label: String, val component: String, val icon: Drawable?)

    private var apps: List<App> = emptyList()
    private lateinit var grid: GridView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

        grid = GridView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(96)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(10)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            clipToPadding = false
            setOnItemClickListener { _, _, pos, _ -> apps.getOrNull(pos)?.let { StreamService.launchOnCar(it.component) } }
        }
        setContentView(grid)

        thread {
            val pm = packageManager
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName }
                .map { App(it.loadLabel(pm).toString(), "${it.activityInfo.packageName}/${it.activityInfo.name}",
                    try { it.loadIcon(pm) } catch (_: Throwable) { null }) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { apps = list; grid.adapter = Adapter(::dp) }
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
                addView(ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)) })
                addView(TextView(context).apply {
                    setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
                    maxLines = 2; setPadding(0, dp(4), 0, 0)
                })
            }
            val a = apps[p]
            (box.getChildAt(0) as ImageView).setImageDrawable(a.icon)
            (box.getChildAt(1) as TextView).text = a.label
            return box
        }
    }
}
