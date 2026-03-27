package com.blacktunnel

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {
    private lateinit var toggleButton: MaterialButton
    private lateinit var saveSettingsButton: MaterialButton
    private lateinit var batteryButton: MaterialButton
    private lateinit var profileNormal: RadioButton
    private lateinit var profilePerformance: RadioButton
    private lateinit var normalContainer: LinearLayout
    private lateinit var performanceContainer: LinearLayout
    private lateinit var appSearchInput: EditText
    private lateinit var appListView: ListView
    private lateinit var hotspotSwitch: SwitchCompat
    private lateinit var blockNonSelectedSwitch: SwitchCompat
    private lateinit var hotspotInfo: TextView
    private lateinit var stateText: TextView
    private lateinit var statusValue: TextView
    private lateinit var latencyValue: TextView
    private lateinit var nameValue: TextView
    private lateinit var expireValue: TextView
    private lateinit var daysLeftValue: TextView
    private lateinit var premiumValue: TextView
    private lateinit var settingsCard: com.google.android.material.card.MaterialCardView
    private lateinit var identifierContainer: LinearLayout
    private lateinit var identifierValue: TextView
    private lateinit var adminContainer: LinearLayout
    private lateinit var adminIdentifierInput: EditText
    private lateinit var adminNameInput: EditText
    private lateinit var adminDaysInput: EditText
    private lateinit var adminResultText: TextView
    private lateinit var adminCreateButton: MaterialButton
    private lateinit var adminAddDaysButton: MaterialButton
    private lateinit var adminDeleteButton: MaterialButton
    private lateinit var adminListButton: MaterialButton

    private val allApps = mutableListOf<Pair<String, String>>()
    private var filteredApps = listOf<Pair<String, String>>()
    private val selectedPackages = mutableSetOf<String>()

    private val sessionListener: (TunnelSessionSnapshot) -> Unit = { snapshot ->
        runOnUiThread { render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        batteryButton = findViewById(R.id.batteryButton)
        profileNormal = findViewById(R.id.profileNormal)
        profilePerformance = findViewById(R.id.profilePerformance)
        normalContainer = findViewById(R.id.normalContainer)
        performanceContainer = findViewById(R.id.performanceContainer)
        appSearchInput = findViewById(R.id.appSearchInput)
        appListView = findViewById(R.id.appListView)
        hotspotSwitch = findViewById(R.id.hotspotSwitch)
        blockNonSelectedSwitch = findViewById(R.id.blockNonSelectedSwitch)
        hotspotInfo = findViewById(R.id.hotspotInfo)
        stateText = findViewById(R.id.stateText)
        statusValue = findViewById(R.id.statusValue)
        latencyValue = findViewById(R.id.latencyValue)
        nameValue = findViewById(R.id.nameValue)
        expireValue = findViewById(R.id.expireValue)
        daysLeftValue = findViewById(R.id.daysLeftValue)
        premiumValue = findViewById(R.id.premiumValue)
        settingsCard = findViewById(R.id.settingsCard)
        identifierContainer = findViewById(R.id.identifierContainer)
        identifierValue = findViewById(R.id.identifierValue)
        adminContainer = findViewById(R.id.adminContainer)
        adminIdentifierInput = findViewById(R.id.adminIdentifierInput)
        adminNameInput = findViewById(R.id.adminNameInput)
        adminDaysInput = findViewById(R.id.adminDaysInput)
        adminResultText = findViewById(R.id.adminResultText)
        adminCreateButton = findViewById(R.id.adminCreateButton)
        adminAddDaysButton = findViewById(R.id.adminAddDaysButton)
        adminDeleteButton = findViewById(R.id.adminDeleteButton)
        adminListButton = findViewById(R.id.adminListButton)

        appListView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        loadInstalledApps()
        loadSettings()
        setupModeSections()

        profileNormal.setOnCheckedChangeListener { _, _ -> updatePerformanceVisibility() }
        profilePerformance.setOnCheckedChangeListener { _, _ -> updatePerformanceVisibility() }

        appSearchInput.addTextChangedListener(SimpleTextWatcher { filterAppList(it) })
        appListView.setOnItemClickListener { _, _, position, _ ->
            val pkg = filteredApps[position].second
            if (selectedPackages.contains(pkg)) selectedPackages.remove(pkg) else selectedPackages.add(pkg)
            filterAppList(appSearchInput.text?.toString().orEmpty())
        }

        toggleButton.setOnClickListener { onToggle() }
        saveSettingsButton.setOnClickListener { saveSettings(showToast = true) }
        batteryButton.setOnClickListener { openBatterySettings() }
        hotspotSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && getHotspotIp() == null) {
                hotspotSwitch.isChecked = false
                Toast.makeText(this, getString(R.string.hotspot_requires_system), Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            TunnelPrefs.setHotspotProxyEnabled(this, isChecked)
            render(TunnelSessionStore.current())
        }

        setupAdminActions()
        render(TunnelSessionStore.current())
    }

    override fun onStart() {
        super.onStart()
        TunnelSessionStore.addListener(sessionListener)
    }

    override fun onStop() {
        TunnelSessionStore.removeListener(sessionListener)
        super.onStop()
    }

    private fun setupModeSections() {
        val isReseller = BuildConfig.APP_MODE.equals("reseller", ignoreCase = true)
        val clientIdentifier = TunnelPrefs.getClientIdentifier(this)

        identifierContainer.visibility = if (isReseller) View.GONE else View.VISIBLE
        adminContainer.visibility = if (isReseller) View.VISIBLE else View.GONE
        settingsCard.visibility = if (isReseller) View.VISIBLE else View.GONE
        identifierValue.text = clientIdentifier
    }

    private fun setupAdminActions() {
        val isReseller = BuildConfig.APP_MODE.equals("reseller", ignoreCase = true)
        if (!isReseller) return

        adminCreateButton.setOnClickListener {
            val body = JSONObject().apply {
                put("identifier", adminIdentifierInput.text?.toString().orEmpty().trim())
                put("name", adminNameInput.text?.toString().orEmpty().trim())
                put("days", adminDaysInput.text?.toString().orEmpty().trim().toIntOrNull() ?: 0)
            }
            runAdminRequest("POST", "/admin/users", body)
        }

        adminAddDaysButton.setOnClickListener {
            val body = JSONObject().apply {
                put("identifier", adminIdentifierInput.text?.toString().orEmpty().trim())
                put("days", adminDaysInput.text?.toString().orEmpty().trim().toIntOrNull() ?: 0)
            }
            runAdminRequest("POST", "/admin/users/add-days", body)
        }

        adminDeleteButton.setOnClickListener {
            val id = adminIdentifierInput.text?.toString().orEmpty().trim()
            runAdminRequest("DELETE", "/admin/users/$id", null)
        }

        adminListButton.setOnClickListener {
            runAdminRequest("GET", "/admin/users", null)
        }
    }

    private fun runAdminRequest(method: String, path: String, body: JSONObject?) {
        thread(isDaemon = true) {
            val result = runCatching {
                val server = BuildConfig.SERVER_URL.trimEnd('/')
                val conn = (URL(server + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    val creds = "${BuildConfig.RESELLER_ADMIN_USER}:${BuildConfig.RESELLER_ADMIN_PASS}"
                    val basic = Base64.getEncoder().encodeToString(creds.toByteArray())
                    setRequestProperty("Authorization", "Basic $basic")
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        outputStream.use { it.write(body.toString().toByteArray()) }
                    }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.readText().orEmpty().ifBlank { "HTTP $code" }
                conn.disconnect()
                "HTTP $code\n$text"
            }.getOrElse { "Error: ${it.message}" }

            runOnUiThread { adminResultText.text = result }
        }
    }

    private fun loadInstalledApps() {
        allApps.clear()
        val packages = packageManager.getInstalledPackages(0)
        allApps += packages.mapNotNull {
            val systemApp = (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (systemApp) return@mapNotNull null
            val launchIntent = packageManager.getLaunchIntentForPackage(it.packageName) ?: return@mapNotNull null
            val label = packageManager.getApplicationLabel(it.applicationInfo).toString()
            label to (launchIntent.component?.packageName ?: it.packageName)
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
        filterAppList("")
    }

    private fun filterAppList(query: String) {
        val baseList = if (query.isBlank()) allApps else allApps.filter {
            it.first.contains(query, true) || it.second.contains(query, true)
        }
        filteredApps = baseList.sortedWith(
            compareByDescending<Pair<String, String>> { selectedPackages.contains(it.second) }
                .thenBy { it.first.lowercase() }
                .thenBy { it.second.lowercase() }
        )
        val labels = filteredApps.map { "${it.first} (${it.second})" }
        appListView.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        filteredApps.forEachIndexed { index, pair -> appListView.setItemChecked(index, selectedPackages.contains(pair.second)) }
    }

    private fun loadSettings() {
        val profile = TunnelPrefs.getProfile(this).ifBlank { "normal" }
        profileNormal.isChecked = profile == "normal"
        profilePerformance.isChecked = profile == "performance"
        hotspotSwitch.isChecked = TunnelPrefs.isHotspotProxyEnabled(this)
        blockNonSelectedSwitch.isChecked = TunnelPrefs.isBlockNonSelectedEnabled(this)

        selectedPackages.clear()
        selectedPackages += TunnelPrefs.getIncludedApps(this)
        filterAppList("")
        updatePerformanceVisibility()
    }

    private fun updatePerformanceVisibility() {
        val performance = profilePerformance.isChecked
        normalContainer.visibility = if (performance) View.GONE else View.VISIBLE
        performanceContainer.visibility = if (performance) View.VISIBLE else View.GONE
        saveSettingsButton.visibility = if (performance) View.VISIBLE else View.GONE
    }

    private fun saveSettings(showToast: Boolean) {
        val profile = if (profilePerformance.isChecked) "performance" else "normal"
        TunnelPrefs.setProfile(this, profile)
        TunnelPrefs.setMux(this, if (profile == "performance") 60 else 32)
        TunnelPrefs.setIncludedApps(this, selectedPackages.toList())
        if (profile == "normal") {
            TunnelPrefs.setHotspotProxyEnabled(this, hotspotSwitch.isChecked)
        }
        TunnelPrefs.setBlockNonSelectedEnabled(this, blockNonSelectedSwitch.isChecked)
        if (showToast) Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun render(snapshot: TunnelSessionSnapshot) {
        stateText.text = when (snapshot.state) {
            "CONNECTING" -> getString(R.string.state_connecting)
            "CONNECTED" -> getString(R.string.state_connected)
            "ERROR" -> getString(R.string.state_error)
            else -> getString(R.string.state_disconnected)
        }

        val isConnected = snapshot.state == "CONNECTING" || snapshot.state == "CONNECTED"
        toggleButton.text = if (isConnected) getString(R.string.disconnect) else getString(R.string.connect)

        statusValue.text = getString(R.string.session_status) + ": " + snapshot.status
        val correctedLatency = if (snapshot.latencyMs >= 0) (snapshot.latencyMs - LATENCY_OFFSET_MS).coerceAtLeast(0) else -1
        latencyValue.text = getString(R.string.latency_label) + ": " + if (correctedLatency >= 0) "${correctedLatency} ms" else "-"
        nameValue.text = getString(R.string.session_name) + ": " + snapshot.name
        expireValue.text = getString(R.string.session_expire) + ": " + snapshot.expire
        daysLeftValue.text = getString(R.string.session_days_left) + ": " + snapshot.daysLeft
        premiumValue.text = getString(R.string.session_premium) + ": " + snapshot.premium
        hotspotInfo.text = if (hotspotSwitch.isChecked) getString(R.string.hotspot_info, getHotspotIp() ?: "-", HOTSPOT_PORT) else getString(R.string.hotspot_disabled)
    }

    private fun onToggle() {
        val current = TunnelSessionStore.current()
        if (current.state == "CONNECTING" || current.state == "CONNECTED") {
            forceRestartAfterStop()
            return
        }

        saveSettings(showToast = false)
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, REQ_VPN_PREPARE)
            return
        }
        startVpn()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VPN_PREPARE && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        TunnelSessionStore.setState("CONNECTING")
        startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_START))
    }

    private fun openBatterySettings() {
        val intents = mutableListOf<Intent>()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        if (manufacturer.contains("xiaomi")) {
            intents += Intent().setClassName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
            ).putExtra("package_name", packageName).putExtra("package_label", getString(R.string.app_name))
        }
        intents += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }

        val opened = intents.firstOrNull { runCatching { startActivity(it); true }.getOrDefault(false) }
        if (opened == null) Toast.makeText(this, getString(R.string.battery_settings_failed), Toast.LENGTH_SHORT).show()
    }

    private fun forceRestartAfterStop() {
        stopService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
        startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
        TunnelSessionStore.reset()

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                1122,
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + 350, pendingIntent)
        }

        finishAffinity()
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }

    private fun getHotspotIp(): String? {
        return runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            val candidates = mutableListOf<Pair<String, String>>()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        candidates += name to addr.hostAddress
                    }
                }
            }
            candidates.firstOrNull { (name, ip) ->
                (name.contains("ap") || name.contains("swlan") || name.contains("rndis") || name.contains("wlan")) &&
                    !ip.startsWith("127.")
            }?.second ?: candidates.firstOrNull()?.second
        }.getOrNull()
    }

    companion object {
        private const val REQ_VPN_PREPARE = 11
        private const val LATENCY_OFFSET_MS = 260L
        private const val HOTSPOT_PORT = 1080
    }
}
