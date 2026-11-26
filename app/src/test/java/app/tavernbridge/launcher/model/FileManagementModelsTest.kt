package app.tavernbridge.launcher.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileManagementModelsTest {
    @Test fun collisionSuggestsUnusedNameWithoutRewritingExtensionsOrFolders() {
        val names = setOf("memo.txt", "memo (1).txt", ".env", "folder.name")
        org.junit.Assert.assertEquals("memo (2).txt", availableTavernName("memo.txt", names, false))
        org.junit.Assert.assertEquals("folder.name (1)", availableTavernName("folder.name", names, true))
        org.junit.Assert.assertEquals(".env (1)", availableTavernName(".env", names, false))
        org.junit.Assert.assertNull(availableTavernName("free", names, false))
        val longest = "가".repeat(85)
        assertTrue(validTavernEntryName(availableTavernName(longest, setOf(longest), true)!!))
    }
    @Test fun entryNamesRejectTraversalAndControlCharacters() {
        listOf("", " ", ".", "..", "../data", "nested/file", "nested\\file", "a\nb", "a\u0000b")
            .forEach { assertFalse(it, validTavernEntryName(it)) }
        listOf("캐릭터", "my folder", "config.yaml", ".gitignore", "name ' quoted")
            .forEach { assertTrue(it, validTavernEntryName(it)) }
    }

    @Test fun fileChangesRequireIdleStoppedConnectedEnvironment() {
        val idle = LauncherUiState(isWorking = false, environment = EnvironmentStatus(managerConnected = true))
        assertTrue(idle.canModifyTavernFiles())
        assertFalse(idle.copy(isWorking = true).canModifyTavernFiles())
        assertFalse(idle.copy(fileBrowserLoading = true).canModifyTavernFiles())
        assertFalse(idle.copy(fileBrowserMutating = true).canModifyTavernFiles())
        assertFalse(idle.copy(environment = idle.environment.copy(processRunning = true)).canModifyTavernFiles())
        assertFalse(idle.copy(environment = idle.environment.copy(operationActive = true)).canModifyTavernFiles())
        assertFalse(idle.copy(environment = idle.environment.copy(recoveryPending = true)).canModifyTavernFiles())
        assertFalse(idle.copy(environment = idle.environment.copy(managerConnected = false)).canModifyTavernFiles())
    }
    @Test fun oneTapUndoRequiresKnownStoppedServerAndNeverApprovesAPause() {
        val env = EnvironmentStatus(managerConnected = true, sillyTavernInstalled = true)
        val idle = LauncherUiState(isWorking = false, environment = env, environmentChecked = true,
            termuxSetup = TermuxSetupState(TermuxSetupStatus.READY))
        assertTrue(idle.canUndoLibraryWithoutStoppingServer())
        for (s in listOf(idle.copy(environment = env.copy(serverReachable = true)),
            idle.copy(environment = env.copy(processRunning = true)), idle.copy(environmentChecked = false),
            idle.copy(termuxSetup = TermuxSetupState()), idle.copy(isWorking = true), idle.copy(termuxWakeBlocked = true),
            idle.copy(environment = env.copy(recoveryPending = true)))) assertFalse(s.canUndoLibraryWithoutStoppingServer())
    }
}
