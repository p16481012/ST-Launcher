package app.tavernbridge.launcher.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PreviousVersionParserTest {
    private val head = "a".repeat(40)
    private val old = "b".repeat(40)
    private val revision = "c".repeat(64)
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    private val name = "SillyTavern-Launcher-20261124-000000.zip"
    private fun versions() = "version_inventory=1\ncurrent_commit=$head\ninstallation_revision=$revision\nbranch=release\nversion\t$old\t123\t${b64("이전 버전")}\n"
    private fun snapshots() = "version_snapshots=1\nsnapshots_truncated=0\nsnapshot\t${b64(name)}\t$revision\t$old\t123000\t1024\t${b64("1.0.0")}\n"

    @Test fun validProtocols() {
        assertEquals(old, PreviousVersionParser.versions(versions()).versions.single().commit)
        val snapshot = PreviousVersionParser.snapshots(snapshots()).snapshots.single()
        assertEquals(name, snapshot.filename); assertEquals(1024L, snapshot.sizeBytes)
    }
    @Test fun refusesMalformedOrAmbiguousOutput() {
        listOf(versions().replace(old, head), versions().replace("branch=release", "branch=other"), versions() + "branch=release\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { PreviousVersionParser.versions(it) }
        }
        listOf(snapshots().replace(b64(name), b64("../backup.zip")), snapshots().replace("\t1024\t", "\t-1\t"), snapshots().replace(revision, "bad")).forEach {
            assertThrows(IllegalArgumentException::class.java) { PreviousVersionParser.snapshots(it) }
        }
    }
}
