package com.nexora.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
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
import com.nexora.R
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
    onIgnoreBatteryClick: () -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF030A18), Color(0xFF081228), Color(0xFF030816))))
    ) {
        if (state == VpnState.CONNECTED || state == VpnState.ERROR) {
            Image(
                painter = painterResource(id = R.drawable.bg_connected),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (state == VpnState.CONNECTED) 0.07f else 0.05f,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HeaderSection(onCopyClientId)
            ConnectionPanel(state, latencyMs, daysLeft, clientName, connectedSince, onConnectClick)
            AdvancedSection(
                expanded = showAdvanced,
                onToggle = { showAdvanced = !showAdvanced },
                isHotspotEnabled = isHotspotEnabled,
                onHotspotToggle = onHotspotToggle,
                hotspotIp = hotspotIp,
                onIgnoreBatteryClick = onIgnoreBatteryClick
            )
            LogPanel(logEntries = logEntries, fallbackStatus = status)
        }
    }
}

@Composable
private fun HeaderSection(onCopyClientId: () -> Unit) {
    Surface(
        color = Color(0xFF06162F),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_nexora_logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF0077FF), RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("NEXORA", fontWeight = FontWeight.Black, color = Color(0xFFE7F2FF), fontSize = 24.sp)
                    Text("Conectividad que te da ventaja", color = Color(0xFF00D4FF), fontSize = 12.sp)
                }
            }
            Text(
                "ID",
                color = Color(0xFF00D4FF),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCopyClientId)
                    .border(1.dp, Color(0xFF00D4FF), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun ConnectionPanel(
    state: VpnState,
    latencyMs: Long,
    daysLeft: String,
    clientName: String,
    connectedSince: Long,
    onConnectClick: () -> Unit
) {
    Surface(
        color = Color(0xFF081B33),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Orb(state)
            Spacer(Modifier.height(10.dp))
            val stateLabel = when (state) {
                VpnState.CONNECTED -> "CONECTADO"
                VpnState.CONNECTING -> "CONECTANDO"
                VpnState.ERROR -> "ERROR"
                VpnState.IDLE -> "DESCONECTADO"
            }
            Text(stateLabel, color = Color(0xFF00E5FF), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (state == VpnState.CONNECTED && connectedSince > 0) {
                Text(formatConnectedSince(connectedSince), fontSize = 12.sp, color = Color(0xFF94A9C7))
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                InfoChip("⚡", if (latencyMs >= 0) "${latencyMs}ms" else "-")
                InfoChip("📅", "$daysLeft días")
                InfoChip("👤", clientName.ifBlank { "mi cuenta" })
            }

            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onConnectClick,
                enabled = state != VpnState.CONNECTING,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF008DFF))
            ) {
                Text(if (state == VpnState.CONNECTED) "DESCONECTAR" else "CONECTAR", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Orb(state: VpnState) {
    val tint = when (state) {
        VpnState.CONNECTED -> Color(0xFF00D4FF)
        VpnState.ERROR -> MaterialTheme.colorScheme.error
        VpnState.CONNECTING -> Color(0xFFFBBF24)
        else -> Color(0xFF4A5D7A)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(tint.copy(0.28f), Color.Transparent)))
            .border(2.dp, tint.copy(0.7f), CircleShape)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_nexora_logo),
            contentDescription = null,
            modifier = Modifier.size(90.dp)
        )
    }
}

@Composable
private fun RowScope.InfoChip(icon: String, label: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF0C243F), modifier = Modifier.weight(1f)) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon)
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 12.sp, color = Color(0xFFE4EAF5), maxLines = 1)
        }
    }
}

@Composable
fun LogPanel(logEntries: List<LogEntry>, fallbackStatus: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth(), color = Color(0xFF081B33), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Log de conexión", fontSize = 12.sp, color = Color(0xFFEAF2FF), fontWeight = FontWeight.SemiBold)
                Text(if (expanded) "Ocultar" else "Ver todo", fontSize = 11.sp, color = Color(0xFF00D4FF))
            }
            val entries = if (logEntries.isNotEmpty()) logEntries.takeLast(6) else listOf(LogEntry("ℹ", fallbackStatus, Color(0xFF94A3B8)))
            val visibleEntries = if (expanded) entries else listOf(entries.last())
            visibleEntries.forEach { entry ->
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
                    Text("${entry.icon}  ${entry.text}", color = Color(0xFFEAF2FF), fontSize = 12.sp)
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
    onIgnoreBatteryClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFF081B33)) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = if (expanded) "▲ Ajustes avanzados" else "▼ Ajustes avanzados",
                modifier = Modifier.clickable { onToggle() },
                color = Color(0xFFEAF2FF)
            )

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Divider(color = Color(0xFF143255))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("🔥 Compartir internet por proxy", color = Color(0xFFEAF2FF))
                        Switch(checked = isHotspotEnabled, onCheckedChange = onHotspotToggle)
                    }
                    if (isHotspotEnabled && hotspotIp != null) {
                        Text("SOCKS5: $hotspotIp:1080", fontSize = 12.sp, color = Color(0xFF92A7C4))
                        Text("HTTP: $hotspotIp:8282", fontSize = 12.sp, color = Color(0xFF92A7C4))
                    }
                    Divider(color = Color(0xFF143255))
                    Text(
                        text = "🔋 Quitar restricción de batería",
                        color = Color(0xFF00D4FF),
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
