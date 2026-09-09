package app.tavernbridge.launcher.data

import android.content.Context
import app.tavernbridge.launcher.model.AppTheme

class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)

    fun loadTheme(): AppTheme {
        val stored = preferences.getString(KEY_THEME, null)
        return AppTheme.entries.firstOrNull { it.name == stored } ?: AppTheme.SYSTEM
    }

    fun saveTheme(theme: AppTheme) {
        preferences.edit().putString(KEY_THEME, theme.name).apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
    }
}
