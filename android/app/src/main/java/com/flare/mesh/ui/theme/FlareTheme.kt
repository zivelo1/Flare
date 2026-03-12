package com.flare.mesh.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.flare.mesh.util.Constants

// Flare brand colors
private val FlareOrange = Color(0xFFFF6B35)
private val FlareOrangeDark = Color(0xFFFF8A5C)
private val FlareSurface = Color(0xFFFFFBFE)
private val FlareSurfaceDark = Color(0xFF1C1B1F)

private val LightColorScheme = lightColorScheme(
    primary = FlareOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCE),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBCE),
    onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF6A5E2F),
    onTertiary = Color.White,
    background = FlareSurface,
    onBackground = Color(0xFF1C1B1F),
    surface = FlareSurface,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFF5DED6),
    onSurfaceVariant = Color(0xFF53433E),
    outline = Color(0xFF85736D),
)

private val FlareSurfaceContainerDark = Color(0xFF231F20)
private val FlareSurfaceContainerHighDark = Color(0xFF2D2829)
private val FlareSurfaceContainerHighestDark = Color(0xFF383234)

private val DarkColorScheme = darkColorScheme(
    primary = FlareOrangeDark,
    onPrimary = Color(0xFF5E1700),
    primaryContainer = Color(0xFF842400),
    onPrimaryContainer = Color(0xFFFFDBCE),
    secondary = Color(0xFFE7BEAE),
    onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035),
    onSecondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFFD4C871),
    onTertiary = Color(0xFF383005),
    background = FlareSurfaceDark,
    onBackground = Color(0xFFE6E1E5),
    surface = FlareSurfaceDark,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF53433E),
    onSurfaceVariant = Color(0xFFD8C2BA),
    outline = Color(0xFFA08D86),
    surfaceContainer = FlareSurfaceContainerDark,
    surfaceContainerHigh = FlareSurfaceContainerHighDark,
    surfaceContainerHighest = FlareSurfaceContainerHighestDark,
    surfaceBright = Color(0xFF3B3637),
    surfaceDim = FlareSurfaceDark,
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    inversePrimary = FlareOrange,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

val FlareTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    ),
)

@Composable
fun FlareTheme(
    darkTheme: Boolean = run {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }
        when (prefs.getString(Constants.KEY_DARK_MODE, Constants.DARK_MODE_SYSTEM)) {
            Constants.DARK_MODE_LIGHT -> false
            Constants.DARK_MODE_DARK -> true
            else -> isSystemInDarkTheme()
        }
    },
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Use Material You dynamic colors on Android 12+
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FlareTypography,
        content = content,
    )
}
