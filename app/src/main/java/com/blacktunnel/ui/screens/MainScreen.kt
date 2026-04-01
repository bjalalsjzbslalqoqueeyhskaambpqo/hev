package com.blacktunnel.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CopyAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacktunnel.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VpnState { IDLE, CONNECTING, CONNECTED, ERROR }

data class LogEntry(val icon: String, val text: String, val color: Color)

@Composable
fun MainScreen(
    state: VpnState,
    clientName: String,
    daysLeft: String,
    latencyMs: Long,
    status: String,
    connectedSince: Long,
    logEntries: List<LogEntry>,
    onConnectClick: () -> Unit,
    onCopyClientId: () -> Unit,
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (state == VpnState.CONNECTED || state == VpnState.ERROR) {
            Image(
                painter = painterResource(id = R.drawable.bg_connected),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (state == VpnState.CONNECTED) 0.06f else 0.045f,
                modifier = Modifier.fillMaxSize()
            )
        }

        val glowColor = when (state) {
            VpnState.CONNECTED -> MaterialTheme.colorScheme.primary.copy(0.16f)
            VpnState.ERROR -> MaterialTheme.colorScheme.error.copy(0.14f)
            VpnState.CONNECTING -> Color(0xFFFBBF24).copy(0.12f)
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Brush.radialGradient(listOf(glowColor, Color.Transparent)))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("BlackTunnel", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onCopyClientId) {
                    Icon(Icons.Default.CopyAll, contentDescription = "Copiar ID", tint = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(16.dp))
            Orb(state)
            Spacer(Modifier.height(14.dp))

            val stateLabel = when (state) {
                VpnState.CONNECTED -> "Conectado"
                VpnState.CONNECTING -> "Conectando..."
                VpnState.ERROR -> "Error"
                VpnState.IDLE -> "Desconectado"
            }
            Text(stateLabel, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)

            if (state == VpnState.CONNECTED && connectedSince > 0) {
                Text(formatConnectedSince(connectedSince), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.7f))
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InfoChip("⚡", if (latencyMs >= 0) "${latencyMs}ms" else "-")
                InfoChip("📅", "$daysLeft días")
                InfoChip("👤", clientName.ifBlank { "-" })
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onConnectClick,
                enabled = state != VpnState.CONNECTING,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(if (state == VpnState.CONNECTED) "DESCONECTAR" else "CONECTAR", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(16.dp))
            AdvancedSection(
                expanded = showAdvanced,
                onToggle = { showAdvanced = !showAdvanced },
                isHotspotEnabled = isHotspotEnabled,
                onHotspotToggle = onHotspotToggle,
                hotspotIp = hotspotIp,
                isWifiDirectEnabled = isWifiDirectEnabled,
                onWifiDirectToggle = onWifiDirectToggle,
                wifiDirectPassword = wifiDirectPassword,
                onWifiDirectPasswordChange = onWifiDirectPasswordChange,
                onIgnoreBatteryClick = onIgnoreBatteryClick
            )

            Spacer(Modifier.height(14.dp))
            LogPanel(logEntries = logEntries, fallbackStatus = status)
        }
    }
}

@Composable
private fun Orb(state: VpnState) {
    val tint = when (state) {
        VpnState.CONNECTED -> MaterialTheme.colorScheme.primary
        VpnState.ERROR -> MaterialTheme.colorScheme.error
        VpnState.CONNECTING -> Color(0xFFFBBF24)
        else -> MaterialTheme.colorScheme.onSurface.copy(0.3f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(170.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(tint.copy(0.25f), Color.Transparent)))
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_xtunnel_logo),
            contentDescription = null,
            modifier = Modifier.size(92.dp)
        )
    }
}

@Composable
private fun InfoChip(icon: String, label: String) {
    Surface(
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
fun LogPanel(logEntries: List<LogEntry>, fallbackStatus: String) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Log de conexión", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.8f), fontWeight = FontWeight.SemiBold)
            val entries = if (logEntries.isNotEmpty()) logEntries.takeLast(6) else listOf(LogEntry("ℹ", fallbackStatus, Color(0xFF94A3B8)))
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(entry.color.copy(alpha = 0.08f))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.width(3.dp).height(18.dp).background(entry.color))
                    Spacer(Modifier.width(8.dp))
                    Text("${entry.icon}  ${entry.text}", color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    isHotspotEnabled: Boolean,
    onHotspotToggle: (Boolean) -> Unit,
    hotspotIp: String?,
    isWifiDirectEnabled: Boolean,
    onWifiDirectToggle: (Boolean) -> Unit,
    wifiDirectPassword: String,
    onWifiDirectPasswordChange: (String) -> Unit,
    onIgnoreBatteryClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = if (expanded) "▲ Ajustes avanzados" else "▼ Ajustes avanzados",
                modifier = Modifier.clickable { onToggle() },
                color = MaterialTheme.colorScheme.onSurface
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥 Compartir Wi‑Fi por proxy")
                        Switch(checked = isHotspotEnabled, onCheckedChange = onHotspotToggle)
                    }
                    if (isHotspotEnabled && hotspotIp != null) {
                        Text("SOCKS5: $hotspotIp:1080", fontSize = 12.sp)
                        Text("HTTP: $hotspotIp:8282", fontSize = 12.sp)
                    }
                    Divider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("📺 WiFi Direct para Smart TV")
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
                    Divider()
                    Text(
                        text = "🔋 Quitar restricción de batería",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onIgnoreBatteryClick)
                    )
                }
            }
        }
    }
}

private fun formatConnectedSince(connectedSince: Long): String {
    val now = System.currentTimeMillis()
    val mins = ((now - connectedSince) / 60_000L).coerceAtLeast(0)
    val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(connectedSince))
    return "Conectado desde las $hhmm · $mins min"
}
