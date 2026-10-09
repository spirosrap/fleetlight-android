package app.fleetlight.mobile.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val wallpaperColors: Boolean = false,
)

/** Small, synchronous preference store for the look of the app. Lives in app-private storage only. */
class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())

    val settings: StateFlow<AppearanceSettings> = mutable.asStateFlow()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        val next = transform(mutable.value)
        preferences.edit()
            .putString(KEY_THEME_MODE, next.themeMode.name)
            .putBoolean(KEY_WALLPAPER_COLORS, next.wallpaperColors)
            .apply()
        mutable.value = next
    }

    private fun load(): AppearanceSettings = AppearanceSettings(
        themeMode = preferences.getString(KEY_THEME_MODE, null)
            ?.let { raw -> ThemeMode.entries.firstOrNull { it.name == raw } }
            ?: ThemeMode.SYSTEM,
        wallpaperColors = preferences.getBoolean(KEY_WALLPAPER_COLORS, false),
    )

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_WALLPAPER_COLORS = "wallpaper_colors"

        @Volatile
        private var shared: AppearancePreferences? = null

        fun get(context: Context): AppearancePreferences =
            shared ?: synchronized(this) { shared ?: AppearancePreferences(context).also { shared = it } }
    }
}
