package app.tavernbridge.launcher.data

import app.tavernbridge.launcher.model.StorageUsageCategory
import org.junit.Assert.*
import org.junit.Test

class StorageUsageParserTest {
    private fun output(complete: Boolean = true): String = "storage_usage=1\nelapsed_ms=12\nscanned_entries=8\ntotal_bytes=8\ncomplete=${if (complete) 1 else 0}\n" +
        StorageUsageCategory.entries.joinToString("") { "storage\t${it.key}\t1\t1\t${if (complete) 1 else 0}\t0\n" }

    @Test fun parsesCompleteAndPartialManualScans() {
        assertEquals(8L, StorageUsageParser.parse(output()).totalBytes)
        assertTrue(StorageUsageParser.parse(output()).complete)
        assertFalse(StorageUsageParser.parse(output(false)).complete)
    }

    @Test fun rejectsDuplicatesOverflowsMismatchedTotalsOrMissingCategories() {
        for (bad in listOf(output() + "storage\tdata\t1\t1\t1\t0\n", output().replace("total_bytes=8", "total_bytes=9"), output().replace("storage\tgit\t1\t1\t1\t0\n", ""), output().replace("\t1\t1\t1\t0", "\t9223372036854775807\t1\t1\t0"), output().replace("complete=1", "complete=0"))) {
            assertTrue(runCatching { StorageUsageParser.parse(bad) }.isFailure)
        }
    }
}
