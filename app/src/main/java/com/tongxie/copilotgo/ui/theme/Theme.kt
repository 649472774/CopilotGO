package com.tongxie.copilotgo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

internal val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAEAEC),
    onPrimaryContainer = OnSurfaceLight,
    secondary = Color(0xFF55565A),
    onSecondary = Color.White,
    secondaryContainer = UserBubble,
    onSecondaryContainer = OnSurfaceLight,
    tertiary = Color(0xFF5C5D62),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDEDEF),
    onTertiaryContainer = OnSurfaceLight,
    background = Background,
    onBackground = OnSurfaceLight,
    surface = Surface,
    onSurface = OnSurfaceLight,
    surfaceVariant = Color(0xFFEDEDEF),
    onSurfaceVariant = Color(0xFF5C5D62),
    outline = Color(0xFF77787D),
    outlineVariant = Color(0xFFDCDCDD),
    surfaceContainerLowest = Background,
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF1F1F2),
    surfaceContainerHigh = Color(0xFFE8E8EA),
    surfaceContainerHighest = Color(0xFFE1E1E3),
    surfaceTint = Primary,
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = PrimaryDark,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF6D1310)
)

internal val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF202020),
    primaryContainer = Color(0xFF383838),
    onPrimaryContainer = OnSurfaceDark,
    secondary = Color(0xFFC6C6CA),
    onSecondary = Color(0xFF252528),
    secondaryContainer = UserBubbleDark,
    onSecondaryContainer = OnSurfaceDark,
    tertiary = Color(0xFFB4B4B8),
    onTertiary = Color(0xFF252528),
    tertiaryContainer = Color(0xFF303030),
    onTertiaryContainer = OnSurfaceDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFB4B4B8),
    outline = Color(0xFF858589),
    outlineVariant = Color(0xFF424244),
    surfaceContainerLowest = BackgroundDark,
    surfaceContainerLow = Color(0xFF202020),
    surfaceContainer = Color(0xFF262626),
    surfaceContainerHigh = Color(0xFF303030),
    surfaceContainerHighest = Color(0xFF3A3A3A),
    surfaceTint = PrimaryDark,
    inverseSurface = Color(0xFFECECEC),
    inverseOnSurface = Color(0xFF202020),
    inversePrimary = Primary,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A1D1B),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun CopilotGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
