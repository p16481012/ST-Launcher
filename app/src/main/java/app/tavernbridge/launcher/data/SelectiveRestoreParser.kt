package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

object SelectiveRestoreParser {
    private val revision = Regex("[a-f0-9]{64}")
    private val headers = setOf("selection_format", "archive_revision", "installation_revision", "next_cursor", "candidate_count")

    fun parse(output: String): SelectiveRestorePage {
        require(output.toByteArray(Charsets.UTF_8).size <= 40 * 1024)
        val values = mutableMapOf<String, String>()
        val items = mutableListOf<SelectiveRestoreItem>()
        for (line in output.lineSequence().filter(String::isNotBlank)) {
            if (line.startsWith("restore_item\t")) items += item(line)
            else {
                val pair = line.split('=', limit = 2)
                require(pair.size == 2 && pair[0] in headers && values.put(pair[0], pair[1]) == null)
            }
        }
        require(values.keys == headers && values["selection_format"] == "1")
        val archive = values.getValue("archive_revision").also { require(revision.matches(it)) }
        val installation = values.getValue("installation_revision").also { require(revision.matches(it)) }
        val next = values.getValue("next_cursor").toInt().also { require(it >= -1) }
        val count = values.getValue("candidate_count").toInt().also { require(it in 0..100000) }
        require(items.size <= count && items.map { it.relativePath }.distinct().size == items.size)
        require(next == -1 || next in 1 until count)
        return SelectiveRestorePage(archive, installation, items, next.takeIf { it >= 0 }, count)
    }

    /** The backend repeats every check, including live target revisions. */
    fun encodeChoices(choices: List<SelectiveRestoreChoice>): String {
        require(choices.size in 1..100 && choices.map { it.item.relativePath }.distinct().size == choices.size)
        val json = choices.joinToString(prefix = "[", postfix = "]") { choice ->
            val item = choice.item
            require(validPath(item.relativePath, item.kind) && item.profileExists)
            require(item.targetRevision == "none" || revision.matches(item.targetRevision))
            "{\"path\":${quote(item.relativePath)},\"policy\":\"${choice.conflict.key}\",\"targetRevision\":\"${item.targetRevision}\"}"
        }
        return Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8)).also { require(it.length <= 65536) }
    }

    private fun item(line: String): SelectiveRestoreItem {
        require(line.length <= 12000)
        val fields = line.split('\t'); require(fields.size == 10)
        val kind = SelectiveRestoreKind.entries.single { it.key == fields[1] }
        val path = decode(fields[2]); require(validPath(path, kind))
        val size = fields[3].toLong().also { require(it >= 0) }
        val exists = flag(fields[4]); val target = fields[5]
        require(if (exists) revision.matches(target) else target == "none")
        val missing = flag(fields[6]); require(kind == SelectiveRestoreKind.CHAT || !missing)
        val profileExists = flag(fields[7]); val profile = decode(fields[8]); val label = decode(fields[9])
        val parts = path.split('/')
        require(profile == parts[1] && label == if (kind == SelectiveRestoreKind.CHARACTER) parts[3] else "${parts[3]}/${parts[4]}")
        return SelectiveRestoreItem(kind, path, size, exists, target, missing, profileExists, profile, label)
    }

    private fun validPath(path: String, kind: SelectiveRestoreKind): Boolean {
        if (path.toByteArray(Charsets.UTF_8).size > 4096) return false
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || ".." in it || it.any { c -> c == '\\' || c.isISOControl() } } || parts.first() != "data") return false
        return when (kind) {
            SelectiveRestoreKind.CHARACTER -> parts.size == 4 && parts[2] == "characters" && parts[3].endsWith(".png", ignoreCase = true)
            SelectiveRestoreKind.CHAT -> parts.size == 5 && parts[2] == "chats" && parts[4].endsWith(".jsonl", ignoreCase = true)
        }
    }

    private fun flag(value: String): Boolean { require(value == "0" || value == "1"); return value == "1" }
    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    private fun decode(value: String): String {
        require(value.length <= 5464)
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size <= 4096 && Base64.getEncoder().encodeToString(bytes) == value)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
