package com.blacktunnel

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {
    private lateinit var toggleButton: MaterialButton
    private lateinit var saveSettingsButton: MaterialButton
    private lateinit var batteryButton: MaterialButton
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

        appSearchInput.addTextChangedListener(SimpleTextWatcher {
            filterAppList(it)
        })

        appListView.setOnItemClickListener { _, _, position, _ ->
            val pkg = filteredApps[position].second
            if (selectedPackages.contains(pkg)) selectedPackages.remove(pkg) else selectedPackages.add(pkg)
        }

        toggleButton.setOnClickListener { onToggle() }
        saveSettingsButton.setOnClickListener { saveSettings() }
        batteryButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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

    private fun loadInstalledApps() {
        allApps.clear()
        val apps = packageManager.getInstalledApplications(0)
        allApps += apps.map {
            val label = packageManager.getApplicationLabel(it).toString()
            label to it.packageName
        }.sortedBy { it.first.lowercase() }
        filterAppList("")
    }

    private fun filterAppList(query: String) {
        filteredApps = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.first.contains(query, true) || it.second.contains(query, true)
            }
        }
        val labels = filteredApps.map { "${it.first} (${it.second})" }
        appListView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        filteredApps.forEachIndexed { index, pair ->
            appListView.setItemChecked(index, selectedPackages.contains(pair.second))
        }
    }

    private fun loadSettings() {
        val profile = TunnelPrefs.getProfile(this)
        profileNormal.isChecked = profile == "normal"
        profilePerformance.isChecked = profile == "performance"
        selectedPackages.clear()
        selectedPackages += TunnelPrefs.getIncludedApps(this)
        filterAppList("")
        updatePerformanceVisibility()
    }

    private fun updatePerformanceVisibility() {
        performanceContainer.visibility = if (profilePerformance.isChecked) LinearLayout.VISIBLE else LinearLayout.GONE
    }

    private fun saveSettings() {
        val profile = if (profilePerformance.isChecked) "performance" else "normal"
        TunnelPrefs.setProfile(this, profile)
        TunnelPrefs.setMux(this, if (profile == "performance") 36 else 24)
        TunnelPrefs.setIncludedApps(this, selectedPackages.toList())
        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
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
        latencyValue.text = getString(R.string.latency_label) + ": " + if (snapshot.latencyMs >= 0) "${snapshot.latencyMs} ms" else "-"
        nameValue.text = getString(R.string.session_name) + ": " + snapshot.name
        expireValue.text = getString(R.string.session_expire) + ": " + snapshot.expire
        daysLeftValue.text = getString(R.string.session_days_left) + ": " + snapshot.daysLeft
        premiumValue.text = getString(R.string.session_premium) + ": " + snapshot.premium
    }

    private fun onToggle() {
        val current = TunnelSessionStore.current()
        if (current.state == "CONNECTING" || current.state == "CONNECTED") {
            stopService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
            startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_STOP))
            TunnelSessionStore.reset()
            recreate()
            return
        }

        saveSettings()
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
        if (requestCode == REQ_VPN_PREPARE && resultCode == RESULT_OK) {
            startVpn()
        }
    }

    private fun startVpn() {
        TunnelSessionStore.setState("CONNECTING")
        startService(Intent(this, BtVpnService::class.java).setAction(BtVpnService.ACTION_START))
    }

    companion object {
        private const val REQ_VPN_PREPARE = 11
    }
}
