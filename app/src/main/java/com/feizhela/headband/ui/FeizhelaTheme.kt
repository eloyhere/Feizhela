package com.feizhela.headband.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.feizhela.headband.ui.components.charts.DarkChartPalette
import com.feizhela.headband.ui.components.charts.LightChartPalette
import com.feizhela.headband.ui.components.charts.LocalChartPalette

/**
 * User-selectable theme mode. Mirrors the three states Android itself supports
 * (`UI_MODE_NIGHT_NO` / `YES` / `FOLLOW_SYSTEM`) so this can later be persisted with
 * `AppCompatDelegate.setDefaultNightMode` or a DataStore preference without changing screens.
 */
enum class ThemeMode { System, Light, Dark }

/**
 * Brand palette. This is the Compose counterpart of the XML theme's `colorPrimary` family:
 * the Activity window/background colors in `res/values/themes.xml` stay the pre-Compose splash
 * colors, and these schemes take over as soon as Compose draws.
 *
 * To change the product accent, edit only [LightColors]/[DarkColors] here; every component and
 * chart reads `MaterialTheme.colorScheme` or [LocalChartPalette], never a hard-coded colour.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF3F5D8C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E3F5),
    onPrimaryContainer = Color(0xFF0B1D33),
    secondary = Color(0xFF4C6A5D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E5DA),
    onSecondaryContainer = Color(0xFF0A2016),
    tertiary = Color(0xFF6A5A8C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE7DFF6),
    onTertiaryContainer = Color(0xFF211534),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE1E4EA),
    onSurfaceVariant = Color(0xFF44474D),
    surfaceTint = Color(0xFF3F5D8C),
    inverseSurface = Color(0xFF2E3136),
    inverseOnSurface = Color(0xFFF0F1F5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF74777D),
    outlineVariant = Color(0xFFC4C7CE),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAAC4E8),
    onPrimary = Color(0xFF102A45),
    primaryContainer = Color(0xFF2A4363),
    onPrimaryContainer = Color(0xFFD8E3F5),
    secondary = Color(0xFFB4CDBF),
    onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF364E43),
    onSecondaryContainer = Color(0xFFD2E5DA),
    tertiary = Color(0xFFC9BDE6),
    onTertiary = Color(0xFF352A4E),
    tertiaryContainer = Color(0xFF4C4066),
    onTertiaryContainer = Color(0xFFE7DFF6),
    background = Color(0xFF101318),
    onBackground = Color(0xFFE3E6EB),
    surface = Color(0xFF14181E),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF3F444D),
    onSurfaceVariant = Color(0xFFC2C7D0),
    surfaceTint = Color(0xFFAAC4E8),
    inverseSurface = Color(0xFFE3E6EB),
    inverseOnSurface = Color(0xFF2E3136),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF8C919A),
    outlineVariant = Color(0xFF44474D),
    scrim = Color(0xFF000000),
)

/** Slightly rounder than the Material default to match the calm, night-time visual language. */
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

/**
 * App theme.
 *
 * - [themeMode] supports following the system or forcing light/dark, so the app can switch themes
 *   without recreating the Activity.
 * - [dynamicColor] opts into the Android 12+ system palette (Material You). This is the same
 *   `colorPrimary` family a host theme would provide, so a device-branded palette stays
 *   consistent with the rest of Android. The observation/chart palette stays semantic.
 * - System bar icon appearance is updated when the resolved theme differs from the system theme.
 * - [content] receives `MaterialTheme` + [LocalChartPalette]; no screen reads raw colours.
 */
@Composable
fun FeizhelaTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = when {
        dynamicColor && supportsDynamicColor ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    val chartPalette = if (darkTheme) DarkChartPalette else LightChartPalette
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalChartPalette provides chartPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
