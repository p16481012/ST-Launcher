package app.tavernbridge.launcher.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectoryAppOptionTest {
    private fun option(
        packageName: String,
        hasDocumentAccess: Boolean = false,
        activityName: String = "$packageName.FolderActivity",
        isRecommended: Boolean = false,
    ) = DirectoryAppOption(
        packageName = packageName,
        activityName = activityName,
        label = packageName,
        hasDocumentAccess = hasDocumentAccess,
        isRecommended = isRecommended,
    )

    @Test fun emptyCandidatesProduceEmptyOptions() {
        assertTrue(rankDirectoryApps(emptyList()).isEmpty())
    }

    @Test fun candidatesWithoutDocumentAccessRemainSelectableInOriginalOrder() {
        val candidates = listOf(option("third.party.z"), option("third.party.a"))

        val result = rankDirectoryApps(candidates)

        assertEquals(candidates, result)
        assertTrue(result.none { it.isRecommended })
    }

    @Test fun documentAccessCandidatesComeFirstAndOnlyFirstOneIsRecommended() {
        val firstOther = option("third.party.z")
        val firstSystem = option("system.z", hasDocumentAccess = true)
        val secondOther = option("third.party.a")
        val secondSystem = option("system.a", hasDocumentAccess = true)

        val result = rankDirectoryApps(listOf(firstOther, firstSystem, secondOther, secondSystem))

        assertEquals(
            listOf(firstSystem.id, secondSystem.id, firstOther.id, secondOther.id),
            result.map { it.id },
        )
        assertTrue(result.first().isRecommended)
        assertTrue(result.drop(1).none { it.isRecommended })
        assertEquals(1, result.count { it.isRecommended })
    }

    @Test fun emptyPackageOrActivityNamesAreExcludedBeforeRecommendation() {
        val valid = option("valid.system", hasDocumentAccess = true)

        val result = rankDirectoryApps(listOf(
            option("", hasDocumentAccess = true, activityName = "MissingPackage"),
            option("invalid.activity", hasDocumentAccess = true, activityName = ""),
            valid,
        ))

        assertEquals(listOf(valid.copy(isRecommended = true)), result)
    }

    @Test fun repeatedComponentsAppearOnlyOnce() {
        val system = option("system.files", hasDocumentAccess = true)
        val other = option("third.party")

        val result = rankDirectoryApps(listOf(other, system, other, system))

        assertEquals(listOf(system.copy(isRecommended = true), other), result)
    }

    @Test fun differentActivitiesWithinSamePackageKeepDistinctIdsAndOptions() {
        val first = option("same.package", activityName = "same.package.FirstActivity")
        val second = option("same.package", activityName = "same.package.SecondActivity")

        assertEquals("same.package/same.package.FirstActivity", first.id)
        assertEquals("same.package/same.package.SecondActivity", second.id)
        assertEquals(listOf(first, second), rankDirectoryApps(listOf(first, second)))
    }

    @Test fun suppliedRecommendationFlagsCannotOverrideAccessBasedRecommendation() {
        val other = option("third.party", isRecommended = true)
        val firstSystem = option("system.first", hasDocumentAccess = true)
        val secondSystem = option("system.second", hasDocumentAccess = true, isRecommended = true)

        val result = rankDirectoryApps(listOf(other, firstSystem, secondSystem))

        assertEquals(listOf(
            firstSystem.copy(isRecommended = true),
            secondSystem.copy(isRecommended = false),
            other.copy(isRecommended = false),
        ), result)
        assertFalse(firstSystem.isRecommended)
        assertTrue(other.isRecommended)
        assertTrue(secondSystem.isRecommended)
    }

    @Test fun suppliedRecommendationIsClearedWhenNoCandidateHasDocumentAccess() {
        val other = option("third.party", isRecommended = true)

        assertEquals(listOf(other.copy(isRecommended = false)), rankDirectoryApps(listOf(other)))
    }
}
