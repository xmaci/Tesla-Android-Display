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
    private lateinit var diag: TextView
    private lateinit var helperStatus: TextView
    @Volatile private var helperMsg = ""
    @Volatile private var helperLine: String? = null
    @Volatile private var selfTest = ""
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

        text("TeslaDroid · M1 · 0.5.0", 26f, true)
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

        text("4. Помічник (один раз) · Helper (one-time)", 17f, true)
        text("Потрібен для екрана у формі авто й роботи із заблокованим телефоном. Телефон має бути в Wi‑Fi.\n" +
            "1) Увімкніть «Параметри розробника» (7× торкніться «Номер збірки»).\n" +
            "2) «Сполучити» → «Налагодження через Wi‑Fi» → «Сполучити пристрій за кодом».\n" +
            "3) Опустіть шторку й введіть 6 цифр у сповіщення TeslaDroid.\n" +
            "Needed for the car-shaped display and a locked phone. Phone must be on Wi‑Fi.\n" +
            "1) Enable Developer options (tap Build number 7×). 2) Pair → Wireless debugging → Pair device with pairing code. " +
            "3) Pull down the shade and type the 6 digits into the TeslaDroid notification.", 12f)
        helperStatus = text("", 14f)
        button("Сполучити · Pair (Wireless debugging)") {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
            }
            PairingService.start(this)
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        }
        button("Запустити помічника · Start helper") {
            helperMsg = "…"
            Thread { helperMsg = Helper.start(this) }.start()
        }
        button("Зупинити помічника · Stop helper") { Thread { Helper.stop(this); helperMsg = "stopped" }.start() }

        text("Діагностика · Diagnostics", 17f, true)
        button("Самоперевірка · Self-test") { runSelfTest() }
        diag = text("", 12f, color = Color.rgb(170, 200, 255)).apply { typeface = Typeface.MONOSPACE }

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

    /** Connects from the phone itself to every listening port on 127.0.0.1 and on the car address. */
    private fun runSelfTest() {
        selfTest = "…"
        Thread {
            val ports = StreamService.instanceServer?.ports ?: emptyList()
            val ip = Config.ip(this)
            val out = ArrayList<String>()
            if (ports.isEmpty()) out += "server not running (tap Start)"
            for (host in listOf("127.0.0.1", ip)) for (p in ports) {
                out += "$host:$p → " + try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(host, p), 2000)
                        s.soTimeout = 2000
                        s.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                        val r = s.getInputStream().bufferedReader().readText()
                        if (r.contains("TeslaDroid OK")) "OK" else "answer: ${r.take(40)}"
                    }
                } catch (e: Exception) { "FAIL ${e.javaClass.simpleName}: ${e.message}" }
            }
            selfTest = out.joinToString("\n")
        }.start()
    }

    private fun interfaces(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { "${ni.name}=${it.hostAddress}" } }
            .joinToString("  ")
    } catch (e: Exception) { "n/a" }

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
        if (StreamService.lastProfile.isNotEmpty()) lines += "⏱ " + StreamService.lastProfile
        status.text = lines.joinToString("\n")
        Thread { helperLine = Helper.ping(this) }.start()
        helperStatus.text = (if (helperLine != null) "✅ $helperLine" else "❌ не запущено · not running") +
            (if (PairingService.state.isNotEmpty()) "\npairing: ${PairingService.state}" else "") +
            (if (helperMsg.isNotEmpty()) "\n$helperMsg" else "")
        val srv = StreamService.instanceServer
        diag.text = buildString {
            append("ports: ").append(srv?.ports ?: "-")
            if (!srv?.lastError.isNullOrEmpty()) append("  (").append(srv?.lastError).append(")")
            append("\nif: ").append(interfaces())
            if (selfTest.isNotEmpty()) append("\nself-test:\n").append(selfTest)
            append("\nconnections (newest first):\n")
            append(srv?.connLog?.joinToString("\n")?.ifEmpty { "none yet" } ?: "none yet")
        }
    }
}
