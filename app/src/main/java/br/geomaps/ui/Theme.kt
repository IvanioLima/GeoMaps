package br.geomaps.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Navy = Color(0xFF0D3B66)
val NavyLight = Color(0xFF1D5C96)
val Orange = Color(0xFFF57C00)

private val Light = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F7),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Orange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDCC2),
    onSecondaryContainer = Color(0xFF2E1500),
    tertiary = Color(0xFF00897B),
    surface = Color(0xFFFCFCFF),
    background = Color(0xFFF7F8FB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = NavyLight,
    onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFFFFB77C),
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF6A3B00),
    onSecondaryContainer = Color(0xFFFFDCC2),
    tertiary = Color(0xFF4DB6AC),
)

@Composable
fun GeoMapsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
