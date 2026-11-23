package com.example.meter.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ночная схема: тёмный фон, красный акцент под цвет импульса счётчика.
private val MeterColors = darkColorScheme(
    primary = Color(0xFFFF5A5A),
    background = Color(0xFF0B0B0B),
    surface = Color(0xFF161616),
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED),
)

@Composable
fun MeterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MeterColors, content = content)
}
