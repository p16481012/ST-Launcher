package app.tavernbridge.launcher.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.tavernbridge.launcher.MainActivity
import app.tavernbridge.launcher.data.ThemePreferences
import app.tavernbridge.launcher.ui.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import java.util.Locale

class ShareImportActivity : ComponentActivity() {
    private val model by viewModels<ShareImportModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = ThemePreferences(this)
        if (!model.started) model.receive(intent)
        setContent {
            SillyTavernLauncherTheme(prefs.loadTheme(), prefs.loadDisplay()) {
                val state = model.state
                val close = {
                    if (state.step == ShareStep.SAVING) toast("지금 카드를 넣고 있어요. 잠시 기다려 주세요.")
                    else finish()
                }
                BackHandler(enabled = state.step == ShareStep.SAVING, onBack = close)
                ShareImportScreen(state, close, model::select, model::skipCard, model::save, model::prepare,
                    { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                      finish() },
                    { if (!model.openTavern()) toast("브라우저를 열지 못했어요.") })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (model.state.step == ShareStep.SAVING) { toast("지금 가져오기가 끝난 뒤 다시 공유해 주세요."); return }
        setIntent(intent)
        model.receive(intent)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

@Composable
internal fun ShareImportScreen(state: CardScreenState, close: () -> Unit, select: (String) -> Unit,
    skip: (Int,Boolean) -> Unit, save: () -> Unit, retry: () -> Unit, launcher: () -> Unit, browser: () -> Unit) {
    var chooseUser by remember(state.step) { mutableStateOf(false) }
    val font = MaterialTheme.typography
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "뒤로") }
            Text("캐릭터 가져오기", style = font.titleLarge)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (state.step) {
                ShareStep.READ -> Text(state.detail.ifBlank { "공유한 파일을 확인하고 있어요." })
                ShareStep.INVALID -> {
                    Text(state.detail, style = font.headlineSmall)
                    if (state.detail.contains("캐릭터 정보")) {
                        SettingsCaption("그림 파일 안에 캐릭터 설정이 들어 있지 않아요.")
                        SettingsGroup { SettingsRow("디스코드에서 받은 그림인가요?",
                            caption = "미리보기에서 저장하면 캐릭터 정보가 빠질 수 있어요. 원본 파일을 다운로드해서 다시 공유해 주세요.") }
                    }
                    if (state.incoming.size > 1) state.incoming.forEach { SettingsGroup { SettingsRow(it.label, caption = it.error) } }
                }
                ShareStep.DONE -> {
                    val saved = state.results.filter { it.filename != null }
                    val title = when {
                        state.results.size == 1 && saved.size == 1 -> saved.first().name + " 캐릭터를 넣었어요"
                        else -> "" + state.results.size + "개 중 " + saved.size + "개를 넣었어요"
                    }
                    Text(title, style = font.headlineSmall)
                    state.results.forEach { result ->
                        if (result.filename != null) SettingsCaption(state.user + " · " + result.filename)
                        else SettingsGroup { SettingsRow(result.name, caption = result.issue) }
                    }
                    if (saved.isNotEmpty() && state.serverRunning) SettingsGroup {
                        SettingsRow("실리태번에서 대화 열기", caption = "브라우저가 열려요. 캐릭터 목록을 새로고침하면 보여요.", onClick = browser)
                    } else if (saved.isNotEmpty()) SettingsCaption("서버를 켜면 바로 보여요.")
                }
                else -> {
                    state.incoming.forEach { incoming ->
                        val card = incoming.card
                        if (card == null) SettingsGroup { SettingsRow(incoming.label, caption = incoming.error) }
                        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            card.preview?.let { Image(it.asImageBitmap(), null,
                                Modifier.size(72.dp, 96.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(card.name, style = font.titleLarge)
                                SettingsCaption("PNG · " + humanBytes(card.file.length()))
                                if (card.creator.isNotBlank()) SettingsCaption("만든 이 · " + card.creator)
                            }
                        }
                    }
                    when (state.step) {
                        ShareStep.SETUP -> SettingsGroup { SettingsRow("먼저 런처에서 처음 설정을 마쳐 주세요",
                            caption = "설정한 뒤 원래 앱에서 다시 공유해 주세요.") }
                        ShareStep.ERROR -> Text(state.detail.ifBlank { "넣을 곳을 확인하지 못했어요." }, color = MaterialTheme.colorScheme.error)
                        ShareStep.SAVING -> SettingsCaption("캐릭터를 넣고 있어요 · " + state.results.size + " / " + state.incoming.size)
                        ShareStep.READY -> {
                            if (state.users.size > 1) {
                                SettingsHeading("넣을 곳")
                                SettingsGroup { SettingsRow("사용자", value = state.user, onClick = { chooseUser = true }) }
                            }
                            state.plans.forEachIndexed { index, (conflict, name) -> if (conflict) {
                                SettingsHeading("같은 이름의 카드가 있어요")
                                if (state.cards.size > 1) SettingsCaption(state.cards[index].name)
                                SettingsGroup { Column(Modifier.padding(horizontal = 14.dp)) {
                                    SettingsRadioRow("이름을 바꿔서 넣기", index !in state.skip) { skip(index, false) }
                                    SettingsCaption(name)
                                    SettingsRadioRow("넣지 않기", index in state.skip) { skip(index, true) }
                                } }
                            } }
                            if (state.serverRunning) SettingsCaption("서버가 켜져 있어요. 실리태번에서 캐릭터 목록을 새로고침하면 보여요.")
                        }
                        else -> Unit
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            when (state.step) {
                ShareStep.READY -> V2SheetPrimaryButton(if (state.skip.size == state.cards.size) "완료" else if (state.cards.size == 1) "가져오기" else "" + (state.cards.size - state.skip.size) + "개 가져오기",
                    onClick = if (state.skip.size == state.cards.size) close else save)
                ShareStep.SETUP -> V2SheetPrimaryButton("런처 열기", onClick = launcher)
                ShareStep.ERROR -> V2SheetPrimaryButton("다시 확인", onClick = retry)
                ShareStep.DONE -> V2SheetPrimaryButton("완료", onClick = close)
                ShareStep.INVALID -> V2SheetPrimaryButton("확인", onClick = close)
                else -> Unit
            }
        }
    }
    if (chooseUser) V2BottomSheet({ chooseUser = false }) {
        Text("사용자 선택", style = font.titleLarge)
        state.users.forEach { user -> SettingsRadioRow(user, user == state.user) { chooseUser = false; select(user) } }
    }
}

private fun humanBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "" + ((n + 1023) / 1024) + " KB"
    else -> String.format(Locale.KOREA, "%.1f MB", n / (1024.0 * 1024))
}
