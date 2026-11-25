package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsHeading("설치된 버전")
        SettingsGroup {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.launcherUpdateInfo == null) Text("런처 ${app.tavernbridge.launcher.BuildConfig.VERSION_NAME}")
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
            }
        }
        if (state.updateInfoError.isNotBlank()) Text(state.updateInfoError, color = MaterialTheme.colorScheme.error)
        when {
            state.updateInfoLoading -> SettingsCaption("업데이트 정보를 확인하고 있어요.")
            state.isWorking -> SettingsCaption("진행 중인 작업이 끝나면 업데이트 정보를 확인할 수 있어요.")
            else -> Button(onClick = onCheck, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("업데이트 정보 확인")
            }
        }
        SettingsCaption("이 앱 안에서 런처와 Node.js 버전 정보를 조회해요. 자동으로 설치하지 않아요.")
        SettingsGroup {
            V2NavigationRow("GitHub 릴리스 열기", { linkFailed = runCatching { uriHandler.openUri(LauncherReleaseClient.RELEASE_URL) }.isFailure },
                description = "브라우저 앱이 열려요", leadingIcon = { Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp)) })
        }
        if (linkFailed) Text("릴리스 페이지를 열 수 없어요. 브라우저 앱을 확인해 주세요.", color = MaterialTheme.colorScheme.error)
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
