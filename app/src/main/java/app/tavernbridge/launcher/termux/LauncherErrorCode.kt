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
        "NODE_LINKER_FAILED" -> "Node.js가 필요한 실행 라이브러리를 불러오지 못했어요. 점검·복구에서 ‘Termux 실행 도구 갱신하기’를 선택해 주세요."
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
        "EXTENSION_RECOVERY_PENDING" -> "설정 → 확장 프로그램 관리에서 중단된 교체를 확인·복구해 주세요. 복구 전에는 자료를 직접 삭제하지 마세요."
        "EXTENSION_CHANGED", "SELECTIVE_RESTORE_SOURCE_CHANGED", "SELECTIVE_RESTORE_TARGET_CHANGED", "VERSION_INSTALLATION_CHANGED", "LAN_SETTINGS_CHANGED" -> "목록을 확인한 뒤 파일 또는 설치 상태가 바뀌었습니다. 창을 닫고 새로 확인한 뒤 다시 선택해 주세요."
        "EXTENSION_UNSUPPORTED" -> "자동 변경을 지원하지 않는 확장 구조 또는 Git 설정입니다. 사용자 수정 내용은 유지하며 SillyTavern의 확장 관리 화면에서 확인해 주세요."
        "EXTENSION_UPDATE_FAILED" -> "확장의 원격 저장소·네트워크·수정 파일 상태를 확인해 주세요. 중단된 교체가 표시되면 확장 관리에서 먼저 복구해 주세요."
        "EXTENSION_PATH_UNSAFE", "LAN_SETTINGS_PATH_UNSAFE" -> "경로가 링크이거나 안전성을 확인할 수 없습니다. 파일을 삭제하지 말고 경로와 상세 로그를 확인해 주세요."
        "EXTENSION_NO_SPACE", "RESTORE_NO_SPACE" -> "휴대폰 여유 공간을 확보한 뒤 다시 확인해 주세요. 복구 중인 자료는 삭제하지 마세요."
        "SELECTIVE_RESTORE_CHARACTER_MISSING" -> "채팅에 연결된 캐릭터 PNG가 필요합니다. 같은 백업에서 해당 캐릭터도 함께 선택해 주세요."
        "SELECTIVE_RESTORE_PROFILE_MISSING" -> "현재 설치에 없는 사용자 폴더입니다. 이 항목은 개별 복원 대신 백업의 전체 복원을 검토해 주세요."
        "SELECTIVE_RESTORE_INVALID" -> "선택한 캐릭터·채팅 파일을 안전하게 복원할 수 없습니다. 목록을 다시 확인하고 상세 로그의 사유를 확인해 주세요."
        "VERSION_TARGET_INVALID" -> "현재 release·staging 브랜치에 남아 있는 이전 커밋만 선택할 수 있습니다. 버전 목록을 다시 확인해 주세요."
        "VERSION_SNAPSHOT_INVALID" -> "전체 백업과 버전 기록이 일치하지 않습니다. 변경된 백업을 자동으로 사용하지 않으며, 기존 전체 복원 메뉴에서 직접 확인할 수 있습니다."
        "LAN_SETTINGS_TRANSFER_FAILED" -> "일회성 로컬 설정 전달이 끝나지 않았습니다. 런처를 화면에 둔 상태에서 외부 접속 설정을 다시 열고 입력해 주세요."
        "LAN_SETTINGS_INPUT_INVALID", "LAN_SETTINGS_CREDENTIALS_REQUIRED" -> "허용할 사설 IP·대역과 인증 계정을 다시 확인해 주세요. 새 암호는 저장되지 않으므로 다시 입력해야 합니다."
        "LAN_SETTINGS_PER_USER_AUTH" -> "사용자별 기본 인증을 사용하는 설치입니다. 인증 계정은 SillyTavern의 사용자 설정에서 관리해 주세요."
        "LAN_SETTINGS_CONFIG_INVALID", "LAN_SETTINGS_SAVE_FAILED" -> "config.yaml의 형식·권한과 설치 상태를 확인해 주세요. 현재 설정을 임의로 초기화하지 않았습니다."
        "SERVER_CONFIG_CHANGED" -> "그 사이 설정 파일이 바뀌었어요. 새 설정을 확인한 뒤 다시 선택해 주세요."
        "SERVER_CONFIG_INVALID" -> "config.yaml을 읽을 수 없어요. 안전 사본에서 이전 설정을 확인해 주세요."
        "SERVER_CONFIG_KEY_UNSUPPORTED" -> "이 버전이 지원하지 않는 설정이에요. 다시 불러와 주세요."
        "SERVER_CONFIG_SAVE_FAILED" -> "설정을 저장하지 못했어요. 설치 폴더와 여유 공간을 확인해 주세요."
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
