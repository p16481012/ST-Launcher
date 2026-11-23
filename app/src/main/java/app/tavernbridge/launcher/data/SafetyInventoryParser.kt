package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.SafetyInventoryItem
import app.tavernbridge.launcher.model.SafetyInventoryPage
import app.tavernbridge.launcher.model.SafetyItemKind
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Fail closed: a partial/malformed page must never become a selectable list. */
object SafetyInventoryParser {
    private const val MAX_BYTES = 40 * 1024
    private val revisionPattern = Regex("[a-f0-9]{64}")
    private val trashPattern = Regex("[0-9]{1,16}-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val archivePattern = "before-(?:forced-update|branch)-[0-9]{8}-[0-9]{6}\\.tar\\.gz"
    private val snapshotPattern = "safety-snapshot-[0-9]+\\.[A-Za-z0-9]{8}"

    fun parse(output: String): SafetyInventoryPage {
        require(output.length <= MAX_BYTES && output.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "보관 목록이 너무 큽니다." }
        val lines = output.lineSequence().filter(String::isNotEmpty).toList()
        val headers = linkedMapOf<String, String>()
        val items = mutableListOf<SafetyInventoryItem>()
        for (line in lines) {
            if (line.startsWith("safety\t")) items += parseItem(line)
            else {
                val separator = line.indexOf('=')
                require(separator > 0) { "보관 목록 응답을 읽을 수 없습니다." }
                val key = line.substring(0, separator)
                require(key in setOf("safety_inventory", "next_cursor", "inventory_truncated", "mutation_blocked", "protected_entries"))
                require(headers.put(key, line.substring(separator + 1)) == null) { "보관 목록 정보가 중복되었습니다." }
            }
        }
        require(headers["safety_inventory"] == "1" && headers.size == 5)
        val next = requireNotNull(headers["next_cursor"]?.toIntOrNull())
        require(next >= -1)
        val protected = requireNotNull(headers["protected_entries"]?.toIntOrNull())
        require(protected >= 0 && items.map { it.id }.distinct().size == items.size)
        return SafetyInventoryPage(
            items = items,
            nextCursor = next.takeIf { it >= 0 },
            truncated = boolean(headers.getValue("inventory_truncated")),
            mutationBlocked = boolean(headers.getValue("mutation_blocked")),
            protectedEntries = protected,
        )
    }

    private fun parseItem(line: String): SafetyInventoryItem {
        require(line.length <= 12000)
        val fields = line.split('\t')
        require(fields.size == 11)
        val kind = requireNotNull(SafetyItemKind.fromKey(fields[1]))
        val path = decode(fields[2], 2048)
        require(validId(kind, path)) { "보관 항목 경로가 올바르지 않습니다." }
        require(revisionPattern.matches(fields[3]))
        val label = decode(fields[4], 4096)
        require(label.isNotEmpty() && label.none(Char::isISOControl))
        val size = requireNotNull(fields[5].toLongOrNull())
        val modified = requireNotNull(fields[6].toLongOrNull())
        require(size >= -1 && modified >= 0)
        val original = decode(fields[8], 4096)
        val restorable = boolean(fields[9])
        val completionRecorded = boolean(fields[10])
        if (kind == SafetyItemKind.TRASH) {
            require(trashPattern.matches(fields[7]) && path == "file-trash/${fields[7]}")
            require(original.isNotEmpty() && original.split('/').all(::validPart) && label == original)
        } else {
            require(fields[7].isEmpty() && original.isEmpty() && !restorable && size >= 0)
            require(label == path.substringAfterLast('/'))
        }
        return SafetyInventoryItem(kind, fields[2], fields[3], label, size.takeIf { it >= 0 }, modified, fields[7], original, restorable, completionRecorded)
    }

    private fun validId(kind: SafetyItemKind, path: String): Boolean = when (kind) {
        SafetyItemKind.ARCHIVE -> Regex("backups/(?:$snapshotPattern/)?$archivePattern").matches(path)
        SafetyItemKind.CONFIG -> Regex("backups/network-settings/config-[0-9]{8}-[0-9]{6}\\.yaml").matches(path)
        SafetyItemKind.TRASH -> path.startsWith("file-trash/") && trashPattern.matches(path.removePrefix("file-trash/"))
    }

    private fun validPart(value: String): Boolean = value.isNotEmpty() && value != "." && value != ".." && value.none { it == '\\' || it.isISOControl() }

    private fun boolean(value: String): Boolean {
        require(value == "0" || value == "1")
        return value == "1"
    }

    private fun decode(value: String, maxBytes: Int): String {
        require(value.length <= (maxBytes + 2) / 3 * 4)
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size <= maxBytes && Base64.getEncoder().encodeToString(bytes) == value)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
