package app.tavernbridge.launcher.data

import android.content.Context
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.BackupKeep
import app.tavernbridge.launcher.model.DisplayPreferences
import app.tavernbridge.launcher.model.readAccent
import app.tavernbridge.launcher.model.readBrightness
import app.tavernbridge.launcher.model.LauncherTextSize

class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)

    init {
        // Resolve legacy named palettes *before* a subsequent brightness change loses that information.
        val old = preferences.getString(KEY_THEME, null)
        if (old in setOf("PASTEL", "AVOCADO", "BANANA", "BLUEBERRY")) {
            val accent = readAccent(preferences.getString("accent", null), old)
            preferences.edit().putString(KEY_THEME, readBrightness(old).name).putString("accent", accent.name).apply()
        }
    }

    fun loadTheme(): AppTheme = readBrightness(preferences.getString(KEY_THEME, null))

    fun saveTheme(theme: AppTheme) {
        preferences.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun loadDisplay() = DisplayPreferences(
        textSize = LauncherTextSize.entries.firstOrNull { it.name == preferences.getString("text_scale", null) } ?: LauncherTextSize.NORMAL,
        accent = readAccent(preferences.getString("accent", null), preferences.getString(KEY_THEME, null)),
        highContrast = preferences.getBoolean("high_contrast", false),
        reducedMotion = if (preferences.contains("reduced_motion")) preferences.getBoolean("reduced_motion", false) else null,
        keepBackups = BackupKeep.entries.firstOrNull { it.name == preferences.getString("keep_backups", null) } ?: BackupKeep.TEN,
        includeSecretsByDefault = preferences.getBoolean("manual_backup_secrets", false),
    )

    fun saveDisplay(value: DisplayPreferences) {
        preferences.edit().putString("text_scale", value.textSize.name).putString("accent", value.accent.name)
            .putBoolean("high_contrast", value.highContrast).putString("keep_backups", value.keepBackups.name)
            .putBoolean("manual_backup_secrets", value.includeSecretsByDefault)
            .also { if (value.reducedMotion == null) it.remove("reduced_motion") else it.putBoolean("reduced_motion", value.reducedMotion) }
            .apply()
    }

    private companion object {
        const val KEY_THEME = "theme"
    }
}
