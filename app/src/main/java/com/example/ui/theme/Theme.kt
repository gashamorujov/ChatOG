package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    secondary = NeonGold,
    tertiary = NeonCyan,
    background = DeepDarkNavy,
    surface = ContainerNavy,
    onPrimary = DeepDarkNavy,
    onSecondary = DeepDarkNavy,
    onTertiary = DeepDarkNavy,
    onBackground = TextLight,
    onSurface = TextLight
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
