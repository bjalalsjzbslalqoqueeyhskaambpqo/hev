package com.blacktunnel

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var toggleButton: MaterialButton
    private lateinit var refreshServersButton: MaterialButton
    private lateinit var saveSettingsButton: MaterialButton
    private lateinit var batteryButton: MaterialButton
    private lateinit var serverSpinner: Spinner
    private lateinit var profileNormal: RadioButton
    private lateinit var profilePerformance: RadioButton
    private lateinit var performanceContainer: LinearLayout
    private lateinit var appSearchInput: EditText
    private lateinit var appListView: ListView
    private lateinit var stateText: TextView
    private lateinit var statusValue: TextView
    private lateinit var latencyValue: TextView
    private lateinit var nameValue: TextView
    private lateinit var expireValue: TextView
    private lateinit var daysLeftValue: TextView
    private lateinit var premiumValue: TextView

    private val allApps = mutableListOf<Pair<String, String>>()
    private var filteredApps = listOf<Pair<String, String>>()
    private val selectedPackages = mutableSetOf<String>()
    private var servers = mutableListOf<String>()

    private val sessionListener: (TunnelSessionSnapshot) -> Unit = { snapshot ->
        runOnUiThread { render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        refreshServersButton = findViewById(R.id.refreshServersButton)
        saveSettingsButton = findViewById(R.id.saveSettingsButton)
        batteryButton = findViewById(R.id.batteryButton)
        serverSpinner = findViewById(R.id.serverSpinner)
        profileNormal = findViewById(R.id.profileNormal)
        profilePerformance = findViewById(R.id.profilePerformance)
        performanceContainer = findViewById(R.id.performanceContainer)
        appSearchInput = findViewById(R.id.appSearchInput)
        appListView = findViewById(R.id.appListView)
        stateText = findViewById(R.id.stateText)
        statusValue = findViewById(R.id.statusValue)
        latencyValue = findViewById(R.id.latencyValue)
        nameValue = findViewById(R.id.nameValue)
        expireValue = findViewById(R.id.expireValue)
        daysLeftValue = findViewById(R.id.daysLeftValue)
        premiumValue = findViewById(R.id.premiumValue)

        appListView.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        loadInstalledApps()
        loadSettings()

        profileNormal.setOnCheckedChangeListener { _, _ -> updatePerformanceVisibility() }
        profilePerformance.setOnCheckedChangeListener { _, _ -> updatePerformanceVisibility() }

        appSearchInput.addTextChangedListener(SimpleTextWatcher { filterAppList(it) })
        appListView.setOnItemClickListener { _, _, position, _ ->
            val pkg = filteredApps[position].second
            if (selectedPackages.contains(pkg)) selectedPackages.remove(pkg) else selectedPackages.add(pkg)
        }

        serverSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                servers.getOrNull(position)?.let { TunnelPrefs.setSelectedServer(this@MainActivity, it) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        toggleButton.setOnClickListener { onToggle() }
        refreshServersButton.setOnClickListener { refreshServers(manual = true) }
        saveSettingsButton.setOnClickListener { saveSettings(showToast = true) }
        batteryButton.setOnClickListener { openBatterySettings() }

        refreshServers(manual = false)
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

    private fun loadInstalledApps() {
        allApps.clear()
        val apps = packageManager.getInstalledApplications(0)
        allApps += apps.map {
            val label = packageManager.getApplicationLabel(it).toString()
            label to it.packageName
        }.sortedBy { it.first.lowercase() }
        filterAppList("")
    }

    private fun refreshServers(manual: Boolean) {
        thread(isDaemon = true, name = "central-servers-fetch") {
            val fetched = CentralServerDiscovery.fetchServers { LogStore.add(it) }
            val merged = (fetched + TunnelPrefs.getCentralServers(this)).distinct().toMutableList()
            if (merged.isEmpty()) {
                merged += "7.brawlpass.com.ar"
            }
            TunnelPrefs.setCentralServers(this, merged)
            runOnUiThread {
                updateServerSpinner(merged)
                if (manual) {
                    Toast.makeText(this, "Servidores actualizados", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateServerSpinner(newServers: List<String>) {
        servers = newServers.toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, servers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        serverSpinner.adapter = adapter
        val selected = TunnelPrefs.getSelectedServer(this)
        val index = servers.indexOf(selected).takeIf { it >= 0 } ?: 0
        serverSpinner.setSelection(index)
    }

    private fun filterAppList(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.first.contains(query, true) || it.second.contains(query, true) }
        }
        val labels = filteredApps.map { "${it.first} (${it.second})" }
        appListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        filteredApps.forEachIndexed { index, pair -> appListView.setItemChecked(index, selectedPackages.contains(pair.second)) }
    }

    private fun loadSettings() {
        val profile = TunnelPrefs.getProfile(this).ifBlank { "normal" }
        profileNormal.isChecked = profile == "normal"
        profilePerformance.isChecked = profile == "performance"
        selectedPackages.clear()
        selectedPackages += TunnelPrefs.getIncludedApps(this)
        updateServerSpinner(TunnelPrefs.getCentralServers(this))
        filterAppList("")
        updatePerformanceVisibility()
    }

    private fun updatePerformanceVisibility() {
        val performance = profilePerformance.isChecked
        performanceContainer.visibility = if (performance) View.VISIBLE else View.GONE
        saveSettingsButton.visibility = if (performance) View.VISIBLE else View.GONE
    }

    private fun saveSettings(showToast: Boolean) {
        val profile = if (profilePerformance.isChecked) "performance" else "normal"
        TunnelPrefs.setProfile(this, profile)
        TunnelPrefs.setMux(this, if (profile == "performance") 36 else 24)
        TunnelPrefs.setIncludedApps(this, selectedPackages.toList())
        if (showToast) {
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }
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
            ).putExtra("package_name", packageName)
                .putExtra("package_label", getString(R.string.app_name))
        }

        intents += Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        intents += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }

        val opened = intents.firstOrNull {
            runCatching { startActivity(it); true }.getOrDefault(false)
        }
        if (opened == null) {
            Toast.makeText(this, "No se pudo abrir ajustes de batería", Toast.LENGTH_SHORT).show()
        }
    }

    private fun forceRestartAfterStop() {
        stopService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
        startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
        TunnelSessionStore.reset()

        val restartIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(restartIntent)
        finish()
    }

    companion object {
        private const val REQ_VPN_PREPARE = 11
        private const val LATENCY_OFFSET_MS = 260L
    }
}
