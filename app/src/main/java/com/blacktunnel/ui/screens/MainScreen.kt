package com.blacktunnel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class VpnState { IDLE, CONNECTING, CONNECTED, ERROR }

data class LogEntry(val icon: String, val text: String, val color: Color)

@Composable
fun MainScreen(
    state: VpnState,
    clientName: String,
    daysLeft: String,
    latencyMs: Long,
    status: String,
    logEntries: List<LogEntry>,
    onConnectClick: () -> Unit,
    onCopyClientId: () -> Unit,
    isPerformance: Boolean,
    onModeChange: (Boolean) -> Unit,
    isHotspotEnabled: Boolean,
    onHotspotToggle: (Boolean) -> Unit,
    hotspotIp: String?,
    isWifiDirectEnabled: Boolean,
    onWifiDirectToggle: (Boolean) -> Unit,
    wifiDirectPassword: String,
    onWifiDirectPasswordChange: (String) -> Unit,
    onIgnoreBatteryClick: () -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("BlackTunnel", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(clientName.ifBlank { "-" }, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            IconButton(onClick = onCopyClientId) {
                Icon(Icons.Default.CopyAll, contentDescription = "Copiar ID", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .size(180.dp)
                .background(
                    color = when (state) {
                        VpnState.CONNECTED -> MaterialTheme.colorScheme.primary.copy(0.2f)
                        VpnState.CONNECTING -> Color(0xFFFBBF24).copy(0.2f)
                        VpnState.ERROR -> MaterialTheme.colorScheme.error.copy(0.2f)
                        else -> MaterialTheme.colorScheme.surface
                    },
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(if (state == VpnState.CONNECTED) "🔒" else "🔓", fontSize = 42.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("Estado: ${state.name}", color = MaterialTheme.colorScheme.onSurface)
        Text("Latencia: ${if (latencyMs >= 0) "$latencyMs ms" else "-"}", color = MaterialTheme.colorScheme.onSurface)
        Text("Servidor: $status", color = MaterialTheme.colorScheme.onSurface)
        Text("Días restantes: $daysLeft", color = MaterialTheme.colorScheme.onSurface)

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onConnectClick,
            enabled = state != VpnState.CONNECTING,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(if (state == VpnState.CONNECTED) "DESCONECTAR" else "CONECTAR", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = !isPerformance, onClick = { onModeChange(false) }, label = { Text("Normal") }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors())
            FilterChip(selected = isPerformance, onClick = { onModeChange(true) }, label = { Text("Performance") }, modifier = Modifier.weight(1f), colors = FilterChipDefaults.filterChipColors())
        }

        Spacer(Modifier.height(14.dp))

        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = if (showAdvanced) "▲ Ajustes avanzados" else "▼ Ajustes avanzados",
                    modifier = Modifier.clickable { showAdvanced = !showAdvanced },
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (showAdvanced) {
                    Spacer(Modifier.height(10.dp))
                    Divider()
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Hotspot proxy", color = MaterialTheme.colorScheme.onSurface)
                        Switch(checked = isHotspotEnabled, onCheckedChange = onHotspotToggle)
                    }
                    if (isHotspotEnabled && hotspotIp != null) {
                        Text("SOCKS5: $hotspotIp:1080", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.7f))
                        Text("HTTP: $hotspotIp:8282", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.7f))
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("WiFi Direct", color = MaterialTheme.colorScheme.onSurface)
                        Switch(checked = isWifiDirectEnabled, onCheckedChange = onWifiDirectToggle)
                    }
                    if (isWifiDirectEnabled) {
                        OutlinedTextField(
                            value = wifiDirectPassword,
                            onValueChange = onWifiDirectPasswordChange,
                            label = { Text("Contraseña WiFi") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Quitar restricción de batería",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onIgnoreBatteryClick)
                    )
                }
            }
        }

        if (logEntries.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(10.dp)) {
                    logEntries.takeLast(6).forEach {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(it.icon)
                            Spacer(Modifier.width(6.dp))
                            Text(it.text, color = it.color, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
