package app.fleetlight.mobile.ui.theme

import android.app.Activity
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
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val FleetlightBlue = Color(0xFF087CF0)
private val FleetlightBlueDark = Color(0xFF9DCAFF)
private val Healthy = Color(0xFF1E8E4A)
private val HealthyDark = Color(0xFF79DD9A)

private val LightColors = lightColorScheme(
    primary = FleetlightBlue,
    secondary = Healthy,
    tertiary = Color(0xFF7259A5),
    surface = Color(0xFFF9F9FC),
    surfaceContainer = Color(0xFFF0F1F5),
)

private val DarkColors = darkColorScheme(
    primary = FleetlightBlueDark,
    secondary = HealthyDark,
    tertiary = Color(0xFFD8B9FF),
    surface = Color(0xFF111316),
    surfaceContainer = Color(0xFF1C1E22),
)

@Composable
fun FleetlightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
    MaterialTheme(colorScheme = colors, content = content)
}
