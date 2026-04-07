package com.nexora

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.nexora.ui.screens.LogViewModel
import com.nexora.ui.screens.MainScreen
import com.nexora.ui.screens.VpnState
import com.nexora.ui.theme.NexoraTheme

class MainActivity : ComponentActivity() {

    private val logVm: LogViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) startVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelPrefs.setProfile(this, "normal")
        requestBatteryExemptionIfNeeded()

        setContent {
            NexoraTheme {
                val session by TunnelSessionStore.stateFlow.collectAsStateWithLifecycle()
                val logEntries by logVm.entries.collectAsStateWithLifecycle()

                val vpnState = when (session.state) {
                    "CONNECTED" -> VpnState.CONNECTED
                    "CONNECTING" -> VpnState.CONNECTING
                    "ERROR" -> VpnState.ERROR
                    else -> VpnState.IDLE
                }

                var isHotspot by rememberSaveable { mutableStateOf(TunnelPrefs.isHotspotProxyEnabled(this)) }

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
                        val ip = BtProxy.getHotspotIp()
                        if (enabled && ip == null) {
                            Toast.makeText(this, getString(R.string.hotspot_enable_first), Toast.LENGTH_SHORT).show()
                        } else {
                            isHotspot = enabled
                            TunnelPrefs.setHotspotProxyEnabled(this, enabled)
                        }
                    },
                    hotspotIp = BtProxy.getHotspotIp(),
                    onIgnoreBatteryClick = {
                        val requested = requestBatteryOptimizationExemption()
                        if (!requested) openBatterySettings()
                    }
                )
            }
        }
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
        startService(Intent(this, TunnelCoreService::class.java).setAction(TunnelCoreService.ACTION_START))
    }

    private fun stopVpn() {
        startService(Intent(this, TunnelCoreService::class.java).setAction(TunnelCoreService.ACTION_STOP))
    }

    private fun copyClientId() {
        val clientId = TunnelPrefs.getOrCreateClientId(this)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("client_id", clientId))
        Toast.makeText(this, getString(R.string.client_id_copied), Toast.LENGTH_SHORT).show()
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
            "motorola" -> "Ajustes → Batería → Gestión de batería → Nexora → Sin restricciones"
            "xiaomi", "redmi", "poco" -> "Ajustes → Aplicaciones → Nexora → Batería → Sin restricciones"
            "samsung" -> "Ajustes → Batería → Optimización de batería → Nexora → No optimizar"
            "huawei", "honor" -> "Ajustes → Batería → Inicio de aplicaciones → Nexora → Activar manualmente"
            "oppo", "realme", "oneplus" -> "Ajustes → Batería → Optimización de batería → Nexora → No optimizar"
            else -> "Ajustes → Batería → Optimización de batería → Nexora → No optimizar"
        }
    }
}
