package app.tavernbridge.launcher.model

enum class ExtensionAction(val argument: String, val label: String) {
    UPDATE("update", "업데이트"), DISABLE("disable", "비활성화"), ENABLE("enable", "다시 사용"), TRASH("trash", "휴지통 이동"),
}

data class ManagedExtension(
    val id: String,
    val revision: String,
    val name: String,
    val version: String,
    val scope: String,
    val disabled: Boolean,
    val gitStatus: String,
    val commit: String,
    val canUpdate: Boolean,
    val updateRestriction: String,
    val gitHubRepository: String = "",
    val gitBranch: String = "",
    val remoteAhead: Int? = null,
    val remoteVersion: String? = null,
    val checkIssue: ExtensionCheckIssue? = null,
)

enum class ExtensionCheckIssue(val label: String) {
    NETWORK("원격 확인 실패"), RATE_LIMIT("GitHub 요청 제한"), UNAVAILABLE("원본·브랜치 접근 불가"),
    DIVERGED("설치본과 원격 이력 다름"), UNVERIFIED("비교 결과 미확인"),
}

internal data class ExtensionUpdateMetadata(val ahead: Int? = null, val version: String? = null, val issue: ExtensionCheckIssue? = null)

internal fun ManagedExtension.checkLabel() = when {
    checkIssue != null -> checkIssue.label
    remoteAhead == 0 -> "원격과 동일"
    remoteAhead != null && remoteAhead > 0 -> "새 커밋 ${remoteAhead}개"
    gitHubRepository.isBlank() || gitBranch.isBlank() -> "원본 미확인"
    else -> "검사 전"
}

internal fun extensionCheckSummary(items: List<ManagedExtension>): String {
    if (items.isEmpty()) return "확인 대상 없음"
    val updates = items.count { (it.remoteAhead ?: 0) > 0 }
    val identical = items.count { it.remoteAhead == 0 }
    val unknown = items.size - identical - updates
    return listOfNotNull("업데이트 ${updates}개".takeIf { updates > 0 },
        "원격과 동일 ${identical}개".takeIf { identical > 0 }, "미확인 ${unknown}개".takeIf { unknown > 0 }).joinToString(" · ")
}

internal fun ManagedExtension.sameCheckTarget(other: ManagedExtension) =
    id == other.id && commit == other.commit && gitHubRepository == other.gitHubRepository &&
        gitBranch == other.gitBranch && revision == other.revision

internal fun extensionUpdateReceipt(output: String): String? = output.lineSequence().filter {
    it.startsWith("extension_result=")
}.singleOrNull()?.substringAfter('=')?.takeIf { it == "already-current" || it == "updated" }

internal fun extensionDisplayOrder(items: List<ManagedExtension>): List<ManagedExtension> =
    items.sortedWith(compareBy<ManagedExtension> {
        when { it.canUpdate && (it.remoteAhead ?: 0) > 0 -> 0; !it.disabled -> 1; else -> 2 }
    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id })

internal fun extensionVersionLabel(item: ManagedExtension): String = when {
    (item.remoteAhead ?: 0) <= 0 -> item.version
    item.remoteVersion != null && item.remoteVersion != item.version ->
        listOf(item.version, item.remoteVersion).filter { it.isNotBlank() }.joinToString(" → ")
    else -> listOf(item.version, "새 커밋 ${item.remoteAhead}개").filter { it.isNotBlank() }.joinToString(" · ")
}

data class ExtensionInventory(
    val items: List<ManagedExtension>, val nextCursor: Int?, val skipped: Int,
    val recoveryStatus: String = "none", val recoveryMessage: String = "",
    val nextCheckOffset: Int = 0,
)

fun extensionActionExplanation(item: ManagedExtension, action: ExtensionAction): String = when (action) {
    ExtensionAction.UPDATE -> "HTTPS GitHub 원본의 수정·추가·무시된 파일이 없는 Git 확장만 업데이트합니다. 새 버전을 별도 준비한 뒤 fast-forward 방식만 적용하며, 기존 폴더는 안전 사본·휴지통에 보관합니다. 보호 사본은 암호화되지 않으며 확장에 저장된 비밀정보가 포함될 수 있습니다. 공용 확장은 모든 계정에 영향을 줍니다. 확장 코드의 안전성은 보증하지 않습니다."
    ExtensionAction.DISABLE -> "manifest.json을 런처의 비활성 파일 이름으로 옮겨 다음 실행부터 확장이 로드되지 않게 합니다. 내용은 바꾸지 않으며 ‘다시 사용’으로 되돌릴 수 있습니다. 공용 확장은 모든 계정에 영향을 줍니다."
    ExtensionAction.ENABLE -> "보관한 manifest.json을 원래 이름으로 되돌립니다. SillyTavern 안에서 별도로 꺼 둔 사용자 설정은 변경하지 않습니다. 같은 이름의 파일이 있으면 중단합니다."
    ExtensionAction.TRASH -> "이 확장 폴더 전체를 런처 휴지통으로 옮깁니다. 영구 삭제하지 않습니다. 공용 확장은 모든 계정에서 사용할 수 없게 됩니다. 복구는 안전 사본·휴지통 관리에서 할 수 있습니다."
} + "\n대상: ${item.name}\n범위: ${item.scope}"
