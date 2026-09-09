package app.tavernbridge.launcher.model

enum class BackupCategory(
    val key: String,
    val label: String,
    val description: String,
) {
    USER_DATA("user_data", "사용자 데이터", "캐릭터·채팅·설정·프리셋"),
    EXTENSIONS("extensions", "사용자 확장 프로그램", "직접 설치한 확장 프로그램"),
    CONFIG("config", "config.yaml", "서버와 접속 관련 설정"),
    FULL("full", "전체 설치", "node_modules를 제외한 전체 SillyTavern"),
    CUSTOM("custom", "직접 항목 선택", "사용자 데이터 안의 폴더를 개별 선택"),
    ;

    companion object {
        fun fromKey(key: String): BackupCategory? = entries.firstOrNull { it.key == key }
    }
}

data class CustomBackupFolder(val key: String, val label: String)

data class BackupFolderInfo(val label: String, val description: String)

val knownBackupFolders = mapOf(
    "characters" to BackupFolderInfo("캐릭터", "캐릭터 카드와 이미지"),
    "chats" to BackupFolderInfo("1:1 채팅", "캐릭터와 나눈 개인 대화"),
    "group chats" to BackupFolderInfo("그룹 채팅", "그룹에서 나눈 대화"),
    "groups" to BackupFolderInfo("그룹", "그룹 구성과 설정"),
    "worlds" to BackupFolderInfo("월드 정보", "로어북과 세계관 정보"),
    "User Avatars" to BackupFolderInfo("사용자 아바타", "사용자 프로필 이미지"),
    "backgrounds" to BackupFolderInfo("배경", "채팅 화면 배경 이미지"),
    "themes" to BackupFolderInfo("테마", "SillyTavern 화면 테마"),
    "QuickReplies" to BackupFolderInfo("빠른 답장", "빠른 답장 버튼과 스크립트"),
    "context" to BackupFolderInfo("컨텍스트 구성 프리셋", "캐릭터·세계관·대화 기록을 프롬프트에 배치하는 방식"),
    "instruct" to BackupFolderInfo("지시 형식 프리셋", "ChatML·Llama 등 모델별 사용자·AI 역할 표시 형식"),
    "sysprompt" to BackupFolderInfo("시스템 프롬프트", "AI의 기본 역할과 응답 지침"),
    "assets" to BackupFolderInfo("사용자 첨부 파일", "채팅에서 업로드하거나 생성한 파일"),
    "comfy_workflows" to BackupFolderInfo("ComfyUI 작업 흐름", "저장한 이미지 생성 작업 흐름"),
    "extensions" to BackupFolderInfo("사용자 확장 데이터", "사용자별 확장 프로그램 설정"),
    "movingUI" to BackupFolderInfo("화면 배치", "이동식 UI 위치와 크기"),
    "presets" to BackupFolderInfo("생성 프리셋", "온도·샘플링 등 생성 설정"),
    "vectors" to BackupFolderInfo("벡터 데이터", "검색과 기억 기능에서 만든 색인"),
)

fun backupFolderInfo(key: String): BackupFolderInfo =
    knownBackupFolders[key] ?: BackupFolderInfo("사용자 폴더 · $key", "사용자 또는 확장 프로그램이 만든 폴더")

fun backupFolderLabel(key: String): String = backupFolderInfo(key).label

data class BackupArchive(
    val fileName: String,
    val sizeBytes: Long,
    val createdAt: String,
    val categories: Set<BackupCategory>,
    val customFolders: Set<String>,
    val includesSecrets: Boolean,
    val sillyTavernVersion: String,
    val branch: String = "",
    val launcherVersion: String = "",
    val expandedBytes: Long = 0,
    val entryCount: Int = 0,
    val integrity: String = "",
    val requiredRestoreBytes: Long = 0,
    val availableRestoreBytes: Long = 0,
) {
    val restoreSpaceReady: Boolean
        get() = requiredRestoreBytes <= 0 || availableRestoreBytes >= requiredRestoreBytes
}
