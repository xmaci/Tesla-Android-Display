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
        const val VERSION = "0.13.13"
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
    private lateinit var pairBtn: View
    private lateinit var helperBox: LinearLayout
    private var helperForceOpen = false
    private lateinit var licLine: TextView
    private lateinit var licBox: LinearLayout
    private lateinit var licField: EditText
    private var licOpen = false
    private val stepTitle = ArrayList<TextView>()
    private val stepDesc = ArrayList<TextView>()
    private val stepBtn = ArrayList<Button>()
    private val stepSkip = ArrayList<Button>()
    private lateinit var helperFoot: TextView
    private lateinit var diag: TextView
    @Volatile private var helperMsg = ""
    @Volatile private var helperLine: String? = null
    @Volatile private var selfTest = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutoHelperJob.schedule(this)
        // Ask for notifications up front, in the app (pairing code entry and setup hints need them).
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
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

        // ---- step 1: the helper (once; afterwards it starts itself on Wi‑Fi) ----
        val helperCard = card()
        text(helperCard, "Підготовка телефона (один раз) · Phone setup (once)", 16f, true)
        helperStatus = text(helperCard, "", 14f)
        helperBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        helperCard.addView(helperBox)
        // One row per step: title; for the current step also what to do and a button that does it.
        for (k in 0 until 5) {
            stepTitle.add(text(helperBox, "", 15f, true))
            stepDesc.add(text(helperBox, "", 13f, color = GREY))
            stepBtn.add(button(helperBox, "") { stepAction(k) })
            // If a check is wrong on some phone, the user can move on.
            stepSkip.add(button(helperBox, "Вже зроблено · Already done") {
                getSharedPreferences("tesladroid", MODE_PRIVATE).edit().putBoolean("step_done_$k", true).apply()
            })
        }
        pairBtn = button(helperBox, "Сполучити знову · Pair again") { startPairing() }
        helperFoot = text(helperBox, "", 12f, color = GREY)
        // Tap the status to show the steps again even when everything works.
        helperStatus.setOnClickListener { helperForceOpen = !helperForceOpen }

        // ---- licence: trial / active / expired, device code, activation ----
        val licCard = card()
        licLine = text(licCard, "", 14f)
        licBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        licCard.addView(licBox)
        val dev = License.deviceCode(this)
        text(licBox, "Код телефона (надішліть власнику) · Device code (send to the owner):", 12f, color = GREY)
        text(licBox, dev, 20f, true, GREEN).apply {
            setTextIsSelectable(true)
            setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("TeslaDroid device", dev))
                Toast.makeText(this@MainActivity, "Скопійовано · Copied", Toast.LENGTH_SHORT).show()
            }
        }
        licField = EditText(this).apply {
            hint = "Код активації · Activation code"; setTextColor(Color.WHITE); setHintTextColor(GREY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        licBox.addView(licField)
        button(licBox, "Активувати · Activate") {
            val err = License.activate(this, licField.text.toString())
            Toast.makeText(this, err ?: "✅ Активовано · Activated", Toast.LENGTH_LONG).show()
            if (err == null) { licField.setText(""); licOpen = false }
        }
        licLine.setOnClickListener { licOpen = !licOpen }

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

        button(advanced, "Зупинити помічника · Stop helper") { Thread { Helper.stop(this); helperMsg = "stopped" }.start() }

        text(advanced, "Діагностика · Diagnostics", 15f, true)
        button(advanced, "Самоперевірка · Self-test") { runSelfTest() }
        diag = text(advanced, "", 12f, color = Color.rgb(170, 200, 255)).apply { typeface = Typeface.MONOSPACE }

        text(col, "v$VERSION", 11f, color = Color.rgb(90, 90, 90), center = true)
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.rgb(17, 17, 17)); addView(col) })
    }

    override fun onResume() { super.onResume(); ui.post(tick); autoStartHelper() }

    private val helperStarting = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Opening the app starts the helper by itself when it can: paired, on Wi‑Fi, helper not running.
     * With the auto-start permission it turns Wireless debugging on and off itself; without it, only
     * when Wireless debugging is already on.
     */
    private fun autoStartHelper() {
        if (!Config.paired(this) || !phoneOnWifi()) return
        if (!helperStarting.compareAndSet(false, true)) return
        Thread {
            try {
                if (Helper.ping(this) == null) {
                    val can = AutoHelperJob.canAutoStart(this)
                    if (can || wirelessDebuggingOn()) {
                        helperMsg = "⏳ Запускаю помічника… · Starting the helper…"
                        helperMsg = if (can) AutoHelperJob.tryStart(this) else Helper.start(this)
                    }
                }
            } finally { helperStarting.set(false) }
        }.start()
    }
    override fun onPause() { ui.removeCallbacks(tick); super.onPause() }

    // ---------- start / stop ----------

    private fun startFlow() {
        val ip = ipField.text.toString().trim()
        if (!Config.isValidIpv4(ip) || Config.isPrivate(ip)) {
            Toast.makeText(this, "Невірна або приватна адреса (Додатково) · Invalid or private address (Advanced)", Toast.LENGTH_LONG).show()
            return
        }
        Config.setIp(this, ip)
        // Licence: only a new Start is refused, never a running session.
        val lic = License.state(this)
        if (!lic.canStart) {
            licOpen = true
            Toast.makeText(this, "Потрібен код активації · Activation code needed — ${(lic as License.State.Expired).why}", Toast.LENGTH_LONG).show()
            return
        }
        // Like ATTD's «Старт»: bring the helper up too if it is not running and the phone is on Wi‑Fi.
        if (Config.paired(this) && helperLine == null && phoneOnWifi()) {
            helperMsg = "…"
            Thread { helperMsg = if (AutoHelperJob.canAutoStart(this)) AutoHelperJob.tryStart(this) else Helper.start(this) }.start()
        }
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
        // Android 14+: ask for the whole screen straight away (no "one app" choice): with one app shared,
        // switching apps would leave the car black.
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_CAPTURE)
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

        refreshHelper()
        refreshLicence()
        if (advanced.visibility == View.VISIBLE) refreshAdvanced()
    }

    private fun refreshLicence() {
        val st = License.state(this)
        val df = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.US)
        licLine.text = when (st) {
            is License.State.Trial -> "🕐 Пробний період: ще ${st.daysLeft} дн. · Trial: ${st.daysLeft} days left ▸"
            is License.State.Active -> if (st.untilMs == null) "✅ Активовано · Activated" else
                "✅ Активно до ${df.format(java.util.Date(st.untilMs - 1))} · Active until ${df.format(java.util.Date(st.untilMs - 1))}"
            is License.State.Expired -> "⛔ ${st.why} — потрібен код · activation code needed ▾"
        }
        licBox.visibility = if (licOpen || st is License.State.Expired) View.VISIBLE else View.GONE
    }

    // ---------- step 1: guided setup ----------

    /**
     * Android 17 (Pixel) no longer shows apps the Developer options flag: also count any debugging being on
     * (impossible without Developer options), or the user saying it is done.
     */
    private fun devOptionsOn(): Boolean {
        fun g(k: String) = try { Settings.Global.getInt(contentResolver, k, 0) == 1 } catch (_: Exception) { false }
        fun sec(k: String) = try { Settings.Secure.getInt(contentResolver, k, 0) == 1 } catch (_: Exception) { false }
        return g(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) || sec(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) ||
            g(Settings.Global.ADB_ENABLED) || g("adb_wifi_enabled") || skipped(0)
    }
    private fun skipped(k: Int) = getSharedPreferences("tesladroid", MODE_PRIVATE).getBoolean("step_done_$k", false)
    private fun wirelessDebuggingOn() = Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) == 1

    /** Opens a settings screen with one row highlighted (the same extras Shizuku uses). */
    private fun openSettings(action: String, highlight: String?, hint: String) {
        val i = Intent(action)
        if (highlight != null) {
            i.putExtra(":settings:fragment_args_key", highlight)
            i.putExtra(":settings:show_fragment_args", android.os.Bundle().apply { putString(":settings:fragment_args_key", highlight) })
        }
        try { startActivity(i) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        topHint(hint)
    }

    /**
     * The hint as a heads-up notification at the TOP of the screen: a toast sits at the bottom, right over
     * «Build number», and Android no longer lets apps move toasts. Silent; after a few seconds Android moves
     * it into the shade, so the row to tap is free on every phone (Xiaomi has it near the top).
     */
    private fun topHint(text: String) {
        if (text.isEmpty()) return
        // Never ask here: the question would pop up over the settings screen just opened (asked at app start).
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("hints", "Підказки · Hints",
            android.app.NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null); enableVibration(false) })
        nm.notify(77, android.app.Notification.Builder(this, "hints")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TeslaDroid")
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            // No timeout: Android shows it on top for a few seconds only, then it waits in the shade
            // (pull down to read again) until the step is done — then refreshSetup removes it.
            .setAutoCancel(true)
            .build())
    }

    /**
     * Opens the «Wireless debugging» screen itself (toggle + «Pair device with pairing code»), the way a long
     * press on its Quick Settings tile does: Developer options opened with QS_TILE_PREFERENCES for the
     * WirelessDebugging tile goes straight to WirelessDebuggingFragment (AOSP, Android 12+). Falls back to
     * Developer options with the row highlighted.
     */
    private fun openWirelessDebugging(hint: String) {
        val tile = android.content.ComponentName("com.android.settings",
            "com.android.settings.development.qstile.DevelopmentTiles\$WirelessDebugging")
        val i = Intent("android.service.quicksettings.action.QS_TILE_PREFERENCES")
            .setComponent(android.content.ComponentName("com.android.settings",
                "com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"))
            .putExtra(Intent.EXTRA_COMPONENT_NAME, tile)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i); topHint(hint) }
        catch (_: Exception) { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "toggle_adb_wireless", hint) }
    }

    private fun notificationsOn(): Boolean =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

    private fun openNotificationSettings() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
            !getSharedPreferences("tesladroid", MODE_PRIVATE).getBoolean("notif_asked", false)) {
            getSharedPreferences("tesladroid", MODE_PRIVATE).edit().putBoolean("notif_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9); return
        }
        // Refused before: Android will not ask again, so open the app's notification settings.
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    /** Live pairing state under step 4, so it is clear what the app is waiting for. */
    private fun pairingStateLine(): String = when {
        PairingService.state.startsWith("waiting") -> "\n⏳ Чекаю на вікно з кодом (Pair device with pairing code)… · Waiting for the code window…"
        PairingService.state.startsWith("pairing port") -> "\n✋ Вікно знайдено — введіть 6 цифр у сповіщенні TeslaDroid (шторка) · Found — enter the code in the notification"
        PairingService.state.isNotEmpty() -> "\n" + PairingService.state.lineSequence().first()
        else -> ""
    }

    private fun startPairing() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        PairingService.start(this)
        // no hint here: the pairing notification itself (code entry) must stay on top
        openWirelessDebugging("")
    }

    private class Step(val done: Boolean, val title: String, val what: String, val button: String)

    private fun steps(): List<Step> {
        val dev = devOptionsOn(); val wifi = phoneOnWifi(); val paired = Config.paired(this); val helper = helperLine != null
        val adb = (dev && wirelessDebuggingOn()) || skipped(2)   // the setting stays 1 even with Developer options off
        return listOf(
            Step(dev, "Режим розробника · Developer options",
                "Потрібні налаштування Android ховає в «Параметрах розробника». Кнопка відкриє «Про телефон».\n" +
                buildNumberWhere() + "\nПотім введіть PIN і поверніться сюди · Then enter your PIN and come back.",
                "Відкрити «Про телефон» · Open About phone"),
            Step(wifi || helper || skipped(1), "Wi‑Fi",
                "Бездротове налагодження працює лише тоді, коли телефон підключений до Wi‑Fi (вдома). Роздачу на цей час вимкніть.\n" +
                "Wireless debugging only works on Wi‑Fi (at home). Turn the hotspot off for now.",
                "Відкрити Wi‑Fi · Open Wi‑Fi"),
            Step(adb || helper, "Бездротове налагодження · Wireless debugging",
                "Кнопка відкриє «Бездротове налагодження»: увімкніть перемикач угорі. " +
                "У запиті поставте галочку «Завжди дозволяти в цій мережі» і натисніть «Дозволити».\n" +
                "Turn on «Wireless debugging», tick «Always allow on this network», tap Allow.",
                "Відкрити й увімкнути · Open and turn on"),
            Step(paired, "Сполучення · Pairing",
                if (!notificationsOn())
                    "⚠ Сповіщення TeslaDroid вимкнені — код сполучення вводиться саме в сповіщенні. Натисніть кнопку й увімкніть їх.\n" +
                    "⚠ TeslaDroid notifications are off — the pairing code is typed into a notification. Turn them on."
                else "Кнопка відкриє «Бездротове налагодження». Натисніть там «Сполучити пристрій за допомогою коду» — з'являться 6 цифр, " +
                    "вікно не закривайте. Потягніть шторку вниз і в сповіщенні TeslaDroid натисніть «Ввести код».\n" +
                    "Tap «Pair device with pairing code», keep that window open, pull down the shade, tap «Enter code» in the TeslaDroid notification." +
                    pairingStateLine(),
                if (!notificationsOn()) "Дозволити сповіщення · Allow notifications" else "Сполучити · Pair"),
            Step(helper, "Помічник · Helper",
                "Після сполучення помічник запускається сам за кілька секунд. Якщо ні — натисніть кнопку.\n" +
                "After pairing the helper starts by itself. If not, tap the button.",
                "Запустити помічника · Start helper"))
    }

    private fun brand() = (android.os.Build.MANUFACTURER ?: "").lowercase()
    private fun isXiaomi() = brand().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    /** Where the 7 taps go, by maker (the row is not in the same place everywhere). */
    private fun buildNumberWhere(): String = when {
        isXiaomi() -> "Xiaomi / Redmi / POCO: «Версія MIUI» або «Версія HyperOS» — плитка вгорі екрана, натисніть її 7 разів · " +
            "tap «MIUI / HyperOS version» (top tile) 7 times."
        brand().contains("samsung") -> "Samsung: «Відомості про ПЗ» → «Номер збірки», натисніть 7 разів · " +
            "Software information → Build number, tap 7 times."
        brand().let { it.contains("oneplus") || it.contains("oppo") || it.contains("realme") } ->
            "OnePlus / Oppo / Realme: «Версія» → «Номер збірки», натисніть 7 разів · Version → Build number, tap 7 times."
        else -> "Прокрутіть у самий низ, «Номер збірки» — натисніть 7 разів · Scroll to the bottom, tap «Build number» 7 times."
    }

    private fun stepAction(k: Int) {
        when (k) {
            0 -> openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS, "build_number", buildNumberWhere())
            1 -> openSettings(Settings.ACTION_WIFI_SETTINGS, null, "Підключіться до Wi‑Fi · Connect to Wi‑Fi")
            2 -> openWirelessDebugging("Увімкніть перемикач угорі, «Завжди дозволяти в цій мережі» → «Дозволити» · " +
                "Turn on the switch at the top, always allow on this network")
            3 -> if (!notificationsOn()) openNotificationSettings() else startPairing()
            4 -> if (helperStarting.compareAndSet(false, true)) {
                helperMsg = "⏳ Запускаю помічника… · Starting the helper…"
                Thread { try { helperMsg = Helper.start(this) } finally { helperStarting.set(false) } }.start()
            }
        }
    }

    private var hintStep = -2

    private fun refreshSetup() {
        val st = steps()
        val current = st.indexOfFirst { !it.done }
        if (current != hintStep) {          // step done (or changed): its hint in the shade is no longer needed
            if (hintStep != -2) getSystemService(android.app.NotificationManager::class.java).cancel(77)
            hintStep = current
        }
        for ((k, x) in st.withIndex()) {
            val now = k == current
            stepTitle[k].text = (if (x.done) "✅ " else if (now) "👉 " else "⬜ ") + "${k + 1} з ${st.size} · ${x.title}"
            stepTitle[k].setTextColor(if (x.done || now) Color.WHITE else GREY)
            stepDesc[k].text = x.what
            stepDesc[k].visibility = if (now) View.VISIBLE else View.GONE
            stepBtn[k].text = x.button
            stepBtn[k].visibility = if (now) View.VISIBLE else View.GONE
            stepSkip[k].visibility = if (now && k <= 2) View.VISIBLE else View.GONE
        }
        pairBtn.visibility = if (current < 0 && helperForceOpen) View.VISIBLE else View.GONE
    }

    private fun refreshHelper() {
        refreshSetup()
        Thread { helperLine = Helper.ping(this) }.start()
        val ok = helperLine != null && AutoHelperJob.canAutoStart(this)
        helperBox.visibility = if (ok && !helperForceOpen) View.GONE else View.VISIBLE
        helperStatus.text = when {
            ok && !helperForceOpen -> "✅ Помічник працює, автозапуск на Wi‑Fi увімкнено · Helper running, auto start on ▸"
            helperLine != null -> "✅ Помічник працює · Helper running"
            helperStarting.get() -> "⏳ Запускаю помічника… · Starting the helper…"
            else -> "❌ Помічник ще не запущено — пройдіть кроки нижче · Helper not running yet — follow the steps below"
        }
        helperFoot.text = (if (AutoHelperJob.canAutoStart(this)) "🔁 автозапуск на Wi‑Fi увімкнено · auto start on Wi‑Fi on"
                     else "⚪ автозапуск з'явиться після першого запуску · auto start comes after the first start") +
            AutoHelperJob.lastResult(this).let { if (it.isEmpty()) "" else "\n   останній автозапуск · last: $it" } +
            (if (PairingService.state.isNotEmpty()) "\npairing: ${PairingService.state}" else "") +
            (if (helperMsg.isNotEmpty()) "\n$helperMsg" else "")
    }

    // ---------- advanced ----------

    private fun refreshAdvanced() {
        val srv = StreamService.instanceServer
        diag.text = buildString {
            append("address: ").append(if (CarVpnService.running) CarVpnService.address else "- ${CarVpnService.lastError}")
                .append(if (CarVpnService.rebuilds > 0) "  (rebuilt ${CarVpnService.rebuilds}×)" else "")
            append("\nstream: ").append(StreamService.status)
            if (StreamService.carStatus.isNotEmpty()) append("\ncar screen: ").append(StreamService.carStatus)
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
