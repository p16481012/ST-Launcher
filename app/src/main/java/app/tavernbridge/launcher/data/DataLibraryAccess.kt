package app.tavernbridge.launcher.data

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import app.tavernbridge.launcher.model.operationCheckpoint
import app.tavernbridge.launcher.share.readPngCard
import app.tavernbridge.launcher.termux.TermuxCommandResult
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class LibraryKind(val wire: String, val label: String, val maxMiB: Int = 0) {
    CARDS("cards", "캐릭터 카드", 20), ORPHANS("orphans", "카드 없이 남은 채팅"),
    BACKGROUNDS("backgrounds", "배경 그림", 30), WORLDS("worlds", "로어북", 50),
    PRESETS("presets", "채팅 완성 프리셋", 50), THEMES("themes", "테마", 50),
    QUICK_REPLIES("quickreplies", "빠른 답장(QR)", 50), HISTORY("history", "휴지통에서 되돌리기")
}
internal data class LibraryFilePlan(val uri: Uri, val name: String, val info: String, val rejection: String = "")
internal data class LibraryWrite(val operation: String, val label: String, val pause: Boolean,
    val message: (TermuxCommandResult) -> String, val execute: suspend () -> TermuxCommandResult)

private fun TermuxCommandResult.field(key: String) = stdout.lineSequence().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
private fun TermuxCommandResult.verified(): TermuxCommandResult {
    check(isSuccess) { readableError() }
    check(!stdoutTruncated) { "자료 목록 응답이 잘렸어요. 다시 확인해 주세요." }
    return this
}
private fun wire(command: String, vararg parts: String) = (listOf(command) + parts.map {
    require(it.length <= 95_000 && it.matches(Regex("[A-Za-z0-9+/=_.:-]*")))
    "'$it'"
}).joinToString(" ")


/** No library scans run on launch, home, server start, or in the background. */
internal class DataLibraryAccess(private val context: Context, private val getUsers: suspend () -> List<String>,
    private val run: suspend (String, Long) -> TermuxCommandResult) {
    private val staging = SharedImportStaging(context)
    val artwork = LibraryArtworkLoader(run)
    val lastMoved = MutableStateFlow<Pair<String,LibraryGroup>?>(null)
    val pendingUndo = MutableStateFlow<Pair<String,LibraryGroup>?>(null)
    fun requestUndo(receipt: Pair<String,LibraryGroup>) {
        if (lastMoved.value == receipt) pendingUndo.value = receipt
    }
    suspend fun users() = getUsers().distinct()
    suspend fun page(user: String, kind: LibraryKind, cursor: String): LibraryPage {
        require(kind != LibraryKind.HISTORY)
        val cmd = wire("library-list", libraryEncode(user), kind.wire, cursor)
        return LibraryProtocol.page(run(cmd, 10 * 60_000L).verified().stdout)
    }
    suspend fun groups(user: String) =
        LibraryProtocol.groups(run(wire("library-history", libraryEncode(user)), 60_000).verified().stdout)
    suspend fun preview(user: String, row: LibraryItem): LibraryAssetPreview {
        require(row.isManagedAsset() && !row.path.startsWith("backgrounds/"))
        val result = run(wire("library-preview", libraryEncode(user), libraryEncode(row.path), row.revision), 35_000L).verified()
        return LibraryAssetPreview.parse(result.stdout)
    }
    suspend fun assetUsage(user: String, row: LibraryItem): AssetUsage {
        val card = row.isCardAsset()
        require(card || row.isManagedAsset())
        val r = run(wire(if (card) "library-card-usage" else "library-usage",
            libraryEncode(user), libraryEncode(row.path), row.revision), 5 * 60_000L).verified()
        val state = when (r.field("state")) {
            "used" -> AssetUse.USED; "none" -> AssetUse.NONE; "unconfirmed" -> AssetUse.UNKNOWN
            else -> error("사용처 검사 결과를 확인할 수 없어요. 다시 확인해 주세요.")
        }
        return AssetUsage(state, r.field("detail_b64")?.let(::libraryDecode).orEmpty())
    }

    fun changeAsset(user: String, row: LibraryItem, action: AssetChange, newStem: String = "", allowUnknown: Boolean = false): LibraryWrite {
        val card = row.isCardAsset()
        require(card || row.isManagedAsset())
        require(!card || (!allowUnknown && action != AssetChange.TRASH)) {
            "채팅·그룹 연결이 불확실한 카드는 원본을 바꾸지 않아요."
        }
        val stem = if (action == AssetChange.TRASH) "" else {
            assetNameError(row, newStem)?.let { throw IllegalArgumentException(it) }
            assetStem(newStem)
        }
        val operation = if (card) "library-card-change" else "library-asset-change"
        val args = listOf(libraryEncode(user), libraryEncode(row.path), row.revision, action.wire, libraryEncode(stem)) +
            (if (card) emptyList() else listOf(if (allowUnknown) "1" else "0"))
        val command = wire(operation, *args.toTypedArray())
        return LibraryWrite(operation, action.label, true, { result ->
            val name = result.field("name_b64")?.let(::libraryDecode)
            when (action) {
                AssetChange.TRASH -> "휴지통으로 옮겼어요. 원래 위치로 되돌릴 수 있어요."
                AssetChange.RENAME -> {
                    val count = if (card) result.field("chats_moved")?.toIntOrNull() ?: 0 else 0
                    if (count > 0) "이름을 바꾸고 1:1 채팅 ${count}개를 옮겼어요 · ${name ?: stem}\n실리태번이 열려 있다면 새로고침한 뒤 새 이름의 카드를 열어 주세요."
                    else "이름을 바꿨어요 · ${name ?: stem}"
                }
                AssetChange.COPY -> "새 이름으로 복사했어요 · ${name ?: stem}\n" +
                    if (card) "기존 채팅과 그룹은 원본 카드에 남아 있어요." else "원본은 그대로예요."
            }
        }) {
            lastMoved.value = null
            run(command, 5 * 60_000L).also { r ->
                val id = r.field("group_id")
                if (action == AssetChange.TRASH && r.isSuccess && !r.stdoutTruncated &&
                    r.field("action") == "trash" && r.field("moved") == "1" && id != null && libraryGroupId.matches(id))
                    lastMoved.value = user to LibraryGroup(id, System.currentTimeMillis(), 1)
            }
        }
    }
    fun trash(user: String, rows: List<LibraryItem>, chats: Boolean): LibraryWrite {
        require(rows.isNotEmpty() && rows.size <= 500 && rows.all { it.path.safeLibraryRelative() && librarySha.matches(it.revision) })
        val array = JSONArray().apply { rows.forEach { put(JSONObject().put("path",it.path).put("revision",it.revision)) } }
        val command = wire("library-trash",libraryEncode(user),libraryEncode(array.toString()),if(chats) "1" else "0")
        return LibraryWrite("library-trash", "휴지통으로 옮기기", true, { r ->
            "휴지통으로 ${r.field("moved") ?: "0"}개 옮겼어요. 건너뜀 ${r.field("skipped") ?: "0"}개 · 비우기 전에는 공간이 늘지 않아요."
        }) {
            lastMoved.value = null
            run(command, 30 * 60_000L).also { r ->
                val id = r.field("group_id"); val count = r.field("moved")?.toIntOrNull() ?: 0
                if (r.isSuccess && !r.stdoutTruncated && id != null && libraryGroupId.matches(id) && count > 0)
                    lastMoved.value = user to LibraryGroup(id, System.currentTimeMillis(), count)
            }
        }
    }
    fun undo(user: String, group: LibraryGroup): LibraryWrite {
        require(libraryGroupId.matches(group.id))
        return LibraryWrite("library-undo", "휴지통에서 되돌리기", true, { r ->
            "${r.field("restored") ?: "0"}개를 되돌렸어요. 건너뜀 ${r.field("skipped") ?: "0"}개."
        }) { run(wire("library-undo",libraryEncode(user),group.id),30 * 60_000L).also { r ->
            if (r.isSuccess && lastMoved.value?.second?.id == group.id) lastMoved.value = null
        } }
    }

    suspend fun plan(uri: Uri, kind: LibraryKind): LibraryFilePlan = withContext(Dispatchers.IO) {
        require(uri.scheme == "content" && kind.maxMiB > 0)
        val name = staging.displayNameCancellable(uri)
        val basic = LibraryFilePlan(uri, name, "")
        try {
            val max = kind.maxMiB * 1024L * 1024
            val size = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE),null,null,null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1
            } ?: -1
            require(size <= max) { "${kind.maxMiB} MiB 이하만 넣을 수 있어요." }
            require(name.length in 1..500 && !name.contains('\u0000')) { "파일 이름을 확인하지 못했어요." }
            val ext = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            when (kind) {
                LibraryKind.CARDS -> {
                    require(ext == "png") { "캐릭터는 PNG 카드만 넣을 수 있어요. 다른 형식은 실리태번에서 가져와 주세요." }
                    val (cardName, _) = checkNotNull(context.contentResolver.openInputStream(uri)) {
                        "선택한 카드를 읽지 못했어요."
                    }.use { readPngCard(it,size.takeIf { n -> n >= 0 }) }
                    basic.copy(info = "PNG 캐릭터 카드 · $cardName")
                }
                LibraryKind.BACKGROUNDS -> {
                    require(ext in setOf("png","jpg","jpeg","gif","webp","bmp","avif")) { "지원하는 배경 그림 형식이 아니에요." }
                    val header = ByteArray(1024)
                    val used = checkNotNull(context.contentResolver.openInputStream(uri)) { "배경 그림을 읽지 못했어요." }.use { input ->
                        var used = 0
                        while (used < header.size) {
                            val n = input.read(header,used,header.size-used)
                            if(n <= 0) break
                            used += n
                        }
                        used
                    }
                    require(LibraryProtocol.picture(header.copyOf(used)) == (if(ext == "jpeg") "jpg" else ext)) {
                        "그림 내용과 파일 확장자가 맞지 않아요. 원본 그림을 다시 골라 주세요."
                    }
                    basic.copy(info = "배경 그림")
                }
                LibraryKind.WORLDS, LibraryKind.PRESETS, LibraryKind.THEMES, LibraryKind.QUICK_REPLIES -> {
                    require(ext == "json") { "실리태번 JSON 파일을 골라 주세요." }
                    val keys = readKeys(uri,max)
                    if (kind == LibraryKind.WORLDS) {
                        require(keys["entries"] == "BEGIN_OBJECT") { "실리태번 로어북이 아니에요. 다른 형식은 실리태번에서 가져와 주세요." }
                        basic.copy(info = "실리태번 로어북")
                    } else {
                        val detected = LibraryProtocol.jsonLibrary(keys)
                        require(detected == kind.wire) {
                            val other = LibraryKind.entries.firstOrNull { it.wire == detected }
                            if (other != null) "${other.label} 파일이에요. 자료 관리의 ‘${other.label}’에서 가져와 주세요."
                            else "${kind.label} 형식이 아니에요. 다른 형식은 실리태번에서 가져와 주세요."
                        }
                        basic.copy(info = kind.label)
                    }
                }
                else -> error("가져올 수 없는 목록이에요.")
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) { basic.copy(rejection = e.message ?: "파일 형식을 확인하지 못했어요.") }
    }
    private fun readKeys(uri: Uri, max: Long): Map<String,String> {
        val source = checkNotNull(context.contentResolver.openInputStream(uri)) { "선택한 파일을 읽지 못했어요." }
        return JsonReader(LimitedInput(source, max).bufferedReader(Charsets.UTF_8)).use { reader ->
            val result = mutableMapOf<String,String>()
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "JSON 객체가 아니에요." }
            reader.beginObject()
            while (reader.hasNext()) {
                require(result.size < 4096) { "JSON 항목이 너무 많아요." }
                val key = reader.nextName()
                require(key !in result) { "중복된 JSON 항목이 있어요." }
                result[key] = reader.peek().name
                reader.skipValue()
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT)
            result
        }
    }
    fun import(user: String, kind: LibraryKind, entries: List<LibraryFilePlan>): LibraryWrite {
        require(kind.maxMiB > 0 && entries.size in 1..20 && entries.all { it.rejection.isEmpty() })
        return LibraryWrite("library-import", "${kind.label} 가져오기", false,
            { r -> r.field("library_notice")?.let(::libraryDecode) ?: "가져오기를 마쳤어요." }) {
            val saved = mutableListOf<String>()
            val failures = mutableListOf<String>()
            for (item in entries) {
                operationCheckpoint()
                val digest = MessageDigest.getInstance("SHA-256")
                val transfer = staging.copyLimited(item.uri,false,kind.maxMiB * 1024L * 1024, digest)
                var dispatched = false
                try {
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    val args = listOf(libraryEncode(user),kind.wire,transfer.name,libraryEncode(item.name),hash)
                    val cmd = wire("library-import", *args.toTypedArray())
                    operationCheckpoint()
                    dispatched = true
                    val r = run(cmd, 5 * 60_000L)
                    staging.remove(transfer)
                    if (r.exitCode == 130) return@LibraryWrite r
                    if (!r.isSuccess) failures += "${item.name}: ${r.readableError().lineSequence().lastOrNull().orEmpty().take(120)}"
                    else {
                        r.verified()
                        saved += libraryDecode(checkNotNull(r.field("imported_b64")) { "저장한 이름을 확인하지 못했어요." })
                    }
                } finally {
                    // Unknown completion/timeout may leave Termux reading. Idle cleanup owns the pending entry.
                    if (!dispatched) staging.remove(transfer)
                }
            }
            val text = buildString {
                append("${entries.size}개 중 ${saved.size}개를 가져왔어요.")
                if (saved.size == 1) append(" · ${saved.first()}")
                if (failures.isNotEmpty()) append("\n못 넣은 파일: " + failures.joinToString(" / ").take(650))
            }
            check(saved.isNotEmpty()) { text }
            TermuxCommandResult("", "library_notice=" + libraryEncode(text), "", 0, Activity.RESULT_OK, "")
        }
    }
    private class LimitedInput(stream: InputStream, private val max: Long) : FilterInputStream(stream) {
        private var total = 0L
        private fun checkSize(n: Int) { if (n > 0) { total += n; require(total <= max) { "파일이 허용 용량보다 커요." } } }
        override fun read(): Int = super.read().also { if (it >= 0) checkSize(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b,off,len).also(::checkSize)
    }
}
