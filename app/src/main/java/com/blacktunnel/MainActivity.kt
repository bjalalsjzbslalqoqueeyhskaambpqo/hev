package com.blacktunnel

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {
    private lateinit var toggleButton: MaterialButton
    private lateinit var saveSettingsButton: MaterialButton
    private lateinit var batteryButton: MaterialButton
    private lateinit var profileNormal: RadioButton
    private lateinit var profilePerformance: RadioButton
    private lateinit var includedAppsInput: TextInputEditText
    private lateinit var stateText: TextView
    private lateinit var statusValue: TextView
    private lateinit var nameValue: TextView
    private lateinit var expireValue: TextView
    private lateinit var daysLeftValue: TextView
    private lateinit var premiumValue: TextView

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
        includedAppsInput = findViewById(R.id.includedAppsInput)
        stateText = findViewById(R.id.stateText)
        statusValue = findViewById(R.id.statusValue)
        nameValue = findViewById(R.id.nameValue)
        expireValue = findViewById(R.id.expireValue)
        daysLeftValue = findViewById(R.id.daysLeftValue)
        premiumValue = findViewById(R.id.premiumValue)

        loadSettings()
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

    private fun loadSettings() {
        val profile = TunnelPrefs.getProfile(this)
        profileNormal.isChecked = profile == "normal"
        profilePerformance.isChecked = profile == "performance"
        includedAppsInput.setText(TunnelPrefs.getIncludedApps(this).joinToString(","))
    }

    private fun saveSettings() {
        val profile = if (profilePerformance.isChecked) "performance" else "normal"
        val packages = includedAppsInput.text
            ?.toString()
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        TunnelPrefs.setProfile(this, profile)
        TunnelPrefs.setMux(this, if (profile == "performance") 32 else 16)
        TunnelPrefs.setIncludedApps(this, packages)
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
