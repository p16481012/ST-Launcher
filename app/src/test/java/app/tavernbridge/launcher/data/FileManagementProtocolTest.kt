package app.tavernbridge.launcher.data

import org.junit.Assert.*
import org.junit.Test

class FileManagementProtocolTest {
    @Test fun undoHighlightsOnlyValidPathsReportedByTheSuccessfulBackend() {
        fun receipt(path: String) = "restored_b64=${encodeFileArgument(path)}"
        assertEquals("data/한글.txt", FileManagementProtocol.restoredPath(receipt("data/한글.txt")))
        listOf("/absolute", "../old", ".", "foo//bar", "").forEach { assertNull(FileManagementProtocol.restoredPath(receipt(it))) }
        assertNull(FileManagementProtocol.restoredPath("restored_b64=?"))
        assertNull(FileManagementProtocol.restoredPath("trash_id=abc"))
    }
    @Test fun installationInspectionCarriesDestinationReplacementConsent() {
        val source = "/storage/emulated/0/Documents/SillyTavern"
        val destination = "/data/data/com.termux/files/home/SillyTavern"
        val output = "source_path_b64=${encodeFileArgument(source)}\n" +
            "source_version_b64=${encodeFileArgument("1.0.0")}\nsource_bytes=1024\n" +
            "destination_path_b64=${encodeFileArgument(destination)}\nsame_installation=0\n"
        assertFalse(FileManagementProtocol.installation(output).destinationExists)
        assertFalse(FileManagementProtocol.installation(output + "destination_exists=0\n").destinationExists)
        val existing = FileManagementProtocol.installation(output + "destination_exists=1\n")
        assertTrue(existing.destinationExists)
        assertFalse(existing.sameInstallation)
        assertEquals(source, existing.sourcePath)
        assertEquals(destination, existing.destinationPath)
    }

    @Test fun mapsLocalTreesWithoutMakingCopies() {
        assertEquals("/storage/emulated/0/Documents/SillyTavern", localFolderPathForTree(
            "com.android.externalstorage.documents", "primary:Documents/SillyTavern", "/storage/emulated/0"))
        val termux = "/data/data/com.termux/files/home/SillyTavern"
        assertEquals(termux, localFolderPathForTree("com.termux.documents", termux, "/storage/emulated/0"))
    }

    @Test fun rejectsUnknownProvidersAndEscapes() {
        for ((provider, path) in listOf(
            "cloud.documents" to "primary:SillyTavern",
            "com.android.externalstorage.documents" to "primary:../SillyTavern",
            "com.termux.documents" to "/data/data/com.termux/files/home/../usr",
            "com.termux.documents" to "/data/data/com.termux/files/home-other/SillyTavern",
            "com.android.externalstorage.documents" to "primary:",
            "com.android.externalstorage.documents" to "primary:/",
            "com.android.externalstorage.documents" to "secondary:Documents/Folder",
            "com.android.externalstorage.documents" to "primary:Documents/bad\\name",
            "com.android.externalstorage.documents" to "primary:Documents/bad\nname",
            "com.termux.documents" to "/data/data/com.termux/files/home/",
        )) assertThrows(IllegalArgumentException::class.java) { localFolderPathForTree(provider, path, "/storage/emulated/0") }
    }

    @Test fun folderImportKeepsSelectedFolderNameUnderCurrentLocation() {
        val source = localFolderPathForTree("com.android.externalstorage.documents", "primary:Download/새 폴더/", "/storage/emulated/0")
        assertEquals("/storage/emulated/0/Download/새 폴더", source)
        assertEquals("data/default-user/새 폴더", fileChildPath("data/default-user", source.substringAfterLast('/')))
        assertEquals("새 폴더", fileChildPath("", source.substringAfterLast('/')))
    }

    @Test fun usesCurrentProfileSharedStorageWithoutGuessingPrivateStorage() {
        for (profile in listOf("10", "150")) {
            val root = "/storage/emulated/$profile"
            assertEquals("$root/Documents/SillyTavern", localFolderPathForTree(
                "com.android.externalstorage.documents", "primary:Documents/SillyTavern", root))
            assertThrows(IllegalArgumentException::class.java) {
                localFolderPathForTree("com.android.externalstorage.documents", "primary:", root)
            }
            assertThrows(IllegalArgumentException::class.java) {
                localFolderPathForTree("com.termux.documents", "/data/data/com.termux/files/home/SillyTavern", root)
            }
        }
        for (root in listOf("/storage/emulated/10/../0", "/storage/emulated", "/", "/storage/emulated/0/foreign")) {
            assertThrows(IllegalArgumentException::class.java) {
                localFolderPathForTree("com.android.externalstorage.documents", "primary:SillyTavern", root)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            localFolderPathForTree("com.termux.documents", "/data/user/10/com.termux/files/home/SillyTavern", "/storage/emulated/0")
        }
    }

    @Test fun preservesUnicodeAndEmptyText() {
        val hash = "a".repeat(64)
        assertEquals("한글\n", FileManagementProtocol.textFile("config.yaml",
            "content_b64=${encodeFileArgument("한글\n")}\nsha256=$hash\n").content)
        assertEquals("", FileManagementProtocol.textFile("empty.txt", "content_b64=\nsha256=$hash").content)
    }

    @Test fun rejectsOversizedTextAndBadRevision() {
        assertThrows(IllegalArgumentException::class.java) {
            FileManagementProtocol.textFile("big", "content_b64=${encodeFileArgument("a".repeat(TEXT_EDIT_LIMIT + 1))}\nsha256=${"a".repeat(64)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            FileManagementProtocol.textFile("a", "content_b64=\nsha256=unsafe")
        }
    }

    @Test fun rejectsInvalidNames() {
        assertEquals("data/새 파일.txt", fileChildPath("data", "새 파일.txt"))
        for (name in listOf("..", "../x", "a/b", "x\n", "")) {
            assertThrows(IllegalArgumentException::class.java) { fileChildPath("data", name) }
        }
        assertThrows(IllegalArgumentException::class.java) { fileChildPath("../data", "x") }
    }
}
