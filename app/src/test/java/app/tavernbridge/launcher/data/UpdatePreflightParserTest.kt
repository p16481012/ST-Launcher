package app.tavernbridge.launcher.data

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePreflightParserTest {
    @Test
    fun parsesSafetyChecksAndRelativeModifiedPaths() {
        val dirty = "config.yaml\npublic/scripts/example.js"
        val output = """
            branch=release
            current_commit=1111111111111111111111111111111111111111
            remote_commit=2222222222222222222222222222222222222222
            current_version=1.0.0
            target_version=1.1.0
            update_available=1
            commits_behind=2
            node_version=v24.0.0
            node_required=>= 20
            node_compatible=1
            free_bytes=2147483648
            required_bytes=1073741824
            space_ready=1
            dirty_count=2
            dirty_files_b64=${Base64.getEncoder().encodeToString(dirty.toByteArray())}
            backup_storage_ready=0
        """.trimIndent()

        val parsed = UpdatePreflightParser.parse(output)

        assertEquals("release", parsed.branch)
        assertEquals("1.1.0", parsed.targetVersion)
        assertTrue(parsed.updateAvailable)
        assertEquals(2, parsed.commitsBehind)
        assertTrue(parsed.nodeCompatible)
        assertTrue(parsed.spaceReady)
        assertEquals(listOf("config.yaml", "public/scripts/example.js"), parsed.modifiedFiles)
        assertFalse(parsed.backupStorageReady)
    }

    @Test
    fun keepsSameVersionCommitUpdatesVisible() {
        val parsed = UpdatePreflightParser.parse(
            """
                branch=release
                current_commit=1111111
                remote_commit=2222222
                current_version=1.13.4
                target_version=1.13.4
                update_available=1
                commits_behind=3
                node_compatible=1
                space_ready=1
                backup_storage_ready=1
            """.trimIndent(),
        )

        assertTrue(parsed.updateAvailable)
        assertEquals(3, parsed.commitsBehind)
        assertEquals(parsed.currentVersion, parsed.targetVersion)
    }

    @Test
    fun parsesPersistentUpdateRecord() {
        val parsed = UpdateRecordParser.parse(
            """
                created_at=2026-07-16 12:00:00
                branch=release
                old_commit=1111111111111111111111111111111111111111
                target_commit=2222222222222222222222222222222222222222
                old_version=1.0.0
                result=success
                new_commit=2222222222222222222222222222222222222222
                new_version=1.1.0
            """.trimIndent(),
        )

        requireNotNull(parsed)
        assertEquals("release", parsed.branch)
        assertEquals("success", parsed.result)
        assertEquals("1.1.0", parsed.newVersion)
    }
}
