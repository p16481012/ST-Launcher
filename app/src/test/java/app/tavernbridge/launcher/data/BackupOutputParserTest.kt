package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.BackupCategory
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupOutputParserTest {
    @Test
    fun parsesAndSortsLauncherBackupMetadata() {
        val older = line("old.zip", "2026-07-15 10:00:00", "config", "", false, "1.13.4", 100)
        val newer = line(
            "SillyTavern-Launcher-20260716-120000.zip",
            "2026-07-16 12:00:00",
            "user_data,custom",
            "characters,chats",
            true,
            "1.13.5",
            2048,
        )

        val parsed = BackupOutputParser.parse("$older\nignored output\n$newer")

        assertEquals(2, parsed.size)
        assertEquals("SillyTavern-Launcher-20260716-120000.zip", parsed.first().fileName)
        assertEquals(2048, parsed.first().sizeBytes)
        assertEquals(setOf(BackupCategory.USER_DATA, BackupCategory.CUSTOM), parsed.first().categories)
        assertEquals(setOf("characters", "chats"), parsed.first().customFolders)
        assertTrue(parsed.first().includesSecrets)
    }

    @Test
    fun parsesExtendedRestorePreviewMetadata() {
        val record = line("backup.zip", "2026-07-24 12:00:00", "full", "", false, "1.13.5", 1024) +
            listOf(
                encode("release"),
                encode("0.1.0-alpha35"),
                "4096",
                "12",
                encode("zip-crc32"),
                "268443648",
                "1000000000",
            ).joinToString(separator = "\t", prefix = "\t")

        val parsed = BackupOutputParser.parse(record).single()

        assertEquals("release", parsed.branch)
        assertEquals(4096, parsed.expandedBytes)
        assertEquals(12, parsed.entryCount)
        assertEquals("zip-crc32", parsed.integrity)
        assertTrue(parsed.restoreSpaceReady)
    }

    private fun line(
        name: String,
        created: String,
        items: String,
        custom: String,
        secrets: Boolean,
        version: String,
        size: Long,
    ) = listOf(
        "backup",
        encode(name),
        size.toString(),
        encode(created),
        encode(items),
        encode(custom),
        if (secrets) "1" else "0",
        encode(version),
    ).joinToString("\t")

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())
}
