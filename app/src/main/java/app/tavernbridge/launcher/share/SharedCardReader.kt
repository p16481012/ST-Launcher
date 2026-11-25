package app.tavernbridge.launcher.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.JsonReader
import android.util.JsonToken
import java.io.DataInputStream
import java.io.File
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.CRC32
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val MAX_CARD_BYTES = 20 * 1024 * 1024
internal const val MAX_SHARED_CARDS = 20
internal data class SharedCard(val file: File, val label: String, val name: String, val creator: String,
    val hash: String, val preview: Bitmap?)
internal data class IncomingCard(val label: String, val card: SharedCard? = null, val error: String = "")

private fun bad(): Nothing = throw IllegalArgumentException("캐릭터 정보가 없는 그림이에요")
private val signature = byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)

internal fun readPngCard(file: File): Pair<String,String> {
    require(file.length() <= MAX_CARD_BYTES) { "파일이 너무 커요. 20 MB 이하만 넣을 수 있어요." }
    DataInputStream(file.inputStream().buffered()).use { input ->
        require(file.length() >= 8 && ByteArray(8).also { input.readFully(it) }.contentEquals(signature)) { "캐릭터 카드는 PNG만 넣을 수 있어요." }
        var left = file.length() - 8
        var chunks = 0
        var data = false
        var end = false
        val tags = mutableMapOf<String,ByteArray>()
        val buffer = ByteArray(64 * 1024)
        while (left >= 12 && ++chunks <= 65536) {
            val n = input.readInt()
            if (n < 0 || n + 12L > left) bad()
            val type = ByteArray(4).also { input.readFully(it) }
            val kind = String(type, Charsets.US_ASCII)
            if (chunks == 1 && (kind != "IHDR" || n != 13) || chunks != 1 && kind == "IHDR") bad()
            val crc = CRC32().apply { update(type) }
            // Inspect only the 5-byte card keyword; stream unrelated images and text without retaining them.
            val prefix = if (kind == "tEXt") ByteArray(minOf(n,6)).also { input.readFully(it); crc.update(it) } else ByteArray(0)
            val zero = prefix.indexOf(0.toByte())
            val key = if (zero > 0) String(prefix,0,zero,Charsets.US_ASCII) else ""
            var remaining = n-prefix.size
            if (key in setOf("ccv3","chara")) {
                if (key in tags) bad()
                val buffered = prefix.size - zero - 1
                tags[key] = ByteArray(buffered + remaining).also {
                    prefix.copyInto(it, 0, zero+1)
                    input.readFully(it,buffered,remaining); crc.update(it,buffered,remaining)
                }
            } else while (remaining > 0) {
                val count = minOf(buffer.size,remaining)
                input.readFully(buffer,0,count); crc.update(buffer,0,count); remaining -= count
            }
            if (crc.value.toInt() != input.readInt()) bad()
            left -= n+12L
            if (kind == "IDAT") data = true
            if (kind == "IEND") { end = n == 0 && left == 0L; break }
        }
        if (!end || !data) bad()
        val encoded = tags["ccv3"] ?: tags["chara"] ?: bad()
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { bad() }
        // Strict padding without allocating a second encoded copy of a multi-MB card.
        if (encoded.size % 4 != 0 || (bytes.isNotEmpty() &&
            !Base64.getEncoder().encode(bytes.takeLast(((bytes.size-1)%3)+1).toByteArray())
                .contentEquals(encoded.copyOfRange(encoded.size-4, encoded.size)))) bad()
        var name = ""; var nested = ""; var creator = ""
        try {
            JsonReader(InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8.newDecoder())).use { r ->
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "name" -> if (r.peek() == JsonToken.STRING) name=r.nextString() else r.skipValue()
                    "data" -> if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "name" -> if (r.peek()==JsonToken.STRING) nested=r.nextString() else r.skipValue()
                            "creator" -> if (r.peek()==JsonToken.STRING) creator=r.nextString() else r.skipValue()
                            else -> r.skipValue()
                        }
                        r.endObject()
                    } else r.skipValue()
                    else -> r.skipValue()
                }
                r.endObject()
                if (r.peek()!=JsonToken.END_DOCUMENT) bad()
            }
        } catch (_: Exception) { bad() }
        val chosen = cardText(nested.ifBlank { name })
        if (chosen.isEmpty()) bad()
        return chosen to cardText(creator)
    }
}

private fun cardText(s: String) = s.replace(Regex("[\\p{Cc}\\p{Cf}]")," ").trim().take(200)

internal suspend fun receiveCard(context: Context, source: Uri, directory: File): IncomingCard {
    var label = "공유 파일"
    var cached: File? = null
    try {
        require(source.scheme=="content" && !source.authority.isNullOrBlank() && source.authority?.contains('@')==false) { "공유한 파일을 읽을 수 없어요." }
        val provider = context.packageManager.resolveContentProvider(source.authority!!,0)
        require(source.authority!=context.packageName+".private-backup" && provider?.applicationInfo?.uid != android.os.Process.myUid()) { "런처 내부 파일은 공유로 가져올 수 없어요." }
        // Never dereference a content URI for which the sender has not delegated read access.
        require(context.checkUriPermission(source,android.os.Process.myPid(),android.os.Process.myUid(),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "파일 읽기 권한이 없어요. 원래 앱에서 다시 공유해 주세요."
        }
        label = runCatching { context.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
            if(it.moveToFirst()) cardText(it.getString(0).orEmpty()) else ""
        } }.getOrNull().orEmpty()
        val hasSourceName=label.isNotBlank()
        if (!hasSourceName) label="공유 파일"
        val file = File(directory,UUID.randomUUID().toString()+".png")
        check(file.createNewFile()); cached=file
        val digest=MessageDigest.getInstance("SHA-256"); val scope=currentCoroutineContext()
        context.contentResolver.openInputStream(source)?.use { input -> file.outputStream().buffered().use { out ->
            val block=ByteArray(64*1024);var total=0
            while(true){
                scope.ensureActive()
                val n=input.read(block); if(n<0)break
                total+=n; require(total<=MAX_CARD_BYTES) { "파일이 너무 커요. 20 MB 이하만 넣을 수 있어요." }
                out.write(block,0,n);digest.update(block,0,n)
            }
        } } ?: error("공유 파일을 열지 못했어요.")
        if ((label.endsWith(".json",true) || label.endsWith(".charx",true)) &&
            !file.inputStream().use { it.read() == 0x89 }) {
            throw IllegalArgumentException("이 형식은 실리태번 화면의 ‘캐릭터 가져오기’로 넣어 주세요.")
        }
        val (name,creator) = readPngCard(file)
        val bounds=BitmapFactory.Options().also { it.inJustDecodeBounds=true; BitmapFactory.decodeFile(file.path,it) }
        if(bounds.outWidth<=0 || bounds.outHeight<=0)bad()
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight).toLong()/sample>288)sample*=2
        require(bounds.outWidth.toLong()*bounds.outHeight<=100_000_000) { "그림 크기가 너무 커서 안전하게 미리 볼 수 없어요." }
        val preview=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample }) ?: bad()
        cached=null
        return IncomingCard(label,SharedCard(file,if(hasSourceName) label else name,name,creator,
            digest.digest().joinToString("") { "%02x".format(it) },preview))
    } catch(e: kotlinx.coroutines.CancellationException){throw e}
    catch(e: Exception){return IncomingCard(label,error=if(e is IllegalArgumentException) e.message ?: "카드를 확인할 수 없어요." else "파일을 읽을 수 없어요. 원본을 다시 공유해 주세요.")}
    finally{cached?.delete()}
}
