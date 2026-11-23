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
)

data class ExtensionInventory(
    val items: List<ManagedExtension>, val nextCursor: Int?, val skipped: Int,
    val recoveryStatus: String = "none", val recoveryMessage: String = "",
)

fun extensionActionExplanation(item: ManagedExtension, action: ExtensionAction): String = when (action) {
    ExtensionAction.UPDATE -> "HTTPS GitHub 원본의 수정·추가·무시된 파일이 없는 Git 확장만 업데이트합니다. 새 버전을 별도 준비한 뒤 fast-forward 방식만 적용하며, 기존 폴더는 안전 사본·휴지통에 보관합니다. 보호 사본은 암호화되지 않으며 확장에 저장된 비밀정보가 포함될 수 있습니다. 공용 확장은 모든 계정에 영향을 줍니다. 확장 코드의 안전성은 보증하지 않습니다."
    ExtensionAction.DISABLE -> "manifest.json을 런처의 비활성 파일 이름으로 옮겨 다음 실행부터 확장이 로드되지 않게 합니다. 내용은 바꾸지 않으며 ‘다시 사용’으로 되돌릴 수 있습니다. 공용 확장은 모든 계정에 영향을 줍니다."
    ExtensionAction.ENABLE -> "보관한 manifest.json을 원래 이름으로 되돌립니다. SillyTavern 안에서 별도로 꺼 둔 사용자 설정은 변경하지 않습니다. 같은 이름의 파일이 있으면 중단합니다."
    ExtensionAction.TRASH -> "이 확장 폴더 전체를 런처 휴지통으로 옮깁니다. 영구 삭제하지 않습니다. 공용 확장은 모든 계정에서 사용할 수 없게 됩니다. 복구는 안전 사본·휴지통 관리에서 할 수 있습니다."
} + "\n대상: ${item.name}\n범위: ${item.scope}"
