package com.tibatech.gptmax.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Business-blue palette from the GpTMax report (dark-first studio).
private val Background = Color(0xFF0B1220)
private val Surface = Color(0xFF101B2E)
private val SurfaceVariant = Color(0xFF182742)
private val Primary = Color(0xFF7AA7E8)
private val Secondary = Color(0xFF4A7AC7)
private val Outline = Color(0xFF33507A)
private val OnBg = Color(0xFFE6EEFA)
private val OnSurfaceVariant = Color(0xFF9FB3D1)

private val GpTMaxColors = darkColorScheme(
    primary = Primary,
    onPrimary = Background,
    secondary = Secondary,
    onSecondary = OnBg,
    background = Background,
    onBackground = OnBg,
    surface = Surface,
    onSurface = OnBg,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    error = Color(0xFFE57373),
)

@Composable
fun GpTMaxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // The studio is designed dark-first; light mode falls back to the same
    // scheme until a dedicated palette lands (M8 polish phase).
    MaterialTheme(
        colorScheme = GpTMaxColors,
        typography = Typography(),
        content = content,
    )
}
