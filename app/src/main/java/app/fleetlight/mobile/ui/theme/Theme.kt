package app.fleetlight.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Fleetlight brand palette. Semantic roles used across the app:
//   primary   -> brand blue, actions and update signals
//   secondary -> healthy / online
//   tertiary  -> amber, slow / restart / attention-but-not-broken
//   error     -> offline, access issues, alerts
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5FD0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E6FF),
    onPrimaryContainer = Color(0xFF001B44),
    secondary = Color(0xFF1C7A45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF1DA),
    onSecondaryContainer = Color(0xFF00210E),
    tertiary = Color(0xFF9C5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDFB8),
    onTertiaryContainer = Color(0xFF301A00),
    error = Color(0xFFB8232A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD7),
    onErrorContainer = Color(0xFF410003),
    background = Color(0xFFF3F5FA),
    onBackground = Color(0xFF161B23),
    surface = Color(0xFFF3F5FA),
    onSurface = Color(0xFF161B23),
    surfaceVariant = Color(0xFFE0E5EE),
    onSurfaceVariant = Color(0xFF475160),
    outline = Color(0xFF75808F),
    outlineVariant = Color(0xFFD3DAE4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE9EDF4),
    surfaceContainerHighest = Color(0xFFE1E6EE),
    inverseSurface = Color(0xFF2B3038),
    inverseOnSurface = Color(0xFFF0F2F7),
    inversePrimary = Color(0xFF9FC4FF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC4FF),
    onPrimary = Color(0xFF00316D),
    primaryContainer = Color(0xFF0C4799),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF7FD79B),
    onSecondary = Color(0xFF003919),
    secondaryContainer = Color(0xFF0E5230),
    onSecondaryContainer = Color(0xFFBDF2CC),
    tertiary = Color(0xFFFFB95F),
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF653E00),
    onTertiaryContainer = Color(0xFFFFDDB7),
    error = Color(0xFFFFB3AE),
    onError = Color(0xFF680009),
    errorContainer = Color(0xFF8C1D22),
    onErrorContainer = Color(0xFFFFDAD7),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E6EC),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE3E6EC),
    surfaceVariant = Color(0xFF2A3038),
    onSurfaceVariant = Color(0xFFB7C0CC),
    outline = Color(0xFF808A97),
    outlineVariant = Color(0xFF353C46),
    surfaceContainerLowest = Color(0xFF090B0F),
    surfaceContainerLow = Color(0xFF14181E),
    surfaceContainer = Color(0xFF191E25),
    surfaceContainerHigh = Color(0xFF22282F),
    surfaceContainerHighest = Color(0xFF2B323A),
    inverseSurface = Color(0xFFE3E6EC),
    inverseOnSurface = Color(0xFF2B3038),
    inversePrimary = Color(0xFF0B5FD0),
)

private val FleetlightTypography: Typography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

private val FleetlightShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun FleetlightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
    MaterialTheme(
        colorScheme = colors,
        typography = FleetlightTypography,
        shapes = FleetlightShapes,
        content = content,
    )
}
