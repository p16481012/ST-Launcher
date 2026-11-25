package app.tavernbridge.launcher.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.R

private const val DEFAULT_SAMPLE = "비 오는 오후, 창가에 앉아 어제 나눈 대화를 읽었어요."
private fun previewLength(value: String) = value.codePointCount(0, value.length)
private fun limitPreview(value: String): String =
    if (previewLength(value) <= 80) value else value.substring(0, value.offsetByCodePoints(0, 80))

@Composable
internal fun AppearanceTextPreview() {
    val app = LocalContext.current.applicationContext
    // Deliberately not in report/launcher settings export or Termux backup.
    val store = remember(app) { app.getSharedPreferences("local_appearance_preview", Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(limitPreview(store.getString("sample", "").orEmpty())) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    fun apply(value: String) {
        saved = value.trim()
        store.edit().apply { if (saved.isEmpty()) remove("sample") else putString("sample", saved) }.apply()
        editing = false
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(saved.ifBlank { DEFAULT_SAMPLE }, style = MaterialTheme.typography.bodyLarge)
        SettingsCaption("안내 문구도 이 크기로 보여요.")
        V2LinkAction("미리보기 문장 바꾸기", { draft = saved; editing = true }) {
            ReviewGlyph(R.drawable.rv_edit, 16.dp, MaterialTheme.colorScheme.secondary)
        }
    }
    if (editing) V2BottomSheet(onClose = { editing = false }, footer = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            V2OutlineAction("기본 문장으로", { apply("") }, Modifier.weight(1f))
            Box(Modifier.weight(1f)) { V2SheetPrimaryButton("적용") { apply(draft) } }
        }
    }) {
        Text("미리보기 문장", style = MaterialTheme.typography.titleLarge)
        Text("자주 보는 대화 한 줄을 넣으면 글자 크기를 고르기 쉬워요. 이 기기에만 저장돼요.")
        Text("문장", style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(draft, { draft = limitPreview(it.replace('\n', ' ')) }, Modifier.fillMaxWidth().heightIn(min = 96.dp).semantics { contentDescription = "미리보기 문장 입력" },
            placeholder = { Text("오늘도 늦게까지 이야기 나눠 줘서 고마워.") },
            shape = RoundedCornerShape(12.dp), maxLines = 3)
        SettingsCaption("${previewLength(draft)}/80 · 비우면 기본 문장을 써요")
    }
}
