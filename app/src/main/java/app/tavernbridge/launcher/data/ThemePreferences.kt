package app.tavernbridge.launcher.data

import android.content.Context
import app.tavernbridge.launcher.model.AppTheme
import app.tavernbridge.launcher.model.BackupKeep
import app.tavernbridge.launcher.model.DisplayPreferences
import app.tavernbridge.launcher.model.LauncherAccent
import app.tavernbridge.launcher.model.LauncherTextSize

class ThemePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("launcher_preferences", Context.MODE_PRIVATE)

    fun loadTheme(): AppTheme {
        val stored = preferences.getString(KEY_THEME, null)
        return AppTheme.entries.firstOrNull { it.name == stored } ?: AppTheme.SYSTEM
    }

    fun saveTheme(theme: AppTheme) {
        preferences.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun loadDisplay() = DisplayPreferences(
        textSize = LauncherTextSize.entries.firstOrNull { it.name == preferences.getString("text_scale", null) } ?: LauncherTextSize.NORMAL,
        accent = LauncherAccent.entries.firstOrNull { it.name == preferences.getString("accent", null) } ?: LauncherAccent.AMBER,
        highContrast = preferences.getBoolean("high_contrast", false),
        reducedMotion = if (preferences.contains("reduced_motion")) preferences.getBoolean("reduced_motion", false) else null,
        keepBackups = BackupKeep.entries.firstOrNull { it.name == preferences.getString("keep_backups", null) } ?: BackupKeep.UNLIMITED,
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
