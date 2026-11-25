package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.SafetyItemKind
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SafetyInventoryParserTest {
    private fun b64(value: String) = Base64.getEncoder().encodeToString(value.toByteArray())
    private val header = "safety_inventory=1\nnext_cursor=-1\ninventory_truncated=0\nmutation_blocked=0\nprotected_entries=2\n"
    private val revision = "a".repeat(64)
    private val archive = "before-forced-update-20261123-010203.tar.gz"
    private fun row(path: String = "backups/$archive") = "safety\tarchive\t${b64(path)}\t$revision\t${b64(archive)}\t1234\t123456\t\t\t0\t0\n"

    @Test fun parsesSafetyArchiveSeparatelyFromNativeBackup() {
        val page = SafetyInventoryParser.parse(header + row())
        assertEquals(SafetyItemKind.ARCHIVE, page.items.single().kind)
        assertEquals(1234L, page.items.single().sizeBytes)
        assertTrue(page.items.single().canExport)
        assertFalse(page.items.single().completionRecorded)
        assertFalse(page.items.single().isDirectory)
        assertNull(page.nextCursor)
        assertEquals(2, page.protectedEntries)
    }

    @Test fun trashKeepsRestoreIdAndUnknownSize() {
        val id = "1234-12345678-abcd-abcd-abcd-123456789abc"
        val item = SafetyInventoryParser.parse(header + "safety\ttrash\t${b64("file-trash/$id")}\t$revision\t${b64("data/한글")}\t-1\t1234\t$id\t${b64("data/한글")}\t1\t1\n").items.single()
        assertEquals(id, item.trashId)
        assertTrue(item.restorable)
        assertFalse(item.canExport)
        assertNull(item.sizeBytes)
    }

    @Test fun appendOnlyDirectoryFlagAndCategoryTotalsHaveStrictBoundaries() {
        val id = "1234-12345678-abcd-abcd-abcd-123456789abc"
        val folder = "safety\ttrash\t${b64("file-trash/$id")}\t$revision\t${b64("data/folder")}\t84\t1234\t$id\t${b64("data/folder")}\t1\t1\t1\n"
        val page = SafetyInventoryParser.parse(header + "inventory_counts=0,1,5\n" + folder)
        assertEquals(5, page.totals?.get(SafetyItemKind.TRASH))
        assertTrue(page.items.single().isDirectory)
        for (bad in listOf("inventory_counts=1,1,0\n" + folder, "inventory_counts=invalid,0,1\n" + folder,
            row().trimEnd() + "\t1\n", folder.trimEnd().dropLast(1) + "x\n")) {
            assertTrue(runCatching { SafetyInventoryParser.parse(header + bad) }.isFailure)
        }
    }

    @Test fun rejectsRecoveryOrTraversalPaths() {
        for (path in listOf("backups/update-work-123/$archive", "backups/../$archive", "backups/safety-snapshot-1.abcdefgh/../$archive", "/tmp/$archive")) {
            assertTrue(runCatching { SafetyInventoryParser.parse(header + row(path)) }.isFailure)
        }
    }

    @Test fun rejectsMalformedPartialDuplicateOrOversizedPages() {
        for (output in listOf(header + row() + row(), header.replace("mutation_blocked=0\n", ""), header + "safety\tarchive\n", header + "x".repeat(41000), header + "next_cursor=0\n", header + row().replace("\t1234\t", "\t-2\t"))) {
            assertTrue(runCatching { SafetyInventoryParser.parse(output) }.isFailure)
        }
    }

    @Test fun exposesProtectedRecoveryAndNextPageWithoutSelectingIt() {
        val page = SafetyInventoryParser.parse(header.replace("next_cursor=-1", "next_cursor=10").replace("mutation_blocked=0", "mutation_blocked=1"))
        assertTrue(page.mutationBlocked)
        assertEquals(10, page.nextCursor)
        assertTrue(page.items.isEmpty())
    }
}
