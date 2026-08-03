package com.gocavgo.ikuriye.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val DarkColorScheme = darkColorScheme(
    primary = CavgoBlueDark,
    secondary = CavgoGreenDark,
    tertiary = CavgoAmberDark,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceAlt,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onTertiary = DarkBackground,
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkMuted,
    outline = DarkOutline,
    error = CavgoRedDark
)

private val LightColorScheme = lightColorScheme(
    primary = CavgoBlue,
    secondary = CavgoGreen,
    tertiary = CavgoAmber,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceAlt,
    onPrimary = LightSurface,
    onSecondary = LightSurface,
    onTertiary = LightOnSurface,
    onBackground = LightOnSurface,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightMuted,
    outline = LightOutline,
    error = CavgoRed
)

@Immutable
data class DriversColors(
    val blue: Color,
    val green: Color,
    val amber: Color,
    val red: Color,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val topStart: Color
)

private val LightDriversColors = DriversColors(
    blue = CavgoBlue,
    green = CavgoGreen,
    amber = CavgoAmber,
    red = CavgoRed,
    background = LightBackground,
    surface = LightSurface,
    surfaceAlt = LightSurfaceAlt,
    textPrimary = LightOnSurface,
    textSecondary = LightMuted,
    divider = LightOutline,
    topStart = LightTopStart
)

private val DarkDriversColors = DriversColors(
    blue = CavgoBlueDark,
    green = CavgoGreenDark,
    amber = CavgoAmberDark,
    red = CavgoRedDark,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceAlt = DarkSurfaceAlt,
    textPrimary = DarkOnSurface,
    textSecondary = DarkMuted,
    divider = DarkOutline,
    topStart = DarkTopStart
)

val LocalDriversColors = staticCompositionLocalOf { LightDriversColors }


@Composable
fun IkuriyeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
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

    // Cap the system font scale to 1.2x to prevent layout breakage (badges,
    // buttons, text overflow) when users set their device font size to large
    // or extra-large. Users still get a moderate size increase, but the UI
    // stays usable.
    val fontScale = LocalDensity.current.fontScale.coerceIn(0.85f, 1.2f)
    val cappedDensity = LocalDensity.current.run { Density(density, fontScale) }

    CompositionLocalProvider(
        LocalDensity provides cappedDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography
        ) {
            val driversColors = if (darkTheme) DarkDriversColors else LightDriversColors
            CompositionLocalProvider(LocalDriversColors provides driversColors) {
                content()
            }
        }
    }
}