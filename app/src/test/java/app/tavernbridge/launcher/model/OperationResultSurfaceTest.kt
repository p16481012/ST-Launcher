package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class OperationResultSurfaceTest {
    @Test fun acceptedModalWorkExposesThePersistentHomeResultInsteadOfReopeningItsDialog() {
        val cases = listOf(
            LauncherUiState(selectiveRestore = SelectiveRestoreUiState("backup.zip")),
            LauncherUiState(previousVersions = PreviousVersionUiState()),
            LauncherUiState(extensionManagerOpen = true, extensionLoading = true),
            LauncherUiState(safetyInventory = SafetyInventoryUiState(open = true)),
            LauncherUiState(backupCleanupPreview = BackupRetentionPreview(emptyList(), 0, 0, 0)),
        )
        cases.forEach { initial ->
            val before = initial.copy(section = MainSection.SETUP)
            val after = before.closeOperationDialogsForResult()
            assertEquals(MainSection.HOME, after.section)
            assertNull(after.selectiveRestore)
            assertNull(after.previousVersions)
            assertFalse(after.extensionManagerOpen)
            assertFalse(after.extensionLoading)
            assertFalse(after.safetyInventory.open)
            assertNull(after.backupCleanupPreview)
            assertEquals(before.environment, after.environment)
            assertEquals(before.lastOperationResult, after.lastOperationResult)
            assertEquals(before.backups, after.backups)
            assertEquals(MainSection.SETUP, before.section)
        }
    }

    @Test fun ordinaryOperationsDoNotUnexpectedlyChangeTheirScreen() {
        val before = LauncherUiState(section = MainSection.SETTINGS)
        assertSame(before, before.closeOperationDialogsForResult())
    }
}
