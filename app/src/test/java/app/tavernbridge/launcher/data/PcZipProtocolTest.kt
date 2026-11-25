package app.tavernbridge.launcher.data

import org.junit.Assert.*
import org.junit.Test

class PcZipProtocolTest {
    private val output = listOf("layout_kind=user_data","mergeable=1","archive_revision="+"a".repeat(64),
        "installation_revision="+"b".repeat(64),"add_count=1","keep_count=2","add_bytes=100","required_bytes=268435556",
        "relationship_skipped=1","excluded_count=3","profile\td29yaw==\td29yaw==","target\td29yaw==","kind\tworlds\t1\t2").joinToString("\n")
    @Test fun mappedUsersAndSafeCounts() {
        val p = parsePcZipPreview(output)
        assertEquals(mapOf("work" to "work"),p.profiles)
        assertEquals("worlds",p.categories.single().key)
        assertEquals(1,p.relationshipsSkipped)
        assertTrue(p.canAdd)
        assertFalse(parsePcZipPreview(output.replace("profile\td29yaw==\td29yaw==","profile\td29yaw==\t")).canAdd)
    }
    @Test fun rejectsBrokenReceiptInsteadOfPresentingEmptyZip() {
        for (bad in listOf(output.replace("add_count=1", "add_count=-1"), output.substringBefore("installation_revision="),
            output.replace("layout_kind=user_data","layout_kind=unexpected"))) {
            assertTrue(runCatching { parsePcZipPreview(bad) }.isFailure)
        }
    }
}
