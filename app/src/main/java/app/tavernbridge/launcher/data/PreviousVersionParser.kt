package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

object PreviousVersionParser {
    private val commit = Regex("[a-f0-9]{40}")
    private val revision = Regex("[a-f0-9]{64}")
    private val filename = Regex("SillyTavern-Launcher-[0-9]{8}-[0-9]{6}(-[0-9]+)?( \\([0-9]+\\))*\\.zip")

    fun versions(output: String): PreviousVersionPage {
        val (headers, rows) = split(output, "version", setOf("version_inventory", "current_commit", "installation_revision", "branch"))
        require(headers["version_inventory"] == "1" && commit.matches(headers.getValue("current_commit")))
        require(revision.matches(headers.getValue("installation_revision")) && headers["branch"] in setOf("release", "staging"))
        require(rows.size <= 30)
        val items = rows.map { fields ->
            require(fields.size == 4 && commit.matches(fields[1]) && fields[1] != headers["current_commit"])
            val seconds = fields[2].toLong().also { require(it >= 0) }
            PreviousVersion(fields[1], seconds, decode(fields[3], 1024).also { require(it.none(Char::isISOControl)) })
        }
        require(items.map { it.commit }.distinct().size == items.size)
        return PreviousVersionPage(headers.getValue("current_commit"), headers.getValue("installation_revision"), headers.getValue("branch"), items)
    }

    fun snapshots(output: String): VersionSnapshotPage {
        val (headers, rows) = split(output, "snapshot", setOf("version_snapshots", "snapshots_truncated"))
        require(headers["version_snapshots"] == "1" && headers["snapshots_truncated"] in setOf("0", "1") && rows.size <= 50)
        val items = rows.map { fields ->
            require(fields.size == 7)
            val name = decode(fields[1], 255).also { require(filename.matches(it)) }
            require(revision.matches(fields[2]) && commit.matches(fields[3]))
            val created = fields[4].toLong().also { require(it >= 0) }
            val bytes = fields[5].toLong().also { require(it >= 0) }
            VersionSnapshot(name, fields[2], fields[3], created, bytes, decode(fields[6], 512).also { require(it.none(Char::isISOControl)) })
        }
        require(items.map { it.filename }.distinct().size == items.size)
        return VersionSnapshotPage(items, headers.getValue("snapshots_truncated") == "1")
    }

    private fun split(output: String, rowKey: String, keys: Set<String>): Pair<Map<String, String>, List<List<String>>> {
        require(output.toByteArray(Charsets.UTF_8).size <= 40 * 1024)
        val headers = mutableMapOf<String, String>(); val rows = mutableListOf<List<String>>()
        for (line in output.lineSequence().filter(String::isNotEmpty)) {
            if (line.startsWith("$rowKey\t")) rows += line.split('\t')
            else { val pair = line.split('=', limit = 2); require(pair.size == 2 && pair[0] in keys && headers.put(pair[0], pair[1]) == null) }
        }
        require(headers.keys == keys)
        return headers to rows
    }

    private fun decode(value: String, maxBytes: Int): String {
        require(value.length <= (maxBytes + 2) / 3 * 4)
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size <= maxBytes && Base64.getEncoder().encodeToString(bytes) == value)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
}
