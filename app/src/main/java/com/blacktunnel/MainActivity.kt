package com.blacktunnel

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Build
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var wifiDirectSwitch: SwitchCompat
    private lateinit var blockNonSelectedSwitch: SwitchCompat
    private lateinit var wifiDirectPasswordInput: EditText
    private lateinit var wifiDirectLegacyHint: TextView
    private lateinit var wifiDirectCredsInfo: TextView
    private lateinit var wifiDirectSocks5Info: TextView
    private lateinit var wifiDirectHttpInfo: TextView
    private lateinit var socks5Info: TextView
    private lateinit var httpInfo: TextView
    private lateinit var stateText: TextView
    private lateinit var statusValue: TextView
    private lateinit var latencyValue: TextView
    private lateinit var nameValue: TextView
    private lateinit var daysLeftValue: TextView

    private lateinit var clientId: String
    private val allApps = mutableListOf<Pair<String, String>>()
    private var filteredApps = listOf<Pair<String, String>>()
    private val selectedPackages = mutableSetOf<String>()
    private var pendingWifiDirectStart = false

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
            val requested = requestBatteryOptimizationExemption()
            if (!requested) openBatterySettings()
        }
        hotspotSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && BtProxy.getHotspotIp() == null) {
                hotspotSwitch.isChecked = false
                Toast.makeText(this, getString(R.string.hotspot_enable_first), Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            TunnelPrefs.setHotspotProxyEnabled(this, isChecked)
            render(TunnelSessionStore.current())
        }
        wifiDirectSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val pass = wifiDirectPasswordInput.text?.toString().orEmpty().trim()
                if (pass.length < WIFI_DIRECT_MIN_PASSWORD_LEN) {
                    Toast.makeText(this, getString(R.string.wifi_direct_password_min), Toast.LENGTH_SHORT).show()
                    wifiDirectSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                BtWifiDirect.savePassword(this, pass)
                requestWifiDirectPermissionOrStart()
            } else {
                pendingWifiDirectStart = false
                BtWifiDirect.stop(this)
                refreshWifiDirectInfo()
            }
        }
        wifiDirectPasswordInput.addTextChangedListener(SimpleTextWatcher {
            val pass = it.trim()
            if (pass.length >= WIFI_DIRECT_MIN_PASSWORD_LEN) BtWifiDirect.savePassword(this, pass)
            refreshWifiDirectInfo()
        })

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

    override fun onResume() {
        super.onResume()
        refreshHotspotInfo()
        refreshWifiDirectInfo()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_WIFI_DIRECT_PERMISSION) return

        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted && pendingWifiDirectStart && wifiDirectSwitch.isChecked) {
            pendingWifiDirectStart = false
            startWifiDirect()
            return
        }

        pendingWifiDirectStart = false
        wifiDirectSwitch.isChecked = false
        Toast.makeText(this, getString(R.string.wifi_direct_permission_required), Toast.LENGTH_SHORT).show()
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
        wifiDirectSwitch = findViewById(R.id.switchWifiDirect)
        blockNonSelectedSwitch = findViewById(R.id.blockNonSelectedSwitch)
        wifiDirectPasswordInput = findViewById(R.id.etWifiDirectPassword)
        wifiDirectLegacyHint = findViewById(R.id.tvWifiDirectLegacyHint)
        wifiDirectCredsInfo = findViewById(R.id.tvWifiDirectCredentials)
        wifiDirectSocks5Info = findViewById(R.id.tvWifiDirectSocks5Info)
        wifiDirectHttpInfo = findViewById(R.id.tvWifiDirectHttpInfo)
        socks5Info = findViewById(R.id.tvSocks5Info)
        httpInfo = findViewById(R.id.tvHttpInfo)
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
        wifiDirectPasswordInput.setText(BtWifiDirect.getSavedPassword(this))
        wifiDirectLegacyHint.visibility = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) View.VISIBLE else View.GONE
        wifiDirectSwitch.isChecked = BtWifiDirect.isActive

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
        refreshHotspotInfo()
        refreshWifiDirectInfo()
    }

    private fun refreshHotspotInfo() {
        val ip = BtProxy.getHotspotIp()
        if (ip != null && hotspotSwitch.isChecked) {
            socks5Info.text = getString(R.string.hotspot_socks5_info, ip, HOTSPOT_SOCKS5_PORT)
            httpInfo.text = getString(R.string.hotspot_http_info, ip, HOTSPOT_HTTP_PORT)
            return
        }

        if (ip == null && TunnelPrefs.isHotspotProxyEnabled(this)) {
            TunnelPrefs.setHotspotProxyEnabled(this, false)
            hotspotSwitch.isChecked = false
        }
        socks5Info.text = ""
        httpInfo.text = ""
    }

    private fun refreshWifiDirectInfo() {
        if (!BtWifiDirect.isActive) {
            wifiDirectCredsInfo.text = ""
            wifiDirectSocks5Info.text = ""
            wifiDirectHttpInfo.text = ""
            return
        }
        val info = BtWifiDirect.getConnectionInfo(this)
        val ssid = info["ssid"]?.toString().orEmpty()
        val password = info["password"]?.toString().orEmpty()
        val ip = info["ip"]?.toString().orEmpty()
        val socks5Port = info["socks5"]?.toString().orEmpty()
        val httpPort = info["http"]?.toString().orEmpty()
        wifiDirectCredsInfo.text = getString(R.string.wifi_direct_credentials, ssid, password)
        wifiDirectSocks5Info.text = getString(R.string.wifi_direct_socks5_info, ip, socks5Port)
        wifiDirectHttpInfo.text = getString(R.string.wifi_direct_http_info, ip, httpPort)
    }

    private fun requestWifiDirectPermissionOrStart() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startWifiDirect()
            return
        }
        pendingWifiDirectStart = true
        ActivityCompat.requestPermissions(this, arrayOf(permission), REQ_WIFI_DIRECT_PERMISSION)
    }

    private fun startWifiDirect() {
        BtWifiDirect.start(this) { success ->
            runOnUiThread {
                if (!success) {
                    wifiDirectSwitch.isChecked = false
                    Toast.makeText(this, getString(R.string.wifi_direct_start_error), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                refreshWifiDirectInfo()
            }
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

    private fun requestBatteryOptimizationExemption(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return false

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    private fun openBatterySettings() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()

        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
            intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            intents += Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            intents += Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        } else if (manufacturer.contains("samsung")) {
            intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
        } else if (manufacturer.contains("motorola")) {
            intents += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }

        intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        intents += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

        val opened = intents.firstOrNull { intent ->
            intent.resolveActivity(packageManager) != null &&
                runCatching { startActivity(intent); true }.getOrDefault(false)
        }
        if (opened == null) Toast.makeText(this, getString(R.string.battery_settings_failed), Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_VPN_PREPARE = 11
        private const val LATENCY_OFFSET_MS = 260L
        private const val HOTSPOT_SOCKS5_PORT = 1080
        private const val HOTSPOT_HTTP_PORT = 8282
        private const val WIFI_DIRECT_MIN_PASSWORD_LEN = 8
        private const val REQ_WIFI_DIRECT_PERMISSION = 3101
    }
}
