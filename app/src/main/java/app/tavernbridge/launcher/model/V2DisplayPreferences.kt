package app.tavernbridge.launcher.model

enum class LauncherTextSize(val label: String, val scale: Float) {
    SMALL("작게", .9f), NORMAL("보통", 1f), LARGE("크게", 1.15f), MAXIMUM("최대", 1.3f);

    fun applyTo(systemScale: Float): Float = (systemScale * scale).coerceAtMost(2f)
}

enum class LauncherAccent(val label: String, val lightArgb: Long, val darkArgb: Long) {
    AMBER("엠버", 0xFFC2410C, 0xFFFFAA79),
    GREEN("초록", 0xFF17794D, 0xFF72D7A1),
    WINE("와인", 0xFFA42D4A, 0xFFFFA0B2),
    SLATE("슬레이트", 0xFF5B6371, 0xFFBBC5D3),
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
    val keepBackups: BackupKeep = BackupKeep.UNLIMITED,
    val includeSecretsByDefault: Boolean = false,
)
