package com.todoer.app.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Purple = Color(0xFF6366F1)
val PurpleLight = Color(0xFF818CF8)
val PurpleDark = Color(0xFF4F46E5)
val Background = Color(0xFF0F1117)
val Surface = Color(0xFF1A1B23)
val SurfaceLight = Color(0xFF252631)
val TextPrimary = Color(0xFFE2E8F0)
val TextSecondary = Color(0xFF94A3B8)
val DangerRed = Color(0xFFEF4444)
val SuccessGreen = Color(0xFF22C55E)

private val DarkColorScheme = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleDark,
    secondary = PurpleLight,
    background = Background,
    surface = Surface,
    surfaceVariant = SurfaceLight,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = DangerRed,
    outline = Color(0xFF334155),
)

@Composable
fun ToDoerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
