package app.tavernbridge.launcher.model

enum class LauncherTextSize(val label: String, val scale: Float) {
    SMALL("작게", .9f), NORMAL("보통", 1f), LARGE("크게", 1.15f), MAXIMUM("최대", 1.3f);

    fun applyTo(systemScale: Float): Float = (systemScale * scale).coerceAtMost(2f)
}

enum class LauncherAccent(val label: String, val lightArgb: Long, val darkArgb: Long, val lightWash: Long, val darkWash: Long) {
    AMBER("엠버", 0xFFC2410C, 0xFFFF8A4C, 0xFFFFEDE3, 0xFF3E291F),
    GREEN("초록", 0xFF1A7F51, 0xFF72D7A1, 0xFFE7F5ED, 0xFF1A3328),
    WINE("와인", 0xFF9E2E45, 0xFFFFA0B2, 0xFFFBEAF0, 0xFF3B242C),
    SLATE("슬레이트", 0xFF5C636E, 0xFFBBC5D3, 0xFFEBEDF0, 0xFF2B3038),
    BLUE("블루", 0xFF245AA5, 0xFF93C2FF, 0xFFE8F0FF, 0xFF203146),
    VIOLET("바이올렛", 0xFF6944A1, 0xFFCCAEFF, 0xFFF0EBFF, 0xFF33283F),
}

enum class BackupKeep(val label: String, val count: Int?) {
    UNLIMITED("무제한", null), THREE("최근 3개", 3), FIVE("최근 5개", 5), TEN("최근 10개", 10),
}

data class DisplayPreferences(
    val textSize: LauncherTextSize = LauncherTextSize.NORMAL,
    val accent: LauncherAccent = LauncherAccent.AMBER,
    val highContrast: Boolean = false,
    /** Null follows Android's animator duration scale; an explicit choice survives process death. */
    val reducedMotion: Boolean? = null,
    val keepBackups: BackupKeep = BackupKeep.TEN,
    val includeSecretsByDefault: Boolean = false,
    val systemFont: Boolean = false,
)

internal fun readBrightness(name: String?): AppTheme = when (name) {
    "DARK" -> AppTheme.DARK
    "LIGHT", "PASTEL", "AVOCADO", "BANANA", "BLUEBERRY" -> AppTheme.LIGHT
    else -> AppTheme.SYSTEM
}

internal fun readAccent(stored: String?, oldTheme: String?): LauncherAccent =
    LauncherAccent.entries.firstOrNull { it.name == stored } ?: when (oldTheme) {
        "AVOCADO" -> LauncherAccent.GREEN
        "PASTEL" -> LauncherAccent.WINE
        "BLUEBERRY" -> LauncherAccent.SLATE
        else -> LauncherAccent.AMBER
    }
