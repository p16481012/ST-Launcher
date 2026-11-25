package app.tavernbridge.launcher.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.tavernbridge.launcher.model.*
import app.tavernbridge.launcher.ui.theme.SillyTavernLauncherTheme
import org.junit.Rule
import org.junit.Test

class BackupReminderBadgeTest {
    @get:Rule val ui = createComposeRule()

    @Test fun dueDotBelongsToBackupNotToolsAndClearsOnOptOutOrNewBackup() {
        val day = 86_400_000L
        val saved = 100 * day
        val reminder = BackupReminderState(lastBackupAtMillis = saved, interval = BackupInterval.MONTHLY)
        val state = mutableStateOf(LauncherUiState(environment = EnvironmentStatus(sillyTavernInstalled = true),
            backupReminder = reminder, backupReminderNowMillis = saved + 29 * day))
        ui.setContent { SillyTavernLauncherTheme(AppTheme.LIGHT) {
            CompositionLocalProvider(LocalV2TabAttention provides state.value.tabAttention()) {
                V2TabBar(MainSection.HOME) {}
            }
        } }
        val dot = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "확인 필요")
        val backupTab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasAnyDescendant(hasText("백업"))
        val backupDot = ui.onNode(dot and hasAnyAncestor(backupTab), useUnmergedTree = true)
        backupDot.assertDoesNotExist()
        ui.runOnIdle { state.value = state.value.copy(backupReminderNowMillis = saved + 30 * day) }
        backupDot.assertExists()
        ui.onAllNodes(dot, useUnmergedTree = true).assertCountEquals(1)
        ui.runOnIdle { state.value = state.value.copy(backupReminder = reminder.copy(enabled = false)) }
        backupDot.assertDoesNotExist()
        ui.runOnIdle { state.value = state.value.copy(backupReminder = reminder) }
        backupDot.assertExists()
        ui.runOnIdle { state.value = state.value.copy(backupReminder = reminder.copy(lastBackupAtMillis = saved + 30 * day)) }
        backupDot.assertDoesNotExist()
    }
}
