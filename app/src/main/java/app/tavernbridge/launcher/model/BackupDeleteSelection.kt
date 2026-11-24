package app.tavernbridge.launcher.model

/** A filename alone is not a deletion target: Downloads can change while a dialog is open. */
internal object BackupDeleteSelection {
    private val revision = Regex("[a-f0-9]{64}")
    private fun target(value: String): Pair<String, String>? {
        if (value.length !in 66..320) return null
        val name = value.substringBeforeLast('#')
        val identity = value.substringAfterLast('#', "")
        return if (BackupRetentionPolicy.validFileName(name) && revision.matches(identity)) name to identity else null
    }

    fun key(archive: BackupArchive) = "${archive.fileName}#${archive.revision}"
    fun identifiable(archive: BackupArchive) = BackupRetentionPolicy.validFileName(archive.fileName) && revision.matches(archive.revision)

    fun valid(keys: List<String>): Boolean {
        if (keys.size !in 1..200) return false
        val names = HashSet<String>()
        return keys.all { value -> target(value)?.let { names.add(it.first) } == true }
    }

    /** Resolve only the versions the user actually saw. The backend rechecks disk identity. */
    fun resolve(keys: List<String>, catalog: List<BackupArchive>): List<BackupArchive>? {
        if (!valid(keys)) return null
        val candidates = keys.toSet()
        val matched = catalog.filter { key(it) in candidates }
        return matched.takeIf { it.size == candidates.size && it.map(::key).toSet() == candidates }
    }
}
