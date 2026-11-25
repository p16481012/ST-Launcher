package app.tavernbridge.launcher.model

/** Only navigational state is retained: never keep the entire (potentially sensitive) UI state. */
data class LauncherVisit(
    val id: Long,
    val section: MainSection,
    val tool: ToolPage,
    val diagnostic: DiagnosticPanel,
    val folderPath: String,
    val splitToolsRoot: Long? = null,
    val safetyOpen: Boolean = false,
)

data class LauncherNavigation(
    val entryId: Long = 0,
    val nextId: Long = 1,
    val previous: List<LauncherVisit> = emptyList(),
    val splitToolsRoot: Long? = null,
)

private fun LauncherUiState.visitSnapshot() = LauncherVisit(navigation.entryId, section, toolPage, diagnosticPanel,
    if (section == MainSection.LOGS && toolPage == ToolPage.FILES) fileBrowserPath else "", navigation.splitToolsRoot, section == MainSection.BACKUPS && safetyInventory.open)

internal fun LauncherUiState.visit(
    destination: MainSection,
    tool: ToolPage = ToolPage.INDEX,
    diagnostic: DiagnosticPanel = DiagnosticPanel.OVERVIEW,
    reset: Boolean = false,
    replaceToolsPane: Boolean = false,
    safetyOpen: Boolean = false,
): LauncherUiState {
    // Log segments/refreshes are part of one screen, not another entry on Back.
    val sameScreen = section == destination && (destination != MainSection.LOGS || toolPage == tool) &&
        (destination != MainSection.BACKUPS || safetyInventory.open == safetyOpen)
    val next = when {
        reset -> LauncherNavigation(navigation.nextId, navigation.nextId + 1)
        // Selecting a sibling in the tablet's visible master list is not a new Back destination.
        // Give a changed detail its own id (so pending actions cannot leak into it), while retaining
        // the master id for scroll/focus and any original Home/Settings/deep-link origin.
        replaceToolsPane && destination == MainSection.LOGS && section == destination ->
            navigation.copy(entryId = if (sameScreen) navigation.entryId else navigation.nextId,
                nextId = if (sameScreen) navigation.nextId else navigation.nextId + 1,
                splitToolsRoot = navigation.masterEntry(destination))
        sameScreen -> navigation
        else -> LauncherNavigation(navigation.nextId, navigation.nextId + 1,
            (navigation.previous + visitSnapshot()).takeLast(20))
    }
    return copy(section = destination, toolPage = tool, diagnosticPanel = diagnostic, navigation = next,
        safetyInventory = safetyInventory.copy(open = destination == MainSection.BACKUPS && safetyOpen))
}

internal fun LauncherUiState.previousScreen(): LauncherUiState {
    val target = navigation.previous.lastOrNull() ?: return this
    return copy(section = target.section, toolPage = target.tool, diagnosticPanel = target.diagnostic,
        safetyInventory = safetyInventory.copy(open = target.section == MainSection.BACKUPS && target.safetyOpen),
        fileBrowserPath = if (target.tool == ToolPage.FILES) target.folderPath else fileBrowserPath,
        navigation = navigation.copy(entryId = target.id, previous = navigation.previous.dropLast(1), splitToolsRoot = target.splitToolsRoot))
}

internal fun LauncherNavigation.masterEntry(section: MainSection): Long {
    val first = previous.takeLastWhile { it.section == section }.firstOrNull()
    return first?.splitToolsRoot ?: first?.id ?: splitToolsRoot ?: entryId
}

/** A bottom tab starts a primary route, not another detail visit. Back goes to Home once. */
internal fun LauncherUiState.reselectRoot(tab: MainSection): LauncherUiState {
    var next = navigation.nextId
    val home = if (tab == MainSection.HOME) null else if (section == MainSection.HOME) visitSnapshot()
        else navigation.previous.lastOrNull { it.section == MainSection.HOME }
            ?: LauncherVisit(next++, MainSection.HOME, ToolPage.INDEX, DiagnosticPanel.OVERVIEW, "")
    return copy(section = tab, toolPage = ToolPage.INDEX, diagnosticPanel = DiagnosticPanel.OVERVIEW,
        safetyInventory = safetyInventory.copy(open = false),
        navigation = LauncherNavigation(next, next + 1, listOfNotNull(home)))
}
