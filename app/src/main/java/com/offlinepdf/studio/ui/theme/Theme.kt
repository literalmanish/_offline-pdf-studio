package com.offlinepdf.studio.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

data class ThemeController(
    val isDark: Boolean,
    val toggleTheme: () -> Unit
)

val LocalThemeController = staticCompositionLocalOf {
    ThemeController(isDark = true, toggleTheme = {})
}

// Dark theme mapped from user specification
private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color.White,
    primaryContainer = DarkPrimaryLight,
    onPrimaryContainer = DarkPrimaryHover,
    secondary = DarkAccent,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkSurface2,
    onBackground = DarkHeading,
    onSurface = DarkHeading,
    onSurfaceVariant = DarkText,
    outline = DarkBorder,
    outlineVariant = DarkBorder.copy(alpha = 0.5f),
    error = DarkError
)

// Light theme mapped from user specification
private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryLight,
    onPrimaryContainer = LightPrimaryHover,
    secondary = LightAccent,
    background = LightBg,
    surface = LightSurface,
    surfaceVariant = LightSurface2,
    onBackground = LightHeading,
    onSurface = LightHeading,
    onSurfaceVariant = LightText,
    outline = LightBorder,
    outlineVariant = LightBorder.copy(alpha = 0.5f),
    error = LightError
)

object AppTheme {
    val isDark: Boolean
        @Composable get() = LocalThemeController.current.isDark

    val primary: Color
        @Composable get() = if (isDark) DarkPrimary else LightPrimary

    val primaryHover: Color
        @Composable get() = if (isDark) DarkPrimaryHover else LightPrimaryHover

    val bg: Color
        @Composable get() = if (isDark) DarkBg else LightBg

    val surface: Color
        @Composable get() = if (isDark) DarkSurface else LightSurface

    val surface2: Color
        @Composable get() = if (isDark) DarkSurface2 else LightSurface2

    val heading: Color
        @Composable get() = if (isDark) DarkHeading else LightHeading

    val text: Color
        @Composable get() = if (isDark) DarkText else LightText

    val muted: Color
        @Composable get() = if (isDark) DarkMuted else LightMuted

    val border: Color
        @Composable get() = if (isDark) DarkBorder else LightBorder

    val accent: Color
        @Composable get() = if (isDark) DarkAccent else LightAccent

    val success: Color
        @Composable get() = if (isDark) DarkSuccess else LightSuccess

    val error: Color
        @Composable get() = if (isDark) DarkError else LightError
}

@Composable
fun OfflinePdfStudioTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val statusBarBg = if (darkTheme) DarkBg else LightBg
            val navBarBg = if (darkTheme) DarkSurface else LightSurface
            window.statusBarColor = statusBarBg.toArgb()
            window.navigationBarColor = navBarBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
