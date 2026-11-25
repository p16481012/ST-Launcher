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
        assertEquals(null, parsed.first().characterFiles)
        assertEquals(null, parsed.first().chatFiles)
        assertEquals(null, parsed.first().extensionCount)
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
        val withCounts = BackupOutputParser.parse("$record\t3\t0").single()
        assertEquals(3, withCounts.characterFiles)
        assertEquals(0, withCounts.chatFiles)
        val revision = "9".repeat(64)
        val withExtensions = BackupOutputParser.parse("$record\t3\t0\t$revision\t12").single()
        assertEquals(revision, withExtensions.revision)
        assertEquals(12, withExtensions.extensionCount)
        for (bad in listOf("-1", "500001", "99999999999999", "broken", "")) {
            val unknown = BackupOutputParser.parse("$record\t$bad\t$bad\t$revision\t$bad").single()
            assertEquals(null, unknown.characterFiles)
            assertEquals(null, unknown.chatFiles)
            assertEquals(null, unknown.extensionCount)
        }
    }

    @Test fun missingOrInvalidSafetyInventoryIsNotZero() {
        assertEquals(0, BackupOutputParser.safetyCopies("safety_copies=0"))
        assertEquals(3, BackupOutputParser.safetyCopies("zip\nsafety_copies=3\n"))
        for (bad in listOf("", "safety_copies=-1", "safety_copies=3\nsafety_copies=3", "safety_copies=40000000")) {
            assertEquals(null, BackupOutputParser.safetyCopies(bad))
        }
        val counts = "safety_copies=3\ntrash_items=0"
        assertEquals(3, BackupOutputParser.safetyCopies(counts))
        assertEquals(0, BackupOutputParser.trashItems(counts))
        for (bad in listOf("safety_copies=0", "trash_items=-1", "trash_items=1\ntrash_items=0"))
            assertEquals(null, BackupOutputParser.trashItems(bad))
    }

    @Test fun unknownRestoreSpaceIsNotZeroAndDoesNotHideTheArchive() {
        val base = line("a.zip", "now", "config", "", false, "", 512) + "\t\t\t256\t3\t\t100000"
        fun row(free: String) = BackupOutputParser.parse("$base\t$free\t2\t3").single()
        for (free in listOf("", "unknown", "-1", "1e+12")) {
            assertEquals(null, row(free).availableRestoreBytes)
            assertTrue(row(free).restoreSpaceReady)
            assertEquals(2, row(free).characterFiles)
        }
        assertEquals(0L, row("0").availableRestoreBytes)
        assertEquals(false, row("0").restoreSpaceReady)
        assertTrue(row("126419751936").restoreSpaceReady)
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
