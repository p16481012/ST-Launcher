package app.tavernbridge.launcher.data

import org.junit.Assert.*
import org.junit.Test

class PcZipProtocolTest {
    @Test fun retainsOnlyUnsentZipFromAnOpenUncancelledSession() {
        for (sent in listOf(false,true)) for (same in listOf(false,true)) for (active in listOf(false,true)) for (open in listOf(false,true)) {
            val expected = if (sent) PcZipStagingOwner.TERMUX else if (same && active && open) PcZipStagingOwner.PREVIEW else PcZipStagingOwner.DISCARD
            assertEquals(expected, pcZipStagingOwner(sent, same, active, open))
        }
    }
    private val output = listOf("layout_kind=user_data","mergeable=1","archive_revision="+"a".repeat(64),
        "installation_revision="+"b".repeat(64),"add_count=1","keep_count=2","add_bytes=100","required_bytes=268435556",
        "relationship_skipped=1","excluded_count=3","profile\td29yaw==\td29yaw==","target\td29yaw==","kind\tworlds\t1\t2").joinToString("\n")
    @Test fun rechecksMustKeepTheSelectedArchiveButMayRefreshThePhoneInventory() {
        val initial = parsePcZipPreview(output).copy(name = "my-pc.zip")
        val fresh = initial.copy(installation = "c".repeat(64), name = "")
        assertEquals(fresh.copy(name = initial.name, checking = true), initial.refreshFrom(fresh))
        assertNull(initial.refreshFrom(fresh.copy(archive = "d".repeat(64))))
    }
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
    @Test fun handlesDetailedExclusionsAndDoesNotTreatUnknownImpactAsZero() {
        val detail = output + "\nrelation\tgroups\t1\nexcluded\tsettings\t1\nexcluded\tother\t2"
        assertEquals(mapOf("groups" to 1),parsePcZipPreview(detail).relations)
        assertEquals(2,parsePcZipPreview(detail).exclusions["other"])
        assertNull(parsePcZipPreview(detail).impact)
        assertEquals(false,parsePcZipPreview(detail+"\nreplace_impact\tunknown").impact?.known)
        assertEquals(PcZipImpact(true,8,12,1),parsePcZipPreview(detail+"\nreplace_impact\tknown\t8\t12\t1").impact)
        assertTrue(runCatching { parsePcZipPreview(detail+"\nrelation\tcharacters\t1") }.isFailure)
        assertTrue(runCatching { parsePcZipPreview(detail+"\nreplace_impact\tknown\t-1\t0\t0") }.isFailure)
        val withoutAvatars = parsePcZipPreview(output+"\navatars_included=0\ncandidate_avatars=3\nexcluded\tavatars\t3")
        assertFalse(withoutAvatars.avatarsIncluded)
        assertEquals(3,withoutAvatars.candidateAvatars)
    }
}
