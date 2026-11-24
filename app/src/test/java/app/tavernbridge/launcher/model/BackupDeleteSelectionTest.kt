package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class BackupDeleteSelectionTest {
    private val original = BackupArchive("SillyTavern-Launcher-20261121-120000.zip", 20,
        "2026-11-21 12:00:00", emptySet(), emptySet(), false, "", revision = "a".repeat(64))
    private val replaced = original.copy(revision = "b".repeat(64))

    @Test fun replacedSameNameCannotBeConfirmedOrKeptInSelection() {
        val keys = listOf(BackupDeleteSelection.key(original))
        assertEquals(listOf(original), BackupDeleteSelection.resolve(keys, listOf(original)))
        assertNull(BackupDeleteSelection.resolve(keys, listOf(replaced)))
        assertNull(BackupDeleteSelection.resolve(keys, emptyList()))
    }

    @Test fun ambiguousOrOldPromptsExpireWithoutInterpretingTheirNames() {
        assertFalse(BackupDeleteSelection.valid(listOf(original.fileName)))
        assertFalse(BackupDeleteSelection.valid(listOf(BackupDeleteSelection.key(original), BackupDeleteSelection.key(replaced))))
        assertFalse(BackupDeleteSelection.valid(listOf("../${BackupDeleteSelection.key(original)}")))
        assertFalse(BackupDeleteSelection.identifiable(original.copy(revision = "")))
    }
}
