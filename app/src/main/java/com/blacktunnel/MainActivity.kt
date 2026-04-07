package com.blacktunnel

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacktunnel.ui.screens.LogViewModel
import com.blacktunnel.ui.screens.MainScreen
import com.blacktunnel.ui.screens.VpnState
import com.blacktunnel.ui.theme.BlackTunnelTheme

class MainActivity : ComponentActivity() {

    private val logVm: LogViewModel by viewModels()
    private var pendingWifiDirectStart = false
    private val wifiDirectEnabledState = mutableStateOf(BtWifiDirect.isActive)
    private val wifiDirectPasswordState = mutableStateOf("")

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiDirectPasswordState.value = BtWifiDirect.getSavedPassword(this)
        TunnelPrefs.setProfile(this, "normal")
        requestBatteryExemptionIfNeeded()

        setContent {
            BlackTunnelTheme {
                val session by TunnelSessionStore.stateFlow.collectAsStateWithLifecycle()
                val logEntries by logVm.entries.collectAsStateWithLifecycle()

                val vpnState = when (session.state) {
                    "CONNECTED" -> VpnState.CONNECTED
                    "CONNECTING" -> VpnState.CONNECTING
                    "ERROR" -> VpnState.ERROR
                    else -> VpnState.IDLE
                }

                var isHotspot by rememberSaveable { mutableStateOf(TunnelPrefs.isHotspotProxyEnabled(this)) }
                var isWifiDirect by wifiDirectEnabledState
                var wifiPass by wifiDirectPasswordState

                MainScreen(
                    state = vpnState,
                    clientName = session.name,
                    daysLeft = session.daysLeft,
                    latencyMs = session.latencyMs,
                    status = session.status,
                    connectedSince = session.connectedSince,
                    logEntries = logEntries,
                    onConnectClick = {
                        if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) {
                            stopVpn()
                            logVm.add("⏹", "Desconectando túnel", LogLevel.INFO)
                        } else {
                            logVm.clear()
                            logVm.add("▶", "Iniciando túnel", LogLevel.INFO)
                            startVpnWithPermission()
                        }
                    },
                    onCopyClientId = { copyClientId() },
                    isHotspotEnabled = isHotspot,
                    onHotspotToggle = { enabled ->
                        val ip = BtVpnService.resolveHotspotIp()
                        if (enabled && ip == null) {
                            Toast.makeText(this, getString(R.string.hotspot_enable_first), Toast.LENGTH_SHORT).show()
                        } else {
                            isHotspot = enabled
                            TunnelPrefs.setHotspotProxyEnabled(this, enabled)
                        }
                    },
                    hotspotIp = BtVpnService.resolveHotspotIp(),
                    isWifiDirectEnabled = isWifiDirect,
                    onWifiDirectToggle = { enabled ->
                        if (enabled) {
                            if (wifiPass.length < WIFI_DIRECT_MIN_PASSWORD_LEN) {
                                Toast.makeText(this, getString(R.string.wifi_direct_password_min), Toast.LENGTH_SHORT).show()
                            } else {
                                BtWifiDirect.savePassword(this, wifiPass)
                                requestWifiDirectPermissionOrStart {
                                    BtWifiDirect.start(this) { ok -> isWifiDirect = ok }
                                }
                            }
                        } else {
                            BtWifiDirect.stop(this)
                            isWifiDirect = false
                        }
                    },
                    wifiDirectPassword = wifiPass,
                    onWifiDirectPasswordChange = { pwd ->
                        wifiPass = pwd
                        if (pwd.length >= WIFI_DIRECT_MIN_PASSWORD_LEN) BtWifiDirect.savePassword(this, pwd)
                    },
                    onIgnoreBatteryClick = {
                        val requested = requestBatteryOptimizationExemption()
                        if (!requested) openBatterySettings()
                    }
                )
            }
        }
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
        if (granted && pendingWifiDirectStart) {
            pendingWifiDirectStart = false
            BtWifiDirect.start(this) { ok -> wifiDirectEnabledState.value = ok }
            return
        }

        pendingWifiDirectStart = false
        Toast.makeText(this, getString(R.string.wifi_direct_permission_required), Toast.LENGTH_SHORT).show()
    }

    private fun startVpnWithPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
            return
        }
        startVpn()
    }

    private fun startVpn() {
        TunnelSessionStore.setState("CONNECTING")
        startService(BtVpnService.startIntent(this))
    }

    private fun stopVpn() {
        startService(BtVpnService.stopIntent(this))
    }

    private fun copyClientId() {
        val clientId = TunnelPrefs.getOrCreateClientId(this)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("client_id", clientId))
        Toast.makeText(this, getString(R.string.client_id_copied), Toast.LENGTH_SHORT).show()
    }

    private fun requestWifiDirectPermissionOrStart(onGranted: () -> Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onGranted()
            return
        }
        pendingWifiDirectStart = true
        requestPermissions(arrayOf(permission), REQ_WIFI_DIRECT_PERMISSION)
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
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )

        val opened = intents.firstOrNull { intent ->
            intent.resolveActivity(packageManager) != null &&
                runCatching { startActivity(intent); true }.getOrDefault(false)
        }
        if (opened == null) Toast.makeText(this, getString(R.string.battery_settings_failed), Toast.LENGTH_SHORT).show()
    }

    private fun requestBatteryExemptionIfNeeded() {
        if (TunnelPrefs.isOnboardingShown(this)) return
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Mantener conexión activa")
                .setMessage(getBatteryInstructionsByBrand())
                .setPositiveButton("Configurar ahora") { _, _ ->
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                    TunnelPrefs.setOnboardingShown(this)
                }
                .setNegativeButton("Después", null)
                .show()
        } else {
            TunnelPrefs.setOnboardingShown(this)
        }
    }

    private fun getBatteryInstructionsByBrand(): String {
        return when (Build.MANUFACTURER.lowercase()) {
            "motorola" -> "Ajustes → Batería → Gestión de batería → XTunnel → Sin restricciones"
            "xiaomi", "redmi", "poco" -> "Ajustes → Aplicaciones → XTunnel → Batería → Sin restricciones"
            "samsung" -> "Ajustes → Batería → Optimización de batería → XTunnel → No optimizar"
            "huawei", "honor" -> "Ajustes → Batería → Inicio de aplicaciones → XTunnel → Activar manualmente"
            "oppo", "realme", "oneplus" -> "Ajustes → Batería → Optimización de batería → XTunnel → No optimizar"
            else -> "Ajustes → Batería → Optimización de batería → XTunnel → No optimizar"
        }
    }

    companion object {
        private const val WIFI_DIRECT_MIN_PASSWORD_LEN = 8
        private const val REQ_WIFI_DIRECT_PERMISSION = 3101
    }
}
