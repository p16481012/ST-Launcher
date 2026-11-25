package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.UpdatePreflight
import app.tavernbridge.launcher.model.UpdateRecord
import app.tavernbridge.launcher.ui.theme.V2Mono

/** Everything formerly behind the management/update tab belongs on this destination. */
@Composable
internal fun UpdateManagementContent(state: LauncherUiState, mod: Modifier,
    onCheck: () -> Unit, onBackupPreference: (Boolean) -> Unit, onUpdate: () -> Unit, onInstallation: () -> Unit,
    onVersions: () -> Unit, onBackups: () -> Unit, onAllChanges: () -> Unit) {
    val stopToContinue = rememberStopToContinue(state)
    val report = state.updatePreflight
    val env = state.environment
    val busy = state.isWorking || env.operationActive
    val blocked = when {
        busy -> "진행 중인 작업이 끝난 뒤 다시 확인해 주세요."
        env.recoveryPending -> "먼저 중단된 작업을 복구해 주세요."
        !env.managerConnected -> "먼저 Termux에 연결해 주세요."
        !env.sillyTavernInstalled -> "먼저 SillyTavern 설치 상태를 확인해 주세요."
        else -> ""
    }
    val ready = blocked.isEmpty() && report?.updateAvailable == true &&
        report.nodeCompatible && report.spaceReady && (!state.autoBackupBeforeUpdate || report.backupStorageReady)
    val branch = report?.branch?.ifBlank { null } ?: env.branch?.label
    val version = report?.currentVersion?.ifBlank { null } ?: env.sillyTavernVersion
    val sameVersion = report != null && report.updateAvailable && report.currentVersion.isNotBlank() &&
        report.currentVersion == report.targetVersion
    DetailLayout(mod, footer = {
        val buttonMod = Modifier.weight(1f).heightIn(min = 52.dp)
        if (report?.updateAvailable == false) {
            OutlinedButton(onClick = onCheck, modifier = buttonMod, enabled = blocked.isEmpty(),
                shape = RoundedCornerShape(14.dp)) { Text("다시 확인") }
        } else Button(onClick = if (report == null) onCheck else onUpdate, modifier = buttonMod,
            enabled = if (report == null) blocked.isEmpty() else ready, shape = RoundedCornerShape(14.dp)) {
            Text(if (report == null) "업데이트 안전 검사" else "업데이트")
        }
    }) {
        Lead(when {
            report == null -> "업데이트를 확인해요"
            !report.updateAvailable -> "최신 버전이에요"
            sameVersion -> "같은 버전에 새 커밋이 있어요"
            else -> "${report.targetVersion.ifBlank { "새" }} 버전을\n받을 수 있어요"
        }, listOfNotNull(version.takeIf(String::isNotBlank)?.let { "지금 $it" }, branch,
            report?.takeIf { it.updateAvailable }?.let { "새 커밋 ${it.commitsBehind.coerceAtLeast(1)}개 · 약 ${formatBackupBytes(it.requiredBytes)}" })
            .joinToString(" · "))
        if (report?.updateAvailable == true) UpdateChangeListPreview(report, onAllChanges)

        ToolGroup {
            SettingsRow("업데이트 전에 자동 백업", checked = state.autoBackupBeforeUpdate, enabled = !busy,
                caption = "사용자 데이터·확장 프로그램·config.yaml을 백업해요. secrets.json과 외부 접속 로그인 정보는 넣지 않아요.",
                onClick = { onBackupPreference(!state.autoBackupBeforeUpdate) })
        }
        ToolGroup("안전 검사 결과") {
            if (report == null) ToolRow("아직 검사하지 않았어요.", "커밋·Node.js·저장 공간·수정 파일을 확인해요.")
            else {
                SettingsRow("현재 커밋", report.currentCommit.shortCommit(), mono = true)
                ToolDivider()
                SettingsRow("대상 커밋", report.targetCommit.shortCommit(), mono = true)
                ToolDivider()
                SettingsRow("Node.js", "${report.nodeVersion.ifBlank { "없음" }} / 필요 ${report.nodeRequired.ifBlank { "알 수 없음" }}",
                    caption = if (report.nodeCompatible) "" else "현재 Node.js가 대상 버전의 요구 조건을 충족하지 않아요.",
                    valueColor = if (report.nodeCompatible) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                ToolDivider()
                ToolRow("업데이트 공간", "${formatBackupBytes(report.requiredBytes)} 필요 · ${formatBackupBytes(report.freeBytes)} 여유",
                    danger = !report.spaceReady)
                if (!report.spaceReady) ToolRow("기존 패키지를 보존하고 새 패키지를 설치할 공간이 부족해요.", danger = true)
                if (state.autoBackupBeforeUpdate && !report.backupStorageReady) {
                    ToolDivider()
                    ToolRow("백업 저장공간 연결", "자동 백업을 사용하려면 백업 화면에서 저장공간을 연결해 주세요.",
                        danger = true, onClick = onBackups)
                }
                ToolDivider()
                if (report.modifiedFiles.isEmpty()) ToolRow("수정된 파일", trailing = "없음") else {
                    ToolRow("수정된 파일 ${report.modifiedFiles.size}개", "별도 안전 파일에 보관하지만 새 버전에는 자동으로 적용되지 않아요.", danger = true)
                    SelectionContainer {
                        Text(report.modifiedFiles.joinToString("\n"), Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodySmall, fontFamily = V2Mono)
                    }
                }
                if (report.updateAvailable) {
                    ToolDivider()
                    SettingsRow("업데이트 안전 검사", caption = "원격 커밋과 설치 환경을 다시 확인해요.", enabled = blocked.isEmpty(), onClick = onCheck)
                }
            }
            if (blocked.isNotBlank()) ToolRow("지금 검사할 수 없어요", blocked)
        }

        ToolGroup("버전 관리") {
            SettingsRow("이전 버전 선택·복귀", caption = when {
                blocked.isEmpty() && stopToContinue != null -> "서버를 끈 뒤 돌아갈 버전을 선택해요."
                blocked.isNotBlank() -> blocked
                env.processRunning -> "서버를 종료한 뒤 선택할 수 있어요."
                else -> "이전 커밋이나 버전 백업으로 돌아가요."
            }, enabled = blocked.isEmpty() && (!env.processRunning || stopToContinue != null),
                onClick = { enterStoppedAction(env.processRunning, stopToContinue, "이전 버전 선택", onVersions) })
            ToolDivider()
            ToolRow("설치 정보·가져오기·브랜치", onClick = onInstallation)
        }
        UpdateRecordGroup(state.lastUpdateRecord, onBackups)
    }
}

@Composable
private fun UpdateChangeListPreview(report: UpdatePreflight, onAll: () -> Unit) {
    ToolGroup("바뀐 내용", if (report.changes.size > 3) "${report.changes.size}개 모두" else null, onAll) {
        report.changes.take(3).forEachIndexed { i, change ->
            if (i != 0) ToolDivider()
            ToolRow(change.subject, change.commit.shortCommit(), dataNote = true)
        }
        if (report.changes.isEmpty()) ToolRow("커밋 목록을 확인하지 못했어요.", "안전 검사를 다시 실행하면 변경 내역을 다시 조회해요.")
        else {
            ToolDivider()
            Text("SillyTavern 커밋 제목이에요. 기능별 릴리스 설명과는 다를 수 있어요." +
                if (report.changesTruncated) " 최근 ${report.changes.size}개만 조회했어요." else "",
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun String.shortCommit(): String = take(7).ifBlank { "알 수 없음" }

@Composable
private fun UpdateRecordGroup(record: UpdateRecord?, onBackups: () -> Unit) {
    ToolGroup("최근 업데이트 기록") {
        if (record == null) ToolRow("표시할 업데이트 기록이 없어요.") else {
            val rolledBack = record.result.startsWith("rolled_back")
            ToolRow(when {
                record.result == "success" -> "업데이트 및 서버 검증 완료"
                rolledBack -> "업데이트 실패 후 이전 상태로 롤백됨"
                record.result == "rollback_failed" -> "자동 롤백 확인 필요"
                else -> "업데이트 기록: ${record.result.ifBlank { "알 수 없음" }}"
            }, danger = record.result == "rollback_failed")
            ToolDivider()
            SettingsRow("시각", record.createdAt.ifBlank { "알 수 없음" })
            ToolDivider()
            SettingsRow("브랜치", record.branch.ifBlank { "알 수 없음" })
            ToolDivider()
            SettingsRow("변경 전 커밋", record.oldCommit.shortCommit(), mono = true)
            ToolDivider()
            SettingsRow("기록된 대상 커밋", record.newCommit.ifBlank { record.targetCommit }.shortCommit(), mono = true)
            if (rolledBack || record.result == "rollback_failed") {
                ToolDivider()
                ToolRow("업데이트 전 ZIP 백업 확인·복원", onClick = onBackups)
            }
        }
    }
}
