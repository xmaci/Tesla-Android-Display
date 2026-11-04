package com.xmaci.tesladroid

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * One simple screen: a big start/stop button, the link for the car, and a short checklist.
 * Car address, helper and diagnostics live under "Advanced" (hidden by default).
 * Plain framework views, no AndroidX.
 */
class MainActivity : Activity() {

    private companion object {
        const val VERSION = "0.7.1-A"
        const val REQ_VPN = 1
        const val REQ_CAPTURE = 2
        const val REQ_NOTIF = 3
        val GREEN = Color.rgb(95, 211, 141)
        val GREY = Color.rgb(150, 150, 150)
        val TEXT = Color.rgb(230, 230, 230)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 1000) } }
    private var dp = 1f

    private lateinit var bigButton: ImageView
    private lateinit var state: TextView
    private lateinit var url: TextView
    private lateinit var hotspotLine: TextView
    private lateinit var hotspotBtn: Button
    private lateinit var carLine: TextView
    private lateinit var touchLine: TextView
    private lateinit var touchBox: LinearLayout
    private lateinit var wifiLine: TextView
    private lateinit var advanced: LinearLayout
    private lateinit var ipField: EditText
    private lateinit var helperStatus: TextView
    private lateinit var diag: TextView
    @Volatile private var helperMsg = ""
    @Volatile private var helperLine: String? = null
    @Volatile private var selfTest = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad * 2)
            setBackgroundColor(Color.rgb(17, 17, 17))
        }
        fun text(parent: LinearLayout, s: String, size: Float = 16f, bold: Boolean = false, color: Int = TEXT,
                 center: Boolean = false) = TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (center) gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, pad / 3, 0, pad / 3)
        }.also { parent.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        fun button(parent: LinearLayout, s: String, onClick: () -> Unit) =
            Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }
                .also { parent.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)) }
        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply { setColor(Color.rgb(28, 28, 28)); cornerRadius = 14 * dp }
        }.also { col.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = pad }) }

        text(col, "TeslaDroid", 26f, true, center = true)
        text(col, "Екран телефона на Tesla · Phone screen on the Tesla", 13f, color = GREY, center = true)

        // ---- the big start / stop button ----
        bigButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_start)
            contentDescription = "Start"
            setOnClickListener { if (StreamService.running || CarVpnService.running) stopAll() else startFlow() }
        }
        col.addView(bigButton, LinearLayout.LayoutParams((168 * dp).toInt(), (168 * dp).toInt()).apply {
            topMargin = pad; bottomMargin = pad / 2
        })
        state = text(col, "", 17f, center = true)
        url = text(col, "", 24f, true, GREEN, center = true)

        // ---- checklist ----
        val list = card()
        hotspotLine = text(list, "", 15f)
        hotspotBtn = button(list, "Налаштування роздачі · Hotspot settings") {
            try { startActivity(Intent("android.settings.TETHER_SETTINGS")) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
        }
        carLine = text(list, "", 15f)
        wifiLine = text(list, "⚠ Вимкніть Wi‑Fi телефона, роздача лишається увімкненою · " +
            "Turn the phone's Wi‑Fi off, keep the hotspot on", 14f, color = Color.rgb(255, 200, 90))
        touchLine = text(list, "", 15f)
        touchBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(touchBox)
        text(touchBox, "Щоб керувати телефоном з екрана Tesla: увімкніть «TeslaDroid» у Спеціальних можливостях. " +
            "Якщо перемикач сірий — спершу «Дозволити обмежені налаштування».\n" +
            "To control the phone from the Tesla screen, turn on \"TeslaDroid\" in Accessibility. " +
            "If the switch is greyed out, first allow restricted settings.", 12f, color = GREY)
        button(touchBox, "1. Дозволити обмежені налаштування · Allow restricted settings") {
            // App info → ⋮ (top right) → Allow restricted settings
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            Toast.makeText(this, "⋮ (вгорі праворуч) → Дозволити обмежені налаштування · ⋮ → Allow restricted settings",
                Toast.LENGTH_LONG).show()
        }
        button(touchBox, "2. Увімкнути дотик · Turn on touch") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ---- advanced (hidden) ----
        val advToggle = text(col, "Додатково · Advanced ▸", 14f, color = GREY, center = true)
        advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        col.addView(advanced, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        advToggle.setOnClickListener {
            val show = advanced.visibility != View.VISIBLE
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            advToggle.text = if (show) "Додатково · Advanced ▾" else "Додатково · Advanced ▸"
        }

        text(advanced, "Адреса для авто · Car address", 15f, true)
        ipField = EditText(this).apply {
            setText(Config.ip(this@MainActivity)); inputType = InputType.TYPE_CLASS_TEXT; setTextColor(Color.WHITE)
        }
        advanced.addView(ipField)
        text(advanced, "Не приватна IP. Типово 7.7.7.8. · Must not be a private IP. Default 7.7.7.8.", 12f, color = GREY)

        text(advanced, "Помічник (для майбутнього екрана у формі авто) · Helper (for the coming car-shaped display)", 15f, true)
        text(advanced, "Потрібні «Параметри розробника» і Wi‑Fi. Звичайній трансляції не потрібен. · " +
            "Needs Developer options and Wi‑Fi. Not needed for normal streaming.", 12f, color = GREY)
        helperStatus = text(advanced, "", 13f)
        button(advanced, "Сполучити · Pair (Wireless debugging)") {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
            }
            PairingService.start(this)
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        }
        button(advanced, "Запустити помічника · Start helper") { helperMsg = "…"; Thread { helperMsg = Helper.start(this) }.start() }
        button(advanced, "Зупинити помічника · Stop helper") { Thread { Helper.stop(this); helperMsg = "stopped" }.start() }

        text(advanced, "Діагностика · Diagnostics", 15f, true)
        button(advanced, "Самоперевірка · Self-test") { runSelfTest() }
        diag = text(advanced, "", 12f, color = Color.rgb(170, 200, 255)).apply { typeface = Typeface.MONOSPACE }

        text(col, "v$VERSION", 11f, color = Color.rgb(90, 90, 90), center = true)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(17, 17, 17)); addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    // ---------- start / stop ----------

    private fun startFlow() {
        val ip = ipField.text.toString().trim()
        if (!Config.isValidIpv4(ip) || Config.isPrivate(ip)) {
            Toast.makeText(this, "Невірна або приватна адреса (Додатково) · Invalid or private address (Advanced)", Toast.LENGTH_LONG).show()
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
                else {
                    CarVpnService.stop(this)
                    Toast.makeText(this, "Потрібен запис екрана — оберіть «Увесь екран» · Choose \"Entire screen\"", Toast.LENGTH_LONG).show()
                }
        }
        refresh()
    }

    private fun stopAll() {
        StreamService.stop(this)
        CarVpnService.stop(this)
        ui.postDelayed({ refresh() }, 300)
    }

    // ---------- checks ----------

    /** Hotspot is on if an access-point style interface has an IPv4 address (names differ by phone). */
    private fun hotspotOn(): Boolean = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().any { ni ->
            val n = ni.name.lowercase()
            ni.isUp && (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") ||
                Regex("wlan[1-9]").matches(n)) &&
                ni.inetAddresses.toList().any { it is java.net.Inet4Address && !it.isLoopbackAddress }
        }
    } catch (_: Exception) { false }

    /** The phone itself is connected to a Wi‑Fi network (breaks streaming while the hotspot is on). */
    @Suppress("DEPRECATION")
    private fun phoneOnWifi(): Boolean = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.any { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    } catch (_: Exception) { false }

    private fun refresh() {
        val running = StreamService.running && CarVpnService.running
        val starting = !running && (StreamService.running || CarVpnService.running)
        bigButton.setImageResource(if (running || starting) R.drawable.ic_stop else R.drawable.ic_start)
        bigButton.contentDescription = if (running || starting) "Stop" else "Start"
        state.text = when {
            running && StreamService.carConnected -> "✅ Tesla підключена · Tesla connected"
            running -> "Відкрийте в браузері Tesla · Open in the Tesla browser:"
            starting -> "Запуск… · Starting…"
            StreamService.status.startsWith("error") || CarVpnService.lastError.isNotEmpty() ->
                "❌ " + (if (StreamService.status.startsWith("error")) StreamService.status else CarVpnService.lastError)
            else -> "Натисніть, щоб почати · Tap to start"
        }
        url.text = if (running) Config.carUrl(Config.ip(this), StreamService.serverPort) else ""
        url.visibility = if (running) View.VISIBLE else View.GONE

        val hs = hotspotOn()
        hotspotLine.text = if (hs) "✅ Роздача увімкнена · Hotspot on" else "⬜ Увімкніть роздачу й підключіть Tesla · Turn on the hotspot, connect the Tesla"
        hotspotBtn.visibility = if (hs) View.GONE else View.VISIBLE
        carLine.text = if (StreamService.carConnected) "✅ Tesla на зв'язку · Tesla connected  (${StreamService.statsLine})"
            else "⬜ Tesla ще не відкрила адресу · Tesla has not opened the link yet"
        wifiLine.visibility = if (running && phoneOnWifi()) View.VISIBLE else View.GONE
        val touch = TouchService.instance != null
        touchLine.text = if (touch) "✅ Дотик з екрана Tesla · Touch from the Tesla" else "⬜ Дотик вимкнено (лише перегляд) · Touch off (view only)"
        touchBox.visibility = if (touch) View.GONE else View.VISIBLE

        if (advanced.visibility == View.VISIBLE) refreshAdvanced()
    }

    // ---------- advanced ----------

    private fun refreshAdvanced() {
        Thread { helperLine = Helper.ping(this) }.start()
        helperStatus.text = (if (helperLine != null) "✅ $helperLine" else "❌ не запущено · not running") +
            (if (PairingService.state.isNotEmpty()) "\npairing: ${PairingService.state}" else "") +
            (if (helperMsg.isNotEmpty()) "\n$helperMsg" else "")
        val srv = StreamService.instanceServer
        diag.text = buildString {
            append("address: ").append(if (CarVpnService.running) CarVpnService.address else "- ${CarVpnService.lastError}")
            append("\nstream: ").append(StreamService.status)
            append("\nports: ").append(srv?.ports ?: "-")
            if (!srv?.lastError.isNullOrEmpty()) append("  (").append(srv?.lastError).append(")")
            append("\nif: ").append(interfaces())
            if (StreamService.lastProfile.isNotEmpty()) append("\nprofile: ").append(StreamService.lastProfile)
            if (selfTest.isNotEmpty()) append("\nself-test:\n").append(selfTest)
            append("\nconnections (newest first):\n")
            append(srv?.connLog?.joinToString("\n")?.ifEmpty { "none yet" } ?: "none yet")
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
}
