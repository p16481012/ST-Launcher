package app.tavernbridge.launcher.data

import org.junit.Assert.*
import org.junit.Test

class FileManagementProtocolTest {
    @Test fun mapsLocalTreesWithoutMakingCopies() {
        assertEquals("/storage/emulated/0/Documents/SillyTavern", installationPathForTree(
            "com.android.externalstorage.documents", "primary:Documents/SillyTavern"))
        val termux = "/data/data/com.termux/files/home/SillyTavern"
        assertEquals(termux, installationPathForTree("com.termux.documents", termux))
    }

    @Test fun rejectsUnknownProvidersAndEscapes() {
        for ((provider, path) in listOf(
            "cloud.documents" to "primary:SillyTavern",
            "com.android.externalstorage.documents" to "primary:../SillyTavern",
            "com.termux.documents" to "/data/data/com.termux/files/home/../usr",
            "com.termux.documents" to "/data/data/com.termux/files/home-other/SillyTavern",
        )) assertThrows(IllegalArgumentException::class.java) { installationPathForTree(provider, path) }
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
