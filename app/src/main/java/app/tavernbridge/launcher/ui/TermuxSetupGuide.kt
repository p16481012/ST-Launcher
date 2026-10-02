package app.tavernbridge.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.model.LauncherUiState
import app.tavernbridge.launcher.model.TermuxSetupStatus

/** An in-app example, not an overlay on Termux or an interactive terminal. */
@Composable
internal fun TermuxPasteIllustration() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Termux 화면 예시",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = "Termux 화면 예시: 검은 화면을 길게 눌러 붙여넣기를 선택하고, 키보드의 Enter 키를 누릅니다."
                },
            color = Color(0xFF17191D),
            contentColor = Color(0xFFF2F4F8),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("$ ▌", fontFamily = FontFamily.Monospace, color = Color(0xFFF2F4F8))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(62.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier.size(60.dp)
                                    .border(1.dp, Color(0xFFAFC8FF).copy(alpha = .4f), CircleShape),
                            )
                            Box(
                                Modifier.size(44.dp)
                                    .background(Color(0xFFAFC8FF).copy(alpha = .16f), CircleShape)
                                    .border(1.dp, Color(0xFFAFC8FF), CircleShape),
                            )
                            Icon(Icons.Outlined.TouchApp, null, Modifier.size(30.dp), tint = Color(0xFFF2F4F8))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFF0F1F4),
                                contentColor = Color(0xFF1B1D22),
                            ) {
                                Text(
                                    "붙여넣기",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Text(
                                "빈 곳을 길게 눌러요",
                                color = Color(0xFFD8DCE5),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF30343D))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "키보드",
                        modifier = Modifier.weight(1f),
                        color = Color(0xFFD8DCE5),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardReturn, null, Modifier.size(22.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Enter", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SetupInstruction(number: String, instruction: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(
                number,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            formatUiText(instruction, keepWordsTogether = true),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun TermuxSetupFeedback(state: LauncherUiState) {
    val setup = state.termuxSetup
    val checking = setup.status == TermuxSetupStatus.CHECKING
    val needsAttention = setup.status == TermuxSetupStatus.EXTERNAL_APPS_DISABLED ||
        setup.status == TermuxSetupStatus.UNVERIFIED
    val title = when (setup.status) {
        TermuxSetupStatus.CHECKING -> "Termux 연결을 확인하고 있어요"
        TermuxSetupStatus.READY -> "Termux 연결이 확인됐어요"
        TermuxSetupStatus.EXTERNAL_APPS_DISABLED -> "Termux의 외부 명령 허용이 꺼져 있어요"
        TermuxSetupStatus.UNVERIFIED -> "아직 연결을 확인하지 못했어요"
        TermuxSetupStatus.TERMUX_MISSING -> "Termux 설치를 확인해 주세요"
        TermuxSetupStatus.PERMISSION_REQUIRED -> "Android 실행 권한이 필요해요"
        TermuxSetupStatus.UNKNOWN -> "돌아오면 실제 연결을 확인할게요"
    }
    val description = setup.detail.ifBlank {
        when (setup.status) {
            TermuxSetupStatus.CHECKING -> "파일을 바꾸지 않는 짧은 명령으로 확인 중이에요."
            TermuxSetupStatus.READY -> "이제 다음 단계에서 런처를 연결할 수 있어요."
            TermuxSetupStatus.EXTERNAL_APPS_DISABLED -> "위 명령을 Termux에 다시 붙여넣고 Enter를 누른 뒤, 아래에서 다시 확인해 주세요."
            TermuxSetupStatus.UNVERIFIED -> "Termux에서 Enter를 눌렀는지 확인하고 다시 시도해 주세요."
            TermuxSetupStatus.TERMUX_MISSING -> "Termux를 설치하고 한 번 열어 주세요."
            TermuxSetupStatus.PERMISSION_REQUIRED -> "앱 권한 설정에서 Termux 명령 실행을 허용해 주세요."
            TermuxSetupStatus.UNKNOWN -> "복사하거나 Termux를 여는 것만으로는 완료되지 않아요. 붙여넣은 뒤 Enter까지 눌러 주세요."
        }
    }
    val containerColor = when {
        needsAttention -> MaterialTheme.colorScheme.errorContainer
        setup.verified -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        needsAttention -> MaterialTheme.colorScheme.onErrorContainer
        setup.verified -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        when {
                            setup.verified -> Icons.Outlined.CheckCircle
                            needsAttention -> Icons.Outlined.WarningAmber
                            else -> Icons.Outlined.Info
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    formatUiText(title, keepWordsTogether = true),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                formatUiText(description, keepWordsTogether = true),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
