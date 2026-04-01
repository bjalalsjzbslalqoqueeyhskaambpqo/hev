package com.blacktunnel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BlackTunnelDark = darkColorScheme(
    primary = Color(0xFF00E5FF),
    background = Color(0xFF0A0E1A),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE2E8F0),
    error = Color(0xFFFF4C6A)
)

@Composable
fun BlackTunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BlackTunnelDark,
        content = content
    )
}
