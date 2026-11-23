package app.tavernbridge.launcher.model

import org.junit.Assert.*
import org.junit.Test

class FileBrowserOptionsTest {
    private fun entry(name: String, size: Long = 0, modified: String = "2026-01-01 00:00", folder: Boolean = false) =
        TavernFileEntry("data/$name", name, folder, size, modified, false)

    @Test fun searchIsCaseInsensitiveCurrentFolderOnlyAndSortingKeepsFoldersFirst() {
        val files = listOf(entry("Beta.txt", 20), entry("alpha.txt", 10), entry("folder", folder = true),
            entry("recent.txt", 5, "2026-11-24 00:00"))
        assertEquals(listOf("folder", "alpha.txt", "Beta.txt", "recent.txt"), visibleTavernFiles(files, "", TavernFileSort.NAME).map { it.name })
        assertEquals(listOf("folder", "Beta.txt", "alpha.txt", "recent.txt"), visibleTavernFiles(files, "", TavernFileSort.SIZE).map { it.name })
        assertEquals(listOf("folder", "recent.txt", "alpha.txt", "Beta.txt"), visibleTavernFiles(files, "", TavernFileSort.MODIFIED).map { it.name })
        assertEquals(listOf("Beta.txt"), visibleTavernFiles(files, " BETA ", TavernFileSort.NAME).map { it.name })
        assertTrue(visibleTavernFiles(files, "data", TavernFileSort.NAME).isEmpty())
    }

    @Test fun selectedItemsMustStillExistInCurrentFolder() {
        val file = entry("a")
        assertEquals(listOf(file), selectedTavernFiles(listOf(file, entry("b")), setOf(file.relativePath, "elsewhere/c")))
    }

    @Test fun pngPreviewIsOfferedOnlyForPngFiles() {
        assertTrue(isTavernPng(entry("card.PNG")))
        assertFalse(isTavernPng(entry("card.png", folder = true)))
        assertFalse(isTavernPng(entry("photo.jpg")))
    }
}
