package com.nexora.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NexoraDark = darkColorScheme(
    primary = Color(0xFF00D4FF),
    background = Color(0xFF030A18),
    surface = Color(0xFF081B33),
    onSurface = Color(0xFFEAF2FF),
    error = Color(0xFFFF4C6A)
)

@Composable
fun NexoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NexoraDark,
        content = content
    )
}
