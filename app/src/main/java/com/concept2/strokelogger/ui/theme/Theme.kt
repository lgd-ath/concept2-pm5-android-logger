package com.concept2.strokelogger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    secondary = AccentGreen,
    background = CarbonDark,
    surface = CarbonCard,
    onPrimary = CarbonDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun Concept2StrokeLoggerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
