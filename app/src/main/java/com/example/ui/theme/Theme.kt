package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme =
  darkColorScheme(
    primary = SignalEmerald,
    onPrimary = ObsidianBg,
    primaryContainer = SignalEmeraldDark,
    onPrimaryContainer = SignalEmeraldLight,
    secondary = TelemetryCyan,
    onSecondary = ObsidianBg,
    secondaryContainer = TelemetryCyanDark,
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = NimLime,
    onTertiary = ObsidianBg,
    tertiaryContainer = NimLimeDark,
    onTertiaryContainer = Color(0xFFECFCCB),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = ObsidianSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = TextSecondaryDark,
    outline = ObsidianOutline,
    error = AlertRed,
    onError = Color.White,
    errorContainer = AlertRedDark,
    onErrorContainer = Color(0xFFFEE2E2),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = SignalEmeraldLightMode,
    onPrimary = Color.White,
    primaryContainer = SignalEmeraldContainerLight,
    onPrimaryContainer = SignalEmeraldDark,
    secondary = TelemetryCyanLightMode,
    onSecondary = Color.White,
    secondaryContainer = TelemetryCyanContainerLight,
    onSecondaryContainer = TelemetryCyanDark,
    tertiary = NimLimeLightMode,
    onTertiary = Color.White,
    background = SlateBg,
    onBackground = TextPrimaryLight,
    surface = SlateSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = SlateSurfaceHigh,
    onSurfaceVariant = TextSecondaryLight,
    outline = SlateOutline,
    error = AlertRed,
    onError = Color.White,
  )

private val OledDarkColorScheme =
  DarkColorScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF0F141B),
    outline = Color(0xFF222933),
  )

val AppShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
  )

@Composable
fun ClawLinkTheme(
  themeMode: com.example.data.prefs.AppThemeMode = com.example.data.prefs.AppThemeMode.SYSTEM,
  content: @Composable () -> Unit,
) {
  val systemDark = isSystemInDarkTheme()
  val colorScheme =
    when (themeMode) {
      com.example.data.prefs.AppThemeMode.SYSTEM -> if (systemDark) DarkColorScheme else LightColorScheme
      com.example.data.prefs.AppThemeMode.LIGHT -> LightColorScheme
      com.example.data.prefs.AppThemeMode.DARK -> DarkColorScheme
      com.example.data.prefs.AppThemeMode.OLED -> OledDarkColorScheme
    }
  MaterialTheme(
    colorScheme = colorScheme,
    typography = AppTypography,
    shapes = AppShapes,
    content = content,
  )
}
