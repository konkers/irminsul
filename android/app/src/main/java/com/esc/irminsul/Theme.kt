package com.esc.irminsul

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Primary Colors
val Primary = Color(0xFF00B4D8)
val PrimaryDark = Color(0xFF023E8A)
val PrimaryLight = Color(0xFF90E0EF)

// Secondary Colors
val Secondary = Color(0xFF7B2CBF)
val SecondaryDark = Color(0xFF5A189A)
val SecondaryLight = Color(0xFF9D4EDD)

// Accent Colors
val Accent = Color(0xFFFF9800)
val AccentDark = Color(0xFFE65100)

// Background Colors
val Background = Color(0xFF0A0A12)
val BackgroundGradientStart = Color(0xFF0D1B2A)
val BackgroundGradientEnd = Color(0xFF1B263B)
val Surface = Color(0xFF1A1A2E)
val SurfaceLight = Color(0xFF252540)
val SurfaceHighlight = Color(0xFF2D2D50)

// Text Colors
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFB8C5D6)
val TextHint = Color(0xFF6B7A8C)
val TextDisabled = Color(0xFF4A5568)

// Status Colors
val Success = Color(0xFF48BB78)
val SuccessLight = Color(0xFF81E6A9)
val Error = Color(0xFFFC8181)
val Warning = Color(0xFFF6AD55)
val Info = Color(0xFF63B3ED)

// Button Colors
val ButtonPrimary = Color(0xFF00B4D8)
val ButtonSecondary = Color(0xFF2D3748)
val ButtonSuccess = Color(0xFF38A169)
val ButtonDanger = Color(0xFFE53E3E)

// Border Colors
val Border = Color(0xFF2D3748)
val BorderLight = Color(0xFF4A5568)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    secondary = Secondary,
    tertiary = Accent,
    background = Background,
    surface = Surface,
    onPrimary = TextPrimary,
    onSecondary = TextPrimary,
    onTertiary = TextPrimary,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun IrminsulTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> DarkColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = Background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
