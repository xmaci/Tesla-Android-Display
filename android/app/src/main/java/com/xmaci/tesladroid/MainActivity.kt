package com.xmaci.tesladroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** M1a control screen. Plain framework views, no AndroidX. */
class MainActivity : Activity() {

    private companion object {
        const val REQ_VPN = 1
        const val REQ_CAPTURE = 2
        const val REQ_NOTIF = 3
    }

    private lateinit var status: TextView
    private lateinit var url: TextView
    private lateinit var ipField: EditText
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(17, 17, 17))
        }
        fun text(s: String, size: Float = 16f, bold: Boolean = false, color: Int = Color.rgb(230, 230, 230)) =
            TextView(this).apply {
                text = s; textSize = size; setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, pad / 3, 0, pad / 3)
            }.also { col.addView(it) }
        fun button(s: String, onClick: () -> Unit) =
            Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }
                .also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }

        text("TeslaDroid · M1a", 26f, true)
        text("Екран телефона на Tesla · Phone screen on the Tesla", 14f, color = Color.GRAY)

        text("1. Дотик з екрана авто · Touch from the car screen", 17f, true)
        text("Увімкніть «TeslaDroid» у Спеціальних можливостях. Якщо перемикач сірий: Налаштування → Застосунки → TeslaDroid → ⋮ → «Дозволити обмежені налаштування».\n" +
            "Turn on \"TeslaDroid\" in Accessibility. If the switch is greyed out: Settings → Apps → TeslaDroid → ⋮ → \"Allow restricted settings\".", 13f)
        button("Відкрити спецможливості · Open Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        text("2. Роздача · Hotspot", 17f, true)
        text("Увімкніть точку доступу й підключіть до неї Tesla. · Turn on the hotspot and connect the Tesla to it.", 13f)
        button("Налаштування роздачі · Hotspot settings") {
            try { startActivity(Intent("android.settings.TETHER_SETTINGS")) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }

        text("3. Адреса для авто · Car address", 17f, true)
        ipField = EditText(this).apply {
            setText(Config.ip(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Color.WHITE)
        }
        col.addView(ipField)
        text("Не приватна IP (не 10.x / 172.16–31.x / 192.168.x). Типово 7.7.7.8. · Must not be a private IP. Default 7.7.7.8.", 12f, color = Color.GRAY)

        button("▶ Старт · Start") { startFlow() }
        button("■ Стоп · Stop") { stopAll() }

        text("Відкрийте в браузері Tesla · Open in the Tesla browser:", 14f)
        url = text("", 22f, true, Color.rgb(95, 211, 141)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        status = text("", 14f)

        text("Поки що (M1a) телефон має лишатися розблокованим, екран — увімкненим. · For now (M1a) keep the phone unlocked with the screen on.",
            12f, color = Color.GRAY)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    private fun startFlow() {
        val ip = ipField.text.toString().trim()
        if (!Config.isValidIpv4(ip) || Config.isPrivate(ip)) {
            Toast.makeText(this, "Невірна або приватна адреса · Invalid or private address", Toast.LENGTH_LONG).show()
            return
        }
        Config.setIp(this, ip)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        val vpn = VpnService.prepare(this)
        if (vpn != null) startActivityForResult(vpn, REQ_VPN) else onVpnReady()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) {
            val vpn = VpnService.prepare(this)
            if (vpn != null) startActivityForResult(vpn, REQ_VPN) else onVpnReady()
        }
    }

    private fun onVpnReady() {
        CarVpnService.start(this)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
    }

    @Deprecated("plain Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN -> if (resultCode == RESULT_OK) onVpnReady()
                else Toast.makeText(this, "Потрібен дозвіл VPN · VPN permission needed", Toast.LENGTH_LONG).show()
            REQ_CAPTURE -> if (resultCode == RESULT_OK && data != null) StreamService.start(this, resultCode, data)
                else Toast.makeText(this, "Потрібен запис екрана · Screen capture needed", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAll() {
        StreamService.stop(this)
        CarVpnService.stop(this)
    }

    private fun refresh() {
        val ip = Config.ip(this)
        url.text = if (StreamService.running && CarVpnService.running) Config.carUrl(ip, StreamService.serverPort) else "—"
        val lines = ArrayList<String>()
        lines += "Адреса · Address: " + if (CarVpnService.running) "✅ ${CarVpnService.address}" else "❌ ${CarVpnService.lastError}"
        lines += "Трансляція · Stream: " + if (StreamService.running) "✅ port ${StreamService.serverPort}" else "❌ ${StreamService.status}"
        lines += "Дотик · Touch: " + if (TouchService.instance != null) "✅" else "❌ (крок 1 · step 1)"
        lines += "Tesla: " + if (StreamService.carConnected) "✅ ${StreamService.statsLine}" else "очікування · waiting"
        status.text = lines.joinToString("\n")
    }
}
