package com.smalljelly.batterydata.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private val LightColors = lightColorScheme(
    primary = ChartBlue,
    background = LightBackground,
    onBackground = LightTextPrimary,
    onBackgroundVariant = LightTextSecondary,
    surface = LightBackground,
    onSurface = LightTextPrimary,
    surfaceContainer = LightCard,
    onSurfaceContainer = LightTextPrimary,
    onSurfaceContainerVariant = LightTextSecondary,
    onSurfaceVariantSummary = LightTextSecondary,
    surfaceVariant = LightCard,
    dividerLine = LightDivider,
    secondaryContainer = Color(0xFFEEEEF0),
    tertiaryContainer = Color(0xFFEAF2FF),
    onTertiaryContainer = ChartBlue,
)

private val DarkColors = darkColorScheme(
    primary = ChartBlue,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    onBackgroundVariant = DarkTextSecondary,
    surface = DarkBackground,
    onSurface = DarkTextPrimary,
    surfaceContainer = DarkCard,
    onSurfaceContainer = DarkTextPrimary,
    onSurfaceContainerVariant = DarkTextSecondary,
    onSurfaceVariantSummary = DarkTextSecondary,
    surfaceVariant = DarkCard,
    dividerLine = DarkDivider,
    secondaryContainer = Color(0xFF2C2C2E),
    tertiaryContainer = Color(0xFF13233C),
    onTertiaryContainer = Color(0xFF7FB0FF),
)

@Composable
fun BatteryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme) { if (darkTheme) DarkColors else LightColors }
    MiuixTheme(colors = colors, content = content)
}
