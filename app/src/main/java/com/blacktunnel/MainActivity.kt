package com.blacktunnel

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private lateinit var toggleButton: MaterialButton
    private lateinit var copyClientIdButton: MaterialButton
    private lateinit var saveSettingsButton: MaterialButton
    private lateinit var batteryButton: MaterialButton
    private lateinit var clientIdValue: TextView
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
    private lateinit var daysLeftValue: TextView

    private lateinit var clientId: String
    private val allApps = mutableListOf<Pair<String, String>>()
    private var filteredApps = listOf<Pair<String, String>>()
    private val selectedPackages = mutableSetOf<String>()

    private val sessionListener: (TunnelSessionSnapshot) -> Unit = { snapshot ->
        runOnUiThread { render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()

        clientId = TunnelPrefs.getOrCreateClientId(this)
        clientIdValue.text = clientId

        appListView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        loadInstalledApps()
        loadSettings()

        profileNormal.setOnCheckedChangeListener { _, _ -> updatePerformanceVisibility() }
        profilePerformance.setOnCheckedChangeListener { _, _ -> updatePerformanceVisibility() }

        appSearchInput.addTextChangedListener(SimpleTextWatcher { filterAppList(it) })
        appListView.setOnItemClickListener { _, _, position, _ ->
            val pkg = filteredApps[position].second
            if (selectedPackages.contains(pkg)) selectedPackages.remove(pkg) else selectedPackages.add(pkg)
            filterAppList(appSearchInput.text?.toString().orEmpty())
        }

        toggleButton.setOnClickListener { onToggle() }
        copyClientIdButton.setOnClickListener { copyClientId() }
        saveSettingsButton.setOnClickListener { saveSettings(showToast = true) }
        batteryButton.setOnClickListener {
            requestBatteryOptimizationExemption()
            openBatterySettings()
        }
        hotspotSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && getHotspotIp() == null) {
                hotspotSwitch.isChecked = false
                Toast.makeText(this, getString(R.string.hotspot_requires_system), Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            TunnelPrefs.setHotspotProxyEnabled(this, isChecked)
            render(TunnelSessionStore.current())
        }

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

    private fun bindViews() {
        toggleButton = findViewById(R.id.toggleButton)
        copyClientIdButton = findViewById(R.id.copyClientIdButton)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        batteryButton = findViewById(R.id.batteryButton)
        clientIdValue = findViewById(R.id.clientIdValue)
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
        daysLeftValue = findViewById(R.id.daysLeftValue)
    }

    private fun loadInstalledApps() {
        allApps.clear()
        allApps += packageManager.getInstalledPackages(0)
            .mapNotNull {
                val systemApp = (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (systemApp) return@mapNotNull null
                val launchIntent = packageManager.getLaunchIntentForPackage(it.packageName) ?: return@mapNotNull null
                val label = packageManager.getApplicationLabel(it.applicationInfo).toString()
                label to (launchIntent.component?.packageName ?: it.packageName)
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        filterAppList("")
    }

    private fun filterAppList(query: String) {
        val baseList = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.first.contains(query, true) || it.second.contains(query, true) }
        }

        filteredApps = baseList.sortedWith(
            compareByDescending<Pair<String, String>> { selectedPackages.contains(it.second) }
                .thenBy { it.first.lowercase() }
                .thenBy { it.second.lowercase() }
        )

        appListView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            filteredApps.map { "${it.first} (${it.second})" }
        )
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

    private fun copyClientId() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("client_id", clientId))
        Toast.makeText(this, getString(R.string.client_id_copied), Toast.LENGTH_SHORT).show()
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
        if (profile == "normal") TunnelPrefs.setHotspotProxyEnabled(this, hotspotSwitch.isChecked)
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

        toggleButton.text = when (snapshot.state) {
            "CONNECTING" -> getString(R.string.connecting)
            "CONNECTED" -> getString(R.string.disconnect)
            else -> getString(R.string.connect)
        }

        statusValue.text = getString(R.string.session_status) + ": " + snapshot.status
        val correctedLatency = if (snapshot.latencyMs >= 0) (snapshot.latencyMs - LATENCY_OFFSET_MS).coerceAtLeast(0) else -1
        latencyValue.text = getString(R.string.latency_label) + ": " + if (correctedLatency >= 0) "${correctedLatency} ms" else "-"
        nameValue.text = getString(R.string.session_name) + ": " + snapshot.name
        daysLeftValue.text = getString(R.string.session_days_left) + ": " + snapshot.daysLeft
        hotspotInfo.text = if (hotspotSwitch.isChecked) {
            getString(R.string.hotspot_info, getHotspotIp() ?: "-", HOTSPOT_PORT)
        } else {
            getString(R.string.hotspot_disabled)
        }
    }

    private fun onToggle() {
        val current = TunnelSessionStore.current()
        if (current.state == "CONNECTING" || current.state == "CONNECTED") {
            startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
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

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
    }

    private fun openBatterySettings() {
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()

        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
            intents += Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            intents += Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        } else if (manufacturer.contains("samsung")) {
            intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
        }

        intents += Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
        intents += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }

        val opened = intents.firstOrNull { runCatching { startActivity(it); true }.getOrDefault(false) }
        if (opened == null) Toast.makeText(this, getString(R.string.battery_settings_failed), Toast.LENGTH_SHORT).show()
    }

    private fun getHotspotIp(): String? = runCatching {
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

    companion object {
        private const val REQ_VPN_PREPARE = 11
        private const val LATENCY_OFFSET_MS = 260L
        private const val HOTSPOT_PORT = 1080
    }
}
