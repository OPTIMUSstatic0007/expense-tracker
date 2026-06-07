package com.example.expensetracker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkTextOnPrimary,
    primaryContainer = DarkPrimarySoft,
    onPrimaryContainer = DarkAccent,
    secondary = DarkInfo,
    onSecondary = DarkTextOnPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceAlt,
    onSurfaceVariant = DarkTextSecondary,
    error = DarkDanger,
    onError = DarkTextOnPrimary,
    errorContainer = DarkDangerSoft,
    onErrorContainer = DarkDanger,
    outline = DarkBorder,
    outlineVariant = DarkBorderFocus
)

private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightTextOnPrimary,
    primaryContainer = LightPrimarySoft,
    onPrimaryContainer = LightAccent,
    secondary = LightInfo,
    onSecondary = LightTextOnPrimary,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceAlt,
    onSurfaceVariant = LightTextSecondary,
    error = LightDanger,
    onError = LightTextOnPrimary,
    errorContainer = LightDangerSoft,
    onErrorContainer = LightDanger,
    outline = LightBorder,
    outlineVariant = LightBorderFocus
)

@Composable
fun ExpenseTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Disabled dynamic color to strictly enforce our custom theme
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // Assume Typography is correctly defined elsewhere or use default
        content = content
    )
}
