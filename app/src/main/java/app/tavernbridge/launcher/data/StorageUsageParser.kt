package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.StorageUsageCategory
import app.tavernbridge.launcher.model.StorageUsageEntry
import app.tavernbridge.launcher.model.StorageUsageReport

object StorageUsageParser {
    fun parse(output: String): StorageUsageReport {
        require(output.length <= 8192)
        val header = linkedMapOf<String, String>()
        val entries = mutableListOf<StorageUsageEntry>()
        output.lineSequence().filter(String::isNotEmpty).forEach { line ->
            if (line.startsWith("storage\t")) {
                val fields = line.split('\t')
                require(fields.size == 6)
                entries += StorageUsageEntry(requireNotNull(StorageUsageCategory.fromKey(fields[1])), positive(fields[2]), positive(fields[3]), boolean(fields[4]), positive(fields[5]))
            } else {
                val parts = line.split('=', limit = 2)
                require(parts.size == 2 && parts[0] in setOf("storage_usage", "elapsed_ms", "scanned_entries", "total_bytes", "complete"))
                require(header.put(parts[0], parts[1]) == null)
            }
        }
        require(header["storage_usage"] == "1" && header.size == 5)
        require(entries.size == StorageUsageCategory.entries.size && entries.map { it.category }.distinct().size == entries.size)
        val total = entries.fold(0L) { sum, row -> Math.addExact(sum, row.sizeBytes) }
        require(total == positive(header.getValue("total_bytes")))
        val complete = boolean(header.getValue("complete"))
        require(complete == entries.all { it.complete })
        return StorageUsageReport(entries, positive(header.getValue("elapsed_ms")), positive(header.getValue("scanned_entries")), total, complete)
    }

    private fun positive(value: String): Long = requireNotNull(value.toLongOrNull()).also { require(it >= 0) }
    private fun boolean(value: String): Boolean { require(value == "0" || value == "1"); return value == "1" }
}
