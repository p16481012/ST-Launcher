package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.data.LauncherReleaseClient
import app.tavernbridge.launcher.model.LauncherUiState

@Composable
internal fun UpdateInformationCard(state: LauncherUiState, onCheck: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var linkFailed by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("업데이트 정보", style = MaterialTheme.typography.titleMedium)
            Text("직접 확인할 때만 조회합니다. 앱이나 패키지를 자동으로 설치하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            state.launcherUpdateInfo?.let { info ->
                Text("런처 설치 ${info.installedVersion} · 공개 ${info.publishedVersion}")
                Text(when (info.newerVersionAvailable) {
                    true -> "새 버전이 공개되었습니다."
                    false -> "더 높은 공개 버전이 없습니다. 같은 버전 번호로 교체된 APK는 구분하지 않습니다."
                    null -> "버전 번호를 자동 비교할 수 없습니다. 릴리스 페이지를 확인해 주세요."
                }, style = MaterialTheme.typography.bodySmall)
            }
            state.nodePackageInfo?.let { info ->
                Text("Node.js 패키지 설치: ${info.installed.ifBlank { "확인 불가" }}")
                Text("패키지 목록의 후보: ${info.candidate.ifBlank { "확인 불가" }}", style = MaterialTheme.typography.bodySmall)
                Text("기기에 저장된 패키지 목록 기준입니다. 목록이 오래되었을 수 있으며 호환성을 보장하는 정보는 아닙니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.updateInfoError.isNotBlank()) Text(state.updateInfoError, color = MaterialTheme.colorScheme.error)
            if (state.updateInfoLoading) V2BusyIndicator(linear = false)
            TextButton(onClick = onCheck, enabled = !state.updateInfoLoading && !state.isWorking) { Text("업데이트 정보 확인") }
            TextButton(onClick = { linkFailed = runCatching { uriHandler.openUri(LauncherReleaseClient.RELEASE_URL) }.isFailure }) { Text("공식 저장소 릴리스 열기") }
            if (linkFailed) Text("릴리스 페이지를 열 수 없어요. 브라우저 앱을 확인해 주세요.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun BrowserAutoOpenSetting(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("시작 후 브라우저 자동 열기")
            Text("직접 시작한 서버가 준비되고 런처가 화면에 있을 때만 엽니다.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}
