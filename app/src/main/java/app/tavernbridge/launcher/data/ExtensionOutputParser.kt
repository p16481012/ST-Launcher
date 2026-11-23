package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.ExtensionInventory
import app.tavernbridge.launcher.model.ManagedExtension
import java.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object ExtensionOutputParser {
    fun parse(output: String): ExtensionInventory {
        require(output.length <= 48 * 1024) { "확장 목록 응답이 너무 큽니다." }
        val lines = output.lineSequence().toList()
        fun field(key: String): String = lines.filter { it.startsWith("$key=") }.singleOrNull()?.substringAfter('=')
            ?: error("확장 목록 응답이 올바르지 않습니다.")
        require(field("extension_inventory") == "1")
        val next = field("next_cursor").toInt().also { require(it in -1..2000) }
        val skipped = field("skipped_entries").toInt().also { require(it >= 0) }
        fun text(encoded: String): String {
            require(encoded.length <= 8192)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getDecoder().decode(encoded))).toString()
                .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
        }
        val items = lines.filter { it.startsWith("extension\t") }.map { line ->
            val parts = line.split('\t')
            require(parts.size == 11)
            val id = parts[1].also { require(it.length in 1..8192); Base64.getDecoder().decode(it) }
            val revision = parts[2].also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            require(parts[6] in setOf("enabled", "disabled") && parts[7] in setOf("clean", "dirty", "unsupported"))
            require(parts[9] in setOf("0", "1"))
            ManagedExtension(id, revision, text(parts[3]), text(parts[4]), text(parts[5]), parts[6] == "disabled",
                parts[7], parts[8].also { require(it.isEmpty() || it.matches(Regex("[a-f0-9]{7,64}"))) }, parts[9] == "1", text(parts[10]))
        }
        require(items.map { it.id }.distinct().size == items.size)
        val recovery = field("extension_recovery").also { require(it in setOf("none", "pending", "blocked")) }
        return ExtensionInventory(items, next.takeIf { it >= 0 }, skipped, recovery, text(field("extension_recovery_message_b64")))
    }
}
