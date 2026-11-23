package app.tavernbridge.launcher.termux

import android.app.Activity

internal object LauncherErrorCode {
    private val protocolLine = Regex("""^error_code=([A-Z0-9_]+)$""")

    fun resolve(stdout: String, stderr: String, exitCode: Int, androidErrorCode: Int): String {
        return sequenceOf(stderr, stdout)
            .flatMap { it.lineSequence() }
            .mapNotNull { protocolLine.matchEntire(it.trim())?.groupValues?.get(1) }
            .firstOrNull()
            ?: fallback(exitCode, androidErrorCode)
    }

    fun withoutProtocolLines(text: String): String = text.lineSequence()
        .filterNot { protocolLine.matches(it.trim()) }
        .joinToString("\n")
        .trim()

    fun recommendation(code: String): String = when (code) {
        "" -> ""
        "PACKAGE_INDEX_FAILED" -> "Termux 패키지 목록을 갱신하지 못했습니다. 인터넷 연결과 상세 로그의 저장소 오류를 확인한 뒤 다시 시도해 주세요."
        "ARCHIVE_TOOL_INSTALL_FAILED" -> "ZIP 압축 도구 설치에 실패했습니다. 상세 로그의 패키지 오류를 확인해 주세요. Termux에서 apt-get update && apt-get install zip unzip 실행 결과도 확인할 수 있습니다."
        "PROCESS_KILLED" -> "작업 프로세스가 강제 종료됐습니다. 먼저 점검·복구로 남은 작업을 확인해 주세요. 메모리 부족이나 Android의 백그라운드 제한, Android 12 이상의 하위 프로세스(phantom process) 제한 등이 원인일 수 있습니다. 종료 코드만으로 원인을 확정할 수 없습니다. 반복되면 Termux 공식 안내와 기기의 배터리 제한 설정을 확인해 주세요."
        "TERMUX_COMMAND_TOO_LARGE" -> "한 번에 전달할 항목이 너무 많습니다. 선택 항목을 나누어 다시 시도해 주세요."
        "SAFETY_ITEM_CHANGED" -> "선택한 보관 항목이 변경되었습니다. 목록을 새로고침하고 다시 선택해 주세요."
        "SAFETY_RECOVERY_PENDING" -> "미완료 복구 자료가 있어 정리를 잠갔습니다. 설치 점검·복구를 먼저 실행해 주세요."
        "SAFETY_PATH_UNSAFE" -> "보관 경로의 안전성을 확인하지 못했습니다. 자료를 직접 옮기거나 삭제하지 말고 상태를 확인해 주세요."
        "SAFETY_EXPORT_FAILED" -> "내보내기를 완료하지 못했습니다. Download의 여유 공간·접근 권한과 상세 로그를 확인해 주세요. 원본은 유지됩니다."
        "SAFETY_ITEM_UNSUPPORTED" -> "지원하지 않는 보관 항목입니다. 휴지통 항목은 원래 위치로 복원한 뒤 사용해 주세요."
        "SAFETY_INVENTORY_FAILED" -> "보관 목록을 읽지 못했습니다. 자료는 변경하지 않았습니다. 다시 확인해 주세요."
        else -> "오류 코드와 작업명을 함께 전달하면 문제 확인이 더 빨라집니다."
    }

    private fun fallback(exitCode: Int, androidErrorCode: Int): String = when (exitCode) {
        2 -> "UNSUPPORTED_BRANCH"
        3 -> "INSTALL_PATH_EXISTS"
        4 -> "INSTALLATION_NOT_FOUND"
        5 -> "SERVER_START_FAILED"
        6 -> "FILE_NOT_FOUND"
        7 -> "BACKUP_STORAGE_UNAVAILABLE"
        8 -> "INSTALLATION_INCOMPLETE"
        9 -> "LOCAL_CHANGES_PRESENT"
        10 -> "SERVER_MUST_STOP"
        11 -> "OPERATION_ALREADY_RUNNING"
        12 -> "DEPENDENCY_SETUP_FAILED"
        14 -> "PACKAGE_INDEX_FAILED"
        15 -> "TERMUX_TOOL_INSTALL_FAILED"
        16 -> "ESBUILD_INSTALL_FAILED"
        17 -> "GIT_DOWNLOAD_FAILED"
        18 -> "NETWORK_FETCH_FAILED"
        19 -> "BACKUP_INTEGRITY_FAILED"
        20 -> "ZIP_DAMAGED"
        21 -> "ZIP_UNSAFE_PATH"
        22 -> "ZIP_SYMLINK_REJECTED"
        23 -> "BACKUP_FORMAT_UNSUPPORTED"
        24 -> "BACKUP_CONTENT_INVALID"
        25 -> "RESTORE_ROLLBACK_REQUIRED"
        26 -> "RESTORE_DEPENDENCY_FAILED"
        27 -> "RESTORE_VERIFICATION_FAILED"
        28 -> "BACKUP_DELETE_FAILED"
        29 -> "REQUIREMENT_NOT_MET"
        30 -> "INSUFFICIENT_STORAGE"
        31 -> "UPDATE_GIT_FAILED"
        32 -> "UPDATE_PACKAGES_FAILED"
        33 -> "UPDATE_SERVER_FAILED"
        34 -> "UPDATE_HISTORY_DIVERGED"
        35 -> "SAFETY_BACKUP_NO_SPACE"
        36 -> "SAFETY_BACKUP_FAILED"
        37 -> "SAFETY_BACKUP_SOURCE_CHANGED"
        40 -> "FILE_EDIT_TOO_LARGE"
        41 -> "FILE_NOT_TEXT"
        42 -> "FILE_EDIT_CONFLICT"
        43 -> "FILE_ALREADY_EXISTS"
        44 -> "FILE_PROTECTED_PATH"
        45 -> "FILE_OPERATION_FAILED"
        50 -> "INSTALL_IMPORT_INVALID_PATH"
        51 -> "INSTALL_IMPORT_OVERLAP"
        52 -> "INSTALL_IMPORT_INVALID_INSTALLATION"
        53 -> "INSTALL_IMPORT_UNSAFE_ENTRY"
        54 -> "INSTALL_IMPORT_NO_SPACE"
        55 -> "INSTALL_IMPORT_COPY_FAILED"
        56 -> "NODE_VERSION_UNSUPPORTED"
        57 -> "NODE_VERSION_CHECK_FAILED"
        58 -> "BACKUP_NO_SPACE"
        59 -> "BACKUP_CREATE_FAILED"
        60 -> "BACKUP_SOURCE_CHANGED"
        61 -> "TERMUX_PACKAGE_UPGRADE_FAILED"
        62 -> "NODE_RUNTIME_FAILED"
        63 -> "NPM_RUNTIME_FAILED"
        64 -> "INVALID_ARGUMENT"
        65 -> "ARCHIVE_TOOL_INSTALL_FAILED"
        130 -> "OPERATION_CANCELLED"
        137 -> "PROCESS_KILLED"
        else -> if (androidErrorCode != Activity.RESULT_OK) "TERMUX_COMMAND_FAILED" else "UNKNOWN_COMMAND_FAILURE"
    }
}
