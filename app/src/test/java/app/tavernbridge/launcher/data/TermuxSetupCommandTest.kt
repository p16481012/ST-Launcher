package app.tavernbridge.launcher.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TermuxSetupCommandTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun conflictingDuplicatesAreNormalizedAndOtherSettingsSurviveRepeatedRuns() {
        val directory = temporary.newFolder("Termux ' settings")
        val properties = directory.resolve("termux.properties")
        val other = "# keep this comment\n# allow-external-apps=false\nextra-keys = [['ESC']]\nallow-external-apps-other=false\n"
        properties.writeText(other + "allow-external-apps=true\n allow-external-apps = false\nallow-external-apps: false\n")
        assertEquals(0, runCommand(directory).first)
        val expected = other + "allow-external-apps=true\n"
        assertEquals(expected, properties.readText())
        assertEquals(0, runCommand(directory).first)
        assertEquals(expected, properties.readText())
        assertEquals(setOf("termux.properties"), directory.list().orEmpty().toSet())
    }

    @Test
    fun missingSettingsAreCreatedAndCompletionIsVisible() {
        val directory = temporary.root.resolve("new-termux")
        val (exitCode, output) = runCommand(directory)
        assertEquals(0, exitCode)
        assertEquals("allow-external-apps=true\n", directory.resolve("termux.properties").readText())
        assertTrue(output.contains("설정 완료"))
    }

    @Test
    fun existingDirectoryIsNeverOverwritten() {
        val directory = temporary.newFolder("termux")
        val properties = directory.resolve("termux.properties").apply { mkdir() }
        val original = properties.resolve("keep").apply { writeText("untouched") }
        assertTrue(runCommand(directory).first != 0)
        assertEquals("untouched", original.readText())
        assertEquals(setOf("termux.properties"), directory.list().orEmpty().toSet())
    }

    @Test
    fun symbolicLinksAreNotReplacedOrFollowed() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        val directory = temporary.newFolder("termux")
        val original = temporary.newFile("original").apply { writeText("original=true\n") }
        val link = directory.resolve("termux.properties")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        assertTrue(runCommand(directory).first != 0)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("original=true\n", original.readText())
    }

    @Test
    fun reloadFailureDoesNotPrintSuccess() {
        val directory = temporary.newFolder("termux")
        val (exitCode, output) = runCommand(directory, reloadExitCode = 1)
        assertTrue(exitCode != 0)
        assertFalse(output.contains("설정 완료"))
        assertEquals(setOf("termux.properties"), directory.list().orEmpty().toSet())
    }

    private fun runCommand(directory: File, reloadExitCode: Int = 0): Pair<Int, String> {
        val gitBash = File("C:/Program Files/Git/bin/bash.exe")
        val bash = if (System.getProperty("os.name").startsWith("Windows") && gitBash.isFile) gitBash.path else "bash"
        val command = "termux-reload-settings() { return $reloadExitCode; }; " +
            createTermuxSetupCommand(directory.absolutePath.replace('\\', '/'))
        val process = ProcessBuilder(bash, "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return process.waitFor() to output
    }
}
