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
    primaryContainer = Color(0xFFDDF4E4),
    onPrimaryContainer = Color(0xFF0B3D1A),
    secondary = Color(0xFF3F6079),
    onSecondary = Color.White,
    secondaryContainer = UserBubble,
    onSecondaryContainer = Color(0xFF122B42),
    tertiary = Color(0xFF57606A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEAEFF4),
    onTertiaryContainer = OnSurfaceLight,
    background = Background,
    onBackground = OnSurfaceLight,
    surface = Surface,
    onSurface = OnSurfaceLight,
    surfaceVariant = Color(0xFFEAEFF4),
    onSurfaceVariant = Color(0xFF4B5561),
    outline = Color(0xFF6E7781),
    outlineVariant = Color(0xFFD0D7DE),
    surfaceContainerLowest = Background,
    surfaceContainerLow = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFEAEFF4),
    surfaceContainerHighest = Color(0xFFE2E8EF)
)

internal val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF063B18),
    primaryContainer = Color(0xFF164C2A),
    onPrimaryContainer = Color(0xFFC9F5D5),
    secondary = Color(0xFFAACBE5),
    onSecondary = Color(0xFF0E334B),
    secondaryContainer = UserBubbleDark,
    onSecondaryContainer = Color(0xFFD7EAFF),
    tertiary = Color(0xFFB6BEC9),
    onTertiary = Color(0xFF27303A),
    tertiaryContainer = Color(0xFF303A46),
    onTertiaryContainer = OnSurfaceDark,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = Color(0xFF212A35),
    onSurfaceVariant = Color(0xFFB6C2CF),
    outline = Color(0xFF8B949E),
    outlineVariant = Color(0xFF3B4653),
    surfaceContainerLowest = BackgroundDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF212830),
    surfaceContainerHighest = Color(0xFF2B333D)
)

@Composable
fun CopilotGoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
        content = content
    )
}
