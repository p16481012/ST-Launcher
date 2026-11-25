package app.tavernbridge.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.TermuxProcessHelp

/** Settings links are deliberate user actions; this card never changes Android restrictions itself. */
@Composable
internal fun TermuxProcessHelpCard(
    androidSdk: Int,
    onOpenTermuxSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenOfficialHelp: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Termux가 갑자기 종료될 때", modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "도움말 접기" else "도움말 펼치기",
                )
            }
            if (expanded) {
                Text(TermuxProcessHelp.explanation(androidSdk), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "먼저 진행 중이던 작업의 점검·복구 여부를 확인하세요. 반복되면 Termux 앱 정보에서 배터리 제한을 확인하세요. 설정 변경은 직접 선택하며, 제한을 바꾸면 배터리 사용량이 늘 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                V2NavigationGroup {
                    V2NavigationRow("Termux 앱 정보 열기", onOpenTermuxSettings, description = "Android 설정 앱이 열려요")
                    V2NavigationRow("배터리 설정 열기", onOpenBatterySettings, description = "Android 설정 앱이 열려요")
                    if (TermuxProcessHelp.includesAndroid12ProcessLimit(androidSdk)) {
                        V2NavigationRow("Android 12 이상 공식 도움말", { onOpenOfficialHelp(TermuxProcessHelp.OFFICIAL_HELP_URL) },
                            description = "브라우저 앱이 열려요")
                    }
                }
            }
        }
    }
}
