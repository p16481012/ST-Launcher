package app.tavernbridge.launcher.data

import java.util.Base64

internal data class PcZipCategory(val key: String, val added: Int, val kept: Int)
internal data class PcZipPreview(
    val layout: String, val mergeable: Boolean, val archive: String, val installation: String,
    val profiles: Map<String, String>, val targets: List<String>, val categories: List<PcZipCategory>,
    val added: Int, val kept: Int, val bytes: Long, val excluded: Int,
    val requiredBytes: Long, val relationshipsSkipped: Int, val availableBytes: Long?, val name: String = "",
) {
    val canAdd get() = mergeable && added > 0 && profiles.values.any(String::isNotBlank)
}

internal fun parsePcZipPreview(output: String): PcZipPreview {
    val fields = mutableMapOf<String, String>()
    val profiles = linkedMapOf<String, String>()
    val targets = mutableListOf<String>()
    val counts = mutableListOf<PcZipCategory>()
    fun decode(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    fun count(s: String) = s.toInt().also { require(it >= 0) }
    for (line in output.lineSequence()) {
        val cols = line.trimEnd('\r').split('\t')
        when (cols.first()) {
            "profile" -> { require(cols.size == 3); require(profiles.put(decode(cols[1]), decode(cols[2])) == null) }
            "target" -> { require(cols.size == 2); targets.add(decode(cols[1])) }
            "kind" -> { require(cols.size == 4); counts += PcZipCategory(cols[1], count(cols[2]), count(cols[3])) }
            else -> if ('=' in line) fields[line.substringBefore('=')] = line.substringAfter('=').trim()
        }
    }
    val allowed = setOf("user_data", "launcher_backup", "full_candidate", "config_extensions", "legacy_public", "unknown")
    val layout = fields.getValue("layout_kind").also { require(it in allowed) }
    val hash = Regex("[a-f0-9]{64}")
    return PcZipPreview(layout, fields.getValue("mergeable").also { require(it in setOf("0","1")) } == "1",
        fields.getValue("archive_revision").also { require(hash.matches(it)) },
        fields.getValue("installation_revision").also { require(hash.matches(it)) },
        profiles, targets.distinct(), counts, count(fields.getValue("add_count")), count(fields.getValue("keep_count")),
        fields.getValue("add_bytes").toLong().also { require(it >= 0) }, count(fields.getValue("excluded_count")),
        fields.getValue("required_bytes").toLong().also { require(it >= 0) },
        count(fields.getValue("relationship_skipped")), fields["available_bytes"]?.toLongOrNull()?.takeIf { it >= 0L })
}
