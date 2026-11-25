package app.tavernbridge.launcher.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tavernbridge.launcher.data.LauncherRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ShareStep { READ, INVALID, SETUP, ERROR, READY, SAVING, DONE }

internal data class ShareResult(val name: String, val filename: String? = null, val issue: String = "")
internal data class CardScreenState(
    val step: ShareStep = ShareStep.READ,
    val incoming: List<IncomingCard> = emptyList(),
    val users: List<String> = emptyList(),
    val user: String = "",
    val serverRunning: Boolean = false,
    val plans: List<Pair<Boolean, String>> = emptyList(),
    val skip: Set<Int> = emptySet(),
    val results: List<ShareResult> = emptyList(),
    val detail: String = "",
) {
    val cards get() = incoming.mapNotNull { it.card }
}

@Suppress("DEPRECATION")
private fun sharedUris(intent: Intent): List<Uri> {
    require(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) { "파일 앱의 공유하기에서 열어 주세요." }
    val raw: List<Parcelable> = if (intent.action == Intent.ACTION_SEND)
        listOfNotNull(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM))
    else intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM).orEmpty()
    val uris = if (raw.isNotEmpty()) {
        require(raw.size <= MAX_SHARED_CARDS) { "한 번에 20개까지 넣을 수 있어요." }
        raw.map { it as? Uri ?: error("공유 파일 주소를 확인하지 못했어요.") }
    } else {
        val clip = intent.clipData ?: error("공유한 파일이 없어요.")
        require(clip.itemCount <= MAX_SHARED_CARDS) { "한 번에 20개까지 넣을 수 있어요." }
        (0 until clip.itemCount).map { clip.getItemAt(it).uri ?: error("파일을 다시 공유해 주세요.") }
    }
    require(uris.isNotEmpty() && (intent.action != Intent.ACTION_SEND || uris.size == 1))
    require(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) { "파일 읽기 권한이 없어요. 원래 앱에서 다시 공유해 주세요." }
    // Android migrates standard EXTRA_STREAM shares to ClipData when they leave the sending process.
    // A crafted, different EXTRA_STREAM must not borrow old SAF grants held by the launcher.
    val grants = intent.clipData
    require(grants != null && grants.itemCount in 1..MAX_SHARED_CARDS &&
        uris.all { uri -> uri.toString().length <= 8192 && (0 until grants.itemCount).any { grants.getItemAt(it).uri == uri } }) {
        "공유한 파일과 읽기 권한이 일치하지 않아요. 원래 앱에서 다시 공유해 주세요."
    }
    return uris.distinct()
}

internal class ShareImportModel(app: Application) : AndroidViewModel(app) {
    private val repo = LauncherRepository(app)
    private val root = File(app.cacheDir, "share-import")
    private var job: Job? = null
    private var activeDir: File? = null
    private val sessions = mutableListOf<File>()
    private var version = 0
    var state by mutableStateOf(CardScreenState())
        private set
    var started = false
        private set

    // Only UUID-named files in a cache session we own. Never recurse through external paths.
    private fun clearSession(dir: File) {
        if (dir.parentFile != root || dir.canonicalFile != dir.absoluteFile ||
            runCatching { UUID.fromString(dir.name) }.isFailure) return
        dir.listFiles()?.forEach { entry ->
            if (entry.extension == "png" && runCatching { UUID.fromString(entry.nameWithoutExtension) }.isSuccess)
                entry.delete()
        }
        dir.delete()
    }

    fun receive(intent: Intent) {
        if (state.step == ShareStep.SAVING) return
        val previous = job
        val previousDir = activeDir
        previous?.cancel()
        if (previousDir != null) viewModelScope.launch {
            previous?.join()
            withContext(Dispatchers.IO) { clearSession(previousDir) }
            sessions.remove(previousDir)
        }
        activeDir = null
        val v = ++version
        started = true
        state = CardScreenState()
        val uris = try { sharedUris(intent) } catch (e: Exception) {
            state = state.copy(step = ShareStep.INVALID, detail = e.message ?: "공유 파일을 열 수 없어요.")
            return
        }
        val dir = File(root, UUID.randomUUID().toString())
        sessions += dir
        activeDir = dir
        job = viewModelScope.launch {
            try {
                val incoming = withContext(Dispatchers.IO) {
                    check(dir.mkdirs()) { "임시 저장공간을 열 수 없어요." }
                    root.listFiles()?.filter { it != dir && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                        ?.forEach(::clearSession)
                    uris.map { uri ->
                        ensureActive()
                        if (dir.usableSpace < MAX_CARD_BYTES + 4L * 1024 * 1024)
                            IncomingCard("공유 파일", error = "카드를 확인할 임시 공간이 부족해요.")
                        else receiveCard(getApplication<Application>(), uri, dir)
                    }
                }
                if (v != version) return@launch
                state = state.copy(incoming = incoming)
                if (state.cards.isEmpty()) state = state.copy(step = ShareStep.INVALID,
                    detail = if (incoming.size == 1) incoming.first().error else "가져올 수 있는 캐릭터 카드가 없어요")
                else prepare()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (v == version) state = state.copy(step = ShareStep.INVALID, detail = e.message ?: "파일을 열 수 없어요.") }
        }
    }

    fun prepare() {
        if (state.cards.isEmpty() || state.step == ShareStep.SAVING) return
        state = state.copy(step = ShareStep.READ, detail = "넣을 곳을 확인하고 있어요.")
        val v = ++version
        job = viewModelScope.launch {
            try {
                if (!repo.hasKnownInstallation()) { state = state.copy(step = ShareStep.SETUP); return@launch }
                val env = repo.inspect()
                if (v != version) return@launch
                if (!env.managerConnected || !env.sillyTavernInstalled) { state = state.copy(step = ShareStep.SETUP); return@launch }
                check(!env.operationActive && !env.recoveryPending) { "다른 작업이나 복구가 끝난 뒤 다시 확인해 주세요." }
                check(android.os.Build.VERSION.SDK_INT >= 29) { "공유로 가져오기는 Android 10 이상에서 지원해요." }
                check(repo.backupStorageReady()) { "먼저 런처 백업 화면에서 Download 저장공간을 연결해 주세요." }
                val users = repo.cardUsers()
                if (v != version) return@launch
                check(users.isNotEmpty()) { "캐릭터를 넣을 사용자가 없어요. 실리태번을 먼저 열어 주세요." }
                state = state.copy(users = users, serverRunning = env.processRunning || env.serverReachable)
                select(state.user.takeIf { it in users } ?: users.firstOrNull { it == "default-user" } ?: users.first())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (v == version) state = state.copy(
                step = if (e.message.orEmpty().contains("[TERMUX_SETUP_REQUIRED]")) ShareStep.SETUP else ShareStep.ERROR,
                detail = e.message.orEmpty().take(350)) }
        }
    }

    fun select(user: String) {
        if (user !in state.users || state.step == ShareStep.SAVING) return
        val v = ++version
        state = state.copy(step = ShareStep.READ, user = user, plans = emptyList(), skip = emptySet(), detail = "같은 이름의 카드가 있는지 확인하고 있어요.")
        job = viewModelScope.launch {
            try {
                val plans = repo.cardPlan(user, state.cards.map { it.label })
                if (v == version) state = state.copy(step = ShareStep.READY, plans = plans)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (v == version) state = state.copy(step = ShareStep.ERROR, detail = e.message.orEmpty().take(350)) }
        }
    }

    fun skipCard(index: Int, skip: Boolean) {
        if (state.step == ShareStep.READY && state.plans.getOrNull(index)?.first == true)
            state = state.copy(skip = if (skip) state.skip + index else state.skip - index)
    }

    fun save() {
        val original = state
        if (original.step != ShareStep.READY || original.plans.size != original.cards.size) return
        state = original.copy(step = ShareStep.SAVING)
        job = viewModelScope.launch {
            val results = mutableListOf<ShareResult>()
            var i = 0
            for (incoming in original.incoming) {
                ensureActive()
                val card = incoming.card
                val result = if (card == null) ShareResult(incoming.label, issue = incoming.error)
                else {
                    val number = i++
                    if (number in original.skip) ShareResult(card.name, issue = "넣지 않기로 했어요.")
                    else try {
                        // Without prior consent to rename, refuse a name which became occupied after preview.
                        ShareResult(card.name, repo.importSharedCard(original.user, card, original.plans[number].first))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { ShareResult(card.name, issue = e.message.orEmpty().take(350).ifBlank { "넣지 못했어요." }) }
                }
                results += result
                state = state.copy(results = results.toList())
            }
            state = state.copy(step = ShareStep.DONE)
            withContext(Dispatchers.IO) { activeDir?.let(::clearSession) }
        }
    }

    fun openTavern(): Boolean = repo.openBrowser(repo.configuredPort())

    override fun onCleared() {
        job?.cancel()
        sessions.forEach(::clearSession)
        super.onCleared()
    }
}
