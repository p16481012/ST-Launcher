package app.tavernbridge.launcher.model

internal enum class SpaceLocation(val description: String) {
    DOWNLOAD("백업 ZIP을 저장하는 다운로드 폴더"),
    PRIVATE_TEMP("압축을 검사하거나 풀 때 쓰는 Termux 임시 공간"),
    PRIVATE_INSTALL("설치와 복구 작업에 쓰는 Termux 내부 공간"),
    PRIVATE_SAFETY("되돌리기용 안전 사본을 보관하는 Termux 내부 공간"),
    BACKUP_UNKNOWN("다운로드 폴더 또는 Termux 임시 공간"),
    UNKNOWN("이 작업에 사용하는 저장 공간"),
}

internal data class StorageFailureAdvice(val location: SpaceLocation, val needed: Long? = null, val free: Long? = null)

// Only a failing guard can quote usage. A successful preflight is stale if a later write fails.
private val spaceCodes = mapOf(
    "BACKUP_NO_SPACE" to SpaceLocation.BACKUP_UNKNOWN,
    "SAFETY_BACKUP_NO_SPACE" to SpaceLocation.PRIVATE_SAFETY,
    "RESTORE_NO_SPACE" to SpaceLocation.PRIVATE_INSTALL,
    "IMPORT_NO_SPACE" to SpaceLocation.PRIVATE_INSTALL,
    "INSTALL_IMPORT_NO_SPACE" to SpaceLocation.PRIVATE_INSTALL,
    "EXTENSION_NO_SPACE" to SpaceLocation.PRIVATE_INSTALL,
    "UPDATE_NO_SPACE" to SpaceLocation.PRIVATE_INSTALL,
    "RESET_NO_SPACE" to SpaceLocation.PRIVATE_INSTALL,
    "INSUFFICIENT_STORAGE" to SpaceLocation.UNKNOWN,
)
private val measuredShortage = Regex("""(.+? 부족합니다)\. 필요:? ([0-9]+)바이트, 여유:? ([0-9]+)바이트""")
private val measuredLocations = mapOf(
    "Download 저장 공간이 부족합니다" to SpaceLocation.DOWNLOAD,
    "Termux 임시 검사 공간이 부족합니다" to SpaceLocation.PRIVATE_TEMP,
    "ZIP을 임시 해제할 저장 공간이 부족합니다" to SpaceLocation.PRIVATE_TEMP,
    "백업을 임시 해제하고 현재 상태를 보호할 저장 공간이 부족합니다" to SpaceLocation.PRIVATE_INSTALL,
    "기존 데이터 보호와 복원 적용에 필요한 저장 공간이 부족합니다" to SpaceLocation.PRIVATE_INSTALL,
    "선택 항목을 복원할 저장 공간이 부족합니다" to SpaceLocation.PRIVATE_INSTALL,
    "설치를 안전하게 가져올 저장 공간이 부족합니다" to SpaceLocation.PRIVATE_INSTALL,
    "안전 백업을 저장할 공간이 부족합니다" to SpaceLocation.PRIVATE_SAFETY,
    "기존 설치를 보관하고 다시 설치할 공간이 부족합니다" to SpaceLocation.PRIVATE_INSTALL,
    "업데이트 및 롤백 패키지를 보관할 저장 공간이 부족합니다" to SpaceLocation.PRIVATE_INSTALL,
)
internal fun OperationResultSummary.storageFailureAdvice(): StorageFailureAdvice? {
    if (succeeded) return null
    val fallback = spaceCodes[errorCode] ?: return null
    for (line in detail.lineSequence()) {
        val match = measuredShortage.matchEntire(line.trim()) ?: continue
        val location = measuredLocations[match.groupValues[1]] ?: continue
        val relevant = when (fallback) {
            SpaceLocation.BACKUP_UNKNOWN -> location == SpaceLocation.DOWNLOAD || location == SpaceLocation.PRIVATE_TEMP
            SpaceLocation.PRIVATE_INSTALL -> location == SpaceLocation.PRIVATE_INSTALL || location == SpaceLocation.PRIVATE_TEMP
            else -> location == fallback
        }
        if (!relevant) continue
        val needed = match.groupValues[2].toLongOrNull() ?: continue
        val free = match.groupValues[3].toLongOrNull() ?: continue
        if (needed > free) return StorageFailureAdvice(location, needed, free)
    }
    return StorageFailureAdvice(fallback)
}
