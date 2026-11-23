package app.tavernbridge.launcher.model

enum class StorageUsageCategory(val key: String, val label: String) {
    DATA("data", "사용자 데이터"), NODE_MODULES("node_modules", "실행 패키지"), GIT("git", "Git 정보"),
    INSTALLATION_OTHER("installation_other", "설치 파일"), SAFETY("safety", "내부 안전 사본·복구 자료"),
    TRASH("trash", "휴지통"), DOWNLOAD_BACKUPS("download_backups", "다운로드의 백업·내보낸 사본"), NPM_CACHE("npm_cache", "npm 다운로드 캐시");
    companion object { fun fromKey(value: String): StorageUsageCategory? = entries.firstOrNull { it.key == value } }
}

data class StorageUsageEntry(
    val category: StorageUsageCategory,
    val sizeBytes: Long,
    val files: Long,
    val complete: Boolean,
    val skippedEntries: Long,
)

/** Logical file bytes, not physical disk allocation. No scan runs on construction. */
data class StorageUsageReport(
    val entries: List<StorageUsageEntry>,
    val elapsedMillis: Long,
    val scannedEntries: Long,
    val totalBytes: Long,
    val complete: Boolean,
)
