package app.tavernbridge.launcher.data

import java.util.Base64

// Once Termux receives a ZIP, only its job/idle cleanup may release that staging file.
internal enum class PcZipStagingOwner { TERMUX, PREVIEW, DISCARD }
internal fun pcZipStagingOwner(dispatched: Boolean, sameSession: Boolean, active: Boolean, screenOpen: Boolean) = when {
    dispatched -> PcZipStagingOwner.TERMUX
    sameSession && active && screenOpen -> PcZipStagingOwner.PREVIEW
    else -> PcZipStagingOwner.DISCARD
}

internal data class PcZipCategory(val key: String, val added: Int, val kept: Int)
internal data class PcZipImpact(val known: Boolean, val cards: Int = 0, val chats: Int = 0, val users: Int = 0)
internal data class PcZipPreview(
    val layout: String, val mergeable: Boolean, val archive: String, val installation: String,
    val profiles: Map<String, String>, val targets: List<String>, val categories: List<PcZipCategory>,
    val added: Int, val kept: Int, val bytes: Long, val excluded: Int,
    val requiredBytes: Long, val relationshipsSkipped: Int, val availableBytes: Long?, val name: String = "",
    val relations: Map<String, Int> = emptyMap(), val exclusions: Map<String, Int> = emptyMap(),
    val impact: PcZipImpact? = null, val checking: Boolean = false, val retainedAfterPreflight: Boolean = false,
    val avatarsIncluded: Boolean = true, val candidateAvatars: Int = categories.firstOrNull { it.key == "avatars" }?.added ?: 0,
) {
    val canAdd get() = mergeable && added > 0 && profiles.values.any(String::isNotBlank)
    fun refreshFrom(result: PcZipPreview): PcZipPreview? =
        result.takeIf { it.archive == archive }?.copy(name = name, checking = true)
}

internal fun parsePcZipPreview(output: String): PcZipPreview {
    val fields = mutableMapOf<String, String>()
    val profiles = linkedMapOf<String, String>()
    val targets = mutableListOf<String>()
    val counts = mutableListOf<PcZipCategory>()
    val relations = linkedMapOf<String,Int>()
    val exclusions = linkedMapOf<String,Int>()
    var impact: PcZipImpact? = null
    fun decode(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
    fun count(s: String) = s.toInt().also { require(it >= 0) }
    for (line in output.lineSequence()) {
        val cols = line.trimEnd('\r').split('\t')
        when (cols.first()) {
            "profile" -> { require(cols.size == 3); require(profiles.put(decode(cols[1]), decode(cols[2])) == null) }
            "target" -> { require(cols.size == 2); targets.add(decode(cols[1])) }
            "kind" -> { require(cols.size == 4); counts += PcZipCategory(cols[1], count(cols[2]), count(cols[3])) }
            "relation", "excluded" -> {
                require(cols.size == 3)
                val dest = if (cols[0] == "relation") relations else exclusions
                require(dest.put(cols[1], count(cols[2])) == null)
            }
            "replace_impact" -> {
                require(impact == null)
                impact = when {
                    cols == listOf("replace_impact","unknown") -> PcZipImpact(false)
                    cols.size == 5 && cols[1] == "known" -> PcZipImpact(true,count(cols[2]),count(cols[3]),count(cols[4]))
                    else -> error("교체 영향 정보를 읽지 못했어요.")
                }
            }
            else -> if ('=' in line) fields[line.substringBefore('=')] = line.substringAfter('=').trim()
        }
    }
    val allowed = setOf("user_data", "launcher_backup", "full_candidate", "config_extensions", "legacy_public", "unknown")
    val layout = fields.getValue("layout_kind").also { require(it in allowed) }
    val hash = Regex("[a-f0-9]{64}")
    val skipped = count(fields.getValue("relationship_skipped"))
    val excluded = count(fields.getValue("excluded_count"))
    require(relations.isEmpty() || relations.values.sumOf { it.toLong() } == skipped.toLong())
    require(exclusions.isEmpty() || exclusions.values.sumOf { it.toLong() } == excluded.toLong())
    return PcZipPreview(layout, fields.getValue("mergeable").also { require(it in setOf("0","1")) } == "1",
        fields.getValue("archive_revision").also { require(hash.matches(it)) },
        fields.getValue("installation_revision").also { require(hash.matches(it)) },
        profiles, targets.distinct(), counts, count(fields.getValue("add_count")), count(fields.getValue("keep_count")),
        fields.getValue("add_bytes").toLong().also { require(it >= 0) }, count(fields.getValue("excluded_count")),
        fields.getValue("required_bytes").toLong().also { require(it >= 0) },
        skipped, fields["available_bytes"]?.toLongOrNull()?.takeIf { it >= 0L },
        relations = relations, exclusions = exclusions, impact = impact,
        avatarsIncluded = fields["avatars_included"]?.also { require(it in setOf("0","1")) } != "0",
        candidateAvatars = fields["candidate_avatars"]?.let(::count) ?: counts.firstOrNull { it.key == "avatars" }?.added ?: 0)
}
