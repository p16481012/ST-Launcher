package app.tavernbridge.launcher.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileManagementModelsTest {
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
}
