package app.tavernbridge.launcher.model

/** Only navigational state is retained: never keep the entire (potentially sensitive) UI state. */
data class LauncherVisit(
    val id: Long,
    val section: MainSection,
    val tool: ToolPage,
    val diagnostic: DiagnosticPanel,
    val management: ManagementPanel,
    val folderPath: String,
)

data class LauncherNavigation(
    val entryId: Long = 0,
    val nextId: Long = 1,
    val previous: List<LauncherVisit> = emptyList(),
)

private fun LauncherUiState.visitSnapshot() = LauncherVisit(navigation.entryId, section, toolPage, diagnosticPanel,
    managementPanel, if (section == MainSection.LOGS && toolPage == ToolPage.FILES) fileBrowserPath else "")

internal fun LauncherUiState.visit(
    destination: MainSection,
    tool: ToolPage = ToolPage.INDEX,
    diagnostic: DiagnosticPanel = DiagnosticPanel.OVERVIEW,
    reset: Boolean = false,
): LauncherUiState {
    // Log segments/refreshes are part of one screen, not another entry on Back.
    val sameScreen = section == destination && (destination != MainSection.LOGS || toolPage == tool)
    val next = when {
        reset -> LauncherNavigation(navigation.nextId, navigation.nextId + 1)
        sameScreen -> navigation
        else -> LauncherNavigation(navigation.nextId, navigation.nextId + 1,
            (navigation.previous + visitSnapshot()).takeLast(20))
    }
    return copy(section = destination, toolPage = tool, diagnosticPanel = diagnostic, navigation = next)
}

internal fun LauncherUiState.previousScreen(): LauncherUiState {
    val target = navigation.previous.lastOrNull() ?: return this
    return copy(section = target.section, toolPage = target.tool, diagnosticPanel = target.diagnostic,
        managementPanel = target.management,
        fileBrowserPath = if (target.tool == ToolPage.FILES) target.folderPath else fileBrowserPath,
        navigation = navigation.copy(entryId = target.id, previous = navigation.previous.dropLast(1)))
}
