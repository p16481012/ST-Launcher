package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class LauncherNavigationTest {
    private val home = LauncherUiState()

    @Test fun homeAndToolsUseDifferentSourcesForTheSameLogScreen() {
        val direct = home.visit(MainSection.LOGS, ToolPage.LOGS, DiagnosticPanel.SERVER_LOG)
        val throughTools = home.visit(MainSection.LOGS).visit(MainSection.LOGS, ToolPage.LOGS, DiagnosticPanel.SERVER_LOG)
        assertEquals(MainSection.HOME, direct.previousScreen().section)
        assertEquals(ToolPage.INDEX, throughTools.previousScreen().toolPage)
        assertEquals(MainSection.LOGS, throughTools.previousScreen().section)
        assertEquals(MainSection.HOME, throughTools.previousScreen().previousScreen().section)
    }
    @Test fun logFilterAndRefreshDoNotInsertAVisit() {
        val logs = home.visit(MainSection.LOGS, ToolPage.LOGS, DiagnosticPanel.SERVER_LOG)
        val filtered = logs.visit(MainSection.LOGS, ToolPage.LOGS, DiagnosticPanel.PREVIOUS_SERVER_LOG)
        assertSame(logs.navigation, filtered.navigation)
        assertEquals(MainSection.HOME, filtered.previousScreen().section)
    }
    @Test fun splitListSwitchesReplaceOnlyTheDetailAndKeepTheOriginalBackDestination() {
        val tools = home.visit(MainSection.LOGS)
        val update = tools.visit(MainSection.LOGS, ToolPage.UPDATE, replaceToolsPane = true)
        val extensions = update.visit(MainSection.LOGS, ToolPage.EXTENSIONS, replaceToolsPane = true)
        assertEquals(tools.navigation.previous, extensions.navigation.previous)
        assertEquals(tools.navigation.entryId, extensions.navigation.masterEntry(MainSection.LOGS))
        assertNotEquals(update.navigation.entryId, extensions.navigation.entryId)
        assertEquals(MainSection.HOME, extensions.previousScreen().section)

        val nested = extensions.visit(MainSection.LOGS, ToolPage.REPAIR)
        assertEquals(tools.navigation.entryId, nested.navigation.masterEntry(MainSection.LOGS))
        assertEquals(ToolPage.EXTENSIONS, nested.previousScreen().toolPage)
        assertEquals(extensions.navigation.splitToolsRoot, nested.previousScreen().navigation.splitToolsRoot)
        val direct = home.visit(MainSection.SETTINGS).visit(MainSection.LOGS, ToolPage.INSTALLATION)
        assertEquals(MainSection.SETTINGS, direct.visit(MainSection.LOGS, ToolPage.LOGS,
            DiagnosticPanel.SERVER_LOG, replaceToolsPane = true).previousScreen().section)
    }
    @Test fun crossingSectionsRestoresExactOriginAndNestedFolder() {
        val settings = home.visit(MainSection.SETTINGS)
        val files = settings.visit(MainSection.LOGS, ToolPage.FILES).copy(fileBrowserPath = "data/default-user")
        val backups = files.visit(MainSection.BACKUPS)
        val back = backups.previousScreen()
        assertEquals("data/default-user", back.fileBrowserPath)
        assertEquals(files.navigation.entryId, back.navigation.entryId)
        assertEquals(settings.navigation.entryId, back.previousScreen().navigation.entryId)
        assertNotEquals(files.navigation.entryId, back.previousScreen().visit(MainSection.LOGS, ToolPage.FILES).navigation.entryId)
    }
    @Test fun historyIsBoundedAndWizardCompletionStartsAFreshRoot() {
        var state = home
        repeat(90) { state = state.visit(if (it % 2 == 0) MainSection.SETTINGS else MainSection.HOME) }
        assertEquals(20, state.navigation.previous.size)
        val root = state.visit(MainSection.HOME, reset = true)
        assertTrue(root.navigation.previous.isEmpty())
        assertSame(root, root.previousScreen())
    }
}
