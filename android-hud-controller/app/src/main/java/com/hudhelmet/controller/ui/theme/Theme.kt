package com.hudhelmet.controller.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val HudDarkColorScheme = darkColorScheme(
    primary = HudCyan,
    onPrimary = DarkBackground,
    primaryContainer = HudCyanDark,
    onPrimaryContainer = HudCyanLight,
    secondary = HudMagenta,
    onSecondary = DarkBackground,
    secondaryContainer = HudMagentaDark,
    tertiary = HudYellow,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = HudRed,
    onError = DarkBackground,
    outline = DarkCardBorder,
    outlineVariant = TextTertiary,
)

@Composable
fun HUDHelmetTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = HudDarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DarkBackground.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HudTypography,
        content = content
    )
}
